# GolosAI

GolosAI is a lightweight, 100% local speech-to-text dictation assistant. When you hold a global hotkey (default: **F8**), it captures audio input from your microphone, runs local whisper.cpp speech recognition on your device, and directly types the recognized text into your active application (Browser, Telegram, IntelliJ IDEA, VS Code, Mail, Text Editor, etc.).

---

# Quick Installation (For Users)

Choose your operating system below. No programming experience required.

## Windows 10 / 11

### Option 1: Automated 1-Click Installer
1. Download `GolosAI-Windows-x64.zip` from the Releases page or package folder.
2. Unpack the `.zip` archive to any folder.
3. Double-click **`install.bat`** (or right-click -> "Run as administrator" if prompted).
4. The installer will automatically verify Java 21, set up application shortcuts on your **Desktop** and in your **Start Menu**, and link the application.
5. Launch **GolosAI** from your Desktop shortcut or Start Menu!

### Option 2: Quick Start without Installer
- Unpack `GolosAI-Windows-x64.zip` and run `bin\application.bat`.

---

## Linux (Ubuntu, Debian, Fedora, Arch, Pop!_OS)

### Option 1: Automated 1-Click Installer
1. Download `GolosAI-Linux-x64.tar.gz` and extract it:
   ```bash
   tar -xzf GolosAI-Linux-x64.tar.gz
   cd GolosAI
   ```
2. Run the installer script:
   ```bash
   ./install.sh
   ```
3. The installer automatically installs OpenJDK 21 (if needed), registers `golos-ai` in your system `PATH`, configures Wayland `/dev/input` permissions, and adds **GolosAI** to your desktop application launcher menu.
4. Launch **GolosAI** from your Application Menu or by typing `golos-ai` in your terminal.

---

## macOS (Apple Silicon M1/M2/M3/M4 & Intel)

### Option 1: Automated 1-Click App Installer
1. Download `GolosAI-macOS-universal.zip` and extract it:
   ```bash
   unzip GolosAI-macOS-universal.zip
   cd GolosAI
   ```
2. Run the macOS installer script:
   ```bash
   ./install.sh
   ```
3. The installer creates **`GolosAI.app`** in your `/Applications` directory.
4. **First-time permission grant**:
   - Open **System Settings -> Privacy & Security -> Microphone** -> Allow **GolosAI**.
   - Open **System Settings -> Privacy & Security -> Accessibility** -> Allow **GolosAI** (needed for global hotkey and text typing).
5. Launch **GolosAI** from Launchpad or Spotlight (`Cmd + Space` -> `GolosAI`).

---

# How to Use GolosAI

1. **Start Dictation**: Hold down **F8** (or your configured hotkey) in any application and speak into your microphone.
2. **Finish & Insert**: Release the key. GolosAI transcribes your speech locally in real-time and inserts the text right where your cursor is.
3. **Indicator Bar**: The floating status bar at the top displays current status:
   - **APP** (Green): Engine active and ready.
   - **VOICE** (Green: Idle, Amber: Listening, Red: Processing).
   - **MODE** (Current hotkey & insertion mode).
4. **Settings & Customization**: Click the gear icon or expand the main window to customize:
   - Push-to-talk key vs. Toggle on/off mode.
   - Text insertion mode (Direct keystrokes vs. Clipboard paste).
   - Recognition language (English, Russian, Multilingual auto-detection).
   - Microphone input device, gain, and noise reduction.
   - Application-specific vocabulary and custom correction dictionary.

---

# Developer Setup & Build Instructions

If you want to modify the source code, develop plugins, or build from scratch, follow this section.

## Prerequisites

- **Java Development Kit (JDK)**: Version 21 or higher.
  - Fedora / RHEL: `sudo dnf install java-21-openjdk-devel`
  - Ubuntu / Debian: `sudo apt update && sudo apt install openjdk-21-jdk`
  - macOS (Homebrew): `brew install openjdk@21`
  - Via SDKMAN: `sdk install java 21.0.2-tem`
- **Gradle**: Version 8.10+ (Gradle Wrapper `./gradlew` is included in the repo).
- **System Libraries (Linux X11/Wayland)**:
  - `libX11`, `libXtst` (for global key interception and text injection).

## Clone Repository

```bash
git clone https://github.com/HarisGOD1/GolosAI-Kotlin.git
cd GolosAI-Kotlin
```

## Build and Run from Source

```bash
# Run automated test suite
./gradlew test

# Run code style and static analysis checks
./gradlew ktlintCheck detekt

# Launch application in development mode
./gradlew :application:run
```

## Build Standalone Release Installers

Build standalone archive distributions for Windows, macOS, and Linux:

```bash
./gradlew :application:packageAllDistributions
```

The output packages will be generated under `application/build/distributions/`:
- `GolosAI-Windows-x64-1.0-SNAPSHOT.zip` (with Windows `install.bat` and `uninstall.bat`)
- `GolosAI-Linux-x64-1.0-SNAPSHOT.tar.gz` (with Linux `install.sh` and `uninstall.sh`)
- `GolosAI-macOS-universal-1.0-SNAPSHOT.zip` (with macOS `install.sh` and `uninstall.sh`)

## Optional Environment Variables

- `WHISPER_MODEL`: Absolute path to a local GGML model file (default: automatically managed in `~/.cache/golos-ai/models/`).
- `WHISPER_BIN`: Path to custom `whisper-cli` or `main` binary (default: automatically managed in `~/.cache/golos-ai/bin/`).

Example:
```bash
WHISPER_MODEL="/path/to/ggml-base.bin" WHISPER_BIN="/usr/local/bin/whisper-cli" ./gradlew :application:run
```
