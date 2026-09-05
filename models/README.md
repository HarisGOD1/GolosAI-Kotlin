# GolosAI Models Directory

This directory holds offline GGML models for Whisper.cpp.

When distributing or installing GolosAI from an archive without an active internet connection, place the minimal multilingual model here:
- `models/ggml-tiny.bin` (~75 MB)

You can automatically populate this model prior to packing an offline archive by running:
```bash
./gradlew bundleMinimalModel
```

GolosAI automatically checks this directory on startup if no cached model is found in `~/.cache/golos-ai/models/`.
