package com.k856487.littlechat

internal fun interface GenerationProgress {
    fun onProgress(stage: String)
}

internal class NativeLlm {
    external fun loadModel(path: String, contextSize: Int): Boolean
    external fun generate(prompt: String, maxTokens: Int, thinkingMode: Boolean, progress: GenerationProgress): String
    external fun unload()
    companion object { init { System.loadLibrary("littlechat") } }
}
