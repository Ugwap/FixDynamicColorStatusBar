## Fix Dynamic Color Status Bar v1.0.1

Perbaikan penting: **scope otomatis**. Tidak perlu centang aplikasi manual lagi.

### Yang diperbaiki

- **Scope bawaan (`xposedscope`)** — saat modul di-enable, LSPosed otomatis
  mencentang:
  - `com.android.systemui` (System UI)
  - `com.android.settings` (Settings / Pengaturan)
  - `com.miui.home` (Launcher / Home HyperOS)
  - `com.android.launcher3` (launcher AOSP)
- Teks panduan di layar modul diperbarui: matikan lalu aktifkan lagi modul
  supaya scope bawaan diterapkan.

### Kenapa v1.0.0 tidak bekerja

Di v1.0.0 scope belum dideklarasikan, jadi LSPosed membiarkan daftar scope
kosong. Tanpa scope, tidak ada proses aplikasi yang di-hook sama sekali.

### Pasang / upgrade

```powershell
adb install -r FixDynamicColorStatusBar-v1.0.1.apk
```

Lalu **wajib**:

1. Buka LSPosed Manager.
2. **Matikan** toggle modul, lalu **aktifkan lagi** — supaya scope bawaan
   diterapkan.
3. Force stop aplikasi yang di-scope, atau reboot HP.
4. Buka Pengaturan → jam/sinyal/baterai harus sudah gelap di latar putih.

Kalau scope masih kosong setelah di-enable ulang, centang manual:
`com.android.systemui`, `com.android.settings`, `com.miui.home`.

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
