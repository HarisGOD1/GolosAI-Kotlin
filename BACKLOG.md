# GolosAI Product Backlog & Roadmap

## Production Features & Implementation Plan

### 1. Whisper.cpp Binary & Multilingual Model Downloader
- **Automatic / One-Click Whisper.cpp Setup**:
  - Automatically detect host operating system and CPU/GPU architecture (Linux x86_64/ARM64, macOS Apple Silicon / Intel, Windows x64).
  - Download precompiled, optimized `whisper-cli` binary from official distribution releases into local user cache (`~/.cache/golos-ai/bin/` or `%LOCALAPPDATA%\GolosAI\bin`).
  - Automatically set executable permissions on Unix systems (`chmod +x`).
- **Multilingual Model Manager**:
  - Ability to choose, download, and switch between multilingual models:
    - `ggml-tiny.bin` (~75 MB, ultra-fast multilingual)
    - `ggml-base.bin` (~142 MB, default balanced multilingual)
    - `ggml-small.bin` (~466 MB, high accuracy multilingual)
    - `ggml-large-v3-turbo.bin` (~1.5 GB, state-of-the-art speed & multilingual quality)
  - Non-blocking download UI with progress percentage, remaining time, and cancel option.
  - Language selection setting (Auto-detect, English, Russian, Spanish, German, French, etc.).

---

### 2. Real-Time Streaming & On-the-Fly Insertion
- **Streaming Audio Engine**:
  - Feed incoming microphone audio to the voice backend in rolling chunks (e.g. 500ms-1000ms sliding windows).
  - Process audio iteratively while the user continues speaking.
- **On-the-Fly Insertion vs. Key-Release (Configurable)**:
  - Add preference toggle:
    - `On-the-Fly`: Incremental typing into the active text field while speaking.
    - `On Key Release`: Wait until push-to-talk key is released, then inject complete transcript at once.
- **Delta Word Injection**:
  - Calculate delta words between successive speech recognition hypothesis outputs to avoid re-typing already committed text.

---

### 3. Privacy-First Clipboard & Text Insertion Strategies
- **Preserve User Clipboard (Disabled by Default)**:
  - Transcriptions will NOT pollute the system clipboard by default to protect sensitive user data (passwords, tokens, private keys).
  - Add preference toggle: `Allow copying transcription to system clipboard (Default: OFF)`.
- **Direct Keystroke Typing (`typeText`)**:
  - Directly synthesize character keystrokes into active window without touching system clipboard.
- **Fallback Clipboard Mode**:
  - Option: `Copy to clipboard only when no active input field is detected`.
  - Temporary clipboard mode with instantaneous restoration of previous clipboard contents after paste.

---

### 4. Inference Device Selection (CPU / GPU)
- **Execution Backend Configuration**:
  - Dropdown in Preferences to select inference device:
    - `CPU (Multi-threaded AVX/NEON)`
    - `GPU (CUDA / Nvidia)`
    - `GPU (Vulkan / AMD & Intel)`
    - `GPU (Metal / Apple Silicon)`
  - Automatic fallback to CPU if selected GPU backend is unavailable or lacks drivers.
- **Resource Permission Handling**:
  - Pre-check device accessibility and prompt for system permissions upfront if required.

---

### 5. Dictation History & Clipboard Manager
- **Transcription History Listing**:
  - Scrollable chronological feed of all past dictated transcriptions in the UI.
  - Metadata display: timestamp, audio duration (in ms/seconds), engine used, language.
- **One-Click "Copy" Action**:
  - Dedicated "Copy" button beside each historical entry allowing instant copying of any past text to the system clipboard on demand.
- **Search & Persistence**:
  - Search bar to quickly filter historical transcriptions by keyword.
  - Local embedded storage persistence (SQLite or JSON lines in app directory).
  - "Clear History" button with confirmation.

---

### 6. Human-Readable Microphone Names & Device Identification
- **Clean Device Formatting**:
  - Filter out redundant/cryptic ALSA/JavaSound port names (e.g., `Port Direct Audio Device: hw:0,0`).
  - Present friendly, recognizable titles (e.g., "Default System Microphone", "USB Microphone", "Internal Analog Input").

---

### 7. Alternative Microphone Provider (PortAudio JNA)
- **Secondary Capture Backend**:
  - Implement `PortAudioCapturePort` using `libportaudio.so.2` / `portaudio.dll` via JNA.
  - Allow user to switch between `JavaSound` and `PortAudio` directly from audio preferences.

---

### 8. System Audio Output Monitor (Loopback) & Visual Indicators
- **Output Audio Monitoring**:
  - Support recording/transcribing system audio output (desktop sound, video calls, media).
  - Provide distinct visual icons/badges in the UI distinguishing input microphones from output monitor/loopback channels.

---

### 9. File Speech-to-Text Transcription (MP3/WAV/etc.)
*(Blocked by Task #5: Dictation History implementation)*
- **Audio File Loader**:
  - Support loading pre-recorded audio files (`.mp3`, `.wav`, `.flac`, `.m4a`, `.ogg`).
  - Convert and pass audio to the voice backend for batch transcription.
  - Copy transcribed text to clipboard and save as an entry in the Dictation History list for later retrieval.

---

### 10. Interactive Shortcut Key-Recorder UI
- **Click-to-Record Hotkey Workflow**:
  - Replace manual text/checkbox selection with an interactive "Record Shortcut" button.
  - When active, listen for key down sequence (e.g. `Ctrl` + `Shift` + `L`).
  - On key release, automatically finalize and save the shortcut (standard UX across modern apps like OBS, Discord, JetBrains).

---

### 11. Wayland & Unfocused Global Hotkey Architecture
- **Wayland Global Shortcut Support**:
  - In modern Linux Wayland environments, X11 `XGrabKey` is blocked by design when native Wayland apps are active.
  - Implement XDG Desktop Portal `org.freedesktop.portal.GlobalShortcuts` integration via D-Bus for system-wide Wayland hotkeys.
  - In X11, fix `XGrabKey` C ABI signature (`Bool` as `Int`), install `XSetErrorHandler` to suppress BadAccess aborts, and test root window event propagation.
