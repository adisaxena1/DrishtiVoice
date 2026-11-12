package com.drishti.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.vosk.Model
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var speaker: Speaker
    private lateinit var motion: MotionState
    private lateinit var nav: Nav
    private lateinit var governor: SpeechGovernor

    private var detector: Detector? = null
    private val tracker = Tracker()
    private var voice: AlwaysOnVoice? = null
    private var model: Model? = null

    @Volatile private var lang = Lang.EN
    @Volatile private var latest: List<Tracked> = emptyList()
    @Volatile private var lastSpoken = ""
    @Volatile private var lastDetect = 0L
    @Volatile private var lastFrame = 0L
    private var lastWarn = 0L
    private var lastDark = 0L
    private var started = false
    private val ocrRequested = AtomicBoolean(false)

    private val latinRec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devaRec by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startAll() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD600"))
            setBackgroundColor(Color.parseColor("#07090E"))
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            text = "DrishtiVoice\nStarting..."
        }
        setContentView(status)

        speaker = Speaker(this,
            onSpeaking = { speaking ->
                voice?.setPaused(speaking)                           // don't hear ourselves
                if (!speaking) main.postDelayed({ voice?.setPaused(false); voice?.openFollowUpWindow() }, 300)
            },
            onReady = {
                speaker.setLang(Lang.EN)
                speaker.say("DrishtiVoice. Please allow camera, microphone, location and physical activity permissions.", true)
                askPerms()
            })
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) list += Manifest.permission.ACTIVITY_RECOGNITION
        perms.launch(list.toTypedArray())
    }

    private fun say(en: String, hi: String, interrupt: Boolean = false) {
        val s = Words.t(lang, en, hi)
        lastSpoken = s
        speaker.say(s, interrupt)
    }

    private fun startAll() {
        if (started) return
        if (!granted(Manifest.permission.CAMERA) || !granted(Manifest.permission.RECORD_AUDIO)) {
            say("Camera and microphone permission are required. Please allow them in phone settings.",
                "कैमरा और माइक्रोफ़ोन की अनुमति ज़रूरी है। कृपया फ़ोन सेटिंग में अनुमति दें।", true)
            return
        }
        started = true
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val stepOk = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)
        motion = MotionState(sm, useAccel = !stepOk).also { it.start() }
        nav = Nav(this, lm, sm).also { it.start() }
        governor = SpeechGovernor(motion, { lang }, { speaker.busy },
            { text, interrupt -> lastSpoken = text; speaker.say(text, interrupt) }, ::vibrate)

        try {
            detector = Detector(this)
        } catch (e: Exception) {
            say("Detection model is missing. Add detector dot tflite to the app assets.",
                "डिटेक्शन मॉडल नहीं मिला।", true)
        }
        if (detector != null) startCamera()
        loadVoice(lang)
        say("DrishtiVoice is ready. Say hello vision, then a command. Say hello vision help for the list.",
            "दृष्टि तैयार है। दृष्टि बोलकर कमांड दें।", true)
        main.post(ticker)
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        lastFrame = System.currentTimeMillis()
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val provider = fut.get()
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            lastFrame = now
            val wantOcr = ocrRequested.getAndSet(false)
            if (!wantOcr && now - lastDetect < 120) return           // ~8 FPS is plenty
            lastDetect = now

            val raw = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)

            if (brightness(bmp) < 35 && now - lastDark > 30000) {
                lastDark = now
                say("It is too dark. Detection may miss obstacles.", "बहुत अंधेरा है। रुकावटें छूट सकती हैं।")
            }
            if (wantOcr) runOcr(bmp)

            val tracked = tracker.update(detector!!.detect(bmp), now)
            latest = tracked
            governor.onFrame(tracked, now)
        } finally {
            proxy.close()
        }
    }

    private fun brightness(b: Bitmap): Int {
        var sum = 0L
        var n = 0
        val sx = b.width / 10
        val sy = b.height / 10
        for (y in 1..9) for (x in 1..9) {
            val p = b.getPixel(x * sx, y * sy)
            sum += (Color.red(p) * 3 + Color.green(p) * 6 + Color.blue(p)) / 10
            n++
        }
        return (sum / n).toInt()
    }

    private fun runOcr(bmp: Bitmap) {
        val rec = if (lang == Lang.HI) devaRec else latinRec
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                val s = r.text.replace("\n", " ").trim().take(300)
                if (s.isBlank()) say("No text found. Move closer and hold steady.", "कोई लिखावट नहीं मिली। पास जाएँ और स्थिर रखें।", true)
                else { lastSpoken = s; speaker.say(s, true) }
            }
            .addOnFailureListener { say("Could not read text.", "पढ़ नहीं सका।", true) }
    }

    // ----------------------------------------------------------------- voice

    private fun loadVoice(l: Lang) {
        thread {
            try {
                val dir = if (l == Lang.EN) "model-en" else "model-hi"
                val target = File(filesDir, dir)
                val done = File(target, ".done")
                if (!done.exists()) {
                    target.deleteRecursively()
                    copyAssets(dir, target)
                    done.createNewFile()
                }
                val m = Model(target.absolutePath)
                main.post {
                    voice?.stop()
                    model?.close()
                    model = m
                    voice = AlwaysOnVoice(m, Commands.wake.getValue(l)) { text ->
                        Commands.parse(text, lang)?.let { h -> main.post { onHeard(h) } }
                    }.also { it.start(Commands.grammar(l, nav.savedNames())) }
                }
            } catch (e: Exception) {
                main.post {
                    say("Voice model is missing. Add the Vosk model folders to the app assets.",
                        "वॉइस मॉडल नहीं मिला।", true)
                }
            }
        }
    }

    private fun copyAssets(src: String, dst: File) {
        val list = assets.list(src) ?: emptyArray()
        if (list.isEmpty()) {
            dst.parentFile?.mkdirs()
            assets.open(src).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dst.mkdirs()
            list.forEach { copyAssets("$src/$it", File(dst, it)) }
        }
    }

    private fun restartGrammar() { voice?.start(Commands.grammar(lang, nav.savedNames())) }

    private fun onHeard(h: Heard) {
        when (h) {
            is Heard.Do -> when (h.c) {
                Cmd.SCAN -> { val s = Words.describe(latest, lang); lastSpoken = s; speaker.say(s, true) }
                Cmd.READ -> { vibrate(Priority.INFO); ocrRequested.set(true) }
                Cmd.REPEAT -> speaker.say(speaker.last, true)
                Cmd.HELP -> say(
                    "Say hello vision, then: scan now, read text, repeat, pause, resume, quiet mode, normal mode, " +
                        "detailed mode, hindi, save location as home, navigate to home, or stop navigation. " +
                        "Volume up scans. Volume down stops speech.",
                    "दृष्टि बोलकर कहें: सामने क्या है, पढ़ो, फिर से बोलो, रुको, शुरू करो, शांत मोड, सामान्य मोड, विस्तार मोड, " +
                        "अंग्रेजी, यहाँ घर सेव करो, घर चलो, रास्ता बंद करो।", true)
                Cmd.PAUSE -> { governor.paused = true; say("Paused", "रुका हुआ", true) }
                Cmd.RESUME -> { governor.paused = false; say("Resumed", "शुरू", true) }
                Cmd.QUIET -> { governor.mode = Verbosity.QUIET; say("Quiet mode", "शांत मोड", true) }
                Cmd.NORMAL -> { governor.mode = Verbosity.NORMAL; say("Normal mode", "सामान्य मोड", true) }
                Cmd.DETAILED -> { governor.mode = Verbosity.DETAILED; say("Detailed mode", "विस्तार मोड", true) }
                Cmd.HINDI -> switchLang(Lang.HI)
                Cmd.ENGLISH -> switchLang(Lang.EN)
                Cmd.STOPNAV -> { nav.target = null; say("Navigation stopped", "रास्ता बंद", true) }
            }
            is Heard.Save -> {
                if (nav.save(h.place)) {
                    say("Saved ${h.place}", "${Commands.hiName(h.place) ?: h.place} सेव हो गया", true)
                    restartGrammar()
                } else say("GPS is not ready yet. Try outdoors.", "जीपीएस तैयार नहीं है। बाहर कोशिश करें।", true)
            }
            is Heard.Go -> {
                val loc = nav.get(h.place)
                if (loc == null) say("I do not have that place saved.", "यह जगह सेव नहीं है।", true)
                else {
                    nav.target = h.place to loc
                    say("Navigating to ${h.place}", "${Commands.hiName(h.place) ?: h.place} की ओर चलिए", true)
                }
            }
        }
    }

    private fun switchLang(l: Lang) {
        if (l == lang) return
        if (!speaker.setLang(l)) {
            say("That voice is not installed on this phone.", "वह आवाज़ इस फ़ोन में इंस्टॉल नहीं है।", true)
            return
        }
        lang = l
        say("English mode on", "हिंदी चालू", true)
        loadVoice(l)
    }

    // ------------------------------------------------------------------ misc

    private fun vibrate(p: Priority) {
        val v = getSystemService(Vibrator::class.java) ?: return
        val e = when (p) {
            Priority.DANGER -> VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1)
            Priority.CAUTION -> VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            Priority.INFO -> VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(e)
    }

    private val ticker = object : Runnable {
        var n = 0
        override fun run() {
            val now = System.currentTimeMillis()
            motion.tick(now)
            if (detector != null && now - lastFrame > 3000 && now - lastWarn > 15000) {
                lastWarn = now
                say("Vision stopped.", "दृष्टि रुक गई।", true)       // watchdog
            }
            if (++n % 14 == 0 && nav.target != null && motion.walking && !speaker.busy)
                nav.guidance(lang)?.let { lastSpoken = it; speaker.say(it, false) }
            status.text = "${governor.mode}  |  ${lang.name}  |  " +
                (if (motion.walking) "WALKING" else "STATIONARY") +
                (if (governor.paused) "  |  PAUSED" else "") + "\n\n$lastSpoken"
            main.postDelayed(this, 500)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!started) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { onHeard(Heard.Do(Cmd.SCAN)); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { speaker.stop(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        if (started) { motion.stop(); nav.stop() }
        voice?.stop(); model?.close(); speaker.shutdown(); exec.shutdown()
    }
}
