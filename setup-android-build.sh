#!/bin/bash

echo "=== MQLTV Android Build Setup Script ==="
echo ""

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check Java
echo "1. Checking Java..."
if command_exists java; then
    echo "✅ Java found: $(java -version 2>&1 | head -n 1)"
else
    echo "❌ Java not found. Installing OpenJDK 17..."
    sudo apt update
    sudo apt install -y openjdk-17-jdk
    echo "✅ Java installed"
fi

# Set JAVA_HOME if not set
if [ -z "$JAVA_HOME" ]; then
    echo "Setting JAVA_HOME..."
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    echo "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64" >> ~/.bashrc
    echo "✅ JAVA_HOME set to $JAVA_HOME"
fi

# Check Android SDK
echo ""
echo "2. Checking Android SDK..."
if [ -d "$HOME/Android/Sdk" ] || [ -d "$ANDROID_HOME" ]; then
    echo "✅ Android SDK found"
else
    echo "❌ Android SDK not found. Setting up minimal SDK..."
    
    # Create Android SDK directory
    mkdir -p ~/Android/Sdk
    cd ~/Android/Sdk
    
    # Download command line tools
    echo "Downloading Android Command Line Tools..."
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-8092744_latest.zip
    unzip -q commandlinetools-linux-8092744_latest.zip
    mkdir -p cmdline-tools/latest
    mv cmdline-tools/* cmdline-tools/latest/ 2>/dev/null || true
    rm commandlinetools-linux-8092744_latest.zip
    
    echo "✅ Android SDK tools downloaded"
fi

# Set Android environment variables
echo ""
echo "3. Setting Android environment variables..."
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/build-tools/34.0.0

# Add to bashrc if not already there
if ! grep -q "ANDROID_HOME" ~/.bashrc; then
    echo "export ANDROID_HOME=\$HOME/Android/Sdk" >> ~/.bashrc
    echo "export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin" >> ~/.bashrc
    echo "export PATH=\$PATH:\$ANDROID_HOME/platform-tools" >> ~/.bashrc
    echo "export PATH=\$PATH:\$ANDROID_HOME/build-tools/34.0.0" >> ~/.bashrc
    echo "✅ Environment variables added to ~/.bashrc"
fi

# Install required SDK components
echo ""
echo "4. Installing required SDK components..."
if command_exists sdkmanager; then
    echo "Installing platform-tools, build-tools, and platform..."
    yes | sdkmanager --licenses >/dev/null 2>&1
    sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"
    echo "✅ SDK components installed"
else
    echo "⚠️  sdkmanager not found. You may need to restart terminal and run this script again."
fi

echo ""
echo "5. Testing Gradle build..."
cd /home/dindin/AndroidStudioProjects/MQLTV

# Make gradlew executable
chmod +x gradlew

# Test build
echo "Running clean build test..."
if ./gradlew clean >/dev/null 2>&1; then
    echo "✅ Gradle working correctly"
else
    echo "❌ Gradle test failed. Check dependencies."
fi

echo ""
echo "=== Setup Complete! ==="
echo ""
echo "📋 Next steps:"
echo "1. Restart terminal or run: source ~/.bashrc"
echo "2. In VS Code: Ctrl+Shift+P → 'Tasks: Run Task'"
echo "3. Select 'Build Debug APK' to build the app"
echo ""
echo "🚀 Build commands:"
echo "   ./gradlew assembleDebug      # Build debug APK"
echo "   ./gradlew installDebug       # Install to device"
echo "   ./gradlew clean              # Clean project"
echo ""
echo "📁 APK output: app/build/outputs/apk/debug/app-debug.apk"