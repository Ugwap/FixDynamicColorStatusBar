## Fix Dynamic Color Status Bar v1.0.0

Modul LSPosed baru: ikon status bar (jam, sinyal, baterai) sekarang **ikut
warna latar belakang**. Latar terang → ikon gelap, latar gelap → ikon terang.
Masalah ikon putih di atas latar putih (Pengaturan, recents) tidak lagi terjadi.

### Yang baru

- **Deteksi latar otomatis per aplikasi**
  - Warna diambil dari status bar, background decor, root content view, lalu
    atribut theme `windowBackground` / `colorBackground` / `windowLightStatusBar`.
  - Kalau latar tidak bisa dipastikan, modul tidak mengubah apa pun — jadi
    tidak ada risiko ikon salah warna.
- **Pemasangan appearance yang tahan ditimpa**
  - Memakai `WindowInsetsController.setSystemBarsAppearance`
    (`APPEARANCE_LIGHT_STATUS_BARS`) di Android 11+, dengan fallback
    `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` untuk Android 10.
  - Diterapkan lagi setelah frame pertama dan 400 ms kemudian supaya tidak
    kalah oleh aplikasi yang memasang appearance di akhir.
- **Tiga mode**: Dinamis (default), Selalu gelap, Selalu terang, plus Mati.
- **Navigation bar opsional** — bisa ikut menyesuaikan atau dibiarkan.
- **Sembunyikan ikon** aplikasi dari home screen; layar modul tetap bisa
  dibuka dari LSPosed Manager.

### Pasang

```powershell
adb install -r FixDynamicColorStatusBar-v1.0.0.apk
```

Di LSPosed Manager:

1. Aktifkan modul **Dynamic Status Bar**.
2. Centang aplikasi yang bermasalah: **Pengaturan/Settings**, **Launcher**,
   **System UI** (untuk recents).
3. Force stop aplikasi itu, lalu buka lagi.
4. Kalau belum berubah, reboot.

### Cek log

```powershell
adb logcat -s LSPosedFramework | Select-String DynStatusBar
```

Contoh:

```
DynStatusBar: hook Activity terpasang di com.android.settings
DynStatusBar: config mode=0 navbar=true
DynStatusBar: com.android.settings/SettingsHomeActivity -> ikon gelap (content #FFFFFFFF)
```

### Catatan

- Perubahan setting berlaku setelah aplikasi dibuka ulang.
- Kalau ada aplikasi yang masih salah warna, coba mode **Selalu gelap**, atau
  kirim baris log `-> ikon ... (sumber)` untuk aplikasi tersebut.
- Tidak ada perubahan pada isi layar; hanya appearance ikon sistem.
