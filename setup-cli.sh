#!/usr/bin/env bash
# CloX CLI build environment setup
# Run once: bash setup-cli.sh
set -e

ANDROID_HOME="$HOME/android-sdk"
TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
TOOLS_URL="https://dl.google.com/android/repository/${TOOLS_ZIP}"

echo "=== Step 1: Download Android command-line tools ==="
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
if [ ! -f "$TOOLS_ZIP" ]; then
    curl -O "$TOOLS_URL"
fi
unzip -q "$TOOLS_ZIP" -d "$ANDROID_HOME/cmdline-tools"
# sdkmanager expects cmdline-tools/latest/
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" 2>/dev/null || true
cd -

export ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "=== Step 2: Accept licenses ==="
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "=== Step 3: Install SDK components ==="
sdkmanager \
    "platforms;android-36" \
    "build-tools;34.0.0" \
    "platform-tools" \
    "ndk;25.2.9519653" \
    "cmake;3.22.1"

echo "=== Step 4: Write local.properties ==="
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cat > "$PROJECT_DIR/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_HOME/ndk/25.2.9519653
EOF

echo "=== Step 5: Download Gradle wrapper JAR ==="
WRAPPER_JAR="$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    curl -L \
        "https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar" \
        -o "$WRAPPER_JAR"
fi

echo "=== Step 6: Make gradlew executable ==="
chmod +x "$PROJECT_DIR/gradlew" 2>/dev/null || true

echo ""
echo "Done. Add these to your shell profile (~/.bashrc or ~/.zshrc):"
echo "  export ANDROID_HOME=$ANDROID_HOME"
echo "  export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/ndk/25.2.9519653:\$PATH"
echo ""
echo "Then build with:  cd $PROJECT_DIR && ./gradlew assembleDebug"
echo "Install on device: adb install app/build/outputs/apk/debug/app-debug.apk"
