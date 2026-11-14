package com.ugwap.dynamicstatusbar;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Membuat ikon status bar (jam, sinyal, baterai) menyesuaikan warna latar:
 * latar terang -> ikon gelap, latar gelap -> ikon terang.
 *
 * Caranya: setiap Activity yang tampil dicek latar belakangnya (warna status
 * bar, background decor, lalu atribut theme windowBackground/colorBackground),
 * lalu WindowInsetsController.setSystemBarsAppearance dipakai untuk memasang
 * APPEARANCE_LIGHT_STATUS_BARS sesuai hasil itu. Jadi warna jam/sinyal/baterai
 * mengikuti aplikasi yang sedang dibuka, bukan terkunci putih.
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String LOG = "DynStatusBar";

    /** Mode pengaturan (lihat ConfigReceiver). */
    private static final int MODE_DYNAMIC = 0;
    private static final int MODE_DARK = 1;
    private static final int MODE_LIGHT = 2;
    private static final int MODE_OFF = 3;

    private static final int DEFAULT_MODE = ConfigReceiver.DEFAULT_MODE;
    private static final boolean DEFAULT_NAV = ConfigReceiver.DEFAULT_NAV;

    /**
     * Launcher punya logika wallpaper sendiri di HyperOS (dan sampling kita
     * di sana tidak selalu akurat karena wallpaper digambar di window lain).
     * Jadi dalam mode dinamis kita tidak memaksa warna di launcher: cukup
     * lepas paksaan dan biarkan MIUI yang menentukan.
     */
    private static final String LAUNCHER_PKG = "com.miui.home";

    /** Ambang luminance: di atas ini dianggap latar terang (ikon gelap). */
    private static final float LIGHT_THRESHOLD = 0.55f;

    private static int sMode = -1;
    private static boolean sNav = DEFAULT_NAV;
    private static boolean sHomeWhite = ConfigReceiver.DEFAULT_HOME_WHITE;

    private static long sPrefsCheckedAt;
    private static long sRetryAfter;
    private static boolean sRequestInFlight;
    private static boolean sQueryFailed;

    private static WeakReference<Activity> sLastActivity;
    private static Looper sIpcLooper;

    // ---- Sisi SystemUI ----
    private static volatile Object sLightBarController;
    private static volatile Boolean sOverrideLight;
    private static volatile long sOverrideAt;
    private static volatile Boolean sLastApplied;
    private static volatile boolean sApplyFailed;
    private static Handler sMainHandler;
    private static Context sSysuiContext;

    /**
     * Launcher HyperOS di HP ini dijalankan oleh /system_ext/bin/hyos_spawner
     * (preload launcher), sehingga LSPosed tidak memuat modul di proses
     * com.miui.home. Karena itu "home screen tetap putih" diputuskan dari
     * SystemUI: pantau aplikasi teratas lewat getRunningTasks.
     */
    private static final long HOME_POLL_MS = 500L;
    private static volatile boolean sHomeWatchStarted;
    private static String sHomeAppliedPkg;

    /** State terakhir disimpan supaya bisa dipulihkan setelah SystemUI restart. */
    private static final String PREFS_SYSUI = "dynamicstatusbar_sysui";
    private static final String PREF_HAS = "has";
    private static final String PREF_LIGHT = "light";
    private static final String PREF_AT = "at";
    private static final long STORED_MAX_AGE_MS = 3 * 60 * 1000L;

    // ---- Sisi aplikasi ----
    private static String sLastSentKey;
    private static long sLastSentAt;

    /** Untuk membatasi log supaya tidak membanjiri logcat. */
    private static final WeakHashMap<Activity, String> sLastDecision = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        // system_server ("android") tidak punya Activity normal dan hook di sana
        // berisiko; paket modul sendiri tidak perlu di-hook.
        if (pkg == null || "android".equals(pkg) || ConfigReceiver.PKG.equals(pkg)) return;

        if ("com.android.systemui".equals(pkg)) {
            try {
                hookSystemUi(lpparam);
            } catch (Throwable t) {
                XposedBridge.log(LOG + ": gagal hook SystemUI: " + t);
            }
            return;
        }

        try {
            hookActivity(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(LOG + ": gagal hook " + pkg + ": " + t);
        }
    }

    /**
     * Sisi SystemUI: menerima kabar terang/gelap dari aplikasi yang tampil,
     * lalu memasang warna ikon langsung ke pipeline HyperOS
     * (LightBarController -> DarkIconDispatcher -> LightBarTransitionsController).
     *
     * Ini menutup celah ketika WindowManager tidak mengirim ulang appearance
     * (mis. sesudah recents atau sesudah SystemUI restart), yang membuat ikon
     * nyangkut putih.
     */
    private static void hookSystemUi(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        Class<?> controller = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.LightBarControllerImpl", cl);
        XposedBridge.hookAllMethods(controller, "start", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sLightBarController = param.thisObject;
                applyStoredState();
            }
        });
        XposedBridge.hookAllMethods(controller, "updateStatus", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // Kalau MIUI baru saja menghitung ulang (dan bisa saja memakai
                // appearance basi), tegakkan lagi keputusan aplikasi yang tampil.
                if (overrideFresh()) {
                    Boolean light = sOverrideLight;
                    if (light != null) applyIconColor(light.booleanValue());
                }
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                registerIconReceiver((Context) param.thisObject);
            }
        });

        XposedBridge.log(LOG + ": hook SystemUI terpasang");
    }

    private static void registerIconReceiver(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        sSysuiContext = app != null ? app : context;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                try {
                    sSysuiContext = ctx.getApplicationContext();
                    if (intent.hasExtra(ConfigReceiver.KEY_HOME_WHITE)) {
                        boolean enabled = intent.getBooleanExtra(
                                ConfigReceiver.KEY_HOME_WHITE,
                                ConfigReceiver.DEFAULT_HOME_WHITE);
                        sHomeWhite = enabled;
                        sHomeAppliedPkg = null;
                        if (!enabled) {
                            sOverrideLight = null;
                            saveStoredState(null);
                            Object controller = sLightBarController;
                            if (controller != null) {
                                XposedHelpers.callMethod(controller, "reevaluate");
                            }
                        }
                        XposedBridge.log(LOG + "[sysui]: home putih=" + enabled);
                        return;
                    }
                    if (intent.getBooleanExtra(ConfigReceiver.KEY_FORGET, false)) {
                        boolean had = hasStoredState();
                        saveStoredState(null);
                        if (had) {
                            XposedBridge.log(LOG + "[sysui]: lupakan state tersimpan");
                        }
                        return;
                    }
                    if (intent.getBooleanExtra(ConfigReceiver.KEY_CLEAR, false)) {
                        sOverrideLight = null;
                        sHomeAppliedPkg = null;
                        saveStoredState(null);
                        Object controller = sLightBarController;
                        if (controller != null) {
                            XposedHelpers.callMethod(controller, "reevaluate");
                        }
                        XposedBridge.log(LOG + "[sysui]: paksaan dilepas");
                        return;
                    }

                    boolean light = intent.getBooleanExtra(ConfigReceiver.KEY_LIGHT, false);
                    String from = intent.getStringExtra(ConfigReceiver.KEY_PKG);
                    sOverrideLight = Boolean.valueOf(light);
                    sOverrideAt = SystemClock.elapsedRealtime();
                    saveStoredState(Boolean.valueOf(light));

                    final boolean target = light;
                    mainHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            applyIconColor(target);
                        }
                    });
                    // Ulangi sekali lagi; HyperOS kadang menimpa tepat sesudah
                    // transisi window selesai.
                    mainHandler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            applyIconColor(target);
                        }
                    }, 400L);

                    if (!Boolean.valueOf(light).equals(sLastApplied)) {
                        sLastApplied = Boolean.valueOf(light);
                        XposedBridge.log(LOG + "[sysui]: ikon "
                                + (light ? "gelap" : "terang") + " dari " + from);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(LOG + "[sysui]: gagal terapkan: " + t);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ConfigReceiver.ACTION_ICON);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        XposedBridge.log(LOG + "[sysui]: penerima status bar siap");
        reloadPrefs(sSysuiContext);
        startHomeWatch(sSysuiContext);
    }

    /**
     * Pantau aplikasi teratas. Kalau yang tampil home screen dan pengguna
     * memilih "home screen tetap putih", paksa ikon terang. Ini menggantikan
     * hook launcher yang tidak mungkin dipasang di HyperOS.
     */
    private static void startHomeWatch(Context context) {
        if (context == null || sHomeWatchStarted) return;
        sHomeWatchStarted = true;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final Handler handler = mainHandler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    watchHomeOnce(app);
                } catch (Throwable ignored) {
                    // Polling tidak boleh mematikan SystemUI.
                }
                handler.postDelayed(this, HOME_POLL_MS);
            }
        }, HOME_POLL_MS);
    }

    private static void watchHomeOnce(Context context) {
        // Ikut menyegarkan pengaturan supaya toggle langsung berpengaruh.
        reloadPrefs(context);

        if (!interactive(context)) return;
        String top = topPackage(context);
        if (top == null) return;

        if (!LAUNCHER_PKG.equals(top)) {
            sHomeAppliedPkg = null;
            return;
        }
        if (mode() != MODE_DYNAMIC || !homeWhite()) {
            sHomeAppliedPkg = null;
            return;
        }
        if (keyguardShowing(context)) return;

        // Selalu segarkan waktu override selama home tampil, supaya
        // LightBarController tidak menimpanya dengan appearance basi.
        sOverrideLight = Boolean.FALSE;
        sOverrideAt = SystemClock.elapsedRealtime();
        if (LAUNCHER_PKG.equals(sHomeAppliedPkg)) return;

        saveStoredState(Boolean.FALSE);
        applyIconColor(false);
        if (sLightBarController != null) {
            sHomeAppliedPkg = LAUNCHER_PKG;
            XposedBridge.log(LOG + "[sysui]: home screen -> ikon terang");
        }
    }

    private static boolean interactive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean keyguardShowing(Context context) {
        try {
            KeyguardManager km =
                    (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static String topPackage(Context context) {
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return null;
            ActivityManager.RunningTaskInfo info = tasks.get(0);
            if (info.topActivity != null) return info.topActivity.getPackageName();
            if (info.baseActivity != null) return info.baseActivity.getPackageName();
        } catch (Throwable ignored) {
            // Tanpa izin/tugas tidak tersedia; coba lagi di putaran berikutnya.
        }
        return null;
    }

    private static boolean overrideFresh() {
        Boolean light = sOverrideLight;
        return light != null && SystemClock.elapsedRealtime() - sOverrideAt < 1500L;
    }

    private static boolean hasStoredState() {
        Context context = sSysuiContext;
        if (context == null) return false;
        try {
            return context.getSharedPreferences(PREFS_SYSUI, Context.MODE_PRIVATE)
                    .getBoolean(PREF_HAS, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void saveStoredState(Boolean light) {
        Context context = sSysuiContext;
        if (context == null) return;
        try {
            SharedPreferences.Editor editor = context
                    .getSharedPreferences(PREFS_SYSUI, Context.MODE_PRIVATE).edit();
            if (light == null) {
                editor.putBoolean(PREF_HAS, false);
            } else {
                editor.putBoolean(PREF_HAS, true);
                editor.putBoolean(PREF_LIGHT, light.booleanValue());
                editor.putLong(PREF_AT, SystemClock.elapsedRealtime());
            }
            editor.apply();
        } catch (Throwable ignored) {
            // Kalau penyimpanan ditolak, pemulihan setelah restart dilewati.
        }
    }

    /**
     * SystemUI baru dimulai: pakai lagi keputusan terakhir kalau masih baru.
     * Umur dibatasi dan memakai elapsedRealtime, jadi state dari sebelum
     * reboot (yang jamnya sudah reset) tidak dipakai.
     */
    private static void applyStoredState() {
        Context context = sSysuiContext;
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_SYSUI, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(PREF_HAS, false)) return;

            long at = prefs.getLong(PREF_AT, 0L);
            long now = SystemClock.elapsedRealtime();
            if (at <= 0L || now < at || now - at > STORED_MAX_AGE_MS) return;

            final boolean light = prefs.getBoolean(PREF_LIGHT, false);
            sOverrideLight = Boolean.valueOf(light);
            sOverrideAt = now;
            mainHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyIconColor(light);
                }
            }, 600L);
            mainHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyIconColor(light);
                }
            }, 1800L);
            XposedBridge.log(LOG + "[sysui]: pulihkan state terakhir (ikon "
                    + (light ? "gelap" : "terang") + ")");
        } catch (Throwable ignored) {
            // Tidak ada state tersimpan.
        }
    }

    private static void applyIconColor(boolean light) {
        Object controller = sLightBarController;
        if (controller == null) return;
        try {
            Object dispatcher = XposedHelpers.getObjectField(controller, "mStatusBarIconController");
            if (dispatcher == null) return;
            Object transitions = XposedHelpers.getObjectField(dispatcher, "mTransitionsController");
            if (transitions == null) return;

            // Semua area ikon memakai satu warna.
            try {
                XposedHelpers.callMethod(dispatcher, "setIconsDarkArea",
                        new Class[]{java.util.ArrayList.class}, new Object[]{null});
            } catch (Throwable ignored) {
                // Versi lain mungkin memakai nama/tipe berbeda.
            }

            // light == true berarti latar terang -> ikon gelap.
            XposedHelpers.callMethod(transitions, "setIconsDark",
                    new Class[]{boolean.class, boolean.class, boolean.class},
                    new Object[]{Boolean.valueOf(light), Boolean.FALSE, Boolean.TRUE});
        } catch (Throwable t) {
            if (!sApplyFailed) {
                sApplyFailed = true;
                XposedBridge.log(LOG + "[sysui]: hook warna gagal: " + t);
            }
        }
    }

    private static Handler mainHandler() {
        if (sMainHandler == null) {
            sMainHandler = new Handler(Looper.getMainLooper());
        }
        return sMainHandler;
    }

    private static void hookActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                onActivityShown((Activity) param.thisObject);
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                onActivityShown((Activity) param.thisObject);
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.args[0])) {
                            onActivityShown((Activity) param.thisObject);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(Activity.class, "onConfigurationChanged",
                Configuration.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // Theme bisa berganti saat mode gelap/terang berubah.
                        onActivityShown((Activity) param.thisObject);
                    }
                });

        XposedBridge.log(LOG + ": hook Activity terpasang di " + lpparam.packageName);
    }

    private static void onActivityShown(Activity activity) {
        if (activity == null) return;
        try {
            String pkg = activity.getPackageName();
            if (pkg == null || ConfigReceiver.PKG.equals(pkg)) return;

            sLastActivity = new WeakReference<>(activity);
            reloadPrefs(activity);

            if (mode() == MODE_DYNAMIC && LAUNCHER_PKG.equals(pkg)) {
                if (homeWhite()) {
                    // Pengguna minta home screen selalu putih; tetap jadwalkan
                    // penerapan supaya keputusan itu dikirim ke SystemUI.
                    scheduleApply(activity);
                } else {
                    // Launcher memakai logika wallpaper HyperOS; jangan ditimpa.
                    // Cukup lupakan state tersimpan supaya SystemUI tidak
                    // memulihkan warna aplikasi lama saat home sedang tampil.
                    forgetStoredState(activity);
                }
                return;
            }

            // onResume/onPostResume bisa dijalankan sebelum aplikasi selesai
            // memasang appearance-nya sendiri, jadi terapkan lagi setelah frame
            // pertama dan sekali lagi sedikit lebih lambat.
            scheduleApply(activity);
        } catch (Throwable t) {
            XposedBridge.log(LOG + ": onActivityShown gagal: " + t);
        }
    }

    private static void scheduleApply(final Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        final View decor = window.getDecorView();
        if (decor == null) return;

        decor.post(new Runnable() {
            @Override
            public void run() {
                applyNow(activity);
            }
        });
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Jangan kirim keputusan basi kalau activity ini sudah tidak
                // terlihat (mis. pengguna sudah pindah ke aplikasi lain).
                if (windowVisible(activity)) applyNow(activity);
            }
        }, 400L);
    }

    private static boolean windowVisible(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return false;
            Window window = activity.getWindow();
            if (window == null) return false;
            View decor = window.getDecorView();
            return decor != null && decor.isShown()
                    && decor.getWindowVisibility() == View.VISIBLE;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void applyNow(final Activity activity) {
        try {
            int mode = mode();
            if (mode == MODE_OFF) {
                notifySystemUiClear(activity);
                return;
            }

            if (mode == MODE_DARK) {
                setAppearance(activity, true, nav());
                notifySystemUi(activity, true);
                logDecision(activity, true, "mode selalu gelap");
                return;
            }
            if (mode == MODE_LIGHT) {
                setAppearance(activity, false, nav());
                notifySystemUi(activity, false);
                logDecision(activity, false, "mode selalu terang");
                return;
            }

            // Launcher sudah punya logika warna wallpaper sendiri di HyperOS.
            // Kalau pengguna memilih "home screen tetap putih", paksa ikon
            // terang di sana; kalau tidak, jangan ditimpa sama sekali.
            if (LAUNCHER_PKG.equals(activity.getPackageName())) {
                if (homeWhite()) {
                    setAppearance(activity, false, nav());
                    notifySystemUi(activity, false);
                    logDecision(activity, false, "home screen tetap putih");
                }
                return;
            }

            Detected detected = detect(activity);
            if (detected.light != null) {
                setAppearance(activity, detected.light, nav());
                logDecision(activity, detected.light, detected.source);
            }
            notifyDynamic(activity, detected.light);

            // Kalau status bar transparan, warna theme sering tidak sama dengan
            // yang benar-benar terlihat. Ambil sampel piksel asli di area bar
            // lalu koreksi kalau hasilnya berbeda.
            if (detected.sample) {
                sampleAndRefine(activity);
            }
        } catch (Throwable t) {
            XposedBridge.log(LOG + ": terapkan gagal: " + t);
        }
    }

    /**
     * Baca warna piksel asli di area status bar (di belakang jam/ikon).
     * Hasilnya menggantikan tebakan dari atribut theme kalau pikselnya solid.
     */
    private static void sampleAndRefine(final Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Window window = activity.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (decor == null) return;

        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0) return;

        int sampleWidth = Math.max(8, Math.min(64, width / 10));
        int left = Math.max(0, (width - sampleWidth) / 2);
        int sampleHeight = Math.max(2, Math.min(10, height / 40));
        int top = Math.min(2, Math.max(0, height - sampleHeight));
        if (left + sampleWidth > width || top + sampleHeight > height) return;

        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return;
        }

        try {
            PixelCopy.request(window,
                    new Rect(left, top, left + sampleWidth, top + sampleHeight),
                    bitmap,
                    new PixelCopy.OnPixelCopyFinishedListener() {
                        @Override
                        public void onPixelCopyFinished(int result) {
                            handleSample(activity, bitmap, result);
                        }
                    },
                    new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            try {
                bitmap.recycle();
            } catch (Throwable ignored) {
                // Sudah tidak dipakai.
            }
        }
    }

    private static void handleSample(final Activity activity, Bitmap bitmap, int result) {
        int decision = -1;
        String source = null;

        try {
            if (result == PixelCopy.SUCCESS) {
                long red = 0;
                long green = 0;
                long blue = 0;
                int opaque = 0;
                int total = 0;

                for (int x = 0; x < bitmap.getWidth(); x++) {
                    for (int y = 0; y < bitmap.getHeight(); y++) {
                        total++;
                        int color = bitmap.getPixel(x, y);
                        if (Color.alpha(color) < 250) continue;
                        red += Color.red(color);
                        green += Color.green(color);
                        blue += Color.blue(color);
                        opaque++;
                    }
                }

                // Kalau sebagian besar piksel transparan, area bar tidak digambar
                // aplikasi (misalnya wallpaper di belakang launcher); jangan menebak.
                if (opaque >= Math.max(1, total / 2)) {
                    int average = Color.rgb((int) (red / opaque),
                            (int) (green / opaque), (int) (blue / opaque));
                    decision = Color.luminance(average) > LIGHT_THRESHOLD ? 1 : 0;
                    source = "piksel " + hex(average);
                }
            }
        } catch (Throwable ignored) {
            // Gagal membaca sampel; hasil deteksi theme tetap dipakai.
        } finally {
            try {
                bitmap.recycle();
            } catch (Throwable ignored) {
                // Sudah tidak dipakai.
            }
        }

        if (decision < 0) return;
        if (!windowVisible(activity)) return;

        final boolean light = decision == 1;
        final String from = source;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setAppearance(activity, light, nav());
                notifyDynamic(activity, light);
                logDecision(activity, light, from);
            }
        });
    }

    /**
     * Mode dinamis: kirim keputusan hanya kalau latar diketahui dan bukan
     * launcher. Selain itu lepas paksaan supaya logika bawaan HyperOS
     * (mis. wallpaper launcher) yang bekerja.
     */
    private static void notifyDynamic(Context context, Boolean light) {
        if (context == null) return;
        String pkg = context.getPackageName();
        if (light == null || LAUNCHER_PKG.equals(pkg)) {
            notifySystemUiClear(context);
            return;
        }
        notifySystemUi(context, light.booleanValue());
    }

    /**
     * Kabari SystemUI tentang warna latar yang sedang tampil. Di-throttle
     * supaya beberapa panggilan beruntun untuk keadaan yang sama tidak
     * membanjiri broadcast, tetapi tetap dikirim ulang setelah jeda agar
     * state SystemUI yang hilang (sesudah restart/recents) bisa dipulihkan.
     */
    private static void notifySystemUi(Context context, boolean light) {
        if (context == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            String key = light + "|" + context.getPackageName();
            if (key.equals(sLastSentKey) && now - sLastSentAt < 800L) return;
            sLastSentKey = key;
            sLastSentAt = now;

            Intent intent = new Intent(ConfigReceiver.ACTION_ICON);
            intent.putExtra(ConfigReceiver.KEY_LIGHT, light);
            intent.putExtra(ConfigReceiver.KEY_PKG, context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            XposedBridge.log(LOG + ": kirim state ke SystemUI gagal: " + t);
        }
    }

    private static void notifySystemUiClear(Context context) {
        if (context == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            if ("clear".equals(sLastSentKey) && now - sLastSentAt < 1500L) return;
            sLastSentKey = "clear";
            sLastSentAt = now;

            Intent intent = new Intent(ConfigReceiver.ACTION_ICON);
            intent.putExtra(ConfigReceiver.KEY_CLEAR, true);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
            // Mode mati; tidak ada yang perlu dilakukan.
        }
    }

    /**
     * Hapus state tersimpan tanpa menyentuh warna ikon yang sedang berjalan.
     * Dipakai launcher: saat home tampil, state aplikasi sebelumnya sudah tidak
     * relevan untuk dipulihkan kalau SystemUI restart.
     */
    private static void forgetStoredState(Context context) {
        if (context == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            if ("forget".equals(sLastSentKey) && now - sLastSentAt < 5000L) return;
            sLastSentKey = "forget";
            sLastSentAt = now;

            Intent intent = new Intent(ConfigReceiver.ACTION_ICON);
            intent.putExtra(ConfigReceiver.KEY_FORGET, true);
            intent.putExtra(ConfigReceiver.KEY_PKG, context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
            // Tidak fatal; state tersimpan hanya akan dipakai kalau masih baru.
        }
    }

    private static void setAppearance(Activity activity, boolean light, boolean includeNav) {
        Window window = activity.getWindow();
        if (window == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                if (includeNav) {
                    mask |= WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                }
                int value = light ? mask : 0;
                controller.setSystemBarsAppearance(value, mask);
                return;
            }
        }

        // Fallback Android 10 (API 29).
        View decor = window.getDecorView();
        if (decor == null) return;
        int flags = decor.getSystemUiVisibility();
        int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (includeNav && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mask |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        flags = light ? (flags | mask) : (flags & ~mask);
        decor.setSystemUiVisibility(flags);
    }

    /**
     * Tebakan awal dari atribut window/theme. Kalau status bar transparan,
     * hasil ini nanti dikoreksi oleh {@link #sampleAndRefine(Activity)}.
     */
    private static Detected detect(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return Detected.UNKNOWN;

        try {
            int color = window.getStatusBarColor();
            if (Color.alpha(color) == 255) {
                return new Detected(Color.luminance(color) > LIGHT_THRESHOLD,
                        "statusBar " + hex(color), false);
            }
        } catch (Throwable ignored) {
            // Di beberapa ROM getStatusBarColor bisa melempar; lanjut ke cara lain.
        }

        View decor = window.getDecorView();
        if (decor != null) {
            Integer color = solidColor(decor.getBackground());
            if (color != null) {
                return new Detected(Color.luminance(color) > LIGHT_THRESHOLD,
                        "decor " + hex(color), true);
            }
        }

        Resources.Theme theme = activity.getTheme();
        TypedValue value = new TypedValue();

        try {
            if (theme.resolveAttribute(android.R.attr.windowBackground, value, true)) {
                if (isColorValue(value)) {
                    return new Detected(Color.luminance(value.data) > LIGHT_THRESHOLD,
                            "windowBackground " + hex(value.data), true);
                }
                if (value.resourceId != 0) {
                    try {
                        Drawable drawable = activity.getResources()
                                .getDrawable(value.resourceId, theme);
                        Integer color = solidColor(drawable);
                        if (color != null) {
                            return new Detected(Color.luminance(color) > LIGHT_THRESHOLD,
                                    "windowBackground " + hex(color), true);
                        }
                    } catch (Throwable ignored) {
                        // Resource tidak bisa dibuka; coba atribut berikutnya.
                    }
                }
            }
        } catch (Throwable ignored) {
            // Lanjut ke windowLightStatusBar.
        }

        // Permintaan eksplisit aplikasi: true = ikon gelap di latar terang.
        try {
            if (theme.resolveAttribute(android.R.attr.windowLightStatusBar, value, true)) {
                boolean light = value.data != 0;
                return new Detected(light, "windowLightStatusBar=" + light, true);
            }
        } catch (Throwable ignored) {
            // Lanjut ke colorBackground.
        }

        try {
            if (theme.resolveAttribute(android.R.attr.colorBackground, value, true)
                    && isColorValue(value)) {
                return new Detected(Color.luminance(value.data) > LIGHT_THRESHOLD,
                        "colorBackground " + hex(value.data), true);
            }
        } catch (Throwable ignored) {
            // Tidak ada informasi lain.
        }

        // Mode malam bisa jadi petunjuk terakhir, tapi jangan menebak terlalu
        // jauh: lebih baik tidak mengubah apa pun daripada salah warna.
        int night = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (night == Configuration.UI_MODE_NIGHT_YES) {
            return new Detected(false, "mode malam", true);
        }

        return new Detected(null, "tidak diketahui", true);
    }

    private static boolean isColorValue(TypedValue value) {
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT;
    }

    /** Ambil warna solid dari drawable (termasuk pembungkus yang umum dipakai theme). */
    private static Integer solidColor(Drawable drawable) {
        try {
            if (drawable == null) return null;

            if (drawable instanceof ColorDrawable) {
                int color = ((ColorDrawable) drawable).getColor();
                return Color.alpha(color) == 255 ? color : null;
            }

            if (drawable instanceof GradientDrawable) {
                ColorStateList colors = ((GradientDrawable) drawable).getColor();
                if (colors == null) return null;
                int color = colors.getDefaultColor();
                return Color.alpha(color) == 255 ? color : null;
            }

            if (drawable instanceof InsetDrawable) {
                return solidColor(((InsetDrawable) drawable).getDrawable());
            }

            if (drawable instanceof LayerDrawable) {
                LayerDrawable layers = (LayerDrawable) drawable;
                for (int i = layers.getNumberOfLayers() - 1; i >= 0; i--) {
                    Integer color = solidColor(layers.getDrawable(i));
                    if (color != null) return color;
                }
                return null;
            }

            if (drawable instanceof StateListDrawable) {
                Drawable current = drawable.getCurrent();
                if (current != null && current != drawable) return solidColor(current);
                return null;
            }
        } catch (Throwable ignored) {
            // Drawable aneh/tidak lengkap; anggap tidak diketahui.
        }
        return null;
    }

    private static String hex(int color) {
        return String.format("#%08X", color);
    }

    private static void logDecision(Activity activity, boolean light, String source) {
        if (activity == null) return;
        String key = light + "|" + source;
        if (key.equals(sLastDecision.get(activity))) return;
        sLastDecision.put(activity, key);
        XposedBridge.log(LOG + ": " + activity.getPackageName() + "/"
                + activity.getClass().getSimpleName()
                + " -> ikon " + (light ? "gelap" : "terang")
                + " (" + source + ")");
    }

    /**
     * Baca konfigurasi lewat ordered broadcast eksplisit. Di-throttle 3 detik
     * supaya tidak IPC terus-menerus saat berpindah activity.
     */
    private static void reloadPrefs(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - sPrefsCheckedAt < 3000L || sRequestInFlight || now < sRetryAfter) return;
        sPrefsCheckedAt = now;
        sRequestInFlight = true;

        try {
            Intent intent = new Intent(ConfigReceiver.ACTION);
            intent.setComponent(new ComponentName(ConfigReceiver.PKG, ConfigReceiver.COMPONENT));

            context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent reply) {
                    sRequestInFlight = false;

                    Bundle extras = getResultExtras(false);
                    if (getResultCode() != Activity.RESULT_OK || extras == null) {
                        sRetryAfter = SystemClock.elapsedRealtime() + 10000L;
                        if (!sQueryFailed) {
                            sQueryFailed = true;
                            XposedBridge.log(LOG + ": pengaturan modul belum bisa dibaca");
                        }
                        return;
                    }

                    int mode = extras.getInt(ConfigReceiver.KEY_MODE, DEFAULT_MODE);
                    boolean nav = extras.getBoolean(ConfigReceiver.KEY_NAV, DEFAULT_NAV);
                    boolean homeWhite = extras.getBoolean(ConfigReceiver.KEY_HOME_WHITE,
                            ConfigReceiver.DEFAULT_HOME_WHITE);
                    mode = Math.max(MODE_DYNAMIC, Math.min(mode, MODE_OFF));

                    if (sQueryFailed) {
                        sQueryFailed = false;
                        XposedBridge.log(LOG + ": pengaturan modul terbaca lagi");
                    }

                    if (mode != sMode || nav != sNav || homeWhite != sHomeWhite) {
                        sMode = mode;
                        sNav = nav;
                        sHomeWhite = homeWhite;
                        sLastDecision.clear();
                        XposedBridge.log(LOG + ": config mode=" + mode
                                + " navbar=" + nav + " homeWhite=" + homeWhite);
                        reapplyLast();
                    }
                }
            }, new Handler(ipcLooper()), Activity.RESULT_OK, null, null);
        } catch (Throwable t) {
            sRequestInFlight = false;
            sRetryAfter = SystemClock.elapsedRealtime() + 10000L;
            if (!sQueryFailed) {
                sQueryFailed = true;
                XposedBridge.log(LOG + ": baca config gagal: " + t);
            }
        }
    }

    private static void reapplyLast() {
        Activity activity = sLastActivity == null ? null : sLastActivity.get();
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyNow(activity);
            }
        });
    }

    /** Looper thread terpisah supaya balasan broadcast bisa masuk saat UI sibuk. */
    private static Looper ipcLooper() {
        if (sIpcLooper == null) {
            HandlerThread thread = new HandlerThread("dsb-ipc");
            thread.start();
            sIpcLooper = thread.getLooper();
        }
        return sIpcLooper;
    }

    private static int mode() {
        return sMode < 0 ? DEFAULT_MODE : sMode;
    }

    private static boolean nav() {
        return sNav;
    }

    private static boolean homeWhite() {
        return sHomeWhite;
    }

    /** Hasil deteksi latar; light == null berarti tidak diketahui. */
    private static final class Detected {
        static final Detected UNKNOWN = new Detected(null, "tidak diketahui", false);

        final Boolean light;
        final String source;
        /** True kalau area status bar perlu diambil sampel pikselnya. */
        final boolean sample;

        Detected(Boolean light, String source, boolean sample) {
            this.light = light;
            this.source = source;
            this.sample = sample;
        }
    }
}
