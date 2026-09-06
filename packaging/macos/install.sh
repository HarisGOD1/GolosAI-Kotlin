#!/bin/bash
set -e

# ==============================================================================
# GolosAI Automated Installer & .app Bundler for macOS (Intel & Apple Silicon)
# ==============================================================================

echo "============================================================"
echo "          GolosAI Voice Assistant - macOS Installer         "
echo "============================================================"
echo ""

APP_DIR="/Applications/GolosAI.app"
USER_APP_DIR="$HOME/Applications/GolosAI.app"
TARGET_DIR="$APP_DIR"

if [ ! -w "/Applications" ]; then
    TARGET_DIR="$USER_APP_DIR"
fi

mkdir -p "$TARGET_DIR/Contents/MacOS"
mkdir -p "$TARGET_DIR/Contents/Resources"
mkdir -p "$TARGET_DIR/Contents/Java"

# 1. Check or install Java 21 runtime
check_java() {
    if command -v java >/dev/null 2>&1; then
        JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
        if [ "$JAVA_VER" -ge 21 ] 2>/dev/null; then
            echo "[OK] Java $JAVA_VER is installed."
            return 0
        fi
    fi
    return 1
}

if ! check_java; then
    echo "[!] Java 21 or higher is required."
    if command -v brew >/dev/null 2>&1; then
        echo "    Installing OpenJDK 21 via Homebrew..."
        brew install openjdk@21
        sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk 2>/dev/null || true
    else
        echo "[ERROR] Java 21 not found. Please install OpenJDK 21 from https://adoptium.net/"
        exit 1
    fi
fi

# 2. Build or copy distribution files
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ -f "$PROJECT_ROOT/gradlew" ]; then
    echo "[*] Building application packages..."
    (cd "$PROJECT_ROOT" && ./gradlew :application:installDist --no-daemon -q)
    cp -r "$PROJECT_ROOT/application/build/install/application/lib/"* "$TARGET_DIR/Contents/Java/"
    cp -r "$PROJECT_ROOT/application/build/install/application/bin/"* "$TARGET_DIR/Contents/MacOS/"
elif [ -d "$SCRIPT_DIR/lib" ]; then
    cp -r "$SCRIPT_DIR/lib/"* "$TARGET_DIR/Contents/Java/"
    cp -r "$SCRIPT_DIR/bin/"* "$TARGET_DIR/Contents/MacOS/"
else
    echo "[ERROR] Application binaries not found. Please build with './gradlew :application:installDist'."
    exit 1
fi

# Copy pre-downloaded models if available
if [ -d "$PROJECT_ROOT/models" ]; then
    mkdir -p "$TARGET_DIR/Contents/Resources/models"
    cp -r "$PROJECT_ROOT/models/"* "$TARGET_DIR/Contents/Resources/models/" 2>/dev/null || true
fi

# 3. Create Info.plist
cat << EOF > "$TARGET_DIR/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>golos-launcher</string>
    <key>CFBundleIdentifier</key>
    <string>su.kamil.dev.golos</string>
    <key>CFBundleName</key>
    <string>GolosAI</string>
    <key>CFBundleDisplayName</key>
    <string>GolosAI</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0.0</string>
    <key>CFBundleVersion</key>
    <string>1.0.0</string>
    <key>NSMicrophoneUsageDescription</key>
    <string>GolosAI requires microphone access to transcribe your speech into text.</string>
    <key>NSAppleEventsUsageDescription</key>
    <string>GolosAI requires accessibility permissions to inject transcribed text into your active applications.</string>
    <key>LSUIElement</key>
    <false/>
    <key>NSHighResolutionCapable</key>
    <true/>
</dict>
</plist>
EOF

# 4. Create macOS executable launcher
cat << 'EOF' > "$TARGET_DIR/Contents/MacOS/golos-launcher"
#!/bin/bash
BUNDLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export CLASSPATH="$BUNDLE_DIR/Java/*"

# Locate Java binary
JAVA_CMD="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
elif [ -x "/opt/homebrew/opt/openjdk@21/bin/java" ]; then
    JAVA_CMD="/opt/homebrew/opt/openjdk@21/bin/java"
elif [ -x "/usr/local/opt/openjdk@21/bin/java" ]; then
    JAVA_CMD="/usr/local/opt/openjdk@21/bin/java"
fi

exec "$JAVA_CMD" \
    -Dapple.laf.useScreenMenuBar=true \
    -Dcom.apple.mrj.application.apple.menu.about.name=GolosAI \
    -cp "$BUNDLE_DIR/Java/*" \
    su.kamil.dev.golos.app.MainKt "$@"
EOF

chmod +x "$TARGET_DIR/Contents/MacOS/golos-launcher"
chmod +x "$TARGET_DIR/Contents/MacOS/"* 2>/dev/null || true

# 5. Accessibility & Microphone Permission Instructions
echo ""
echo "============================================================"
echo "           GolosAI successfully installed on macOS!         "
echo "============================================================"
echo ""
echo "Installed to: $TARGET_DIR"
echo ""
echo "Important: First-time setup on macOS:"
echo "  1. Open System Settings -> Privacy & Security -> Microphone"
echo "     and allow GolosAI."
echo "  2. Open System Settings -> Privacy & Security -> Accessibility"
echo "     and allow GolosAI (needed for global hotkey and text typing)."
echo ""
echo "You can launch GolosAI from Spotlight (Cmd+Space -> GolosAI) or Launchpad."
echo "============================================================"
