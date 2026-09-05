# GolosAI

GolosAI is a local push-to-talk speech-to-text dictation application written in Kotlin. When a configured global hotkey is held, it captures audio input from the microphone, processes and transcribes it, and injects the resulting text into the user's active window upon release.

# Setup

## Prerequisites

- **Java Development Kit (JDK)**: Version 21 or higher.
  - On Fedora / RHEL:
    ```bash
    sudo dnf install java-21-openjdk-devel
    ```
  - On Ubuntu / Debian:
    ```bash
    sudo apt update && sudo apt install openjdk-21-jdk
    ```
  - On macOS (Homebrew):
    ```bash
    brew install openjdk@21
    ```
  - Via SDKMAN:
    ```bash
    sdk install java 21.0.2-tem
    ```

- **Gradle**: Version 8.10 or higher.
  - The project includes the Gradle Wrapper (`./gradlew`), so a system-wide Gradle installation is optional.

- **System Libraries (Linux X11)**:
  - `libX11` (required for global key grab).
  - `libXtst` (optional, recommended for direct X11 key simulation without desktop portal prompts):
    ```bash
    sudo dnf install libXtst
    # or
    sudo apt install libxtst6
    ```

## Download the Code

Clone the repository from GitHub:

```bash
git clone https://github.com/HarisGOD1/GolosAI-Kotlin.git
cd GolosAI-Kotlin
```

## Build

Compile all modules and run automated tests:

```bash
./gradlew test
./gradlew build
```

## Running the Application

Launch the application directly from the terminal:

```bash
./gradlew :application:run
```

### Optional Environment Variables

- `WHISPER_MODEL`: Absolute path to a local GGML model file (default: `models/ggml-base.bin`).
- `WHISPER_BIN`: Path to the `whisper-cli` or `main` binary (default: `whisper-cli`).

Example:
```bash
WHISPER_MODEL="/path/to/ggml-base.bin" WHISPER_BIN="/usr/local/bin/whisper-cli" ./gradlew :application:run
```
