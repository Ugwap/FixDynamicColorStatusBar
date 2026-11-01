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
   - background decor view;
   - background root content view;
   - atribut theme `windowBackground` (termasuk drawable solid/gradient);
   - atribut theme `colorBackground`;
   - atribut `windowLightStatusBar` sebagai cadangan;
   - mode malam (hanya untuk memastikan latar gelap).

   Kalau latar tidak bisa dipastikan, modul **tidak mengubah apa pun** supaya
   tidak salah warna.

2. **Pasang appearance yang benar** — memakai
   `WindowInsetsController.setSystemBarsAppearance` dengan
   `APPEARANCE_LIGHT_STATUS_BARS` (API 30+), atau
   `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` di Android 10 (API 29).
   Appearance dipasang ulang setelah frame pertama dan 400 ms kemudian,
   karena sebagian aplikasi memasang appearance-nya sendiri di akhir.

3. **Navigation bar opsional** — ikon navigation bar bisa ikut menyesuaikan
   atau dibiarkan apa adanya.

4. **Opsi sembunyikan ikon** aplikasi modul dari home screen. Modul tetap
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
adb install -r FixDynamicColorStatusBar-v1.0.0.apk
```

Lalu di **LSPosed Manager**:

1. **Matikan lalu aktifkan lagi** modul **Dynamic Status Bar**. Scope bawaan
   (`xposedscope`) otomatis mencentang **System UI**, **Settings**, dan
   **Launcher**.
2. Tambahkan aplikasi lain kalau perlu.
3. Force stop aplikasi yang di-scope, atau reboot HP.
4. Kalau scope tetap kosong, centang manual: `com.android.systemui`,
   `com.android.settings`, `com.miui.home`.

Perubahan setting di layar modul berlaku saat aplikasi dibuka ulang.

## Cek log

```powershell
adb logcat -s LSPosedFramework | Select-String DynStatusBar
```

Contoh baris yang diharapkan:

```
DynStatusBar: hook Activity terpasang di com.android.settings
DynStatusBar: config mode=0 navbar=true
DynStatusBar: com.android.settings/SettingsHomeActivity -> ikon gelap (content #FFFFFFFF)
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
