# Little Chat

A minimal Android on-device chat app powered by **llama.cpp** and GGUF models.

## Target

- A clean, DeepSeek-inspired chat screen: conversation list, message bubbles, composer, stop-generation action.
- Fully local inference; no remote inference API.
- Context-length setting retained and exposed in Settings.
- Default model: [Qwen3.8-2B-Uncensored-GGUF](https://huggingface.co/atakhadivi/Qwen3.8-2B-Uncensored-GGUF), file `Qwen3.8-2B-Uncensored-Q4_K_M.gguf`.
- Initial context default: 2048 tokens; allow users to change it subject to available RAM.
- Model runtime must support the `qwen35` architecture. Pin a sufficiently recent llama.cpp revision rather than an older release.

## Model facts

The model card lists a 1.27 GB Q4_K_M GGUF file and requires llama.cpp with Qwen3.5 / Gated DeltaNet architecture support. Verify the downloaded file's SHA-256 before packaging:

```text
61713f6d29b507f0ddd2ed989683e3a5e997b920b0c4901ee8f332321d4b0cc9
```

The model is large for an APK. The intended release build should download and verify it during CI, package it as an app asset, and publish the APK as a GitHub Actions artifact or Release asset. Do not commit the model binary to Git.

## Implementation direction

Use a native Android UI and llama.cpp through JNI. Keep the inference layer isolated from the UI so the chat screen and settings can be simplified without changing the GGUF runtime. Store conversations and settings locally. The model must be copied from packaged assets into app-private storage before loading.

## Build status

This repository is being initialized. A successful APK build and on-device inference test are required before describing the app as working. The key acceptance test is that the packaged model loads and produces a response on an ARM64 Android device, with context length adjustable in Settings.

## Attribution and license

- llama.cpp: https://github.com/ggml-org/llama.cpp
- Model: https://huggingface.co/atakhadivi/Qwen3.8-2B-Uncensored-GGUF

Review and preserve upstream licenses and notices when incorporating code or redistributing model weights.
