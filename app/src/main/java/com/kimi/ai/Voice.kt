package com.kimi.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

/** Talks to an AI API. Supports OpenAI-style endpoints (Kimi/Moonshot etc.) and Claude. */
object Ai {
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 90000
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        val j = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
        if (!ok) {
            val er = j.opt("error")
            val msg = when (er) {
                is JSONObject -> er.optString("message")
                is String -> er
                else -> ""
            }
            throw Exception("AI error ${c.responseCode}: ${msg.ifBlank { "check API key, model and URL in Menu → Voice & AI" }}")
        }
        return j
    }

    fun chat(ctx: Context, system: String, msgs: List<Pair<String, String>>, maxTokens: Int): String {
        val vault = Vault.prefs(ctx)
        val arr = JSONArray()
        val prov = Prefs.int(ctx, "v_prov", 0)
        if (prov == 0) {
            val key = (vault.getString("v_key_gm", "") ?: "").trim()
            if (key.isEmpty()) throw Exception("Add your Gemini API key in Menu → Voice & AI")
            val model = Prefs.str(ctx, "v_model_gm", "gemini-2.5-flash").trim().removePrefix("models/")
            msgs.forEach {
                arr.put(JSONObject().put("role", if (it.first == "user") "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.second))))
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", arr)
                .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens + 2000))
            val j = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                mapOf("x-goog-api-key" to key), body)
            val cands = j.optJSONArray("candidates")
            val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val text = if (parts == null) "" else (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
            if (text.isEmpty()) throw Exception("Gemini returned no answer (it may have been blocked). Try again")
            return text
        } else if (prov == 1) {
            val key = (vault.getString("v_key_oa", "") ?: "").trim()
            if (key.isEmpty()) throw Exception("Add your API key in Menu → Voice & AI")
            val base = Prefs.str(ctx, "v_base", "https://api.moonshot.ai/v1").trim().trimEnd('/')
            arr.put(JSONObject().put("role", "system").put("content", system))
            msgs.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
            val body = JSONObject().put("model", Prefs.str(ctx, "v_model_oa", "kimi-latest").trim())
                .put("messages", arr).put("max_tokens", maxTokens)
            val j = post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
            return j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
        } else {
            val key = (vault.getString("v_key_cl", "") ?: "").trim()
            if (key.isEmpty()) throw Exception("Add your API key in Menu → Voice & AI")
            msgs.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
            val body = JSONObject().put("model", Prefs.str(ctx, "v_model_cl", "claude-sonnet-5-5").trim())
                .put("max_tokens", maxTokens).put("system", system).put("messages", arr)
            val j = post("https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body)
            val c = j.getJSONArray("content")
            return (0 until c.length()).joinToString("") { c.getJSONObject(it).optString("text") }.trim()
        }
    }
}

/** Voice assistant: listen (speech recognition) -> think (AI) -> speak (text-to-speech). */
object Assistant {
    private lateinit var app: Context
    var phase by mutableStateOf(Phase.IDLE)
    var status by mutableStateOf("")
    var mode by mutableStateOf(Mode.NORMAL)
    val history = mutableStateListOf<Pair<String, String>>()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var rec: SpeechRecognizer? = null
    private var lastId = ""
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        tts = TextToSpeech(app) { s ->
            ready = s == TextToSpeech.SUCCESS
            if (ready) tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == lastId) main.post { if (phase == Phase.SPEAKING) phase = Phase.IDLE }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == lastId) main.post { if (phase == Phase.SPEAKING) phase = Phase.IDLE }
                }
            })
        }
    }

    /* ---------- speaking ---------- */
    private fun maleHint(v: Voice): Boolean {
        val n = v.name.lowercase()
        if ("female" in n) return false
        if ("male" in n) return true
        return listOf("-x-enb", "-x-end", "-x-hib", "-x-hic").any { it in n }
    }

    fun voiceList(): List<Voice> = (tts?.voices ?: emptySet())
        .filter { (it.locale.language == "hi" || (it.locale.language == "en" && it.locale.country == "IN")) && !it.features.contains("notInstalled") }
        .sortedBy { it.name }.take(16)

    fun preview(v: Voice) {
        Prefs.p(app).edit().putString("v_voice", v.name).apply()
        speak(if (v.locale.language == "hi") "नमस्ते! मैं किमी हूँ, आपका वॉइस असिस्टेंट।" else "Hello! I'm Kimi, your voice assistant.")
    }

    fun stopSpeaking() { tts?.stop(); if (phase == Phase.SPEAKING) phase = Phase.IDLE }

    private fun chunks(s: String): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (sent in s.split(Regex("(?<=[.!?।\\n])\\s*"))) {
            if (sent.isBlank()) continue
            if (cur.length + sent.length > 350 && cur.isNotEmpty()) { out.add(cur.toString().trim()); cur = StringBuilder() }
            cur.append(sent).append(' ')
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    fun speak(text: String) {
        val t = tts
        if (t == null || !ready) { phase = Phase.IDLE; status = "Voice engine not ready yet"; return }
        val clean = text.replace(Regex("[*#`_~>|]"), "").replace(Regex("https?://\\S+"), "").trim()
        if (clean.isEmpty()) { phase = Phase.IDLE; return }
        val dev = clean.count { it in '\u0900'..'\u097F' }
        val letters = clean.count { it.isLetter() }
        val loc = if (letters > 0 && dev * 2 > letters) Locale("hi", "IN") else Locale("en", "IN")
        t.setLanguage(loc)
        val vs = (t.voices ?: emptySet()).filter { it.locale.language == loc.language && !it.features.contains("notInstalled") }
        val saved = Prefs.str(app, "v_voice", "")
        val pick = vs.firstOrNull { it.name == saved } ?: vs.firstOrNull { maleHint(it) } ?: vs.firstOrNull()
        if (pick != null) t.setVoice(pick)
        t.setPitch(listOf(0.8f, 0.95f, 1.1f)[Prefs.int(app, "v_pitch", 1).coerceIn(0, 2)])
        t.setSpeechRate(listOf(0.85f, 0.95f, 1.15f)[Prefs.int(app, "v_speed", 1).coerceIn(0, 2)])
        val parts = chunks(clean)
        val stamp = System.nanoTime()
        parts.forEachIndexed { i, c ->
            val id = "k${stamp}_$i"
            if (i == parts.lastIndex) lastId = id
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
        phase = Phase.SPEAKING
    }

    /* ---------- listening ---------- */
    private fun recError(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap and try again"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet"
        else -> "Mic error ($e). Tap and try again"
    }

    fun listen() {
        if (phase == Phase.THINKING) return
        if (phase == Phase.LISTENING) { rec?.stopListening(); return }
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(app)) { status = "Speech recognition isn't available on this phone"; return }
        val r = rec ?: SpeechRecognizer.createSpeechRecognizer(app).also { rec = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onError(e: Int) { phase = Phase.IDLE; status = recError(e) }
            override fun onResults(res: Bundle?) {
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isBlank()) { phase = Phase.IDLE; status = "Didn't catch that. Tap and try again" } else ask(t, true)
            }
        })
        val lang = if (Prefs.int(app, "v_lang", 0) == 0) "hi-IN" else "en-IN"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        status = ""
        phase = Phase.LISTENING
        r.startListening(i)
    }

    /* ---------- thinking ---------- */
    private fun localCommand(t: String): String? {
        val l = t.lowercase().trim().trimEnd('.', '!', '?', '।')
        val hi = Prefs.int(app, "v_lang", 0) == 0
        val cmd: String? = when {
            l.startsWith("search youtube ") || l.startsWith("click ") || l.startsWith("tap ") -> l
            l.split(" ").size > 3 -> null
            listOf("open youtube", "youtube kholo", "youtube khol", "यूट्यूब खोलो", "यूट्यूब खोल", "यूट्यूब चलाओ").any { it in l } -> "open youtube"
            listOf("pause", "ruko", "रुको", "पॉज़", "पॉज", "play", "chalao", "चलाओ", "प्ले").any { it in l } -> "pause"
            listOf("next", "agla", "अगला", "नेक्स्ट").any { it in l } -> "next"
            listOf("previous", "pichla", "पिछला").any { it in l } -> "previous"
            listOf("scroll down", "neeche", "नीचे").any { it in l } -> "scroll down"
            listOf("scroll up", "upar", "ऊपर").any { it in l } -> "scroll up"
            listOf("home", "होम").any { it in l } -> "home"
            listOf("back", "wapas", "वापस", "बैक").any { it in l } -> "back"
            else -> null
        }
        cmd ?: return null
        val r = Control.run(app, cmd)
        return if (r.startsWith("Enable") || r.startsWith("Couldn't")) r else if (hi) "ठीक है।" else "Done."
    }

    private fun system(): String {
        val name = Prefs.str(app, "n_name", "Kimi")
        val base = "You are $name, a warm, friendly young male voice assistant inside the Kimi AI app. " +
            "Your replies are read aloud by text-to-speech, so write plain spoken text only: no markdown, no bullet points, no emojis, no web links. " +
            "Reply in the language the user spoke: Hindi must be written in Devanagari script, English in English. " +
            "If the user mixes Hindi and English, answer in Devanagari Hindi but keep common English tech words as they are. " +
            "Sound natural, polite and caring, like a close friend. "
        val m = when (mode) {
            Mode.NORMAL -> "Keep answers short: one to three sentences unless the user asks for more."
            Mode.GOD -> "Think deeply before answering. Give thorough, insightful and creative answers in clear spoken paragraphs, up to about eight sentences."
            Mode.RESEARCH -> "Act as a careful research analyst. Give accurate, well organised answers in spoken form, say honestly when you are unsure, and keep it under about ten sentences."
        }
        return base + m
    }

    fun ask(text: String, speakIt: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        history.add("user" to t)
        localCommand(t)?.let { r ->
            history.add("assistant" to r)
            if (speakIt) speak(r) else phase = Phase.IDLE
            return
        }
        phase = Phase.THINKING; status = ""
        val msgs = history.takeLast(12).dropWhile { it.first != "user" }.toList()
        val max = when (mode) { Mode.NORMAL -> 500; Mode.GOD -> 1500; Mode.RESEARCH -> 1200 }
        scope.launch {
            try {
                val ans = withContext(Dispatchers.IO) { Ai.chat(app, system(), msgs, max) }
                history.add("assistant" to ans)
                if (speakIt) speak(ans) else phase = Phase.IDLE
            } catch (e: Exception) {
                phase = Phase.IDLE
                status = e.message ?: "Something went wrong"
                history.add("assistant" to "⚠ $status")
            }
        }
    }
}
