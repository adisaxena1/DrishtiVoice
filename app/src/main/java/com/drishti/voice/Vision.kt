package com.drishti.voice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Wraps the bundled TFLite detector (assets/detector.tflite, EfficientDet-Lite0 with metadata). */
class Detector(ctx: Context) {
    data class Raw(val label: String, val score: Float, val box: RectF) // box normalized 0..1

    private val detector: ObjectDetector = ObjectDetector.createFromFileAndOptions(
        ctx, "detector.tflite",
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(3).build())
            .setMaxResults(8)
            .setScoreThreshold(0.5f)
            .build()
    )

    fun detect(bmp: Bitmap): List<Raw> {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        return detector.detect(TensorImage.fromBitmap(bmp)).mapNotNull { d ->
            val c = d.categories.firstOrNull() ?: return@mapNotNull null
            val b = d.boundingBox
            Raw(c.label.lowercase(), c.score, RectF(b.left / w, b.top / h, b.right / w, b.bottom / h))
        }
    }
}

/** Rough monocular distance: real object height + assumed camera field of view. An estimate, not a measurement. */
object Geo {
    private const val VFOV_DEG = 65.0
    private val realHeight = mapOf(
        "person" to 1.7f, "car" to 1.5f, "bus" to 3.0f, "truck" to 3.0f, "motorcycle" to 1.2f,
        "bicycle" to 1.1f, "dog" to 0.5f, "cat" to 0.3f, "cow" to 1.4f, "horse" to 1.6f,
        "chair" to 0.9f, "bench" to 0.9f, "couch" to 0.9f, "dining table" to 0.75f,
        "potted plant" to 0.5f, "traffic light" to 0.9f, "stop sign" to 0.75f
    )
    private val tall = setOf("person", "car", "bus", "truck", "cow", "horse")
    val relevant: Set<String> = realHeight.keys

    fun distance(label: String, box: RectF): Float {
        val hn = box.height().coerceAtLeast(0.01f)
        val real = realHeight[label] ?: return 5f
        val focal = 0.5f / tan(Math.toRadians(VFOV_DEG / 2)).toFloat()   // in frame-heights
        var d = (real * focal / hn).coerceIn(0.3f, 20f)
        val truncated = box.top <= 0.01f || box.bottom >= 0.99f
        if (truncated && label in tall) d = min(d, 1.4f)                 // cut off by frame = very close
        if (hn > 0.92f) d = min(d, 0.8f)
        return d
    }
}

/** Simple IoU tracker: gives stable ids and estimates time-to-collision from box growth. */
class Tracker {
    private class T(val id: Int, val label: String, var box: RectF, var t: Long) { var growth = 0f }

    private val tracks = ArrayList<T>()
    private var nextId = 1

    private fun iou(a: RectF, b: RectF): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    fun update(raws: List<Detector.Raw>, now: Long): List<Tracked> {
        val matched = HashSet<T>()
        val out = ArrayList<Tracked>()
        for (r in raws) {
            var best: T? = null
            var bestIou = 0.25f
            for (t in tracks) {
                if (t.label != r.label || t in matched) continue
                val i = iou(t.box, r.box)
                if (i > bestIou) { bestIou = i; best = t }
            }
            val tr = best ?: T(nextId++, r.label, r.box, now).also { tracks.add(it) }
            if (best != null) {
                val dt = (now - tr.t) / 1000f
                if (dt > 0.05f) {
                    val g = (r.box.height() / tr.box.height().coerceAtLeast(0.01f) - 1f) / dt
                    tr.growth = 0.7f * tr.growth + 0.3f * g.coerceIn(-1.5f, 1.5f)
                }
                tr.box = r.box
                tr.t = now
            }
            matched.add(tr)

            val cx = r.box.centerX()
            val sector = if (cx < 0.33f) -1 else if (cx > 0.67f) 1 else 0
            val corridor = r.box.right > 0.3f && r.box.left < 0.7f
            val ttc = if (tr.growth > 0.12f) 1f / tr.growth else null
            out.add(
                Tracked(tr.id, r.label, r.score, sector, corridor,
                    Geo.distance(r.label, r.box), ttc, ttc != null && ttc < 8f)
            )
        }
        tracks.removeAll { it !in matched && now - it.t > 1000 }
        return out
    }
}
