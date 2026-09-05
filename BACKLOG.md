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
