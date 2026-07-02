# Melodix OpenAL Patch
# [⚠️] This Unofficial fork for Melodix mod 

## 🎯 Tujuan
Patch mod **Melodix Music Player** untuk mengganti backend audio dari `javax.sound.sampled` (Java Sound API) ke **OpenAL** (LWJGL), sehingga kompatibel dengan **Android (Pojav/MJ Launcher)**.

## 📦 File
- `melodix-1.0.0.jar` — JAR asli (letakkan di folder `original/`)
- `melodix-1.0.0-openal.jar` — JAR hasil patch (output)

## 🛠️ Cara Build

### Persyaratan
- Java 21 atau lebih baru
- Gradle (wrapper sudah include)
- PC dengan OS Windows/Linux/Mac

### Langkah

1. **Install Java 21**
   ```bash
   # Ubuntu/Debian
   sudo apt install openjdk-21-jdk

   # Windows: Download dari https://adoptium.net/
   # Mac: brew install openjdk@21
   ```

2. **Clone/Extract project ini**
   ```bash
   cd melodix-openal-gradle
   ```

3. **Letakkan JAR asli**
   ```bash
   mkdir -p original
   # Copy melodix-1.0.0.jar ke folder original/
   ```

4. **Build**
   ```bash
   ./gradlew build
   ./build.sh
   ```

   Atau di Windows:
   ```cmd
   gradlew.bat build
   build.bat
   ```

5. **Ambil hasil**
   - File `melodix-1.0.0-openal.jar` akan muncul di root folder
   - Copy ke `mods/` folder Minecraft
   - Hapus JAR asli

## 🔄 Perubahan

### Class yang Diganti
| Class | Perubahan |
|-------|-----------|
| `AudioEngine` | Hapus `SourceDataLine`, ganti dengan `OpenALAudioPlayer` |
| `AudioEngine$PlaybackSlot` | Rewrite playback logic menggunakan OpenAL |
| `PcmStream` | Hapus `AudioFormat`, ganti dengan parameter primitive |
| `FullPcmDecoder` | Hapus `AudioFormat`, return `ByteBuffer` + sampleRate/channels |
| `OpenALAudioPlayer` | **Class baru** — wrapper OpenAL |

### Class yang Tetap (Tidak Diubah)
- `MusicPlayerManager` — hanya menggunakan `AudioEngine` public API
- `MusicPlayerScreen` — UI layer
- `AudioVisualizerHud` — visualizer (tetap baca dari PCM buffer)
- `SoundtrackStateManager` — vanilla music integration
- Semua class lain yang tidak terkait audio playback

## 🎵 Cara Kerja OpenAL Backend

```
MP3 File → JLayer Decoder → PCM ByteBuffer → [Split ke Visualizer] → OpenAL Buffer → OpenAL Source → Speaker
WAV File → Parse Header → PCM ByteBuffer → [Split ke Visualizer] → OpenAL Buffer → OpenAL Source → Speaker
```

### Keuntungan
- ✅ **PC**: LWJGL OpenAL sudah include di Minecraft
- ✅ **Android**: Pojav/MJ Launcher punya OpenAL wrapper
- ✅ **No javax.sound**: Hapus semua dependency ke Java Sound API

## ⚠️ Known Issues / Limitations

1. **Large files**: Saat ini seluruh file di-buffer ke memory. File > 50MB mungkin crash. Solusi: streaming dengan `alSourceQueueBuffers()`.

2. **Seeking**: Seek diimplementasikan dengan re-decode dari posisi target. Untuk MP3 besar, ini bisa lambat.

3. **Crossfade**: Dua OpenAL source berjalan bersamaan. Volume fade via `AL_GAIN`.

4. **Pitch control**: `AL_PITCH` digunakan untuk playback speed. Ini juga mengubah pitch (chipmunk effect). Untuk time-stretching tanpa pitch shift, butuh algoritma DSP tambahan.

## 🐛 Troubleshooting

### Build Error: "Cannot find symbol"
- Pastikan `JAVA_HOME` di-set ke Java 21
- Pastikan Gradle menggunakan Java 21: `./gradlew -v`

### Runtime Error: "OpenAL error"
- Pastikan driver audio PC/Android berfungsi
- Cek log Minecraft untuk detail error

### No Sound
- Cek volume di mod settings
- Cek apakah file MP3/WAV valid (coba di PC dulu)

## 📄 License
MIT License (sama dengan mod asli)

## 🙏 Credits
- **distelbus-svg**: Mod Melodix asli
- **OpenAL Patch**: Konversi backend audio
