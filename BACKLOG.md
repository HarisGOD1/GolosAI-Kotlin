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

---

### 20. Elimination of X11 Keylogger Rights Prompts via XkbSetDetectableAutoRepeat [Implemented]
- Replaced continuous keyboard state polling (`XQueryKeymap`) with XKB native detectable auto-repeat (`XkbSetDetectableAutoRepeat`).
- X server suppresses auto-repeat release events and delivers `KeyRelease` strictly upon physical key release.
- Global key grab uses strictly `XGrabKey` on the configured keysym and root window.
- Eliminates desktop security warnings and input-monitoring permission alerts on Linux/X11 desktops.

---

### 21. MIT-Licensed Typography and Font Scaling (Hack Font Family) [Implemented]
- Bundled Hack font (`Hack-Regular.ttf` and `Hack-Bold.ttf`) under MIT license.
- Global `FontManager` registers font with `GraphicsEnvironment` and updates Swing `UIManager` defaults.
- Default UI font scaled to 14pt body text, 15pt bold headers, and 12-13pt indicators.

---

### 22. Multi-Language UI Localization and Expanded Spoken Languages [Implemented]
- Interface localization in `AppLocalization` supporting 10 languages: English (EN), French (FR), German (DE), Russian (RU), Japanese (JP), Chinese (CN), Turkish (TR), Arabic (AR), Spanish (ES), Italian (IT).
- Dynamic locale switching across all tabs, buttons, dialogs, and indicator bulbs.
- UI language saved in YAML configuration (`uiLanguage`).
- Expanded Whisper spoken language selector with 16 languages: Auto, EN, FR, DE, RU, JA, ZH, TR, AR, ES, IT, PT, KO, UK, PL, NL.

---

### 23. Bilingual Speech Recognition Model Support [Implemented]
- Added bilingual mode option: English (EN) + any selected language.
- Generates Whisper context prompts (`--prompt`) guiding recognition of mixed technical speech and code-switching vocabulary.
- Configurable via checkbox in Whisper settings and persisted in YAML configuration (`engine.whisper.bilingualMode`).
- Reflected in indicator status: `[Bilingual EN+<LANG>]`.

---

### 24. Collapsible 3-Bulb Indicator Floating Bar [Implemented]
- Photorealistic custom `BulbWidget` rendering 3D glowing LED lamps with radial gradient highlights and aura halos.
- 3 indicators: Application state (active/green), Voice state (idle/green, listening/amber, processing/red), and Mode state (timing, injection, hotkey, bilingual).
- Window collapsible to a sleek floating mini-bar (580x78) with `isAlwaysOnTop = true` for persistent desktop presence during dictation.
- Expand button restores full tabbed configuration window.

---

### 25. Undecorated Floating Mini-Bar Refactor (Bypass OS WM Minimum Window Clamping) [Implemented]
- **Identified Problem**:
  - In-place resizing of the decorated `JFrame` causes X11 and desktop Window Managers (e.g. GNOME Mutter, XFWM, KWin) to clamp the window to the WM minimum decorated size (~480x360), preventing a true compact floating indicator bar.
- **Implementation Strategy**:
  - Separate the floating indicator pill from the primary `JFrame` by introducing a dedicated lightweight, undecorated `JWindow` (or `JDialog(frame, isUndecorated = true)`).
  - On Collapse:
    - Save main window position and hide the decorated `JFrame` (`frame.isVisible = false`).
    - Display the standalone floating pill widget (`420 x 54` or `460 x 58`) at the user's preferred desktop position.
    - Set `window.isAlwaysOnTop = true` and attach window drag listener for fluid movement across multi-monitor setups.
    - Floating pill hosts 3 LED bulbs, compact PTT action button, and expand button (`[+]`).
  - On Expand:
    - Hide the floating `JWindow` and restore the full `JFrame` to its previous position and dimensions.
- **Criteria Alignment**: `B-11`, `D-08`, User UX requirement.

---

### 26. Speech Efficiency Metrics & Statistics Engine (`EfficiencyMetricsHandler`) [Implemented]
- **Core Architecture & Calculations**:
  - Introduce `EfficiencyMetricsHandler` service tracking per-replica and aggregate session metrics:
    1. **Audio Duration ($T_{audio}$)**: captured audio length in seconds and milliseconds.
    2. **Processing / Inference Latency ($T_{proc}$)**: wall-clock time spent by speech engine to transcribe audio.
    3. **Real-Time Factor (RTF)**: $RTF = \frac{T_{proc}}{T_{audio}}$ (RTF $< 1.0\times$ signifies faster-than-real-time speed; e.g., $0.12\times$). (Criterion `N-17`).
    4. **Word Count ($W$) & Character Count ($C$)**: word count and character count of the recognized text.
    5. **Speaking Rate / Words Per Minute (WPM)**: $WPM = \frac{W}{T_{audio} / 60}$. (Criteria `F-05` fast speech 180 WPM, `F-06` slow speech 90 WPM).
    6. **Typing Time Saved ($\Delta T_{saved}$)**: time saved assuming standard human typing speed of 40 WPM: $\Delta T_{saved} = \max\left(0, \frac{W}{40/60} - T_{audio}\right)$.
    7. **Injection Speed / Latency**: typing throughput in characters per second ($C / T_{inject}$). (Criterion `K-22`).
- **Data Persistence**:
  - In-memory circular buffer for active session metrics.
  - Persistent storage in `~/.cache/golos-ai/metrics.json` tracking lifetime aggregates.
- **Criteria Alignment**: `F-05`, `F-06`, `N-17`, `K-22`, `M-02`.

---

### 27. Dashboard Efficiency Metrics Panels (Current Text, History Mean, All Time) [Implemented]
- **UI Presentation on Dashboard (Tab 0)**:
  - Add 3 clean metric card panels between the Push-to-Talk button and Recent Dictation box:
    - **Panel A: `[Current Text]` (Last Dictation)**:
      - Audio duration (e.g. `3.2 s`)
      - Processing latency (e.g. `380 ms`)
      - Real-Time Factor (e.g. `0.12x RTF`)
      - Speaking speed (e.g. `145 WPM` | `24 words / 152 chars`)
      - Time saved (e.g. `+14.5 s`)
    - **Panel B: `[History Mean]` (Current Session / Filtered History)**:
      - Mean audio length (e.g. `4.6 s`)
      - Mean latency (e.g. `510 ms`)
      - Mean RTF (e.g. `0.14x RTF`)
      - Mean speaking speed (e.g. `138 WPM`)
      - Session total (e.g. `42 replicas` | `1,120 words` | `+14.2 min saved`)
    - **Panel C: `[All Time]` (Cumulative Lifetime Statistics)**:
      - Total replicas recorded (e.g. `1,420`)
      - Total spoken audio hours (e.g. `2h 25m`)
      - Total words transcribed (e.g. `19,850 words`)
      - Lifetime average speed (e.g. `142 WPM`)
      - Cumulative typing time saved (e.g. `5.8 hours`)
      - Best recorded RTF (e.g. `0.08x RTF`)
  - Full localization support across all 10 UI languages (`EN`, `FR`, `DE`, `RU`, `JP`, `CN`, `TR`, `AR`, `ES`, `IT`).
  - Strict typographic guidelines: proportional sans-serif for labels, Hack monospace for numeric metrics. Zero emojis.
- **Criteria Alignment**: `F-05`, `N-17`, `M-02`.

---

### 28. Single-Instance Application Lock & Mutex (`B-13`) [Implemented]
- **Goal**: Running a second instance of GolosAI must not conflict with or crash the running instance; it must signal the first instance to come to the foreground and exit cleanly.
- **Design**:
  - Local UNIX domain socket or file lock (`~/.cache/golos-ai/golos.lock` on Linux/macOS, named Mutex on Windows).
  - Second instance connects to existing lock socket, sends `SHOW` command, and exits with code 0.
  - Primary instance restores minimized/tray state, brings window to front, and requests input focus.
- **Criteria Alignment**: `B-13`.

---

### 29. Toggle Push-to-Talk Trigger Mode (`D-03`) [Implemented]
- **Goal**: Support alternate switch/toggle mode where first hotkey press begins recording and second hotkey press stops recording and triggers recognition.
- **Design**:
  - Configuration option: `hotkey.triggerMode`: `HOLD_TO_TALK` (default) vs `TOGGLE_ON_OFF`.
  - State machine handles toggle transitions.
  - UI indicator and PTT button update dynamically ("Click to Start" / "Click to Stop").
- **Criteria Alignment**: `D-03`.

---

### 30. Live Audio Input Signal VU Meter & Silence / Clipping Warning (`C-07`, `C-08`, `C-09`, `E-07`) [Implemented]
- **Goal**: Real-time microphone input volume indicator in UI to prevent speaking into a muted or overloaded microphone.
- **Design**:
  - Audio capture pipeline calculates rolling RMS dB level: $dB = 20 \log_{10}(RMS / RMS_{max})$.
  - Visual mini VU level meter bar on Dashboard and Audio settings tab.
  - Warning banner when input level is below -50 dB for >1.5s ("Microphone muted or volume too low").
  - Warning alert when signal exceeds 0 dB ("Audio clipping detected; lower input volume").
  - Input gain adjustment (0% - 200%) and tanh soft-clipping saturation to handle overloaded audio.
- **Criteria Alignment**: `C-07`, `C-08`, `C-09`, `E-07`.

---

### 31. Rule-Based Speech Text Normalization & Post-Processing (`F-10` - `F-20`, `G-01` - `G-15`) [Planned]
- **Goal**: Automatic formatting of numbers into digits, dates, times, currency, and removal of filler words.
- **Design**:
  - Pluggable `TextNormalizer` pipeline:
    - Number formatter: convert spoken words to digits ("двадцать пять" -> "25", "one hundred" -> "100").
    - Currency and units: "сто рублей" -> "100 руб.", "fifty dollars" -> "$50".
    - Dates and times: "четырнадцать тридцать" -> "14:30".
    - Filler words filter: strip Russian filler sounds ("э-э", "ну", "типа", "как бы") and English ("um", "uh", "like").
    - Punctuation voice commands: "точка" -> ".", "запятая" -> ",", "с новой строки" -> `\n`.
  - Toggleable via configuration (`postProcessing.enabled`).
- **Criteria Alignment**: `F-10` to `F-20`, `G-01` to `G-15`.

---

### 32. Custom Domain Dictionary & Terminology Replacement (`H-01` - `H-14`) [Planned]
- **Goal**: High-accuracy recognition of technical terms, programming identifiers, brands, and domain vocabulary.
- **Design**:
  - Load dictionary from YAML file (`~/.config/golos-ai/dictionary.yaml`).
  - Fast Trie / Aho-Corasick phonetic substitution to correct acoustic confusions.
  - Inject custom vocabulary into Whisper initial prompt (`--prompt`).
  - Track replacement hit statistics in history log.
- **Criteria Alignment**: `H-01` to `H-14`.

---

### 33. Active Window Context Detection & Application Profiles (`J-01` - `J-05`, `M-02`, `M-05`) [Planned]
- **Goal**: Automatically tailor recognition style to the active application and record app name in history.
- **Design**:
  - Query active window title and process identifier:
    - Linux X11: `_NET_ACTIVE_WINDOW` via X11 / `xdotool`.
    - Windows: `GetForegroundWindow` + `GetWindowText`.
    - macOS: `NSWorkspace.shared.frontmostApplication`.
  - Profiles:
    - `Messenger` (Telegram, Slack): short messages, relaxed punctuation.
    - `Code` (VS Code, IntelliJ, Terminal): identifier casing preservation, technical acronyms.
    - `Mail` (Thunderbird, Outlook): formal paragraph structure, full punctuation.
  - Record target application in `HistoryEntry` metadata for filtering.
- **Criteria Alignment**: `J-01` to `J-05`, `M-02`, `M-05`.

---

### 34. Batch Audio File Transcription with Timecodes & RTF Reporting (`N-09`, `N-10`, `N-13`, `N-17`) [Planned]
- **Goal**: Transcribe directories of audio files with batch progress tracking, subtitle export, and processing speed evaluation.
- **Design**:
  - Batch audio file selector and queue manager.
  - Background execution with file-by-file and total progress bars.
  - Export formats: Plain Text (`.txt`), SubRip Subtitles (`.srt`), WebVTT (`.vtt`) with timecodes.
  - Report aggregate Real-Time Factor (RTF) upon completion.
- **Criteria Alignment**: `N-09`, `N-10`, `N-13`, `N-17`.

