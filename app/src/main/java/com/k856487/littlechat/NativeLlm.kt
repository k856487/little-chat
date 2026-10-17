package com.k856487.littlechat

internal class NativeLlm {
    external fun loadModel(path: String, contextSize: Int): Boolean
    external fun generate(prompt: String, maxTokens: Int, thinkingMode: Boolean): String
    external fun unload()
    companion object { init { System.loadLibrary("littlechat") } }
}
