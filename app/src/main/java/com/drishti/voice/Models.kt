package com.drishti.voice

enum class Lang(val tag: String) { EN("en-IN"), HI("hi-IN") }
enum class Priority { INFO, CAUTION, DANGER }
enum class Verbosity { QUIET, NORMAL, DETAILED }

data class Tracked(
    val id: Int,
    val label: String,
    val confidence: Float,
    val sector: Int,            // -1 left, 0 ahead, 1 right
    val inCorridor: Boolean,
    val distanceM: Float,
    val ttcSec: Float?,         // time to collision, null if not approaching
    val approaching: Boolean
)

/** All spoken text lives here so both languages stay consistent. */
object Words {
    fun t(l: Lang, en: String, hi: String) = if (l == Lang.HI) hi else en

    private val hiNames = mapOf(
        "person" to "व्यक्ति", "bicycle" to "साइकिल", "car" to "कार", "motorcycle" to "मोटरसाइकिल",
        "bus" to "बस", "truck" to "ट्रक", "dog" to "कुत्ता", "cat" to "बिल्ली", "cow" to "गाय",
        "horse" to "घोड़ा", "bench" to "बेंच", "chair" to "कुर्सी", "couch" to "सोफ़ा",
        "dining table" to "मेज़", "potted plant" to "गमला", "traffic light" to "ट्रैफिक लाइट",
        "stop sign" to "स्टॉप साइन"
    )
    private val feminine = setOf("car", "bus", "bicycle", "motorcycle", "cat", "cow", "dining table", "traffic light")

    fun name(label: String, l: Lang) = if (l == Lang.HI) hiNames[label] ?: label else label

    private fun side(s: Int, l: Lang) = when (s) {
        -1 -> t(l, "on your left", "बाईं ओर")
        1 -> t(l, "on your right", "दाईं ओर")
        else -> t(l, "ahead", "सामने")
    }
    private fun from(s: Int, l: Lang) = when (s) {
        -1 -> t(l, "from your left", "बाईं ओर से")
        1 -> t(l, "from your right", "दाईं ओर से")
        else -> t(l, "from ahead", "सामने से")
    }
    private fun meters(d: Float): String {
        val r = Math.round(d * 2) / 2f
        return if (r % 1f == 0f) r.toInt().toString() else r.toString()
    }

    fun alert(o: Tracked, p: Priority, l: Lang): String {
        val n = name(o.label, l)
        val m = meters(o.distanceM)
        return if (l == Lang.HI) {
            val verb = if (o.label in feminine) "आ रही है" else "आ रहा है"
            when {
                o.approaching && p != Priority.INFO -> "सावधान! ${from(o.sector, l)} $n $verb"
                p == Priority.DANGER -> "रुको। सामने $n है"
                else -> "${side(o.sector, l)} $n, $m मीटर"
            }
        } else {
            when {
                o.approaching && p != Priority.INFO -> "Warning. $n approaching ${from(o.sector, l)}"
                p == Priority.DANGER -> "Stop. $n ahead"
                else -> "$n ${side(o.sector, l)}, $m meters"
            }
        }
    }

    fun describe(objs: List<Tracked>, l: Lang): String {
        val top = objs.sortedBy { it.distanceM }.take(4)
        if (top.isEmpty()) return t(l, "No obstacle detected", "कोई रुकावट नहीं दिखी")
        return top.joinToString(". ") {
            "${name(it.label, l)} ${side(it.sector, l)}, ${meters(it.distanceM)} ${t(l, "meters", "मीटर")}"
        }
    }
}
