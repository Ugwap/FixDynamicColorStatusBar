# Fix Dynamic Color Status Bar

Modul **LSPosed** untuk HyperOS/Android yang membuat ikon status bar
(jam, sinyal, baterai) **ikut berubah warna** mengikuti latar belakang:
latar terang → ikon gelap, latar gelap → ikon terang.

Dibuat karena di ROM ini ikon status bar tetap putih di atas latar putih
(misalnya di Pengaturan dan layar recents), sehingga jam/sinyal/baterai
tidak terlihat.

## Yang dilakukan modul

1. **Deteksi latar per aplikasi** — setiap Activity yang tampil dicek latar
   di balik status bar dengan urutan:
   - warna status bar (`Window.getStatusBarColor`), kalau solid;
   - **sampel piksel asli** di area status bar (`PixelCopy`) — ini yang
     menentukan warna sebenarnya, termasuk untuk aplikasi bertema terang yang
     isinya gelap;
   - background decor view;
   - atribut theme `windowBackground` (termasuk drawable solid/gradient);
   - atribut `windowLightStatusBar` sebagai cadangan;
   - atribut `colorBackground`;
   - mode malam (hanya untuk memastikan latar gelap).

   Kalau latar tidak bisa dipastikan, modul **tidak mengubah apa pun** supaya
   tidak salah warna.

2. **Pasang appearance yang benar** — memakai
   `WindowInsetsController.setSystemBarsAppearance` dengan
   `APPEARANCE_LIGHT_STATUS_BARS` (API 30+), atau
   `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` di Android 10 (API 29).
   Appearance dipasang ulang setelah frame pertama dan 400 ms kemudian,
   karena sebagian aplikasi memasang appearance-nya sendiri di akhir.

3. **Kanal langsung ke SystemUI** — keputusan terang/gelap juga dikirim ke
   SystemUI. Jadi kalau WindowManager tidak mengirim ulang appearance
   (misalnya sesudah recents atau sesudah SystemUI restart), ikon tetap
   mengikuti latar aplikasi dan tidak nyangkut putih.

4. **Navigation bar opsional** — ikon navigation bar bisa ikut menyesuaikan
   atau dibiarkan apa adanya.

5. **Home screen tetap putih (opsional)** — kalau diaktifkan, jam/sinyal/
   baterai di home screen selalu putih, apa pun warna wallpaper. Launcher
   HyperOS di ROM ini dijalankan lewat `/system_ext/bin/hyos_spawner`
   (preload launcher), sehingga modul LSPosed tidak bisa di-hook di proses
   `com.miui.home`. Karena itu fitur ini diproses di **System UI**: aplikasi
   teratas dipantau tiap 500 ms, dan saat home screen tampil ikon dipaksa
   terang. Toggle-nya ada di layar modul dan langsung berlaku (broadcast).

   Kalau toggle dimatikan, warna ikon di home screen diserahkan sepenuhnya
   ke HyperOS — di wallpaper gelap ikon bisa tertinggal gelap, itu perilaku
   bawaan ROM.

6. **Opsi sembunyikan ikon** aplikasi modul dari home screen. Modul tetap
   aktif dan tetap terdaftar di LSPosed Manager.

## Mode pewarnaan

| Mode | Arti |
| --- | --- |
| **Dinamis** (default) | Ikon mengikuti warna latar tiap aplikasi |
| **Selalu gelap** | Paksa ikon gelap di semua aplikasi yang di-scope |
| **Selalu terang** | Paksa ikon putih (perilaku bawaan) |
| **Mati** | Modul tidak mengubah apa pun |

## Pasang

```powershell
adb install -r FixDynamicColorStatusBar-v1.0.5.apk
```

Lalu di **LSPosed Manager**:

1. **Matikan lalu aktifkan lagi** modul **Dynamic Status Bar**. Scope bawaan
   (`xposedscope`) otomatis mencentang **System UI** dan **Settings**.
2. Tambahkan aplikasi lain yang ikonnya ingin ikut menyesuaikan warna.
   Launcher tidak perlu dicentang — warna home screen diatur dari System UI.
3. Force stop aplikasi yang di-scope, atau reboot HP.
4. Kalau scope tetap kosong, centang manual: `com.android.systemui` dan
   `com.android.settings`.

Perubahan setting di layar modul berlaku saat aplikasi dibuka ulang.

## Cek log

```powershell
adb logcat -s LSPosedFramework | Select-String DynStatusBar
```

Contoh baris yang diharapkan:

```
DynStatusBar: hook Activity terpasang di com.android.settings
DynStatusBar: config mode=0 navbar=true homeWhite=true
DynStatusBar: com.android.settings/SettingsHomeActivity -> ikon gelap (content #FFFFFFFF)
DynStatusBar[sysui]: home screen -> ikon terang
```

Kalau ada aplikasi yang salah warna, kirim baris log `-> ikon ... (sumber)`
untuk aplikasi itu — sumber deteksinya menunjukkan dari mana warnanya diambil.

## Build sendiri

```powershell
$env:JAVA_HOME="$env:LOCALAPPDATA\Programs\jdk17\jdk-17.0.20.1+1"
.\gradlew.bat clean assembleDebug --console=plain
```

APK ada di `app\build\outputs\apk\debug\app-debug.apk`.

Struktur: AGP 8.6.1, compileSdk 35, minSdk 29, Java 17, Xposed API 82
(`app/libs/api-82.jar`, compileOnly).

## Catatan

- Konfigurasi dibaca lewat **ordered broadcast eksplisit** (`ConfigReceiver`),
  bukan `XSharedPreferences`: di ROM ini LSPosed mengalihkan SharedPreferences
  modul ke `/data/misc/apexdata` dan SELinux menolak penulisannya.
- Modul tidak mengubah isi layar, hanya appearance ikon sistem.
- Scope aplikasi tetap ditentukan pengguna di LSPosed Manager.

## Lisensi

MIT
