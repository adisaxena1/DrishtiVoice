package com.drishti.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.text.Normalizer
import java.util.Locale

/** Offline text-to-speech (needs the en-IN / hi-IN voice data installed on the phone). */
class Speaker(ctx: Context, private val onSpeaking: (Boolean) -> Unit, onReady: () -> Unit) {
    private var tts: TextToSpeech? = null
    @Volatile var busy = false
        private set
    @Volatile var last = ""
        private set

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { busy = true; onSpeaking(true) }
                    override fun onDone(id: String?) { busy = false; onSpeaking(false) }
                    override fun onStop(id: String?, interrupted: Boolean) { busy = false; onSpeaking(false) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { busy = false; onSpeaking(false) }
                })
                onReady()
            }
        }
    }

    /** Returns false if that language's voice data is not installed. */
    fun setLang(l: Lang): Boolean {
        val r = tts?.setLanguage(Locale.forLanguageTag(l.tag)) ?: return false
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun say(text: String, interrupt: Boolean) {
        last = text
        tts?.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    fun stop() { tts?.stop(); busy = false }
    fun shutdown() { tts?.shutdown() }
}

enum class Cmd { SCAN, READ, REPEAT, HELP, PAUSE, RESUME, QUIET, NORMAL, DETAILED, HINDI, ENGLISH, STOPNAV }

sealed class Heard {
    data class Do(val c: Cmd) : Heard()
    data class Save(val place: String) : Heard()
    data class Go(val place: String) : Heard()
}

/** Fixed command vocabulary. A small grammar is far more accurate offline than free dictation. */
object Commands {
    // Check these words exist in each model's graph/words.txt; change them if not.
    val wake = mapOf(Lang.EN to "hello vision", Lang.HI to "दृष्टि")

    private val en = mapOf(
        "scan now" to Cmd.SCAN, "what is in front" to Cmd.SCAN, "read text" to Cmd.READ,
        "repeat" to Cmd.REPEAT, "help" to Cmd.HELP, "pause" to Cmd.PAUSE, "resume" to Cmd.RESUME,
        "quiet mode" to Cmd.QUIET, "normal mode" to Cmd.NORMAL, "detailed mode" to Cmd.DETAILED,
        "hindi" to Cmd.HINDI, "stop navigation" to Cmd.STOPNAV
    )
    private val hi = mapOf(
        "सामने क्या है" to Cmd.SCAN, "पढ़ो" to Cmd.READ, "फिर से बोलो" to Cmd.REPEAT, "मदद" to Cmd.HELP,
        "रुको" to Cmd.PAUSE, "शुरू करो" to Cmd.RESUME, "शांत मोड" to Cmd.QUIET,
        "सामान्य मोड" to Cmd.NORMAL, "विस्तार मोड" to Cmd.DETAILED, "अंग्रेजी" to Cmd.ENGLISH,
        "रास्ता बंद करो" to Cmd.STOPNAV
    )
    /** English key (stored) to Hindi name (spoken). */
    val places = listOf(
        "home" to "घर", "clinic" to "क्लिनिक", "pharmacy" to "फार्मेसी", "hospital" to "अस्पताल",
        "shop" to "दुकान", "college" to "कॉलेज", "bus stop" to "बस स्टॉप", "park" to "पार्क",
        "station" to "स्टेशन", "gate" to "गेट", "market" to "बाज़ार"
    )
    fun hiName(en: String): String? = places.firstOrNull { it.first == en }?.second

    fun norm(s: String): String = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)

    fun grammar(l: Lang, saved: Set<String>): List<String> {
        val out = ArrayList<String>()
        if (l == Lang.EN) {
            out += en.keys
            places.forEach { out += "save location as ${it.first}" }
            saved.forEach { p -> out += listOf("navigate to $p", "go to $p", "take me to $p") }
        } else {
            out += hi.keys
            places.forEach { out += "यहाँ ${it.second} सेव करो" }
            saved.forEach { p -> hiName(p)?.let { out += "$it चलो" } }
        }
        return out
    }

    fun parse(raw: String, l: Lang): Heard? {
        val t = norm(raw)
        if (l == Lang.EN) {
            en.entries.firstOrNull { norm(it.key) == t }?.let { return Heard.Do(it.value) }
            places.firstOrNull { t == norm("save location as ${it.first}") }?.let { return Heard.Save(it.first) }
            for (p in places) for (pre in listOf("navigate to ", "go to ", "take me to "))
                if (t == norm(pre + p.first)) return Heard.Go(p.first)
        } else {
            hi.entries.firstOrNull { norm(it.key) == t }?.let { return Heard.Do(it.value) }
            places.firstOrNull { t == norm("यहाँ ${it.second} सेव करो") }?.let { return Heard.Save(it.first) }
            places.firstOrNull { t == norm("${it.second} चलो") }?.let { return Heard.Go(it.first) }
        }
        return null
    }
}

/**
 * Always-on offline listening (Vosk, grammar mode).
 * Commands need the wake word, except for 6 s after the app speaks (natural follow-up).
 */
class AlwaysOnVoice(
    model: Model,
    private val wake: String,
    private val onText: (String) -> Unit
) {
    private val m = model
    private var service: SpeechService? = null
    @Volatile private var followUpUntil = 0L
    private val wakeN = Commands.norm(wake)

    fun start(phrases: List<String>) {
        stop()
        val all = ArrayList<String>()
        phrases.forEach { all += "$wake $it"; all += it }
        all += "[unk]"
        val rec = Recognizer(m, 16000f, JSONArray(all).toString())
        rec.setWords(true)
        service = SpeechService(rec, 16000f).also {
            it.startListening(object : RecognitionListener {
                override fun onResult(h: String?) = handle(h)
                override fun onFinalResult(h: String?) = handle(h)
                override fun onPartialResult(h: String?) {}
                override fun onError(e: Exception?) {}
                override fun onTimeout() {}
            })
        }
    }

    private fun handle(json: String?) {
        val o = JSONObject(json ?: return)
        val text = Commands.norm(o.optString("text"))
        if (text.isEmpty() || text == "[unk]") return

        val words = o.optJSONArray("result")
        if (words != null && words.length() > 0) {
            var sum = 0.0
            for (i in 0 until words.length()) sum += words.getJSONObject(i).optDouble("conf", 1.0)
            if (sum / words.length() < CONF_MIN) return              // reject uncertain matches
        }
        val now = System.currentTimeMillis()
        val hasWake = text.startsWith(wakeN)
        if (!hasWake && now > followUpUntil) return                  // ignore random speech
        followUpUntil = now + FOLLOW_UP_MS
        onText(text.removePrefix(wakeN).trim())
    }

    fun openFollowUpWindow() { followUpUntil = System.currentTimeMillis() + FOLLOW_UP_MS }
    fun setPaused(p: Boolean) { service?.setPause(p) }
    fun stop() { service?.stop(); service?.shutdown(); service = null }

    companion object {
        const val CONF_MIN = 0.6          // tune from real walking tests
        const val FOLLOW_UP_MS = 6000L
    }
}
