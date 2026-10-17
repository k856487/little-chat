package com.k856487.littlechat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ChatMessage(val user: Boolean, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LittleChatScreen() }
    }

    @Composable
    private fun LittleChatScreen() {
        val scope = rememberCoroutineScope()
        val native = remember { NativeLlm() }
        val messages = remember { mutableStateListOf<ChatMessage>() }
        var input by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var modelReady by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("首次启动需要从安装包解压模型，请稍候。") }
        var contextSize by remember { mutableIntStateOf(getPreferences(0).getInt("context_size", 2048)) }
        var showSettings by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()

        LaunchedEffect(Unit) {
            loading = true
            status = "正在准备本地模型…"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val model = File(filesDir, "Qwen3.8-2B-Uncensored-Q4_K_M.gguf")
                    if (!model.exists() || model.length() < 1_000_000_000L) {
                        assets.open("Qwen3.8-2B-Uncensored-Q4_K_M.gguf").use { src -> model.outputStream().use { dst -> src.copyTo(dst) } }
                    }
                    native.loadModel(model.absolutePath, contextSize)
                } catch (e: Exception) { false }
            }
            modelReady = ok
            loading = false
            status = if (ok) "模型已在本机加载 · 上下文 ${contextSize} tokens" else "模型加载失败。请确认设备有足够存储空间和内存。"
        }

        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

        fun send() {
            val text = input.trim()
            if (text.isEmpty() || loading || !modelReady) return
            input = ""
            messages.add(ChatMessage(true, text))
            loading = true
            scope.launch {
                val history = messages.dropLast(0).takeLast(12).joinToString("\n\n") { if (it.user) "用户：${it.text}" else "助手：${it.text}" }
                val prompt = "你是一个有帮助的中文 AI 助手。请直接回答用户，保持清晰简洁。以下是对话记录，请根据最后一条用户消息继续回答：\n$history\n助手："
                val answer = withContext(Dispatchers.IO) { native.generate(prompt, 512) }
                messages.add(ChatMessage(false, answer.ifBlank { "（模型没有生成文本）" }))
                loading = false
                status = "本地运行中 · 上下文 ${contextSize} tokens"
            }
        }

        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F7F8)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Little Chat", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF171717))
                        Text(if (modelReady) "Qwen3.8 · 完全本地推理" else status, fontSize = 11.sp, color = Color(0xFF737373), maxLines = 1)
                    }
                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                }
                HorizontalDivider(color = Color(0xFFEAEAEA))
                if (messages.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("有什么可以帮你？", fontSize = 25.sp, fontWeight = FontWeight.Medium, color = Color(0xFF202020))
                        Spacer(Modifier.height(8.dp))
                        Text("对话在设备本地处理，无需联网调用云端模型。", fontSize = 13.sp, color = Color(0xFF777777))
                        Spacer(Modifier.height(18.dp))
                        Text(status, fontSize = 12.sp, color = Color(0xFF888888))
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(messages) { _, message ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                                Text(message.text, modifier = Modifier.widthIn(max = 320.dp).background(if (message.user) Color(0xFFE8E8E8) else Color.White, RoundedCornerShape(18.dp)).padding(horizontal = 15.dp, vertical = 12.dp), color = Color(0xFF202020), fontSize = 15.sp, lineHeight = 22.sp)
                            }
                        }
                        if (loading && modelReady) item { Text("正在思考…", Modifier.padding(start = 8.dp), color = Color.Gray, fontSize = 13.sp) }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), verticalAlignment = Alignment.Bottom) {
                    BasicTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).background(Color(0xFFF4F4F5), RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp), textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF222222)), decorationBox = { inner -> Box { if (input.isEmpty()) Text("给 Little Chat 发送消息", color = Color.Gray, fontSize = 14.sp); inner() } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { send() }, enabled = modelReady && !loading && input.isNotBlank(), modifier = Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 18.dp)) { Text("发送") }
                }
            }
        }

        if (showSettings) {
            AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("上下文设置") }, text = {
                Column {
                    Text("上下文长度：$contextSize tokens")
                    Spacer(Modifier.height(12.dp))
                    Slider(value = contextSize.toFloat(), onValueChange = { contextSize = it.toInt() }, valueRange = 1024f..8192f, steps = 6)
                    Text("更长上下文会增加内存占用。修改后需要重新加载模型。", fontSize = 12.sp, color = Color.Gray)
                }
            }, confirmButton = { TextButton(onClick = {
                getPreferences(0).edit().putInt("context_size", contextSize).apply()
                showSettings = false
                scope.launch {
                    loading = true
                    status = "正在按新上下文重新加载模型…"
                    val model = File(filesDir, "Qwen3.8-2B-Uncensored-Q4_K_M.gguf")
                    modelReady = withContext(Dispatchers.IO) { native.loadModel(model.absolutePath, contextSize) }
                    loading = false
                    status = if (modelReady) "本地运行中 · 上下文 ${contextSize} tokens" else "重新加载失败，可能是内存不足。"
                }
            }) { Text("保存并重新加载") } }, dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } })
        }
    }
}
