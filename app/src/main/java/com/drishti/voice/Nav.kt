package com.drishti.voice

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import kotlin.math.abs
import kotlin.math.roundToInt

/** Offline GPS + compass guidance to saved places. Straight-line only: it does not know about roads. */
class Nav(ctx: Context, private val lm: LocationManager, private val sm: SensorManager) :
    SensorEventListener, LocationListener {

    @Volatile var loc: Location? = null
    @Volatile var heading = Float.NaN
    @Volatile var target: Pair<String, Location>? = null
    private val prefs = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @SuppressLint("MissingPermission")
    fun start() {
        rv?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this) } catch (_: Exception) {}
    }

    fun stop() {
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (_: Exception) {}
    }

    private fun norm(a: Float): Float = ((a % 360f) + 360f) % 360f
    private fun diff(a: Float, b: Float): Float = ((a - b + 540f) % 360f) - 180f

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9); val r2 = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // Phone held upright with the camera facing forward.
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
        SensorManager.getOrientation(r2, o)
        var az = Math.toDegrees(o[0].toDouble()).toFloat()
        loc?.let {
            az += GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(),
                it.altitude.toFloat(), System.currentTimeMillis()).declination
        }
        val prev = heading
        heading = if (prev.isNaN()) norm(az) else norm(prev + 0.2f * diff(az, prev))
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onLocationChanged(l: Location) { loc = l }

    fun save(name: String): Boolean {
        val l = loc ?: return false
        prefs.edit().putString(name, "${l.latitude},${l.longitude}").apply()
        return true
    }

    fun savedNames(): Set<String> = prefs.all.keys.toSet()

    fun get(name: String): Location? {
        val parts = prefs.getString(name, null)?.split(",") ?: return null
        return Location("saved").apply { latitude = parts[0].toDouble(); longitude = parts[1].toDouble() }
    }

    fun guidance(l: Lang): String? {
        val t = target ?: return null
        val me = loc ?: return Words.t(l, "Waiting for GPS", "जीपीएस का इंतज़ार है")
        if (heading.isNaN()) return null
        val dist = me.distanceTo(t.second)
        val name = if (l == Lang.HI) Commands.hiName(t.first) ?: t.first else t.first
        if (dist < 12f) {
            target = null
            return Words.t(l, "You have arrived at $name", "आप $name पहुँच गए")
        }
        val turn = diff(norm(me.bearingTo(t.second)), heading)
        val steps = (dist / 0.7f).roundToInt()
        val d = if (dist >= 1000f) {
            "%.1f ".format(dist / 1000f) + Words.t(l, "kilometers", "किलोमीटर")
        } else {
            "${(dist / 5f).roundToInt() * 5} " + Words.t(l, "meters", "मीटर") +
                (if (dist < 300f) ", ${Words.t(l, "about", "लगभग")} $steps ${Words.t(l, "steps", "कदम")}" else "")
        }
        val weak = if (me.accuracy > 25f) Words.t(l, "GPS weak. ", "जीपीएस कमज़ोर है। ") else ""
        val deg = abs(turn).roundToInt()
        val dir = when {
            deg < 20 -> Words.t(l, "Go straight", "सीधे चलें")
            turn > 0 -> Words.t(l, "Turn right $deg degrees", "$deg डिग्री दाएँ मुड़ें")
            else -> Words.t(l, "Turn left $deg degrees", "$deg डिग्री बाएँ मुड़ें")
        }
        return "$weak$dir, $d"
    }
}
