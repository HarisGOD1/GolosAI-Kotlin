# GolosAI Product Backlog & Roadmap

## Production Features & Implementation Plan

### 1. Whisper.cpp Binary & Multilingual Model Downloader [Implemented]
- **Automatic / One-Click Whisper.cpp Setup**:
  - Automatically detects host OS and architecture (Linux x86_64/ARM64, macOS, Windows x64).
  - Downloads official precompiled binary archive directly into local user cache (`~/.cache/golos-ai/bin/`).
  - Sets executable permissions (`chmod +x`).
  - Manual executable path selection via file browser or direct path entry.
- **Multilingual Model Manager**:
  - Download and switch between models:
    - `ggml-tiny.bin` (~75 MB, ultra-fast multilingual)
    - `ggml-base.bin` (~142 MB, default balanced multilingual)
    - `ggml-small.bin` (~466 MB, high accuracy multilingual)
    - `ggml-large-v3-turbo.bin` (~1.5 GB, state-of-the-art speed & quality)
  - Non-blocking download UI with progress percentage, MB counters, and cancel option.
  - Language selection setting (Auto-detect, Russian, English, Spanish, German, French, etc.).

---

### 2. Real-Time Streaming & On-the-Fly Insertion [Implemented]
- **Streaming Audio Engine**:
  - Feed incoming microphone audio to the voice backend in rolling chunks (500ms-1000ms sliding windows).
  - Process audio iteratively while the user continues speaking.
- **On-the-Fly Insertion vs. Key-Release**:
  - Preference toggle in UI & YAML configuration:
    - `On-the-Fly`: Live incremental word-by-word injection into active text fields.
    - `On Key Release` (Default): Wait until push-to-talk key is released, then inject complete transcript.
- **Delta Word Injection**:
  - Calculates newly recognized word suffixes compared to already committed words to prevent jitter and redundant typing.

---

### 3. Privacy-First Clipboard & Text Insertion Strategies [Implemented]
- **Preserve User Clipboard (Disabled by Default)**:
  - Transcriptions do NOT pollute the system clipboard by default to protect sensitive user data.
  - Toggle: `Save transcription to clipboard (Default: OFF)`.
- **Direct Keystroke Typing (`typeText`)**:
  - Directly synthesizes character keystrokes into active window without touching system clipboard.
- **Fallback Clipboard Mode**:
  - `Save to clipboard if no active field focused` (enabled by default).
  - Temporary clipboard mode with instantaneous restoration of previous clipboard contents after paste.

---

### 4. Inference Device Selection (CPU / GPU) [Implemented]
- **Execution Backend Configuration**:
  - Dropdown in Preferences to select inference device:
    - `CPU (Multi-threaded AVX)`
    - `GPU (Auto-Accelerated CUDA / Vulkan / Metal)`
  - Automatic fallback to CPU if selected GPU backend is unavailable.

---

### 5. Dictation History & Clipboard Manager [Implemented]
- **Transcription History Listing**:
  - Scrollable chronological feed of all past dictated transcriptions in the UI (newest first).
  - Metadata display: timestamp, duration, engine used, language.
  - Search filter to query past dictations.
  - "Clear History" button with confirmation.
- **One-Click "Copy" Action**:
  - Dedicated "Copy" button beside each historical entry allowing instant copying to the system clipboard on demand with visual feedback.
- **Persistent Storage**:
  - Stored locally in `~/.cache/golos-ai/history.jsonl`.

---

### 6. Human-Readable Microphone Names & Device Identification [Implemented]
- **Clean Device Formatting**:
  - Filter out redundant/cryptic ALSA/JavaSound port names (e.g., `Port Direct Audio Device: hw:0,0`).
  - Present friendly, recognizable titles ("Default System Microphone", "PipeWire / PulseAudio Input", "Hardware Microphone").

---

### 7. Alternative Microphone Provider (PortAudio JNA) [Backlogged]
- **Secondary Capture Backend**:
  - Implement `PortAudioCapturePort` using `libportaudio.so.2` / `portaudio.dll` via JNA.
  - Allow user to switch between `JavaSound` and `PortAudio` directly from audio preferences.

---

### 8. System Audio Output Monitor (Loopback) & Visual Indicators [Backlogged]
- **Output Audio Monitoring**:
  - Support recording/transcribing system audio output (desktop sound, video calls, media).
  - Provide distinct visual icons/badges in the UI distinguishing input microphones from output monitor/loopback channels.

---

### 9. File Speech-to-Text Transcription (MP3/WAV/etc.) [Implemented]
- **Audio File Loader & Transcription Dialog**:
  - Support loading pre-recorded audio files (`.mp3`, `.wav`, `.flac`, `.m4a`, `.ogg`).
  - Dedicated "📁 Transcribe Audio File..." actions on General and History tabs.
  - Non-blocking batch transcription via active speech engine.
  - Pop-up transcription preview dialog with one-click "📋 Copy to Clipboard" and "📜 View in History".
  - Automatically records full transcription in persistent Dictation History.

---

### 10. Interactive Shortcut Key-Recorder UI [Implemented]
- **Click-to-Record Hotkey Workflow**:
  - Interactive "Record Shortcut" button.
  - Listen for key down sequence (e.g. `Ctrl` + `Shift` + `L` or `F8`).
  - On key release, automatically finalize and save the shortcut.

---

### 11. Wayland & Unfocused Global Hotkey Architecture [In Progress / Backlogged]
- **Wayland Global Shortcut Support**:
  - In modern Linux Wayland environments, X11 `XGrabKey` is blocked when native Wayland apps are active.
  - Implement XDG Desktop Portal `org.freedesktop.portal.GlobalShortcuts` integration via D-Bus.
  - In X11: JNA `XGrabKey` ABI alignment (`Int` for `owner_events`), `XSetErrorHandler` installed, and responsive `XPending` event loop implemented.

---

### 12. YAML Configuration Contract & Settings Management [Implemented]
- **Stable Unified YAML Contract**:
  - Persistent settings stored in `~/.config/golos-ai/config.yaml` (Windows: `%APPDATA%\GolosAI\config.yaml`).
  - Schema covers hotkey, insertion strategy & timing, audio device, engine/whisper details, and autostart.
- **Settings Actions in UI**:
  - "Reset to Defaults" button: restores factory settings.
  - "Export Settings..." button: exports YAML file to user-chosen path.
  - "Import Settings..." button: imports and applies external YAML settings file.
- **System Autostart on Login**:
  - Linux XDG autostart (`~/.config/autostart/golos-ai.desktop`).
  - Windows Startup folder integration.

---

### 13. Gradle Quality Tooling: Detekt & Ktlint [Implemented]
- `detekt` static code analysis configured with `./gradlew detektAll` task and `config/detekt/detekt.yml`.
- `ktlint` style checks configured with `./gradlew ktlintAll` and `./gradlew ktlintFormat` across all subprojects.

---

### 14. Test Coverage Reporting: JaCoCo [Implemented]
- Integrated Gradle JaCoCo plugin across all subprojects (`toolVersion = "0.8.12"`).
- Root `jacocoRootReport` task aggregates code coverage metrics into HTML and XML reports (`build/reports/jacoco/jacocoRootReport/html/index.html`).
- Automatically finalized after `./gradlew testAll`.

---

### 15. Reproducible JAR Builds Without Timestamps [Implemented]
- Configured in root `build.gradle.kts`:
  - `isPreserveFileTimestamps = false`
  - `isReproducibleFileOrder = true`
- Repeated builds produce bit-for-bit identical JAR artifacts.

---

### 16. Dependency Locking [Implemented]
- Configured Gradle dependency locking via `dependencyLocking { lockAllConfigurations() }`.
- Generated and committed lock files (`gradle.lockfile`) across root and all subprojects.

---

### 17. Offline Bundled Minimal Audio Model in Project Archive [Implemented]
- Built-in offline model lookup in `ModelDownloader`: checks local cache, project root `models/` directory, and classpath.
- Added `./gradlew bundleMinimalModel` task to download and bundle `ggml-tiny.bin` into `models/` prior to creating distribution archives.
- Added `models/README.md` documentation.

---

### 18. Unified Test Command Across All Modules [Implemented]
- Added root Gradle task:
  - `./gradlew testAll`
- Runs all unit and integration tests across `:core`, `:system-utils`, `:voice-backend`, and `:application` in a single command.

---

### 19. Multi-Platform Build & Run Matrix (Win 11, Ubuntu 24.04, macOS 15) [Backlogged]
- CI matrix covering:
  - Windows 11 (MSVC / MinGW whisper binaries, AWT/Swing rendering, Windows Startup)
  - Ubuntu 24.04 LTS (X11 / Wayland, PipeWire / PulseAudio, XDG autostart)
  - macOS 15 Sequoia (Metal acceleration, CoreAudio, LaunchAgents)
