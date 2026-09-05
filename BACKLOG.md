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

### 7. Alternative Microphone Provider (PortAudio JNA) [Implemented]
- **Secondary Capture Backend**:
  - Implemented `PortAudioAudioCapture` implementing `AudioCapturePort` using `libportaudio.so.2` / `portaudio.dll` via JNA.
  - Seamless fallback to JavaSound audio pipeline if native PortAudio C library is not installed on the host.
  - Dedicated Audio Provider dropdown in Preferences UI allowing on-the-fly switching between JavaSound and PortAudio.

---

### 8. System Audio Output Monitor (Loopback) & Visual Indicators [Implemented]
- **Output Audio Monitoring**:
  - Support capturing and transcribing system audio output (desktop audio, meetings, browser playback).
  - Automatically identifies loopback / monitor / stereo mix mixers across Linux PipeWire/PulseAudio and Windows.
  - Distinct badges and visual clue in the UI: `🎙️ [Microphone]` vs `🎧 [System Output Monitor]`.

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

### 11. Global Hotkey Architecture (X11 Multi-Threaded & Multi-Key Mapping) [Implemented]
- **Robust KeySym Resolution & Event Loop**:
  - `XInitThreads()` initialization prevents multi-threaded Xlib event loss when application is unfocused.
  - Multi-tier keycode resolution supporting exact, lowercase, uppercase, and ASCII code mappings (e.g. `Ctrl+Shift+L`).
  - X11 `XGrabKey` ABI alignment (`Int` for `owner_events`), error handler installed, non-blocking `XPending` polling thread.

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

### 19. Multi-Platform Build & Run Matrix (Win 11, Ubuntu 24.04, macOS 15) [Implemented]
- **Multi-Platform CI Workflow Matrix**:
  - Configured in `.github/workflows/ci.yml`.
  - Matrix runs automated builds and testing across:
    - `ubuntu-24.04` (Linux, headless testing, JaCoCo report generation)
    - `windows-latest` (Windows 11-based GitHub runner, MSVC / Win32 toolchain verification)
    - `macos-15` (macOS Sequoia runner, Apple Silicon / ARM64 / Metal compatibility)
  - Executes full test suite (`testAll`), static code analysis (`detektAll`), style checking (`ktlintAll`), and reproducible artifact build (`build`).
