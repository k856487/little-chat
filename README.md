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

## Build

GitHub Actions builds an ARM64 debug APK on pushes to `main` and on manual workflow dispatch. The workflow downloads the model from Hugging Face, verifies its SHA-256, packages it into the APK, and uploads the APK as an Actions artifact. The resulting APK is about 1.3 GB and requires substantial free storage to install. The artifact is available from the repository's Actions page for 14 days after a successful run.

The app currently implements the Compose chat screen, JNI inference bridge, local model extraction, and context-length setting. It has not yet been verified on a physical Android device; treat the first CI build as a build test, not as proof of successful on-device inference.

## Attribution and license

- llama.cpp: https://github.com/ggml-org/llama.cpp
- Model: https://huggingface.co/atakhadivi/Qwen3.8-2B-Uncensored-GGUF

Review and preserve upstream licenses and notices when incorporating code or redistributing model weights.
