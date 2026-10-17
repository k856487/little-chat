package com.k856487.littlechat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private data class ChatMessage(val user: Boolean, val text: String)
private data class StoredChat(val id: String, val title: String, val messages: List<ChatMessage>)

private const val PREFS_NAME = "little_chat"
private const val CHATS_KEY = "chat_history"

private fun loadChats(prefs: android.content.SharedPreferences): List<StoredChat> {
    return try {
        val array = JSONArray(prefs.getString(CHATS_KEY, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val messagesJson = item.optJSONArray("messages") ?: JSONArray()
            val messages = (0 until messagesJson.length()).mapNotNull { j ->
                val m = messagesJson.optJSONObject(j) ?: return@mapNotNull null
                ChatMessage(m.optBoolean("user"), m.optString("text"))
            }
            StoredChat(item.optString("id", UUID.randomUUID().toString()), item.optString("title", "新对话"), messages)
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveChats(prefs: android.content.SharedPreferences, chats: List<StoredChat>) {
    val array = JSONArray()
    chats.forEach { chat ->
        val messages = JSONArray()
        chat.messages.forEach { message ->
            messages.put(JSONObject().put("user", message.user).put("text", message.text))
        }
        array.put(JSONObject().put("id", chat.id).put("title", chat.title).put("messages", messages))
    }
    prefs.edit().putString(CHATS_KEY, array.toString()).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LittleChatScreen() }
    }

    @Composable
    private fun LittleChatScreen() {
        val prefs = remember { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
        val scope = rememberCoroutineScope()
        val native = remember { NativeLlm() }
        val initialChats = remember { loadChats(prefs) }
        val chatHistory = remember { mutableStateListOf<StoredChat>().apply { addAll(initialChats) } }
        var activeChatId by remember { mutableStateOf(prefs.getString("active_chat_id", null) ?: initialChats.firstOrNull()?.id ?: UUID.randomUUID().toString()) }
        val initialMessages = initialChats.firstOrNull { it.id == activeChatId }?.messages.orEmpty()
        val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) } }
        var input by remember { mutableStateOf("") }
        var generating by remember { mutableStateOf(false) }
        var modelLoading by remember { mutableStateOf(false) }
        var modelReady by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("首次启动需要从安装包解压模型，请稍候。") }
        var contextSize by remember { mutableIntStateOf(prefs.getInt("context_size", 2048)) }
        var thinkingMode by remember { mutableStateOf(prefs.getBoolean("thinking_mode", false)) }
        var showMenu by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()

        fun persistActiveChat() {
            val title = messages.firstOrNull { it.user }?.text?.replace("\n", " ")?.take(24)?.ifBlank { "新对话" } ?: "新对话"
            val current = StoredChat(activeChatId, title, messages.toList())
            val index = chatHistory.indexOfFirst { it.id == activeChatId }
            if (index >= 0) chatHistory[index] = current else chatHistory.add(0, current)
            chatHistory.sortByDescending { it.id == activeChatId }
            saveChats(prefs, chatHistory.toList())
            prefs.edit().putString("active_chat_id", activeChatId).apply()
        }

        LaunchedEffect(Unit) {
            if (chatHistory.isEmpty()) {
                val newChat = StoredChat(activeChatId, "新对话", emptyList())
                chatHistory.add(newChat)
                saveChats(prefs, chatHistory.toList())
            }
            modelLoading = true
            status = "正在准备本地模型…"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val model = File(filesDir, "Qwen3.8-2B-Uncensored-Q4_K_M.gguf")
                    if (!model.exists() || model.length() < 1_000_000_000L) {
                        assets.open("Qwen3.8-2B-Uncensored-Q4_K_M.gguf").use { src -> model.outputStream().use { dst -> src.copyTo(dst) } }
                    }
                    native.loadModel(model.absolutePath, contextSize)
                } catch (_: Exception) { false }
            }
            modelReady = ok
            modelLoading = false
            status = if (ok) "模型已在本机加载 · 上下文 ${contextSize} tokens" else "模型加载失败。请确认设备有足够存储空间和内存。"
        }

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }

        fun startNewChat() {
            if (generating || modelLoading) return
            persistActiveChat()
            messages.clear()
            activeChatId = UUID.randomUUID().toString()
            chatHistory.add(0, StoredChat(activeChatId, "新对话", emptyList()))
            prefs.edit().putString("active_chat_id", activeChatId).apply()
            saveChats(prefs, chatHistory.toList())
            input = ""
            showMenu = false
        }

        fun openChat(chat: StoredChat) {
            if (generating || modelLoading) return
            persistActiveChat()
            activeChatId = chat.id
            messages.clear()
            messages.addAll(chat.messages)
            prefs.edit().putString("active_chat_id", activeChatId).apply()
            showMenu = false
        }

        fun send() {
            val text = input.trim()
            if (text.isEmpty() || generating || modelLoading || !modelReady) return
            val previousMessages = messages.toList()
            input = ""
            messages.add(ChatMessage(true, text))
            persistActiveChat()
            generating = true
            status = if (thinkingMode) "正在思考并生成回答…" else "正在生成回答…"
            scope.launch {
                val history = previousMessages.takeLast(4).joinToString("\n") { message ->
                    val shortText = if (message.text.length > 320) "…" + message.text.takeLast(320) else message.text
                    if (message.user) "用户：$shortText" else "助手：$shortText"
                }
                val prompt = buildString {
                    append("你是一个有帮助的中文 AI 助手。请准确回答用户的最新问题，不要编造事实。")
                    if (history.isNotBlank()) append("\n此前对话仅供理解上下文：\n").append(history)
                    append("\n用户的最新问题：\n").append(text)
                }
                val answer = withContext(Dispatchers.IO) { native.generate(prompt, 256, thinkingMode) }
                val cleanAnswer = answer.trim().ifBlank { "模型没有生成文本。请关闭思考模式后重试；如果仍然无响应，请重启应用。" }
                messages.add(ChatMessage(false, cleanAnswer))
                persistActiveChat()
                generating = false
                status = if (modelReady) "本地运行中 · 上下文 ${contextSize} tokens" else status
            }
        }

        Surface(Modifier.fillMaxSize().imePadding(), color = Color(0xFFF7F7F8)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Little Chat", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF171717))
                        Text(if (modelReady) "Qwen3.8 · 完全本地推理" else status, fontSize = 11.sp, color = Color(0xFF737373), maxLines = 1)
                    }
                    IconButton(onClick = { startNewChat() }, enabled = !generating && !modelLoading) {
                        Text("＋", fontSize = 27.sp, color = Color(0xFF303030))
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Text("☰", fontSize = 23.sp, color = Color(0xFF303030))
                    }
                }
                HorizontalDivider(color = Color(0xFFEAEAEA))
                if (messages.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("有什么可以帮你？", fontSize = 25.sp, fontWeight = FontWeight.Medium, color = Color(0xFF202020))
                        Spacer(Modifier.height(8.dp))
                        Text("对话保存在本机，不会上传到云端。", fontSize = 13.sp, color = Color(0xFF777777))
                        Spacer(Modifier.height(18.dp))
                        Text(if (modelLoading) "正在准备模型…" else status, fontSize = 12.sp, color = Color(0xFF888888))
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(messages) { _, message ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                                Text(message.text, modifier = Modifier.widthIn(max = 320.dp).background(if (message.user) Color(0xFFE8E8E8) else Color.White, RoundedCornerShape(18.dp)).padding(horizontal = 15.dp, vertical = 12.dp), color = Color(0xFF202020), fontSize = 15.sp, lineHeight = 22.sp)
                            }
                        }
                        if (generating) item { Text(if (thinkingMode) "正在思考…" else "正在生成…", Modifier.padding(start = 8.dp), color = Color.Gray, fontSize = 13.sp) }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                    BasicTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).background(Color(0xFFF4F4F5), RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp), textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF222222)), decorationBox = { inner -> Box { if (input.isEmpty()) Text("给 Little Chat 发送消息", color = Color.Gray, fontSize = 14.sp); inner() } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { send() }, enabled = modelReady && !generating && !modelLoading && input.isNotBlank(), modifier = Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 18.dp)) { Text("发送") }
                }
            }
        }

        if (showMenu) {
            AlertDialog(
                onDismissRequest = { showMenu = false },
                title = { Text("菜单") },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Text("设置", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("思考模式", fontSize = 15.sp)
                                Text(if (thinkingMode) "允许模型输出较长的推理过程" else "优先直接回答，通常更快", fontSize = 12.sp, color = Color.Gray)
                            }
                            Switch(checked = thinkingMode, onCheckedChange = {
                                thinkingMode = it
                                prefs.edit().putBoolean("thinking_mode", it).apply()
                            })
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("上下文长度：$contextSize tokens", fontSize = 14.sp)
                        Slider(value = contextSize.toFloat(), onValueChange = { contextSize = it.toInt() }, valueRange = 1024f..8192f, steps = 6)
                        Text("更长上下文会增加内存占用；保存后会重新加载模型。", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Text("聊天记录", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.height(6.dp))
                        if (chatHistory.isEmpty()) {
                            Text("暂无聊天记录", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            chatHistory.forEach { chat ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { openChat(chat) }.padding(vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(chat.title.ifBlank { "新对话" }, maxLines = 1, fontSize = 14.sp, fontWeight = if (chat.id == activeChatId) FontWeight.SemiBold else FontWeight.Normal)
                                        Text("${chat.messages.size} 条消息", fontSize = 11.sp, color = Color.Gray)
                                    }
                                    if (chat.id == activeChatId) Text("当前", fontSize = 11.sp, color = Color(0xFF4D6B57))
                                }
                                HorizontalDivider(color = Color(0xFFEFEFEF))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putInt("context_size", contextSize).apply()
                        showMenu = false
                        if (modelReady && !generating) {
                            scope.launch {
                                modelLoading = true
                                status = "正在按新上下文重新加载模型…"
                                val model = File(filesDir, "Qwen3.8-2B-Uncensored-Q4_K_M.gguf")
                                val ok = withContext(Dispatchers.IO) { native.loadModel(model.absolutePath, contextSize) }
                                modelReady = ok
                                modelLoading = false
                                status = if (ok) "本地运行中 · 上下文 ${contextSize} tokens" else "重新加载失败，可能是内存不足。"
                            }
                        }
                    }) { Text("保存设置") }
                },
                dismissButton = { TextButton(onClick = { showMenu = false }) { Text("关闭") } }
            )
        }
    }
}
