// SPDX-License-Identifier: GPL-2.0-or-later

package org.citra.citra_emu.overlay

import kotlin.math.abs

/**
 * The "Resize" gesture of the edit layout for buttons, the D-pad and the circle pads.
 *
 * Only the horizontal position of the finger counts: the left edge of the screen is the smallest
 * size and the right edge the biggest, the same for every control, so two controls dragged to the
 * same place always end up the same size. The size is the value of the scale slider (0..150, a
 * size factor of (value + 50) / 100) and can never leave that range, so nothing wraps around or
 * snaps back when the finger goes past the end.
 *
 * To keep it gentle, the size does not jump to the finger: it starts only after the finger moved
 * a little and then follows at a limited speed.
 */
class ResizeGesture {
    /** Value (0..150) of the control when the gesture started. */
    var startValue = 50f
        private set

    /** Value (0..150) that is shown right now. */
    var value = 50f
        private set

    private var startX = 0f
    private var screenWidth = 1f
    private var active = false
    private var lastTime = 0L
    var targetValue = 50f
        private set

    /** Current size relative to the size when the gesture started (1.0 = unchanged). */
    val ratio: Float
        get() = (value + BASE) / (startValue + BASE)

    fun begin(x: Float, screenWidth: Int, startValue: Int) {
        this.startValue = startValue.toFloat().coerceIn(MIN_VALUE, MAX_VALUE)
        value = this.startValue
        targetValue = this.startValue
        startX = x
        this.screenWidth = maxOf(screenWidth, 1).toFloat()
        active = false
        lastTime = 0L
    }

    /** Call for every move of the finger ([x] in view pixels, [timeMs] the event time). */
    fun update(x: Float, timeMs: Long) {
        // MotionEvent coordinates end at width - 1, so map that pixel to the exact maximum.
        val maxX = (screenWidth - 1f).coerceAtLeast(1f)
        val clampedX = x.coerceIn(0f, maxX)
        if (!active) {
            val reachedEdge = clampedX <= 0f || clampedX >= maxX
            if (abs(x - startX) < TOUCH_SLOP_PX && !reachedEdge) return
            active = true
            lastTime = timeMs
        }
        targetValue = (MAX_VALUE * clampedX / maxX).coerceIn(MIN_VALUE, MAX_VALUE)
        val maxStep = MAX_UNITS_PER_SECOND * (timeMs - lastTime).coerceAtLeast(0L) / 1000f
        lastTime = timeMs
        value += (targetValue - value).coerceIn(-maxStep, maxStep)

        // Reaching an edge is deliberate; clamp there instead of wrapping or rate-limiting it.
        if (clampedX <= 0f) value = MIN_VALUE
        if (clampedX >= maxX) value = MAX_VALUE
    }

    companion object {
        const val MIN_VALUE = 0f
        const val MAX_VALUE = 150f
        private const val BASE = 50f

        // How fast the size follows the finger, in scale slider units per second.
        private const val MAX_UNITS_PER_SECOND = 60f

        // Ignore small accidental movement when the control is first selected.
        private const val TOUCH_SLOP_PX = 24f
    }
}
