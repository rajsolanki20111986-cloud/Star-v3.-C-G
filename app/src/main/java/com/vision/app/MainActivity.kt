package com.vision.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.util.Locale

private val BG = Color(0xFF05070D)
private val GOLD = Color(0xFFFFD54F)
private val CYAN = Color(0xFF00E5FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent { StarApp() }
    }
}

@Composable
fun StarApp() {
    var tab by remember { mutableIntStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { perm.launch(Manifest.permission.RECORD_AUDIO) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            containerColor = BG, contentColor = Color.White,
            bottomBar = {
                NavigationBar {
                    listOf("Voice" to "🎙", "Chat" to "💬", "Settings" to "⚙").forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).statusBarsPadding().fillMaxSize()) {
                when (tab) { 0 -> VoiceScreen(); 1 -> ChatScreen(); else -> SettingsScreen() }
            }
        }
    }
}

// ---------------------------------------------------------------- Voice

@Composable
fun VoiceScreen() {
    val ctx = LocalContext.current
    val state by StarCore.state.collectAsState()
    val caption by StarCore.caption.collectAsState()

    val color = when (state) {
        LiveState.Disconnected -> Color(0xFF555B66)
        LiveState.Connecting -> Color(0xFFFFB300)
        LiveState.Listening -> CYAN
        LiveState.Speaking -> GOLD
    }
    val label = when (state) {
        LiveState.Disconnected -> "Tap to wake Star"
        LiveState.Connecting -> "Connecting…"
        LiveState.Listening -> "Listening"
        LiveState.Speaking -> "Speaking"
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (state == LiveState.Disconnected) 1f else 1.2f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale"
    )

    fun toggle() {
        if (state != LiveState.Disconnected) { ctx.stopService(Intent(ctx, VoiceService::class.java)); return }
        if (Prefs.apiKey.isBlank()) { Toast.makeText(ctx, "Settings में Gemini API key डालो", Toast.LENGTH_LONG).show(); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(ctx, "माइक की permission दो", Toast.LENGTH_LONG).show(); return
        }
        ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("S T A R", color = GOLD, fontSize = 20.sp, letterSpacing = 6.sp)
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier.size(200.dp).scale(pulse).clip(CircleShape)
                .background(color.copy(alpha = 0.22f)).clickable { toggle() },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(130.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text(if (state == LiveState.Disconnected) "⏻" else "■", fontSize = 40.sp, color = Color.Black)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(label, color = color)
        Spacer(Modifier.height(16.dp))
        Text(caption, color = Color(0xFFB0B8C4), maxLines = 6)
    }
}

// ---------------------------------------------------------------- Chat

class ChatVM : ViewModel() {
    val msgs = mutableStateListOf<Pair<Boolean, String>>()
    var busy by mutableStateOf(false)
    private var lastHistory: List<Pair<Boolean, String>> = emptyList()

    fun send(text: String) {
        if (text.isBlank() || busy) return
        msgs.add(true to text)
        lastHistory = msgs.toList()
        requestReply(lastHistory)
    }

    fun retry() {
        if (!busy && lastHistory.isNotEmpty()) requestReply(lastHistory)
    }

    private fun requestReply(history: List<Pair<Boolean, String>>) {
        busy = true
        viewModelScope.launch {
            val r = try {
                ChatEngine.reply(history)
            } catch (e: IllegalArgumentException) {
                e.message ?: "Check the Gemini API key in Settings."
            } catch (_: Exception) {
                "Star could not connect right now. Check your internet connection and try again."
            }
            msgs.add(false to r)
            busy = false
        }
    }
}

@Composable
fun ChatScreen(vm: ChatVM = viewModel()) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val tts = remember {
        lateinit var t: TextToSpeech
        t = TextToSpeech(ctx) { t.language = Locale("hi", "IN") }
        t
    }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(vm.msgs) { (user, text) ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = if (user) Alignment.End else Alignment.Start
                ) {
                    Text(
                        text, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (user) Color(0xFF1B3A4B) else Color(0xFF1A1F2B)).padding(12.dp)
                    )
                    if (!user) Text("🔊", modifier = Modifier
                        .clickable { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg") }.padding(6.dp))
                }
            }
            if (vm.busy) item { Text("Star is thinking…", color = Color.Gray) }
            val lastReply = vm.msgs.lastOrNull()?.second.orEmpty()
            if (!vm.busy && (lastReply.startsWith("Gemini ") || lastReply.startsWith("Star could not"))) {
                item { Text("Try again", color = CYAN, modifier = Modifier.clickable { vm.retry() }.padding(8.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4)
            Text("🎤", fontSize = 26.sp, modifier = Modifier.clickable { listen(ctx) { input = it } }.padding(8.dp))
            Text("➤", fontSize = 26.sp, color = CYAN, modifier = Modifier.clickable { vm.send(input); input = "" }.padding(8.dp))
        }
    }
}

fun listen(ctx: Context, onText: (String) -> Unit) {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
        Toast.makeText(ctx, "Speech recognition unavailable", Toast.LENGTH_SHORT).show(); return
    }
    val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
    sr.setRecognitionListener(object : RecognitionListener {
        override fun onResults(r: Bundle?) {
            r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
            sr.destroy()
        }
        override fun onError(error: Int) { sr.destroy() }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    })
    sr.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
    )
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var key by remember { mutableStateOf(Prefs.apiKey) }
    var voice by remember { mutableStateOf(Prefs.voice) }
    var notificationEnabled by remember { mutableStateOf(Prefs.notificationAssistantEnabled) }
    var instagramNames by remember { mutableStateOf(Prefs.instagramReplyNames) }
    var whatsappNames by remember { mutableStateOf(Prefs.whatsappReplyNames) }
    var maxMessages by remember { mutableStateOf(Prefs.maxMessagesPerConversation.toString()) }
    var inboxVersion by remember { mutableStateOf(0) }
    val inbox = remember(inboxVersion) { RelayInbox.list() }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 22.sp)
        Text("Configure Gemini to enable Star's voice and chat features.", color = Color.Gray)
        OutlinedTextField(
            key, { key = it; Prefs.apiKey = it }, Modifier.fillMaxWidth(),
            label = { Text("Gemini API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            voice, { voice = it; Prefs.voice = it }, Modifier.fillMaxWidth(),
            label = { Text("Voice (Charon, Puck, Kore, Fenrir, Orus…)") }, singleLine = true
        )
        Text("Your API key is stored in encrypted app preferences. Keep it private.", fontSize = 12.sp, color = Color.Gray)
        HorizontalDivider()
        Text("Notification Assistant", fontSize = 20.sp)
        Text("Only messages from names you add below are eligible. Android requires you to grant Notification Access. Star replies only through a direct-reply action that Android exposes. Test with a trusted contact first.", fontSize = 12.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable notification assistant", modifier = Modifier.weight(1f))
            Switch(checked = notificationEnabled, onCheckedChange = { notificationEnabled = it; Prefs.notificationAssistantEnabled = it })
        }
        OutlinedTextField(
            value = instagramNames,
            onValueChange = { instagramNames = it; Prefs.instagramReplyNames = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Instagram usernames (one per line)") },
            minLines = 2
        )
        OutlinedTextField(
            value = whatsappNames,
            onValueChange = { whatsappNames = it; Prefs.whatsappReplyNames = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("WhatsApp contact names (one per line)") },
            minLines = 2
        )
        OutlinedTextField(
            value = maxMessages,
            onValueChange = { v ->
                maxMessages = v.filter { it.isDigit() }.take(4)
                Prefs.maxMessagesPerConversation = maxMessages.toIntOrNull() ?: 50
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Max messages per conversation (0 = no limit)") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
        )
        Button(onClick = {
            Prefs.clearEndedConversations()
            Toast.makeText(context, "Closed conversations reopened", Toast.LENGTH_SHORT).show()
        }) { Text("Reopen closed conversations") }
        HorizontalDivider()
        Text("Relay inbox (${inbox.size})", fontSize = 20.sp)
        Text("Important messages Star passed on to you. Stored only on this phone.", fontSize = 12.sp, color = Color.Gray)
        if (inbox.isEmpty()) Text("No important messages yet.", color = Color.Gray)
        inbox.forEach { Text(it, fontSize = 13.sp) }
        if (inbox.isNotEmpty()) {
            Button(onClick = { RelayInbox.clear(); inboxVersion++ }) { Text("Clear inbox") }
        }
        Button(onClick = {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { Toast.makeText(context, "Open Android Settings > Notification access and enable Star.", Toast.LENGTH_LONG).show() }
        }) { Text("Grant Notification Access") }
    }
}
