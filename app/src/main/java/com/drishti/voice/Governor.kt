package com.drishti.voice

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Walking vs stationary, with hysteresis so a short pause doesn't flip the mode. */
class MotionState(private val sm: SensorManager, private val useAccel: Boolean) : SensorEventListener {
    @Volatile var walking = false
        private set
    private var windowStart = 0L
    private var stepsInWindow = 0
    private var lastStep = 0L
    private var gravity = 9.8f
    private var lastPeak = 0L

    fun start() {
        val step = if (useAccel) null else sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (step != null) sm.registerListener(this, step, SensorManager.SENSOR_DELAY_NORMAL)
        else sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            onStep(now)
        } else {
            val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            gravity = 0.9f * gravity + 0.1f * mag
            if (mag - gravity > 2.5f && now - lastPeak > 350) { lastPeak = now; onStep(now) }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun onStep(now: Long) {
        if (now - windowStart > 2000) { windowStart = now; stepsInWindow = 0 }
        stepsInWindow++
        lastStep = now
        if (stepsInWindow >= 2) walking = true
    }

    /** Call every ~500 ms. */
    fun tick(now: Long) {
        if (walking && now - lastStep > 4000) walking = false
    }
}

/**
 * Decides what is worth saying.
 * Walking: only things in the walking corridor. Stationary: silent, except something moving toward the user.
 * Never queues stale info; only DANGER interrupts speech.
 */
class SpeechGovernor(
    private val motion: MotionState,
    private val lang: () -> Lang,
    private val ttsBusy: () -> Boolean,
    private val speak: (String, Boolean) -> Unit,
    private val haptic: (Priority) -> Unit
) {
    @Volatile var mode = Verbosity.NORMAL
    @Volatile var paused = false

    private val stable = HashMap<Int, Int>()
    private val last = HashMap<Int, Pair<Long, Priority>>()
    private val cooldown = mapOf(Priority.DANGER to 3000L, Priority.CAUTION to 8000L, Priority.INFO to 20000L)

    fun onFrame(objs: List<Tracked>, now: Long) {
        if (paused) return
        stable.keys.retainAll(objs.map { it.id }.toSet())
        val walking = motion.walking

        var best: Pair<Tracked, Priority>? = null
        for (o in objs) {
            val n = (stable[o.id] ?: 0) + 1
            stable[o.id] = n
            if (n < 3 || o.label !in Geo.relevant) continue         // must persist 3 frames
            val p = classify(o, walking) ?: continue
            if (mode == Verbosity.QUIET && p != Priority.DANGER) continue
            val b = best
            if (b == null || p.ordinal > b.second.ordinal ||
                (p == b.second && o.distanceM < b.first.distanceM)) best = o to p
        }
        val (obj, pri) = best ?: return

        val prev = last[obj.id]
        val allowed = prev == null || pri.ordinal > prev.second.ordinal || now - prev.first > cooldown.getValue(pri)
        if (!allowed) return
        if (pri != Priority.DANGER && ttsBusy()) return              // drop, never queue stale info

        last[obj.id] = now to pri
        haptic(pri)
        speak(Words.alert(obj, pri, lang()), pri == Priority.DANGER)
    }

    private fun classify(o: Tracked, walking: Boolean): Priority? {
        if (!walking) {                                              // stationary: silent unless something approaches
            val t = o.ttcSec ?: return null
            if (!o.approaching) return null
            return when {
                t < 2f -> Priority.DANGER
                t < 4f -> Priority.CAUTION
                else -> null
            }
        }
        if (!o.inCorridor) return null                               // walking: only the walking corridor matters
        val t = o.ttcSec ?: 99f
        return when {
            o.distanceM < 1.2f || t < 2.5f -> Priority.DANGER
            o.distanceM < 3f || t < 5f -> Priority.CAUTION
            mode == Verbosity.DETAILED -> Priority.INFO
            else -> null
        }
    }
}
