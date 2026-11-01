## Fix Dynamic Color Status Bar v1.0.2

Perbaikan deteksi warna latar: ikon tidak lagi "terus gelap" di aplikasi yang
latarnya sebenarnya gelap.

### Yang diperbaiki

- **Sampel piksel asli (`PixelCopy`)** — kalau status bar transparan, modul
  sekarang membaca warna yang benar-benar tampil di area jam/ikon, bukan cuma
  menebak dari atribut theme. Ini menyelesaikan kasus aplikasi bertema terang
  yang isinya gelap (video player, galeri, kamera, aplikasi mode gelap).
- **Deteksi theme dibuat lebih konservatif** — pembacaan background dari anak
  view content dihapus karena sering mengambil warna putih dari view lain
  (misalnya kartu atau tombol) sehingga latar gelap dianggap terang.
- **Log lebih jelas** — setiap perubahan keputusan mencatat sumber warnanya,
  termasuk hasil sampel (`piksel #FF000000`).

### Kalau satu aplikasi masih salah

Coba dulu mode **Selalu gelap** / **Selalu terang** di aplikasi modul untuk
memastikan apakah masalahnya di deteksi atau di aplikasi itu sendiri. Lalu
kirim baris log untuk aplikasi tersebut:

```powershell
adb logcat -s LSPosedFramework | Select-String DynStatusBar
```

Format barisnya:

```
DynStatusBar: com.contoh.app/MainActivity -> ikon terang (piksel #FF000000)
```

### Pasang

```powershell
adb install -r FixDynamicColorStatusBar-v1.0.2.apk
```

Scope tidak perlu diubah lagi. Setelah install, force stop aplikasi yang diuji
(atau reboot) supaya kode baru dimuat.

### Tetap seperti sebelumnya

- Scope otomatis: System UI, Settings, Launcher.
- Mode Dinamis / Selalu gelap / Selalu terang / Mati.
- Navigation bar opsional dan opsi sembunyikan ikon.
