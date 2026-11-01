package com.ugwap.dynamicstatusbar;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Layar pengaturan modul: pilih cara pewarnaan ikon status bar, ikutkan
 * navigation bar atau tidak, dan sembunyikan ikon dari home screen.
 */
public class MainActivity extends Activity {

    private static final String LAUNCHER_ALIAS =
            "com.ugwap.dynamicstatusbar.Launcher";

    private static final String PREFS_NAME = ConfigReceiver.PREFS_NAME;
    private static final String KEY_MODE = ConfigReceiver.KEY_MODE;
    private static final String KEY_NAV = ConfigReceiver.KEY_NAV;

    private SharedPreferences prefs;
    private Switch hideSwitch;
    private boolean suppressListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * dp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101010);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Dynamic Status Bar");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        box.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Jam, sinyal & baterai ikut warna latar belakang");
        subtitle.setTextColor(0xFF4FC3F7);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setPadding(0, Math.round(4 * dp), 0, pad);
        box.addView(subtitle);

        box.addView(body(
                "Modul ini memperbaiki ikon status bar yang tetap putih di atas "
                        + "latar terang. Setiap aplikasi yang dibuka dicek latar "
                        + "belakangnya: kalau terang, jam/sinyal/baterai dibuat "
                        + "gelap; kalau gelap, ikon tetap terang.",
                dp));

        box.addView(header("Cara pewarnaan", dp));

        box.addView(radioGroup(dp,
                new String[]{
                        "Dinamis - ikuti warna latar tiap aplikasi (disarankan)",
                        "Selalu gelap - paksa ikon gelap di semua aplikasi",
                        "Selalu terang - paksa ikon putih (seperti bawaan)",
                        "Mati - jangan ubah apa pun"
                },
                new int[]{0, 1, 2, 3},
                KEY_MODE, 0));

        box.addView(body(
                "Kalau satu aplikasi masih salah warna, coba mode \"Selalu gelap\" "
                        + "atau laporkan nama aplikasinya. Ikon hanya bisa diubah "
                        + "pada aplikasi yang dicentang di scope LSPosed.",
                dp));

        box.addView(header("Navigation bar", dp));

        Switch navSwitch = new Switch(this);
        navSwitch.setText("Ikon navigation bar ikut menyesuaikan");
        navSwitch.setTextColor(Color.WHITE);
        navSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        navSwitch.setPadding(0, Math.round(8 * dp), 0, Math.round(4 * dp));
        navSwitch.setChecked(prefs.getBoolean(KEY_NAV, ConfigReceiver.DEFAULT_NAV));
        navSwitch.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean(KEY_NAV, checked).apply();
            Toast.makeText(this, checked
                            ? "Navigation bar ikut menyesuaikan"
                            : "Navigation bar tidak diubah",
                    Toast.LENGTH_SHORT).show();
        });
        box.addView(navSwitch);

        box.addView(header("Cara mengaktifkan", dp));

        box.addView(body(
                "1. Buka LSPosed Manager.\n"
                        + "2. Aktifkan modul Dynamic Status Bar.\n"
                        + "3. Centang aplikasi yang ikonnya bermasalah "
                        + "(misalnya Pengaturan/Settings, Launcher, System UI).\n"
                        + "4. Force stop aplikasi itu, lalu buka lagi.\n"
                        + "5. Kalau belum berubah, reboot HP.",
                dp));

        box.addView(body(
                "Catatan: System UI dan Launcher sebaiknya juga dicentang supaya "
                        + "layar recents ikut benar. Kalau setelah dicentang justru "
                        + "ada aplikasi yang salah warna, matikan lagi centang untuk "
                        + "aplikasi tersebut.",
                dp));

        box.addView(header("Kalau tidak ada efek", dp));

        box.addView(body(
                "Pastikan LSPosed (Zygisk) aktif. Cek log dengan filter tag "
                        + "\"DynStatusBar\": setiap activity yang diproses akan "
                        + "mencatat hasil deteksi latarnya.\n\n"
                        + "adb logcat -s LSPosedFramework | Select-String DynStatusBar",
                dp));

        box.addView(header("Ikon aplikasi", dp));

        hideSwitch = new Switch(this);
        hideSwitch.setText("Sembunyikan ikon dari home screen");
        hideSwitch.setTextColor(Color.WHITE);
        hideSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hideSwitch.setPadding(0, Math.round(8 * dp), 0, Math.round(4 * dp));
        hideSwitch.setChecked(isLauncherHidden());
        hideSwitch.setOnCheckedChangeListener((view, checked) -> {
            if (!suppressListener) setLauncherHidden(checked);
        });
        box.addView(hideSwitch);

        box.addView(body(
                "Ikon hanya disembunyikan dari launcher. Modul tetap aktif dan "
                        + "tetap muncul di LSPosed Manager; layar ini bisa dibuka "
                        + "dari sana. Cadangan lewat PC:\n\n"
                        + "adb shell am start -n com.ugwap.dynamicstatusbar/.MainActivity",
                dp));

        Button open = new Button(this);
        open.setText("Buka Pengaturan (tes)");
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "Tidak bisa membuka Pengaturan: " + t,
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(24 * dp);
        box.addView(open, lp);

        TextView footer = new TextView(this);
        footer.setText("github.com/Ugwap/FixDynamicColorStatusBar");
        footer.setTextColor(0xFF808080);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, pad, 0, 0);
        box.addView(footer);

        scroll.addView(box);
        setContentView(scroll);
    }

    private RadioGroup radioGroup(float dp, String[] labels, int[] values,
                                  String key, int defValue) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);

        int current = prefs.getInt(key, defValue);
        int checkedId = View.NO_ID;

        for (int i = 0; i < labels.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setTag(values[i]);
            button.setText(labels[i]);
            button.setTextColor(0xFFCCCCCC);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setPadding(0, Math.round(4 * dp), 0, Math.round(4 * dp));
            group.addView(button);
            if (values[i] == current) checkedId = button.getId();
        }

        if (checkedId != View.NO_ID) group.check(checkedId);

        group.setOnCheckedChangeListener((g, id) -> {
            View view = g.findViewById(id);
            if (view == null || view.getTag() == null) return;
            int value = (int) view.getTag();
            prefs.edit().putInt(key, value).apply();
            Toast.makeText(this,
                    "Tersimpan. Buka ulang aplikasi yang mau diuji untuk melihat efeknya.",
                    Toast.LENGTH_SHORT).show();
        });

        return group;
    }

    private ComponentName launcherAlias() {
        return new ComponentName(this, LAUNCHER_ALIAS);
    }

    private boolean isLauncherHidden() {
        int state = getPackageManager().getComponentEnabledSetting(launcherAlias());
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private void setLauncherHidden(boolean hidden) {
        try {
            getPackageManager().setComponentEnabledSetting(
                    launcherAlias(),
                    hidden
                            ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
            Toast.makeText(this, hidden
                            ? "Ikon disembunyikan dari home screen"
                            : "Ikon ditampilkan lagi di home screen",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Gagal mengubah ikon: " + t, Toast.LENGTH_LONG).show();
            suppressListener = true;
            hideSwitch.setChecked(!hidden);
            suppressListener = false;
        }
    }

    private TextView header(String text, float dp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(0, Math.round(20 * dp), 0, Math.round(6 * dp));
        return view;
    }

    private TextView body(String text, float dp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFCCCCCC);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setLineSpacing(Math.round(2 * dp), 1f);
        return view;
    }
}
