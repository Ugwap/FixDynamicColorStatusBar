package com.ugwap.dynamicstatusbar;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
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

    /** Ambang luminance: di atas ini dianggap latar terang (ikon gelap). */
    private static final float LIGHT_THRESHOLD = 0.55f;

    private static int sMode = -1;
    private static boolean sNav = DEFAULT_NAV;

    private static long sPrefsCheckedAt;
    private static long sRetryAfter;
    private static boolean sRequestInFlight;
    private static boolean sQueryFailed;

    private static WeakReference<Activity> sLastActivity;
    private static Looper sIpcLooper;

    /** Untuk membatasi log supaya tidak membanjiri logcat. */
    private static final Set<Activity> sLogged =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        // system_server ("android") tidak punya Activity normal dan hook di sana
        // berisiko; paket modul sendiri tidak perlu di-hook.
        if (pkg == null || "android".equals(pkg) || ConfigReceiver.PKG.equals(pkg)) return;

        try {
            hookActivity(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(LOG + ": gagal hook " + pkg + ": " + t);
        }
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
                applyNow(activity);
            }
        }, 400L);
    }

    private static void applyNow(Activity activity) {
        try {
            int mode = mode();
            if (mode == MODE_OFF) return;

            boolean light;
            String source;

            if (mode == MODE_DARK) {
                light = true;
                source = "mode selalu gelap";
            } else if (mode == MODE_LIGHT) {
                light = false;
                source = "mode selalu terang";
            } else {
                Detected detected = detect(activity);
                if (detected.light == null) {
                    logOnce(activity, LOG + ": " + activity.getClass().getName()
                            + " - latar tidak bisa dipastikan, dibiarkan");
                    return;
                }
                light = detected.light;
                source = detected.source;
            }

            setAppearance(activity, light, nav());
            logOnce(activity, LOG + ": " + activity.getPackageName() + "/"
                    + activity.getClass().getSimpleName()
                    + " -> ikon " + (light ? "gelap" : "terang")
                    + " (" + source + ")");
        } catch (Throwable t) {
            XposedBridge.log(LOG + ": terapkan gagal: " + t);
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
     * Cari tahu apakah latar di balik status bar terang.
     * Urutan: warna status bar -> background decor -> windowBackground theme
     * -> colorBackground theme -> atribut windowLightStatusBar.
     */
    private static Detected detect(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return Detected.UNKNOWN;

        try {
            int color = window.getStatusBarColor();
            if (Color.alpha(color) == 255) {
                return new Detected(Color.luminance(color) > LIGHT_THRESHOLD,
                        "statusBar " + hex(color));
            }
        } catch (Throwable ignored) {
            // Di beberapa ROM getStatusBarColor bisa melempar; lanjut ke cara lain.
        }

        View decor = window.getDecorView();
        if (decor != null) {
            Integer color = solidColor(decor.getBackground());
            if (color != null) {
                return new Detected(Color.luminance(color) > LIGHT_THRESHOLD,
                        "decor " + hex(color));
            }

            // Banyak aplikasi memakai status bar transparan dan mengandalkan
            // background root content view; itu yang sebenarnya terlihat.
            Integer content = contentColor(decor);
            if (content != null) {
                return new Detected(Color.luminance(content) > LIGHT_THRESHOLD,
                        "content " + hex(content));
            }
        }

        Resources.Theme theme = activity.getTheme();
        TypedValue value = new TypedValue();

        try {
            if (theme.resolveAttribute(android.R.attr.windowBackground, value, true)) {
                if (isColorValue(value)) {
                    return new Detected(Color.luminance(value.data) > LIGHT_THRESHOLD,
                            "windowBackground " + hex(value.data));
                }
                if (value.resourceId != 0) {
                    try {
                        Drawable drawable = activity.getResources()
                                .getDrawable(value.resourceId, theme);
                        Integer color = solidColor(drawable);
                        if (color != null) {
                            return new Detected(Color.luminance(color) > LIGHT_THRESHOLD,
                                    "windowBackground " + hex(color));
                        }
                    } catch (Throwable ignored) {
                        // Resource tidak bisa dibuka; coba atribut berikutnya.
                    }
                }
            }
        } catch (Throwable ignored) {
            // Lanjut ke colorBackground.
        }

        try {
            if (theme.resolveAttribute(android.R.attr.colorBackground, value, true)
                    && isColorValue(value)) {
                return new Detected(Color.luminance(value.data) > LIGHT_THRESHOLD,
                        "colorBackground " + hex(value.data));
            }
        } catch (Throwable ignored) {
            // Lanjut ke windowLightStatusBar.
        }

        try {
            if (theme.resolveAttribute(android.R.attr.windowLightStatusBar, value, true)) {
                boolean light = value.data != 0;
                return new Detected(light, "windowLightStatusBar=" + light);
            }
        } catch (Throwable ignored) {
            // Tidak ada informasi.
        }

        // Mode malam bisa jadi petunjuk terakhir, tapi jangan menebak terlalu
        // jauh: lebih baik tidak mengubah apa pun daripada salah warna.
        int night = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (night == Configuration.UI_MODE_NIGHT_YES) {
            return new Detected(false, "mode malam");
        }

        return Detected.UNKNOWN;
    }

    private static boolean isColorValue(TypedValue value) {
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT;
    }

    /**
     * Warna latar root content view (satu atau dua tingkat dari decor).
     * Dipakai saat status bar transparan dan decor tidak punya background.
     */
    private static Integer contentColor(View decor) {
        try {
            View content = decor.findViewById(android.R.id.content);
            if (content == null) return null;
            Integer color = solidColor(content.getBackground());
            if (color != null) return color;
            if (content instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) content;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    color = solidColor(group.getChildAt(i).getBackground());
                    if (color != null) return color;
                }
            }
        } catch (Throwable ignored) {
            // Abaikan; deteksi lanjut ke atribut theme.
        }
        return null;
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

    private static void logOnce(Activity activity, String message) {
        if (activity == null) return;
        if (sLogged.add(activity)) {
            XposedBridge.log(message);
        }
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
                    mode = Math.max(MODE_DYNAMIC, Math.min(mode, MODE_OFF));

                    if (sQueryFailed) {
                        sQueryFailed = false;
                        XposedBridge.log(LOG + ": pengaturan modul terbaca lagi");
                    }

                    if (mode != sMode || nav != sNav) {
                        sMode = mode;
                        sNav = nav;
                        sLogged.clear();
                        XposedBridge.log(LOG + ": config mode=" + mode + " navbar=" + nav);
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

    /** Hasil deteksi latar; light == null berarti tidak diketahui. */
    private static final class Detected {
        static final Detected UNKNOWN = new Detected(null, "tidak diketahui");

        final Boolean light;
        final String source;

        Detected(Boolean light, String source) {
            this.light = light;
            this.source = source;
        }
    }
}
