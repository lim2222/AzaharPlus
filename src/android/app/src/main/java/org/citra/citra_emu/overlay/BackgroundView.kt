// SPDX-License-Identifier: GPL-2.0-or-later

package org.citra.citra_emu.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.citra.citra_emu.utils.Log
import kotlin.math.max

/**
 * Draws a user supplied background image above the emulation surface, with holes cut out where
 * the 3DS screens are. The renderer's surface is opaque, so instead of showing the image through
 * it, the image is drawn everywhere except on the screen rectangles.
 *
 * The image covers the whole view at the default scale. The user transform can zoom it in, zoom it
 * out below that size (the parts of the window that the image does not reach are simply left to the
 * game) and pan it, but never far enough to push it off screen, where it would look like the
 * background was removed.
 *
 * The view does not handle touches; the input overlay above it does.
 */
class BackgroundView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private val holes = ArrayList<Rect>()
    private val drawMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    // Last state logged from onDraw, so a redraw every frame does not flood the log.
    private var lastLoggedState = ""

    /** View that also paints this background (the input overlay); it has to be invalidated too. */
    var mirror: View? = null

    // Transform chosen by the user, applied on top of the "cover the view" base fit.
    // 1.0 / 0 offsets = the image covers the view exactly.
    var translateX = 0f
        private set
    var translateY = 0f
        private set
    var scaleX2 = 1f
        private set
    var scaleY2 = 1f
        private set

    val hasBitmap: Boolean
        get() = bitmap != null

    val bitmapWidth: Int
        get() = bitmap?.width ?: 0

    val bitmapHeight: Int
        get() = bitmap?.height ?: 0

    fun setBitmap(newBitmap: Bitmap?) {
        bitmap = newBitmap
        clampTransform()
        refresh()
    }

    /** The screen rectangles (in view pixels) that must stay free of the background. */
    fun setHoles(rects: List<Rect>) {
        holes.clear()
        rects.forEach { holes.add(Rect(it)) }
        refresh()
    }

    fun setTransform(tx: Float, ty: Float, sx: Float, sy: Float) {
        translateX = tx
        translateY = ty
        scaleX2 = sx
        scaleY2 = sy
        clampTransform()
        refresh()
    }

    /** Redraws this view and any view painting the same background. */
    private fun refresh() {
        invalidate()
        mirror?.invalidate()
    }

    fun moveBy(dx: Float, dy: Float) = setTransform(translateX + dx, translateY + dy, scaleX2, scaleY2)

    /** [factorX] / [factorY] are multipliers, 1.0 = unchanged. */
    fun scaleBy(factorX: Float, factorY: Float) =
        setTransform(translateX, translateY, scaleX2 * factorX, scaleY2 * factorY)

    /** Back to the default "cover the view" placement. */
    fun resetTransform() {
        setTransform(0f, 0f, 1f, 1f)
    }

    /**
     * Keeps the image reachable: the scale stays in [MIN_SCALE, MAX_SCALE], and the offsets stay in
     * the part of the image that hangs over the view (or in the view itself while the image is
     * smaller than it), so the image cannot be dragged out of sight.
     */
    private fun clampTransform() {
        val b = bitmap ?: return
        if (b.width <= 0 || b.height <= 0) return
        // Zooming out is allowed as far as MIN_SCALE, so the image can be made smaller than the
        // window it is drawn in.
        scaleX2 = scaleX2.coerceIn(MIN_SCALE, MAX_SCALE)
        scaleY2 = scaleY2.coerceIn(MIN_SCALE, MAX_SCALE)
        if (width <= 0 || height <= 0) return
        val cover = max(width / b.width.toFloat(), height / b.height.toFloat())
        // While the image is bigger than the view it has to keep covering it, so it can only move
        // by the part that hangs over the edge. A smaller image is placed freely inside the view
        // instead.
        val maxTx = movableRange(b.width * cover * scaleX2, width.toFloat())
        val maxTy = movableRange(b.height * cover * scaleY2, height.toFloat())
        translateX = translateX.coerceIn(-maxTx, maxTx)
        translateY = translateY.coerceIn(-maxTy, maxTy)
    }

    /** How far the image may be moved on one axis. */
    private fun movableRange(drawnSize: Float, viewSize: Float): Float =
        if (drawnSize >= viewSize) {
            (drawnSize - viewSize) / 2f
        } else {
            (viewSize - drawnSize) / 2f
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTransform()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Show or drop the copy painted by the input overlay as well.
        if (changedView === this) mirror?.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
    }

    /**
     * Paints the image with the screen rectangles cut out.
     *
     * The [InputOverlay] calls this too: it draws on the same window canvas but is always
     * composited above the emulation surface, so the image still shows on devices where this view
     * ends up hidden behind the surface. Both calls produce the same pixels, so showing it from
     * both places is harmless.
     *
     * The holes are cut with plain [Canvas.clipRect] calls instead of clipOutRect(): the visible
     * part is split into the rectangles around the screens and the bitmap is drawn once per
     * rectangle. clipOutRect() produced an empty clip on some devices and made the image vanish.
     */
    fun drawBackground(canvas: Canvas) {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        if (visibility != View.VISIBLE || b.isRecycled) return

        // Base fit: scale the image so it covers the whole view, centered.
        val cover = max(width / b.width.toFloat(), height / b.height.toFloat())
        drawMatrix.reset()
        drawMatrix.postScale(cover, cover)
        drawMatrix.postTranslate((width - b.width * cover) / 2f, (height - b.height * cover) / 2f)
        // User transform, scaling around the center of the view.
        drawMatrix.postScale(scaleX2, scaleY2, width / 2f, height / 2f)
        drawMatrix.postTranslate(translateX, translateY)

        val drawn = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        drawMatrix.mapRect(drawn)
        val areas = visibleAreas()
        val state = "bmp=" + b.width + "x" + b.height + " view=" + width + "x" + height +
            " vis=" + visibility + " t=" + translateX + "," + translateY +
            " s=" + scaleX2 + "," + scaleY2 + " dst=" + drawn + " areas=" + areas.size +
            " holes=" + holes.joinToString(";") { r -> r.flattenToString() }
        if (state != lastLoggedState) {
            lastLoggedState = state
            Log.info("[BG] draw " + state)
        }

        val saved = canvas.save()
        for (area in areas) {
            if (area.isEmpty) continue
            val areaSaved = canvas.save()
            canvas.clipRect(area)
            canvas.drawBitmap(b, drawMatrix, paint)
            canvas.restoreToCount(areaSaved)
        }
        canvas.restoreToCount(saved)
    }

    /**
     * The parts of the view that are not covered by a screen: full width bands above, between and
     * below the screens, plus the strips left and right of each screen.
     */
    private fun visibleAreas(): List<RectF> {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val areas = ArrayList<RectF>()

        // Y coordinates where the set of blocked columns can change.
        val edges = sortedSetOf(0f, viewHeight)
        for (hole in holes) {
            edges.add(hole.top.toFloat().coerceIn(0f, viewHeight))
            edges.add(hole.bottom.toFloat().coerceIn(0f, viewHeight))
        }
        val sortedEdges = edges.toList()
        for (i in 0 until sortedEdges.size - 1) {
            val top = sortedEdges[i]
            val bottom = sortedEdges[i + 1]
            if (bottom <= top) continue

            val blocked = ArrayList<Pair<Float, Float>>()
            for (hole in holes) {
                if (hole.bottom > top && hole.top < bottom) {
                    blocked.add(
                        hole.left.toFloat().coerceIn(0f, viewWidth) to
                            hole.right.toFloat().coerceIn(0f, viewWidth)
                    )
                }
            }
            blocked.sortBy { it.first }

            var x = 0f
            for ((left, right) in blocked) {
                if (left > x) areas.add(RectF(x, top, left, bottom))
                x = max(x, right)
            }
            if (x < viewWidth) areas.add(RectF(x, top, viewWidth, bottom))
        }
        return areas
    }

    companion object {
        private const val MIN_SCALE = 0.1f
        private const val MAX_SCALE = 10f
    }
}
