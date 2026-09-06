#!/bin/bash
set -e

# ==============================================================================
# GolosAI Automated Installer & Launcher for Linux (Ubuntu, Debian, Fedora, Arch)
# ==============================================================================

echo "============================================================"
echo "          GolosAI Voice Assistant - Linux Installer         "
echo "============================================================"
echo ""

APP_NAME="GolosAI"
INSTALL_DIR="$HOME/.local/share/golos-ai"
BIN_DIR="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"
AUTOSTART_DIR="$HOME/.config/autostart"

mkdir -p "$INSTALL_DIR" "$BIN_DIR" "$DESKTOP_DIR" "$AUTOSTART_DIR"

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
    echo "    Attempting to install OpenJDK 21 via system package manager..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update && sudo apt-get install -y openjdk-21-jre libxtst6 libx11-6
    elif command -v dnf >/dev/null 2>&1; then
        sudo dnf install -y java-21-openjdk libXtst libX11
    elif command -v pacman >/dev/null 2>&1; then
        sudo pacman -S --noconfirm jre21-openjdk libxtst libx11
    else
        echo "[ERROR] Could not automatically install Java 21. Please install OpenJDK 21 manually."
        exit 1
    fi
fi

# 2. Build or copy distribution files
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ -f "$PROJECT_ROOT/gradlew" ]; then
    echo "[*] Building application packages..."
    (cd "$PROJECT_ROOT" && ./gradlew :application:installDist --no-daemon -q)
    cp -r "$PROJECT_ROOT/application/build/install/application/"* "$INSTALL_DIR/"
elif [ -d "$SCRIPT_DIR/lib" ]; then
    cp -r "$SCRIPT_DIR/"* "$INSTALL_DIR/"
else
    echo "[ERROR] Application binaries not found. Please build with './gradlew :application:installDist'."
    exit 1
fi

# Copy pre-downloaded models if available
if [ -d "$PROJECT_ROOT/models" ]; then
    mkdir -p "$INSTALL_DIR/models"
    cp -r "$PROJECT_ROOT/models/"* "$INSTALL_DIR/models/" 2>/dev/null || true
fi

# 3. Create launcher script in ~/.local/bin/golos-ai
cat << 'EOF' > "$BIN_DIR/golos-ai"
#!/bin/bash
APP_DIR="$HOME/.local/share/golos-ai"
export PATH="$APP_DIR/bin:$PATH"
exec "$APP_DIR/bin/application" "$@"
EOF
chmod +x "$BIN_DIR/golos-ai" "$INSTALL_DIR/bin/application"

# 4. Create Desktop shortcut
cat << EOF > "$DESKTOP_DIR/golos-ai.desktop"
[Desktop Entry]
Type=Application
Name=GolosAI
GenericName=Speech-to-Text Assistant
Comment=Local Push-to-Talk Speech Recognition & Dictation
Exec=$BIN_DIR/golos-ai
Icon=audio-input-microphone
Terminal=false
Categories=Utility;Audio;AudioVideo;
StartupNotify=true
EOF
chmod +x "$DESKTOP_DIR/golos-ai.desktop"

# 5. Linux Wayland / DevInput Permissions Setup
echo "[*] Checking Linux global hotkey permissions (/dev/input)..."
USER_NAME="$(whoami)"
if command -v setfacl >/dev/null 2>&1; then
    sudo setfacl -m u:$USER_NAME:rw /dev/input/event* /dev/uinput 2>/dev/null || true
fi
sudo usermod -aG input "$USER_NAME" 2>/dev/null || true

# Install persistent udev rules if sudo is available
if [ -w /etc/udev/rules.d ] || sudo -n true 2>/dev/null || [ "$EUID" -eq 0 ]; then
    sudo bash -c 'cat << "EOF" > /etc/udev/rules.d/99-golos-input.rules
KERNEL=="event*", SUBSYSTEM=="input", MODE="0660", GROUP="input", TAG+="uaccess"
KERNEL=="uinput", SUBSYSTEM=="misc", MODE="0660", GROUP="input", TAG+="uaccess"
EOF' 2>/dev/null || true
    sudo udevadm control --reload-rules 2>/dev/null || true
    sudo udevadm trigger 2>/dev/null || true
fi

echo ""
echo "============================================================"
echo "           GolosAI successfully installed!                  "
echo "============================================================"
echo ""
echo "You can launch GolosAI in any of the following ways:"
echo "  1. From your Application Menu: search for 'GolosAI'"
echo "  2. From terminal: golos-ai  (or ~/.local/bin/golos-ai)"
echo ""
echo "Default push-to-talk hotkey is F8 (configurable in Settings)."
echo "============================================================"
