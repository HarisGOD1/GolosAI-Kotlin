# GolosAI Product Backlog & Roadmap

## Planned Features for Production Release

### 1. Whisper.cpp Binary & Model Downloader
- **Automatic / One-Click Whisper.cpp Setup**:
  - Automatically detect host operating system and CPU/GPU architecture (Linux x86_64/ARM64, macOS Apple Silicon / Intel, Windows x64).
  - Download precompiled, optimized `whisper-cli` binary from GitHub releases or official distribution artifacts into local user cache (`~/.cache/golos-ai/bin/` or `%LOCALAPPDATA%\GolosAI\bin`).
  - Set executable permissions on Unix systems (`chmod +x`).

- **Interactive Model Manager & Downloader**:
  - Add a "Download Model" button directly in the Swing Preferences UI.
  - Support model selection with size/speed/quality trade-offs:
    - `ggml-tiny.bin` (~75 MB, fastest, minimal RAM)
    - `ggml-base.bin` (~142 MB, recommended default)
    - `ggml-small.bin` (~466 MB, high accuracy)
    - `ggml-large-v3-turbo.bin` (~1.5 GB, state-of-the-art multilingual speed & quality)
  - Display non-blocking download progress bar (bytes downloaded, total size, ETA).
  - SHA256 integrity checksum verification after download.

---

### 2. Audio Processing & System Integration
- **Voice Activity Detection (VAD) & Denoising**:
  - Integrated Silero VAD or WebRTC VAD to discard background noise and trim audio buffers.
  - Streaming audio transcription (500ms sliding windows) for live interim results.
- **Wayland Native Hotkey & Text Injection**:
  - Integration with Wayland global shortcut portal (`org.freedesktop.portal.GlobalShortcuts`) and virtual keyboard / `wtype` text insertion.
- **Audio Device Hot-Plugging**:
  - Listen for USB microphone connect/disconnect events and automatically fall back to default input.
