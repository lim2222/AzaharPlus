// SPDX-License-Identifier: GPL-2.0-or-later

package org.citra.citra_emu.overlay

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Touch regions of the drum.
 *
 * The drum head (the ellipse) is split into four quadrants around its center; everything
 * outside the head is the rim, split into left and right at the horizontal center of the drum.
 */
enum class TaTaConRegion {
    HEAD_UP_LEFT, HEAD_DOWN_LEFT, HEAD_UP_RIGHT, HEAD_DOWN_RIGHT, RIM_LEFT, RIM_RIGHT;

    val isHead: Boolean
        get() = this != RIM_LEFT && this != RIM_RIGHT
}

/**
 * Draws a single tatacon.png image and hit-tests the six regions of [TaTaConRegion].
 *
 * The drum artwork only occupies a small centered area of tatacon.png, so the bitmap is cropped
 * to that artwork first ([cropArtwork]). Hit testing uses the real geometry of the artwork.
 *
 * The rim is accepted over the area given to [setRimHitBounds] (normally the whole screen), so
 * the caller must guard it against other overlay controls.
 *
 * @param buttonIdXxx 3DS button id sent for each region (see NativeLibrary.ButtonType).
 */
class InputOverlayDrawableTaTaCon(
    res: Resources,
    bitmap: Bitmap,
    buttonIdHeadUpLeft: Int,
    buttonIdHeadDownLeft: Int,
    buttonIdHeadUpRight: Int,
    buttonIdHeadDownRight: Int,
    buttonIdRimLeft: Int,
    buttonIdRimRight: Int
) {
    private val buttonIds = intArrayOf(
        buttonIdHeadUpLeft, buttonIdHeadDownLeft, buttonIdHeadUpRight,
        buttonIdHeadDownRight, buttonIdRimLeft, buttonIdRimRight
    )
    private val trackIds = IntArray(TaTaConRegion.values().size) { -1 }
    private val pressed = BooleanArray(TaTaConRegion.values().size)

    private var previousTouchX = 0
    private var previousTouchY = 0
    private var controlPositionX = 0
    private var controlPositionY = 0

    val width: Int
    val height: Int
    private val bitmapDrawable: BitmapDrawable

    var customScaleX: Float = 1.0f
    var customScaleY: Float = 1.0f
    private var _customOpacity: Int = 255
    val customOpacity: Int
        get() = _customOpacity

    /** Area in which rim hits are recognized, normally the whole screen. */
    private var rimHitBounds: Rect? = null

    init {
        this.bitmapDrawable = BitmapDrawable(res, bitmap)
        width = bitmap.width
        height = bitmap.height
    }

    fun setRimHitBounds(bounds: Rect) {
        rimHitBounds = Rect(bounds)
    }

    fun setCustomScale(sx: Float, sy: Float) {
        customScaleX = sx.coerceIn(0.1f, 20.0f)
        customScaleY = sy.coerceIn(0.1f, 20.0f)
        applyCustomSize()
    }

    private fun applyCustomSize() {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val newW = (intrinsicWidth * customScaleX).toInt()
        val newH = (intrinsicHeight * customScaleY).toInt()
        setBounds(centerX - newW / 2, centerY - newH / 2, centerX + newW / 2, centerY + newH / 2)
        controlPositionX = bounds.left
        controlPositionY = bounds.top
    }

    fun setCustomOpacity(opacity: Int) {
        _customOpacity = opacity.coerceIn(0, 255)
        bitmapDrawable.alpha = _customOpacity
    }

    val intrinsicWidth: Int get() = bitmapDrawable.bitmap?.width ?: 0
    val intrinsicHeight: Int get() = bitmapDrawable.bitmap?.height ?: 0

    val bounds: Rect
        get() = bitmapDrawable.bounds

    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        bitmapDrawable.setBounds(left, top, right, bottom)
    }

    fun setPosition(x: Int, y: Int) {
        controlPositionX = x
        controlPositionY = y
    }

    // State of an edit-layout resize gesture.
    private var resizeCenterX = 0
    private var resizeCenterY = 0
    private var resizeStartDist = 1
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartFingerX = 0
    private var resizeStartFingerY = 0

    /** Current width relative to the width the drum was created with (1.0 = unchanged). */
    val resizeRatio: Float
        get() = bounds.width().toFloat() / width

    /** Current height relative to the height the drum was created with (1.0 = unchanged). */
    val resizeRatioY: Float
        get() = bounds.height().toFloat() / height

    /** Edit-layout touch handler. [action] decides whether we MOVE, RESIZE or change OPACITY. */
    fun onConfigureTouch(
        event: MotionEvent,
        action: InputOverlay.EditAction = InputOverlay.EditAction.MOVE
    ) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                previousTouchX = event.x.toInt()
                previousTouchY = event.y.toInt()
                resizeCenterX = bounds.centerX()
                resizeCenterY = bounds.centerY()
                resizeStartWidth = bounds.width()
                resizeStartHeight = bounds.height()
                resizeStartFingerX = event.x.toInt()
                resizeStartFingerY = event.y.toInt()
                resizeStartDist = maxOf(
                    10,
                    Math.hypot(
                        (event.x - resizeCenterX).toDouble(),
                        (event.y - resizeCenterY).toDouble()
                    ).toInt()
                )
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.x.toInt()
                val y = event.y.toInt()
                when (action) {
                    InputOverlay.EditAction.MOVE -> {
                        val currentWidth = bounds.width()
                        val currentHeight = bounds.height()
                        controlPositionX += x - previousTouchX
                        controlPositionY += y - previousTouchY
                        setBounds(
                            controlPositionX,
                            controlPositionY,
                            controlPositionX + currentWidth,
                            controlPositionY + currentHeight
                        )
                    }

                    InputOverlay.EditAction.RESIZE -> {
                        // Horizontal drag changes the width, vertical drag the height. The drum
                        // grows around its center, so each edge follows the finger (hence the 2).
                        val newWidth = (resizeStartWidth + 2 * (x - resizeStartFingerX))
                            .coerceAtLeast(40)
                            .coerceAtMost(resizeStartWidth * 5)
                        val newHeight = (resizeStartHeight + 2 * (y - resizeStartFingerY))
                            .coerceAtLeast(40)
                            .coerceAtMost(resizeStartHeight * 5)
                        controlPositionX = resizeCenterX - newWidth / 2
                        controlPositionY = resizeCenterY - newHeight / 2
                        setBounds(
                            controlPositionX,
                            controlPositionY,
                            controlPositionX + newWidth,
                            controlPositionY + newHeight
                        )
                    }

                    InputOverlay.EditAction.OPACITY -> {
                        val deltaY = previousTouchY - y
                        setCustomOpacity((_customOpacity + deltaY * 255 / 300).coerceIn(13, 255))
                    }
                }
                previousTouchX = x
                previousTouchY = y
            }
        }
    }

    fun resetToDefault(defaultX: Int, defaultY: Int) {
        customScaleX = 1.0f
        customScaleY = 1.0f
        _customOpacity = 255
        bitmapDrawable.alpha = 255
        pressed.fill(false)
        trackIds.fill(-1)
        setPosition(defaultX, defaultY)
        setBounds(defaultX, defaultY, defaultX + intrinsicWidth, defaultY + intrinsicHeight)
        controlPositionX = defaultX
        controlPositionY = defaultY
    }

    fun draw(canvas: Canvas) {
        val drawBounds = bitmapDrawable.bounds
        val anyPressed = pressed.any { it }
        val headPressed = TaTaConRegion.values().any { it.isHead && pressed[it.ordinal] }

        val save = canvas.save()
        // The drum sinks a little while it is being hit.
        if (anyPressed) {
            canvas.translate(0f, drawBounds.height() * SINK_RATIO)
        }
        bitmapDrawable.draw(canvas)
        if (headPressed) {
            drawHeadGlow(canvas, drawBounds)
        }
        canvas.restoreToCount(save)
    }

    private fun drawHeadGlow(canvas: Canvas, drawBounds: Rect) {
        val cx = drawBounds.left + HEAD_CENTER_X * drawBounds.width()
        val cy = drawBounds.top + HEAD_CENTER_Y * drawBounds.height()
        val rx = HEAD_RADIUS_X * drawBounds.width() * GLOW_SCALE
        val ry = HEAD_RADIUS_Y * drawBounds.height() * GLOW_SCALE

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, max(rx, ry),
                intArrayOf(GLOW_COLOR, GLOW_COLOR_TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            alpha = min((_customOpacity * GLOW_ALPHA_FACTOR).toInt(), 255)
        }
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
    }

    /** True if the given point is inside the elliptical drum head. */
    fun isOnHead(x: Int, y: Int): Boolean {
        val b = bitmapDrawable.bounds
        if (b.width() <= 0 || b.height() <= 0) return false
        val nx = ((x - b.left).toFloat() / b.width() - HEAD_CENTER_X) / HEAD_RADIUS_X
        val ny = ((y - b.top).toFloat() / b.height() - HEAD_CENTER_Y) / HEAD_RADIUS_Y
        return nx * nx + ny * ny <= 1f
    }

    /** True if the given point is on the drum artwork (used for edit mode hit testing). */
    fun isOnArtwork(x: Int, y: Int): Boolean {
        val b = bitmapDrawable.bounds
        if (b.width() <= 0 || b.height() <= 0) return false
        val nx = (x - b.left).toFloat() / b.width() - 0.5f
        val ny = (y - b.top).toFloat() / b.height() - 0.5f
        return nx * nx + ny * ny <= 0.25f
    }

    /**
     * Returns the [TaTaConRegion] at the given point, or null when the point is neither on the
     * drum head nor inside the rim hit area.
     *
     * Head: split into four quadrants around the center of the head ellipse.
     * Rim: everything else, split left/right at the horizontal center of the drum.
     */
    fun getRegionAt(x: Int, y: Int): TaTaConRegion? {
        val b = bitmapDrawable.bounds
        if (b.width() <= 0 || b.height() <= 0) return null

        if (isOnHead(x, y)) {
            val headCx = b.left + HEAD_CENTER_X * b.width()
            val headCy = b.top + HEAD_CENTER_Y * b.height()
            val left = x < headCx
            val up = y < headCy
            return when {
                left && up -> TaTaConRegion.HEAD_UP_LEFT
                left -> TaTaConRegion.HEAD_DOWN_LEFT
                up -> TaTaConRegion.HEAD_UP_RIGHT
                else -> TaTaConRegion.HEAD_DOWN_RIGHT
            }
        }

        val rim = rimHitBounds
        if (rim != null && rim.contains(x, y)) {
            return if (x < b.exactCenterX()) TaTaConRegion.RIM_LEFT else TaTaConRegion.RIM_RIGHT
        }

        return null
    }

    fun setPressedState(region: TaTaConRegion, isPressed: Boolean) {
        pressed[region.ordinal] = isPressed
    }

    fun getPressedState(region: TaTaConRegion): Boolean = pressed[region.ordinal]

    /** The 3DS button id sent for this region. */
    fun getLegacyId(region: TaTaConRegion): Int = buttonIds[region.ordinal]

    fun getTrackId(region: TaTaConRegion): Int = trackIds[region.ordinal]

    fun setTrackId(region: TaTaConRegion, id: Int) {
        trackIds[region.ordinal] = id
    }

    companion object {
        /**
         * Position of the drum artwork inside tatacon.png (normalized to the bitmap size).
         * The artwork of the 800x512 image spans x 290..511 and y 160..383.
         */
        private const val ART_LEFT = 0.3625f
        private const val ART_TOP = 0.3125f
        private const val ART_RIGHT = 0.6388f
        private const val ART_BOTTOM = 0.7480f

        /** Geometry of the drum head inside the cropped artwork (normalized). */
        private const val HEAD_CENTER_X = 0.5000f
        private const val HEAD_CENTER_Y = 0.3810f
        private const val HEAD_RADIUS_X = 0.4620f
        private const val HEAD_RADIUS_Y = 0.3720f

        private const val GLOW_SCALE = 0.98f
        private const val GLOW_COLOR = 0xFFFBAD96.toInt()
        private const val GLOW_COLOR_TRANSPARENT = 0x00FBAD96
        private const val GLOW_ALPHA_FACTOR = 1.45f

        /** How far the drum sinks while it is pressed, relative to its height. */
        private const val SINK_RATIO = 0.01f

        /** Crops the drum artwork out of the complete tatacon.png bitmap. */
        fun cropArtwork(bitmap: Bitmap): Bitmap {
            val left = (ART_LEFT * bitmap.width).toInt()
            val top = (ART_TOP * bitmap.height).toInt()
            val right = (ART_RIGHT * bitmap.width).toInt().coerceAtMost(bitmap.width)
            val bottom = (ART_BOTTOM * bitmap.height).toInt().coerceAtMost(bitmap.height)
            if (right <= left || bottom <= top) return bitmap
            return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }
    }
}