# VS Code Android Build Setup

## Setup untuk Build Android di VS Code (Tanpa Android Studio)

### 1. Install Required Tools:

```bash
# Install Java/OpenJDK 17 (Required for Android Gradle Plugin 8+)
sudo apt install openjdk-17-jdk

# Install Android SDK Command Line Tools
wget https://dl.google.com/android/repository/commandlinetools-linux-8092744_latest.zip
unzip commandlinetools-linux-8092744_latest.zip
mkdir -p ~/Android/Sdk/cmdline-tools/latest
mv cmdline-tools/* ~/Android/Sdk/cmdline-tools/latest/

# Set Environment Variables (add to ~/.bashrc)
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/build-tools/34.0.0

# Install SDK components
sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"
```

### 2. VS Code Extensions (Install):

1. **Kotlin Language** - untuk syntax highlighting
2. **Android iOS Emulator** - untuk run emulator
3. **Gradle for Java** - untuk Gradle support
4. **Error Lens** - untuk inline error display

### 3. Build Commands (Gunakan VS Code Tasks):

Tekan `Ctrl+Shift+P` → `Tasks: Run Task` → Pilih:

- **Build Debug APK** - Compile app
- **Install Debug APK** - Install ke device/emulator  
- **Clean Project** - Bersihkan cache
- **Clean & Build Debug** - Clean + Build

### 4. Manual Build Commands:

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install ke device yang terhubung
./gradlew installDebug

# Clean project
./gradlew clean

# Check dependencies
./gradlew dependencies

# Lint check
./gradlew lintDebug
```

### 5. Output Locations:

- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`

### 6. Install APK ke Device:

```bash
# Via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Via Gradle (recommended)
./gradlew installDebug
```

### 7. Keyboard Shortcuts:

- `Ctrl+Shift+P` → `Tasks: Run Task` → Pilih task
- `F5` → Run default build task
- `Ctrl+Shift+B` → Build (if configured)

### 8. Project Structure di VS Code:

```
MQLTV/
├── .vscode/
│   ├── tasks.json        # Build tasks
│   └── settings.json     # Project settings
├── app/
│   ├── src/main/
│   └── build.gradle.kts
├── gradlew              # Gradle wrapper (Linux/Mac)
├── gradlew.bat          # Gradle wrapper (Windows)
└── build.gradle.kts     # Root build file
```

### 9. Monitor Build Output:

VS Code akan menampilkan output di terminal panel bawah. Error akan muncul di:
- **Terminal** - Build output
- **Problems Panel** - Error list
- **Editor** - Inline errors (jika Error Lens installed)

### 10. Tips Optimization:

```bash
# Gradle daemon untuk build lebih cepat
echo "org.gradle.daemon=true" >> gradle.properties
echo "org.gradle.parallel=true" >> gradle.properties
echo "org.gradle.configureondemand=true" >> gradle.properties

# Set Java heap size
echo "org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8" >> gradle.properties
```

### Penghematan Storage vs Android Studio:

- **Android Studio**: ~3-4 GB
- **VS Code Setup**: ~500 MB (SDK + tools minimal)
- **Gradle Cache**: Shared dengan Android Studio jika ada

Dengan setup ini, Anda bisa build Android project menggunakan Gradle yang sama dengan Android Studio, tapi jauh lebih hemat storage!