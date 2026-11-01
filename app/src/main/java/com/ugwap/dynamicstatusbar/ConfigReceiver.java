package com.ugwap.dynamicstatusbar;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * Menjawab permintaan konfigurasi dari proses aplikasi yang di-hook.
 *
 * Kenapa lewat ordered broadcast eksplisit (bukan XSharedPreferences):
 * di ROM ini LSPosed mengalihkan SharedPreferences modul ke
 * /data/misc/apexdata dan SELinux menolak penulisannya, jadi pengaturan
 * tidak pernah tersimpan. Broadcast eksplisit juga tidak terkena filter
 * package visibility, berbeda dengan ContentProvider/bound Service.
 */
public class ConfigReceiver extends BroadcastReceiver {

    public static final String PKG = "com.ugwap.dynamicstatusbar";
    public static final String ACTION = "com.ugwap.dynamicstatusbar.action.GET_CONFIG";
    public static final String COMPONENT = PKG + ".ConfigReceiver";

    public static final String PREFS_NAME = "dynamicstatusbar";
    public static final String KEY_MODE = "mode";           // 0=dinamis, 1=selalu gelap, 2=selalu terang, 3=mati
    public static final String KEY_NAV = "include_nav";      // juga atur ikon navigation bar
    public static final int DEFAULT_MODE = 0;
    public static final boolean DEFAULT_NAV = true;

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle result = new Bundle();
        result.putInt(KEY_MODE, readMode(context));
        result.putBoolean(KEY_NAV, readNav(context));

        // Hanya berpengaruh kalau dipanggil lewat ordered broadcast.
        setResultCode(Activity.RESULT_OK);
        setResultExtras(result);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int readMode(Context context) {
        return prefs(context).getInt(KEY_MODE, DEFAULT_MODE);
    }

    public static boolean readNav(Context context) {
        return prefs(context).getBoolean(KEY_NAV, DEFAULT_NAV);
    }
}
