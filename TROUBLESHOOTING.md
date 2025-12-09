# MQLTV - IPTV Player Troubleshooting

## Masalah: Channel Tidak Bisa Dibuka

### ✅ Perbaikan yang Sudah Diterapkan:

**1. Fix Compatibility Issue (Smartphone vs TV)**
   - **Masalah**: `androidx.tv.material3.Card` tidak responsive untuk touch di smartphone
   - **Solusi**: Conditional card - Material3 untuk smartphone, TV Material3 untuk TV
   - **File**: `MainActivity.kt` - fungsi `ChannelCard()`

**2. Network Security Configuration**
   - Ditambahkan `usesCleartextTraffic="true"` untuk mengizinkan HTTP (non-HTTPS)
   - Ditambahkan `network_security_config.xml` untuk local network support
   - Mendukung alamat local: 192.168.x.x, 10.x.x.x, localhost

2. **Error Logging**
   - Ditambahkan log untuk debugging di:
     - MainActivity: Saat channel diklik
     - PlayerActivity: Saat load channel, buffering, error playback
   - Ditambahkan Toast notification untuk error yang user-friendly

3. **Channel Repository Loading**
   - Otomatis load channels saat MainActivity dibuka
   - Load ulang channels di PlayerActivity sebelum play
   - Refresh channels saat kembali ke MainActivity

### 🔧 **Debugging Tools:**

**Jalankan script monitor:**
```bash
./debug-monitor.sh
```

**Manual monitoring:**
```bash
adb logcat | grep -E "MainActivity|PlayerActivity|ChannelCard"
```

**Log messages yang normal:**
- `ChannelCard onClick triggered for: [Channel Name]` - Channel diklik
- `Starting PlayerActivity with channel ID: [ID]` - PlayerActivity dimulai  
- `Playing channel: [Name], URL: [URL]` - Stream mulai play

### Kemungkinan Penyebab Channel Tidak Bisa Dibuka:

#### 1. **URL Stream Tidak Valid atau Server Tidak Jalan**
   - **Gejala**: Toast "Error: Failed to play stream" atau "Channel not found"
   - **Solusi**: 
     - Pastikan server HLS sudah running
     - Test URL di browser: `http://192.168.18.54:8080/hls/stream.m3u8`
     - Cek apakah device dan server di network yang sama

#### 2. **Format Stream Tidak Didukung**
   - **Gejala**: Player buffering terus atau error
   - **Solusi**: 
     - Gunakan format HLS (m3u8) yang didukung ExoPlayer
     - Pastikan codec video didukung (H.264/H.265)

#### 3. **Firewall atau Network Issue**
   - **Gejala**: Timeout atau tidak ada response
   - **Solusi**:
     - Cek firewall di server
     - Pastikan port 8080 terbuka
     - Ping ke server dari device: `ping 192.168.18.54`

#### 4. **Permission Denied**
   - **Gejala**: App crash atau tidak respon
   - **Solusi**: 
     - Pastikan permission INTERNET sudah granted
     - Reinstall app jika perlu

### Testing URL Stream:

Untuk HDMI Capture local, pastikan:

1. **Server HLS Running**
   ```bash
   # Contoh FFmpeg untuk HDMI capture ke HLS:
   ffmpeg -f v4l2 -i /dev/video0 -c:v libx264 -preset veryfast \
          -f hls -hls_time 2 -hls_list_size 3 -hls_flags delete_segments \
          /path/to/output/stream.m3u8
   ```

2. **Test dengan VLC atau Browser**
   - VLC: Open Network Stream → `http://192.168.18.54:8080/hls/stream.m3u8`
   - Browser: Buka langsung URL di Chrome/Firefox

3. **Sesuaikan IP di ChannelRepository.kt**
   ```kotlin
   Channel(
       id = 1,
       name = "Event",
       url = "http://[IP_ADDRESS]:[PORT]/hls/stream.m3u8", // Sesuaikan IP dan port
       category = "Local",
       logo = ""
   )
   ```

### Debug Steps:

1. **Build & Install**
   ```bash
   ./gradlew clean
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Monitor Logs**
   ```bash
   adb logcat -c  # Clear logs
   adb logcat | grep -E "MainActivity|PlayerActivity"
   ```

3. **Test dengan Sample Channel Dulu**
   - Coba buka channel "Sintel" atau "Tears of Steel" (online demo)
   - Jika berhasil = network OK, masalah di local stream
   - Jika gagal = ada masalah di app atau device

### Contact & Support:
Jika masih ada masalah, check log error dengan command di atas dan lihat pesan error spesifik.
