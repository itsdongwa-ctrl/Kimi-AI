package com.kimi.ai

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Mode(val label: String, val tag: String, val color: Color, val photo: Int, val icon: String) {
    NORMAL("NORMAL MODE", "CHAT • HELP • ASSIST", Color(0xFF22D3EE), R.drawable.photo_normal, "💬"),
    GOD("GOD MODE", "DEEP THINK • CREATE • SOLVE", Color(0xFF8B5CF6), R.drawable.photo_god, "👑"),
    RESEARCH("RESEARCH MODE", "EXPLORE • ANALYZE • FIND", Color(0xFF14B8A6), R.drawable.photo_research, "🔎")
}

val BG = Color(0xFF05060F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.init(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

@Composable
fun RoundIcon(icon: String, c: Color, size: Dp, selected: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape)
        .background(Color.Black.copy(alpha = .5f))
        .background(c.copy(alpha = if (selected) .35f else .15f))
        .border(if (selected) 3.dp else 1.5.dp, c, CircleShape)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(icon, fontSize = (size.value * 0.42f).sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MicButton(c: Color, phase: Phase, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(1f, 1.14f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    val icon = when (phase) { Phase.LISTENING -> "🎙️"; Phase.THINKING -> "⏳"; Phase.SPEAKING -> "🔊"; else -> "🎤" }
    Box(Modifier.size(96.dp)
        .graphicsLayer { val k = if (phase == Phase.LISTENING || phase == Phase.SPEAKING) pulse else 1f; scaleX = k; scaleY = k }
        .clip(CircleShape).background(Color.Black.copy(alpha = .5f)).background(c.copy(alpha = .35f))
        .border(3.dp, c, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(icon, fontSize = 38.sp)
    }
}

@Composable
fun App() {
    var mode by remember { mutableStateOf(Mode.NORMAL) }
    var menu by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(mode) { Assistant.mode = mode }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Assistant.listen() else Assistant.status = "Microphone permission is needed"
    }
    fun onMic() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) Assistant.listen()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    BackHandler(enabled = menu) { if (page != null) page = null else menu = false }
    Box(Modifier.fillMaxSize().background(BG)) {
        Image(painterResource(mode.photo), null, Modifier.fillMaxWidth().align(Alignment.TopCenter),
            contentScale = ContentScale.FillWidth)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon("⋮", mode.color, 44.dp) { page = null; menu = true }
            Spacer(Modifier.weight(1f))
            Text("KIMI AI", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon("💬", mode.color, 56.dp, false) { page = "chat"; menu = true }
                MicButton(mode.color, Assistant.phase) { onMic() }
                RoundIcon(mode.icon, mode.color, 56.dp, false) { mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size] }
            }
            Spacer(Modifier.height(12.dp))
            val line = when (Assistant.phase) {
                Phase.LISTENING -> "Listening…"
                Phase.THINKING -> "Thinking…"
                Phase.SPEAKING -> "Speaking… tap to interrupt"
                else -> Assistant.status.ifEmpty { "Tap to speak" }
            }
            Text(line, color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
            Text(mode.label + "  ›", color = mode.color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        if (menu) MenuLayer(page, { page = it }, { menu = false })
    }
}

private data class Item(val id: String, val icon: String, val title: String, val sub: String, val mode: Mode?)

private val ITEMS = listOf(
    Item("voice", "🎙️", "Voice & AI", "API key, voice, language", null),
    Item("chat", "💬", "Chat & Commands", "Type what Kimi should do", Mode.NORMAL),
    Item("youtube", "▶️", "YouTube Control", "Play, pause, next, search", Mode.NORMAL),
    Item("screen", "📱", "Screen Control", "Home, back, scroll, tap", Mode.GOD),
    Item("paypal", "💳", "PayPal", "Check your balance", Mode.GOD),
    Item("ut", "🧪", "UserTesting", "Log tests and earnings", Mode.GOD),
    Item("pdf", "📄", "PDF & Amazon", "Create a PDF and send it", Mode.RESEARCH),
    Item("settings", "⚙️", "Settings", "Normal, God and Research settings", null)
)

@Composable
fun MenuLayer(page: String?, onPage: (String?) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(BG.copy(alpha = .97f)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (page != null) onPage(null) else onClose() }) {
                Text(if (page != null) "←" else "✕", color = Color.White, fontSize = 22.sp)
            }
            Text(ITEMS.firstOrNull { it.id == page }?.title ?: "Menu", color = Color.White,
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        val n = Mode.NORMAL.color; val g = Mode.GOD.color; val r = Mode.RESEARCH.color
        when (page) {
            null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ITEMS.forEach { it2 ->
                    val col = it2.mode?.color ?: Color.White
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(col.copy(alpha = .1f))
                        .border(1.dp, col.copy(alpha = .5f), RoundedCornerShape(16.dp))
                        .clickable { onPage(it2.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it2.icon, fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(it2.title, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(it2.sub, color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                        }
                    }
                }
            }
            "voice" -> Page { VoiceSettings(Color(0xFFE0E7FF)) }
            "chat" -> Page { ChatBody(n) }
            "youtube" -> Page { YouTubeBody(n) }
            "screen" -> Page { ScreenBody(g) }
            "paypal" -> Page { PayPalCard(g) }
            "ut" -> Page { UserTestingCard(g) }
            "pdf" -> Page { PdfBody(r) }
            "settings" -> Page { SettingsBody() }
        }
    }
}

@Composable
fun KButton(t: String, c: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick, modifier = modifier, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, c),
        colors = ButtonDefaults.buttonColors(containerColor = c.copy(alpha = .2f), contentColor = c)) {
        Text(t, maxLines = 1, fontSize = 13.sp)
    }
}

@Composable
fun Title(t: String, c: Color) = Text(t, color = c, fontSize = 18.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 8.dp))

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
fun Choices(options: List<String>, sel: Int, c: Color, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            Button({ onSel(i) }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
                shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, c),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (i == sel) c else c.copy(alpha = .1f),
                    contentColor = if (i == sel) Color.Black else c)) {
                Text(o, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** A choice row that remembers its value in Prefs under [key]. */
@Composable
fun ChoicePref(label: String, key: String, def: Int, options: List<String>, c: Color) {
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf(Prefs.int(ctx, key, def)) }
    Text(label, color = Color.White, fontSize = 13.sp)
    Choices(options, sel, c) { sel = it; Prefs.p(ctx).edit().putInt(key, it).apply() }
}

@Composable
fun TextPref(label: String, key: String, def: String, c: Color) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(Prefs.str(ctx, key, def)) }
    OutlinedTextField(v, { v = it; Prefs.p(ctx).edit().putString(key, it).apply() },
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
fun SwitchPref(label: String, key: String, def: Boolean) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(Prefs.bool(ctx, key, def)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(v, { v = it; Prefs.p(ctx).edit().putBoolean(key, it).apply() })
        Text("  $label", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun SettingsCard(title: String, c: Color, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = .08f))
        .border(1.dp, c.copy(alpha = .4f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("⚙  $title", color = c, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (open) "▲" else "▼", color = c)
        }
        if (open) content()
    }
}

/* ---------------- Pages ---------------- */
@Composable
fun ChatBody(c: Color) {
    val name = Prefs.str(LocalContext.current, "n_name", "Kimi")
    var msg by remember { mutableStateOf("") }
    OutlinedTextField(msg, { msg = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Send", c, Modifier.weight(1f)) { Assistant.ask(msg, false); msg = "" }
        KButton("Clear", c, Modifier.weight(1f)) { Assistant.history.clear() }
    }
    if (Assistant.phase == Phase.THINKING) Text("Thinking…", color = c)
    if (Assistant.status.isNotEmpty()) Text(Assistant.status, color = Color(0xFFFF8A80))
    Assistant.history.asReversed().take(40).forEach { (r, t) ->
        Text(if (r == "user") "You: $t" else "$name: $t", color = Color.White)
    }
}

@Composable
fun VoiceSettings(c: Color) {
    val ctx = LocalContext.current
    val vault = remember { Vault.prefs(ctx) }
    var prov by remember { mutableStateOf(Prefs.int(ctx, "v_prov", 0)) }
    var keyGm by remember { mutableStateOf(vault.getString("v_key_gm", "") ?: "") }
    var keyOa by remember { mutableStateOf(vault.getString("v_key_oa", "") ?: "") }
    var keyCl by remember { mutableStateOf(vault.getString("v_key_cl", "") ?: "") }
    var voices by remember { mutableStateOf(Assistant.voiceList()) }
    var saved by remember { mutableStateOf(Prefs.str(ctx, "v_voice", "")) }
    Title("AI provider", c)
    Choices(listOf("Gemini", "Kimi/OpenAI"), prov, c) { prov = it; Prefs.p(ctx).edit().putInt("v_prov", it).apply() }
    if (prov == 0) {
        TextPref("Model name", "v_model_gm", "gemini-2.5-flash", c)
        OutlinedTextField(keyGm, { keyGm = it; vault.edit().putString("v_key_gm", it.trim()).apply() },
            label = { Text("Gemini API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
    } else if (prov == 1) {
        TextPref("Base URL", "v_base", "https://api.moonshot.ai/v1", c)
        TextPref("Model name", "v_model_oa", "kimi-latest", c)
        OutlinedTextField(keyOa, { keyOa = it; vault.edit().putString("v_key_oa", it.trim()).apply() },
            label = { Text("API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
    } else {
        TextPref("Model name", "v_model_cl", "claude-sonnet-5-5", c)
        OutlinedTextField(keyCl, { keyCl = it; vault.edit().putString("v_key_cl", it.trim()).apply() },
            label = { Text("API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
    }
    KButton("Test AI (speaks a hello)", c, Modifier.fillMaxWidth()) { Assistant.ask("Say hello in one short, friendly sentence.", true) }
    if (Assistant.status.isNotEmpty()) Text(Assistant.status, color = Color(0xFFFF8A80))
    Title("Voice", c)
    ChoicePref("Listening language", "v_lang", 0, listOf("Hindi", "English (India)"), c)
    ChoicePref("Speed", "v_speed", 1, listOf("Slow", "Normal", "Fast"), c)
    ChoicePref("Pitch", "v_pitch", 1, listOf("Deep", "Normal", "Bright"), c)
    Text("Tap a voice to hear it. The one with ✓ is used.", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Refresh list", c, Modifier.weight(1f)) { voices = Assistant.voiceList() }
        KButton("Auto male", c, Modifier.weight(1f)) {
            Prefs.p(ctx).edit().remove("v_voice").apply(); saved = ""
            Assistant.speak("Hello! I'm Kimi, your voice assistant.")
        }
    }
    voices.forEach { v ->
        KButton((if (v.name == saved) "✓ " else "") + v.locale.language + " • " + v.name.substringAfter("-x-", v.name),
            c, Modifier.fillMaxWidth()) { saved = v.name; Assistant.preview(v) }
    }
}

@Composable
fun YouTubeBody(c: Color) {
    val ctx = LocalContext.current
    var out by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("⏮", c, Modifier.weight(1f)) { Control.media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        KButton("⏯", c, Modifier.weight(1f)) { Control.media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        KButton("⏭", c, Modifier.weight(1f)) { Control.media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Open YouTube", c, Modifier.weight(1f)) { out = Control.run(ctx, "open youtube") }
        KButton("Skip ad", c, Modifier.weight(1f)) { out = Control.run(ctx, "skip ad") }
    }
    if (out.isNotEmpty()) Text(out, color = Color.White)
}

@Composable
fun ScreenBody(c: Color) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(KimiAccessibilityService.instance != null) }
    var out by remember { mutableStateOf("") }
    var txt by remember { mutableStateOf("") }
    fun r(s: String) { out = Control.run(ctx, s) }
    Text(if (on) "✅ Service active" else "⚠️ Service off — tap Enable, then turn on Kimi AI", color = Color.White)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Enable", c, Modifier.weight(1f)) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        KButton("Check", c, Modifier.weight(1f)) { on = KimiAccessibilityService.instance != null }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Home", c, Modifier.weight(1f)) { r("home") }
        KButton("Back", c, Modifier.weight(1f)) { r("back") }
        KButton("Recents", c, Modifier.weight(1f)) { r("recents") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Notifs", c, Modifier.weight(1f)) { r("notifications") }
        KButton("Scroll ↑", c, Modifier.weight(1f)) { r("scroll up") }
        KButton("Scroll ↓", c, Modifier.weight(1f)) { r("scroll down") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(txt, { txt = it }, label = { Text("Button text") }, modifier = Modifier.weight(2f), singleLine = true)
        KButton("Tap", c, Modifier.weight(1f)) { out = Control.tap(txt) }
    }
    if (out.isNotEmpty()) Text(out, color = Color.White)
}

@Composable
fun PdfBody(c: Color) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<File?>(null) }
    var msg by remember { mutableStateOf("") }
    OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(body, { body = it }, label = { Text("Content") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
    KButton("Generate PDF", c, Modifier.fillMaxWidth()) {
        if (body.isBlank()) msg = "Add some content first" else {
            file = Pdf.make(ctx, title.ifBlank { "Untitled" }, body); msg = "Saved: ${file!!.name}"
        }
    }
    if (msg.isNotEmpty()) Text(msg, color = Color.White)
    Title("Send to Amazon", c)
    Text("Amazon has no public upload API for KDP, so Kimi hands the file to the Amazon/Kindle app or your browser.", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
    KButton("Share PDF (pick Amazon/Kindle/Drive)", c, Modifier.fillMaxWidth()) {
        val f = file
        if (f != null) Pdf.share(ctx, f) else msg = "Generate a PDF first"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Open KDP", c, Modifier.weight(1f)) { Control.openUrl(ctx, "https://kdp.amazon.com/") }
        KButton("Seller Central", c, Modifier.weight(1f)) { Control.openUrl(ctx, "https://sellercentral.amazon.com/") }
    }
}

@Composable
fun SettingsBody() {
    val n = Mode.NORMAL.color; val g = Mode.GOD.color; val r = Mode.RESEARCH.color
    SettingsCard("Normal mode", n, true) {
        TextPref("Assistant name", "n_name", "Kimi", n)
        ChoicePref("Open YouTube in", "n_yt", 0, listOf("YouTube app", "Browser"), n)
        ChoicePref("Log length", "n_log", 1, listOf("10", "30", "100"), n)
    }
    SettingsCard("God mode", g, true) {
        ChoicePref("Scroll distance", "g_dist", 1, listOf("Short", "Medium", "Long"), g)
        ChoicePref("Scroll speed", "g_speed", 1, listOf("Fast", "Normal", "Slow"), g)
        TextPref("Skip-ad button text", "g_skip", "Skip", g)
    }
    SettingsCard("Research mode", r, true) {
        ChoicePref("Page size", "r_page", 0, listOf("A4", "Letter"), r)
        ChoicePref("Font size", "r_font", 1, listOf("Small", "Medium", "Large"), r)
        ChoicePref("Margins", "r_margin", 1, listOf("Narrow", "Normal", "Wide"), r)
        TextPref("Author name (optional)", "r_author", "", r)
        SwitchPref("Add \"Made with Kimi AI\" footer", "r_footer", true)
    }
}

@Composable
fun PayPalCard(c: Color) {
    val ctx = LocalContext.current
    val p = remember { Vault.prefs(ctx) }
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(p.getString("pp_id", "") ?: "") }
    var sec by remember { mutableStateOf(p.getString("pp_secret", "") ?: "") }
    var live by remember { mutableStateOf(p.getBoolean("pp_live", false)) }
    var res by remember { mutableStateOf("") }
    OutlinedTextField(id, { id = it }, label = { Text("REST Client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(sec, { sec = it }, label = { Text("REST Secret") }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(live, { live = it }); Text("  Live (off = Sandbox)", color = Color.White)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Save", c, Modifier.weight(1f)) {
            p.edit().putString("pp_id", id).putString("pp_secret", sec).putBoolean("pp_live", live).apply(); res = "Saved (encrypted)"
        }
        KButton("Balance", c, Modifier.weight(1f)) {
            res = "Checking…"
            scope.launch { res = withContext(Dispatchers.IO) { PayPal.balance(id, sec, live) } }
        }
        KButton("Open", c, Modifier.weight(1f)) { Control.openUrl(ctx, "https://www.paypal.com/myaccount/summary") }
    }
    if (res.isNotEmpty()) Text(res, color = Color.White)
}

@Composable
fun UserTestingCard(c: Color) {
    val ctx = LocalContext.current
    val p = remember { Vault.prefs(ctx) }
    var email by remember { mutableStateOf(p.getString("ut_email", "") ?: "") }
    var name by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    val items = remember {
        mutableStateListOf<Pair<String, Double>>().apply {
            val a = JSONArray(p.getString("ut_log", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(o.getString("n") to o.getDouble("a")) }
        }
    }
    fun save() {
        val a = JSONArray(); items.forEach { a.put(JSONObject().put("n", it.first).put("a", it.second)) }
        p.edit().putString("ut_log", a.toString()).putString("ut_email", email).apply()
    }
    OutlinedTextField(email, { email = it; save() }, label = { Text("Account email (note only)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it }, label = { Text("Test") }, modifier = Modifier.weight(2f), singleLine = true)
        OutlinedTextField(amt, { amt = it }, label = { Text("Pay") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KButton("Log test", c, Modifier.weight(1f)) {
            val a = amt.toDoubleOrNull()
            if (name.isNotBlank() && a != null) { items.add(name to a); save(); name = ""; amt = "" }
        }
        KButton("Open site", c, Modifier.weight(1f)) { Control.openUrl(ctx, "https://www.usertesting.com/") }
    }
    items.forEach { Text("• ${it.first}: ${"%.2f".format(it.second)}", color = Color.White) }
    Text("Total earned: ${"%.2f".format(items.sumOf { it.second })}", color = c, fontWeight = FontWeight.Bold)
}

