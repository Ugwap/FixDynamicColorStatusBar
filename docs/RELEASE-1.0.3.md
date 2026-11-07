## Fix Dynamic Color Status Bar v1.0.3

Perbaikan ikon status bar yang **nyangkut putih** setelah kembali dari recents
atau setelah SystemUI dimulai ulang.

### Akar masalah

WindowManager HyperOS tidak selalu mengirim ulang *appearance* (permintaan
`APPEARANCE_LIGHT_STATUS_BARS`) ketika aplikasi kembali ke depan. SystemUI
menyimpan state lama (`appearance=NONE`), lalu `LightBarControllerImpl`
menghitung ulang berdasarkan state basi itu dan memaksa ikon tetap putih.
Membuka ulang aplikasi dari awal biasanya benar, sehingga bug terlihat
"hilang timbul".

### Yang diperbaiki

- **Kanal langsung aplikasi -> SystemUI**: setiap Activity yang tampil mengirim
  keputusan terang/gelap lewat broadcast. SystemUI menerimanya dan memasang
  warna ikon langsung ke pipeline HyperOS
  (`DarkIconDispatcherImpl` -> `LightBarTransitionsController.setIconsDark`),
  tanpa menunggu WindowManager.
- **Penegasan ulang**: kalau MIUI menghitung ulang status bar tepat setelah
  transisi window (dan memakai appearance basi), keputusan aplikasi ditegakkan
  lagi selama ±1,5 detik.
- **Launcher tidak ditimpa** dalam mode dinamis: warna ikon di home screen dan
  recents diserahkan ke logika wallpaper bawaan HyperOS, karena sampling
  piksel di launcher bisa membaca warna yang salah (wallpaper digambar di
  window lain).
- **Mode Mati** melepas paksaan dan mengembalikan kendali ke sistem.

### Hasil uji di perangkat

- Settings dibuka dari awal: ikon gelap.
- SystemUI dimatikan saat Settings di depan, lalu SystemUI hidup lagi:
  ikon kembali gelap (sebelumnya nyangkut putih).
- Home dengan wallpaper gelap: ikon putih (ditangani HyperOS).
- Recents lalu kembali ke Settings: ikon gelap.

### Pasang

```powershell
adb install -r FixDynamicColorStatusBar-v1.0.3.apk
```

Setelah install, **restart SystemUI** sekali (bukan hanya force stop aplikasi)
supaya hook sisi SystemUI dimuat:

```powershell
adb shell su -c "kill -9 $(pidof com.android.systemui)"
```

Tunggu ±30 detik sampai status bar kembali. Scope tidak perlu diubah.

### Catatan

Log tambahan bisa dilihat dengan:

```powershell
adb shell su -c "grep -h DynStatusBar /data/adb/lspd/log/modules_*.log | tail -20"
```

Baris `DynStatusBar[sysui]: ikon gelap/terang dari <paket>` berarti kanal
aplikasi -> SystemUI bekerja.
