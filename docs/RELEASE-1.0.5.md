## Fix Dynamic Color Status Bar v1.0.5

Menambahkan pilihan **home screen tetap putih** dan memperluas dukungan ke
aplikasi pihak ketiga.

### Fitur baru: home screen tetap putih

Launcher HyperOS di ROM ini (`com.miui.home`) dijalankan oleh
`/system_ext/bin/hyos_spawner` sebagai *preload launcher*. Zygisk/LSPosed tetap
ter-inject, tetapi modul tidak dimuat di proses launcher, sehingga hook di
`com.miui.home` tidak mungkin dipasang.

Karena itu fitur ini dipindah ke sisi **System UI**:

- System UI memantau aplikasi teratas tiap 500 ms memakai
  `ActivityManager.getRunningTasks(1)` (System UI memegang izin
  `REAL_GET_TASKS`).
- Selama home screen tampil, ikon dipaksa terang dan waktu override
  (`sOverrideAt`) terus disegarkan supaya `LightBarController` HyperOS tidak
  menimpanya dengan appearance basi.
- Pemeriksaan dilewati saat layar mati atau keyguard aktif.
- Toggle di layar modul mengirim broadcast langsung ke System UI, jadi
  perubahan berlaku seketika tanpa restart.

Kalau toggle dimatikan, modul tidak menyentuh ikon di home screen sama sekali;
warna diserahkan ke HyperOS. Di wallpaper gelap ikon bisa tertinggal gelap —
itu perilaku bawaan ROM, bukan bug modul. Teks di layar modul sudah menjelaskan
hal ini.

### Scope aplikasi pihak ketiga

Modul hanya bisa menyesuaikan warna ikon di aplikasi yang dicentang di scope
LSPosed, karena warna latar harus dibaca dari dalam proses aplikasi. Scope
sekarang mencakup **50 aplikasi pihak ketiga** yang terpasang di perangkat uji
(Chrome, WhatsApp, Reddit, YouTube/YouTube Music, GitHub, Brave, Facebook,
Roblox, PES, Nekogram, dll.), di samping System UI, Settings, dan System
Framework.

Launcher (`com.miui.home`) tetap ada di scope untuk kompatibilitas, tetapi
tidak dipakai lagi untuk fitur home screen.

### Perbaikan lain

- Deskripsi di layar modul diperbarui: Launcher tidak perlu dicentang, dan
  perilaku toggle OFF dijelaskan apa adanya.
- `ConfigReceiver` menerima `KEY_HOME_WHITE` sehingga System UI tahu perubahan
  toggle tanpa menunggu pembacaan berkala.

### Hasil uji di perangkat

- Chrome: latar terang -> ikon gelap (`decor #FFFFFFFF`, `piksel #FFF8F9FD`).
- YouTube Music: latar gelap -> ikon terang (`piksel #FF000000`).
- Settings -> home: ikon gelap berubah menjadi putih (log
  `home screen -> ikon terang`), tidak nyangkut.
- Toggle ON: log `[sysui]: home putih=true`; toggle OFF: `home putih=false`
  dan tidak ada pemaksaan di home.
- Setelah DB scope diperluas dan reboot, modul dimuat di Chrome dan YouTube
  Music (log `hook Activity terpasang` + keputusan warna per aplikasi).

### Pasang

```powershell
adb install -r FixDynamicColorStatusBar-v1.0.5.apk
```

Setelah install, restart SystemUI sekali supaya hook sisi System UI dimuat:

```powershell
adb shell su -c "kill -9 $(pidof com.android.systemui)"
```

Tunggu ±30 detik sampai status bar kembali.

### Catatan

- Scope 50 aplikasi disiapkan lewat basis data LSPosed
  (`/data/adb/lspd/config/modules_config.db`, tabel `scope`) karena UI LSPosed
  Manager di perangkat ini sering tidak responsif. Perubahan tetap bisa
  dilakukan manual lewat LSPosed Manager.
- Log: `adb shell su -c "grep -h DynStatusBar /data/adb/lspd/log/modules_*.log | tail -20"`.
