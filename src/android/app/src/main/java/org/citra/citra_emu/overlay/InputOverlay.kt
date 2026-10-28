// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.overlay

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.View.OnTouchListener
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import java.lang.NullPointerException
import kotlin.math.min
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.features.hotkeys.Hotkey
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.utils.ComboHelper
import org.citra.citra_emu.utils.EmulationMenuSettings
import org.citra.citra_emu.utils.TurboHelper

/**
 * Draws the interactive input overlay on top of the
 * [SurfaceView] that is rendering emulation.
 *
 * @param context The current [Context].
 * @param attrs   [AttributeSet] for parsing XML attributes.
 */
class InputOverlay(context: Context?, attrs: AttributeSet?) :
    SurfaceView(context, attrs),
    OnTouchListener {
    /** What a drag does while the layout is being edited. */
    enum class EditAction { MOVE, RESIZE, OPACITY }

    var editAction: EditAction = EditAction.MOVE
        private set

    fun setEditAction(action: EditAction) {
        editAction = action
    }

    /**
     * Edit modes that do not work on the controls: they move/resize the 3DS screens (custom
     * layout) or the background image. The drags are reported to [screenEditListener].
     */
    enum class ScreenEditMode { NONE, SCREEN_MOVE, SCREEN_SIZE, BG_MOVE, BG_SIZE }

    interface ScreenEditListener {
        /** A drag started at ([x], [y]), in view pixels. */
        fun onEditDragStart(mode: ScreenEditMode, x: Float, y: Float)

        /** The finger moved by ([dx], [dy]) pixels since the last call. */
        fun onEditDrag(mode: ScreenEditMode, dx: Float, dy: Float)

        fun onEditDragEnd(mode: ScreenEditMode)
    }

    var screenEditMode: ScreenEditMode = ScreenEditMode.NONE
        private set
    var screenEditListener: ScreenEditListener? = null

    private var screenEditActive = false
    private var screenEditLastX = 0f
    private var screenEditLastY = 0f

    // Outlines of the 3DS screens drawn while they are being moved or resized.
    private var screenOutlines: List<Rect> = emptyList()
    private var selectedOutline = -1
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val outlineLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
    }

    fun setScreenEditMode(mode: ScreenEditMode) {
        screenEditMode = mode
        screenEditActive = false
        if (mode != ScreenEditMode.SCREEN_MOVE && mode != ScreenEditMode.SCREEN_SIZE) {
            screenOutlines = emptyList()
            selectedOutline = -1
        }
        invalidate()
    }

    /** Shows the scale value of the control being resized. */
    private fun drawResizeLabel(canvas: Canvas) {
        val value: Float
        val bounds: Rect
        when {
            buttonBeingConfigured != null -> {
                value = buttonBeingConfigured!!.resizeValue
                bounds = buttonBeingConfigured!!.bounds
            }
            dpadBeingConfigured != null -> {
                value = dpadBeingConfigured!!.resizeValue
                bounds = dpadBeingConfigured!!.bounds
            }
            joystickBeingConfigured != null -> {
                value = joystickBeingConfigured!!.resizeValue
                bounds = joystickBeingConfigured!!.bounds
            }
            else -> return
        }
        outlineLabelPaint.color = Color.WHITE
        canvas.drawText(
            "Size ${Math.round(value)}",
            bounds.left.toFloat(),
            (bounds.top - 16f).coerceAtLeast(40f),
            outlineLabelPaint
        )
    }

    /** [rects]: top screen first, bottom screen second. [selected] is -1 when none is picked. */
    fun setScreenOutlines(rects: List<Rect>, selected: Int) {
        screenOutlines = rects.map { Rect(it) }
        selectedOutline = selected
        invalidate()
    }

    private fun drawScreenOutlines(canvas: Canvas) {
        screenOutlines.forEachIndexed { index, rect ->
            val selected = index == selectedOutline
            val color = if (selected) Color.YELLOW else Color.WHITE
            outlinePaint.color = color
            outlineLabelPaint.color = color
            canvas.drawRect(rect, outlinePaint)
            canvas.drawText(
                if (index == 0) "Top" else "Bottom",
                rect.left + 12f,
                rect.top + 44f,
                outlineLabelPaint
            )
        }
    }

    /** Touch handling of the screen / background edit modes: one finger, reported as drags. */
    private fun handleScreenEditTouch(event: MotionEvent): Boolean {
        val listener = screenEditListener ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                screenEditActive = true
                screenEditLastX = event.x
                screenEditLastY = event.y
                listener.onEditDragStart(screenEditMode, event.x, event.y)
            }

            MotionEvent.ACTION_MOVE -> if (screenEditActive) {
                val dx = event.x - screenEditLastX
                val dy = event.y - screenEditLastY
                screenEditLastX = event.x
                screenEditLastY = event.y
                listener.onEditDrag(screenEditMode, dx, dy)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> if (screenEditActive) {
                screenEditActive = false
                listener.onEditDragEnd(screenEditMode)
            }
        }
        return true
    }

    private val overlayButtons: MutableSet<InputOverlayDrawableButton> = HashSet()
    private val overlayDpads: MutableSet<InputOverlayDrawableDpad> = HashSet()
    private val overlayJoysticks: MutableSet<InputOverlayDrawableJoystick> = HashSet()
    private var isInEditMode = false
    private var buttonBeingConfigured: InputOverlayDrawableButton? = null
    private var dpadBeingConfigured: InputOverlayDrawableDpad? = null
    private var joystickBeingConfigured: InputOverlayDrawableJoystick? = null

    // Taiko drum (TaTaCon) overlay, ported from the Dolphin fork.
    private var overlayTaTaCon: InputOverlayDrawableTaTaCon? = null
    private var tataconBeingConfigured: InputOverlayDrawableTaTaCon? = null

    // Pointers that landed on the drum but could not be tracked (region already held by
    // another finger). They are swallowed so they never reach the 3DS touchscreen.
    private val tataconSwallowedPointers: MutableSet<Int> = HashSet()

    private val vibrator: Vibrator? by lazy {
        val ctx = context ?: return@lazy null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager?)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        }
    }
    private val settingsViewModel = NativeLibrary.sEmulationActivity.get()!!.settingsViewModel

    // Stores the ID of the pointer that interacted with the 3DS touchscreen.
    private var touchscreenPointerId = -1

    init {
        if (!preferences.getBoolean("OverlayInit", false)) {
            defaultOverlay()
        }

        // Reset 3ds touchscreen pointer ID
        touchscreenPointerId = -1

        // Load the controls.
        refreshControls()

        // Set the on touch listener.
        setOnTouchListener(this)

        // Force draw
        setWillNotDraw(false)

        // Request focus for the overlay so it has priority on presses.
        requestFocus()
    }

    /**
     * Background image painted under the controls. The emulation surface is opaque and covers the
     * window behind it, so a plain view below it is never visible; this overlay is drawn above the
     * surface, which is why the wallpaper has to be painted from here.
     */
    var backgroundRenderer: BackgroundView? = null

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        backgroundRenderer?.drawBackground(canvas)
        overlayButtons.forEach { it.draw(canvas) }
        overlayDpads.forEach { it.draw(canvas) }
        overlayJoysticks.forEach { it.draw(canvas) }
        overlayTaTaCon?.draw(canvas)
        if (isInEditMode && editAction == EditAction.RESIZE && screenEditMode == ScreenEditMode.NONE) {
            drawResizeLabel(canvas)
        }
        if (isInEditMode &&
            (screenEditMode == ScreenEditMode.SCREEN_MOVE ||
                screenEditMode == ScreenEditMode.SCREEN_SIZE)
        ) {
            drawScreenOutlines(canvas)
        }
    }

    private fun swapScreen() {
        val isEnabled = !EmulationMenuSettings.swapScreens
        EmulationMenuSettings.swapScreens = isEnabled
        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display.rotation
        } else {
            @Suppress("DEPRECATION")
            (context as Activity).windowManager.defaultDisplay.rotation
        }
        NativeLibrary.swapScreens(
            isEnabled,
            displayRotation
        )
    }

    fun hapticFeedback(type: Int) {
        if (EmulationMenuSettings.hapticFeedback) {
            performHapticFeedback(type)
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (isInEditMode) {
            return onTouchWhileEditing(event)
        }

        val motionEvent = event.action and MotionEvent.ACTION_MASK
        val isActionDown =
            motionEvent == MotionEvent.ACTION_DOWN || motionEvent == MotionEvent.ACTION_POINTER_DOWN
        val isActionMove = motionEvent == MotionEvent.ACTION_MOVE
        val isActionUp =
            motionEvent == MotionEvent.ACTION_UP || motionEvent == MotionEvent.ACTION_POINTER_UP

        // The drum is handled first. Pointers that belong to the drum must not reach the
        // buttons or the 3DS touchscreen below.
        val tataconPointers: Set<Int> =
            overlayTaTaCon?.let { handleTaTaConTouch(it, event) } ?: emptySet()

        val pointerList = (0 until event.pointerCount).toMutableList()
        // Move the pointer that triggered the most recent event to the front
        // of the list so that it is processed first
        val currentActionPointer = event.actionIndex
        pointerList.remove(pointerList.indexOf(currentActionPointer))
        pointerList.add(0, currentActionPointer)

        // Set up a loop for if we need to check touches other than the most recent one
        // (Only happens if we're dragging the touch)
        for (pointerIndex in pointerList) {
            val pointerId = event.getPointerId(pointerIndex)

            if (pointerId in tataconPointers) {
                if (isActionMove) continue else break
            }

            var xPosition = event.getX(pointerIndex).toInt()
            var yPosition = event.getY(pointerIndex).toInt()

            if (BooleanSetting.EXPAND_TO_CUTOUT_AREA.boolean) {
                val cutout = ViewCompat.getRootWindowInsets(this)?.displayCutout
                val marginsX = (cutout?.safeInsetLeft?.plus(cutout.safeInsetRight)) ?: 0
                val marginsY = (cutout?.safeInsetTop?.plus(cutout.safeInsetBottom)) ?: 0
                xPosition += marginsX
                yPosition += marginsY
            }

            var hasActiveButtons = false
            for (button in overlayButtons) {
                if (button.trackId == pointerId) {
                    hasActiveButtons = true
                    break
                }
            }

            var hasActiveDpad = false
            if (!hasActiveButtons) {
                for (dpad in overlayDpads) {
                    if (dpad.trackId == pointerId) {
                        hasActiveDpad = true
                        break
                    }
                }
            }

            var hasActiveJoystick = false
            if (!hasActiveButtons && !hasActiveDpad) {
                for (joystick in overlayJoysticks) {
                    if (joystick.trackId == pointerId) {
                        hasActiveJoystick = true
                        break
                    }
                }
            }

            val hasActiveOverlay = hasActiveButtons || hasActiveDpad || hasActiveJoystick

            if (globalPreferences.getBoolean("isTouchEnabled", true) && !hasActiveOverlay) {
                if (isActionMove) {
                    NativeLibrary.onTouchMoved(xPosition.toFloat(), yPosition.toFloat())
                    continue
                } else if (isActionUp) {
                    NativeLibrary.onTouchEvent(0f, 0f, false)
                    break // Up and down actions shouldn't loop
                }
            }

            var anyOverlayStateChanged = false
            var shouldUpdateView = false
            if (!hasActiveDpad && !hasActiveJoystick) {
                for (button in overlayButtons) {
                    val stateChanged = button.updateStatus(
                        event,
                        pointerIndex,
                        hasActiveButtons,
                        this
                    )
                    if (!stateChanged) {
                        continue
                    }
                    anyOverlayStateChanged = true

                    // The Taiko hotkey only shows and hides the drum; like the other app hotkeys
                    // it is not sent to the core.
                    if (button.id == NativeLibrary.ButtonType.BUTTON_TAIKO) {
                        if (button.status == NativeLibrary.ButtonState.PRESSED) {
                            toggleTaTaCon()
                        }
                        shouldUpdateView = true
                        continue
                    }

                    if (button.id == NativeLibrary.ButtonType.BUTTON_SWAP &&
                        button.status == NativeLibrary.ButtonState.PRESSED
                    ) {
                        swapScreen()
                    } else if (button.id == NativeLibrary.ButtonType.BUTTON_TURBO &&
                        button.status == NativeLibrary.ButtonState.PRESSED
                    ) {
                        TurboHelper.toggleTurbo(true)
                    } else if (button.id == Hotkey.COMBO_BUTTON.button) {
                        ComboHelper.comboActivate(button.status)
                    }

                    NativeLibrary.onGamePadEvent(
                        NativeLibrary.TOUCHSCREEN_DEVICE,
                        button.id,
                        button.status
                    )

                    shouldUpdateView = true
                }
            }

            if (!hasActiveButtons && !hasActiveJoystick) {
                for (dpad in overlayDpads) {
                    val stateChanged = dpad.updateStatus(
                        event,
                        pointerIndex,
                        hasActiveDpad,
                        EmulationMenuSettings.dpadSlide,
                        this
                    )
                    if (!stateChanged) {
                        continue
                    }
                    anyOverlayStateChanged = true

                    NativeLibrary.onGamePadEvent(
                        NativeLibrary.TOUCHSCREEN_DEVICE,
                        dpad.upId,
                        dpad.upStatus
                    )
                    NativeLibrary.onGamePadEvent(
                        NativeLibrary.TOUCHSCREEN_DEVICE,
                        dpad.downId,
                        dpad.downStatus
                    )
                    NativeLibrary.onGamePadEvent(
                        NativeLibrary.TOUCHSCREEN_DEVICE,
                        dpad.leftId,
                        dpad.leftStatus
                    )
                    NativeLibrary.onGamePadEvent(
                        NativeLibrary.TOUCHSCREEN_DEVICE,
                        dpad.rightId,
                        dpad.rightStatus
                    )

                    shouldUpdateView = true
                }
            }

            if (!hasActiveDpad && !hasActiveButtons) {
                for (joystick in overlayJoysticks) {
                    val stateChanged = joystick.updateStatus(
                        event,
                        pointerIndex,
                        hasActiveJoystick,
                        this
                    )
                    if (!stateChanged) {
                        continue
                    }
                    anyOverlayStateChanged = true

                    val axisID = joystick.joystickId
                    NativeLibrary.onGamePadMoveEvent(
                        NativeLibrary.TOUCHSCREEN_DEVICE,
                        axisID,
                        joystick.xAxis,
                        joystick.yAxis
                    )

                    shouldUpdateView = true
                }
            }

            if (shouldUpdateView) {
                invalidate()
            }

            if (globalPreferences.getBoolean("isTouchEnabled", true) &&
                isActionDown &&
                !anyOverlayStateChanged
            ) {
                // These need to be recalculated because touching the area
                // right in the middle of the dpad (between the "buttons") or
                // tapping a joystick in a certain way both don't cause
                // `anyOverlayStateChanged` to be set to true
                var isDpadPressed = false
                for (dpad in overlayDpads) {
                    if (dpad.trackId == pointerId) {
                        isDpadPressed = true
                        break
                    }
                }
                var isJoystickPressed = false
                for (joystick in overlayJoysticks) {
                    if (joystick.trackId == pointerId) {
                        isJoystickPressed = true
                        break
                    }
                }

                if (!isDpadPressed && !isJoystickPressed) {
                    NativeLibrary.onTouchEvent(xPosition.toFloat(), yPosition.toFloat(), true)
                }
            }

            // We should only loop here if touch is being dragged
            if (!isActionMove) {
                break
            }
        }
        return true
    }

    fun onTouchWhileEditing(event: MotionEvent): Boolean {
        if (screenEditMode != ScreenEditMode.NONE) {
            return handleScreenEditTouch(event)
        }
        val pointerIndex = event.actionIndex
        val fingerPositionX = event.getX(pointerIndex).toInt()
        val fingerPositionY = event.getY(pointerIndex).toInt()
        val orientation =
            if (resources.configuration.orientation ==
                Configuration.ORIENTATION_PORTRAIT
            ) {
                "-Portrait"
            } else {
                ""
            }

        // Maybe combine Button and Joystick as subclasses of the same parent?
        // Or maybe create an interface like IMoveableHUDControl?
        overlayButtons.forEach {
            // Determine the button state to apply based on the MotionEvent action flag.
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                    // If no button is being moved now, remember the currently touched button to move.
                    if (buttonBeingConfigured == null &&
                        it.bounds.contains(fingerPositionX, fingerPositionY)
                    ) {
                        buttonBeingConfigured = it
                        if (editAction == EditAction.RESIZE) {
                            it.beginResize(
                                event.getX(pointerIndex),
                                width,
                                getIntPerOrient("controlScale-${it.id}", orientation, 50),
                            )
                        }
                        buttonBeingConfigured!!.onConfigureTouch(event, editAction)
                    }

                MotionEvent.ACTION_MOVE -> if (buttonBeingConfigured != null) {
                    buttonBeingConfigured!!.onConfigureTouch(event, editAction)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (buttonBeingConfigured ==
                    it
                ) {
                    val edited = buttonBeingConfigured!!
                    when (editAction) {
                        EditAction.MOVE -> saveControlPosition(
                            edited.id, edited.bounds.left, edited.bounds.top, orientation
                        )

                        EditAction.RESIZE -> {
                            edited.finishResize()
                            saveScaleFromRatio("controlScale-${edited.id}$orientation", edited.resizeRatio)
                            saveControlPosition(
                                edited.id, edited.bounds.left, edited.bounds.top, orientation
                            )
                            // Rebuild with the new size; deferred because we are iterating
                            // over the buttons right now.
                            post { refreshControls() }
                        }

                        EditAction.OPACITY ->
                            saveOpacity("controlOpacity-${edited.id}$orientation", edited.opacity)
                    }
                    buttonBeingConfigured = null
                }
            }
        }
        overlayDpads.forEach {
            // Determine the button state to apply based on the MotionEvent action flag.
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                    // If no button is being moved now, remember the currently touched button to move.
                    if (buttonBeingConfigured == null &&
                        dpadBeingConfigured == null &&
                        it.bounds.contains(fingerPositionX, fingerPositionY)
                    ) {
                        dpadBeingConfigured = it
                        if (editAction == EditAction.RESIZE) {
                            it.beginResize(
                                event.getX(pointerIndex),
                                width,
                                getIntPerOrient(
                                    "controlScale-${NativeLibrary.ButtonType.DPAD}",
                                    orientation,
                                    50
                                ),
                            )
                        }
                        dpadBeingConfigured!!.onConfigureTouch(event, editAction)
                    }

                MotionEvent.ACTION_MOVE -> if (dpadBeingConfigured != null) {
                    dpadBeingConfigured!!.onConfigureTouch(event, editAction)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (dpadBeingConfigured == it) {
                    val edited = dpadBeingConfigured!!
                    val scaleKey = "controlScale-" + NativeLibrary.ButtonType.DPAD + orientation
                    val opacityKey = "controlOpacity-" + NativeLibrary.ButtonType.DPAD + orientation
                    when (editAction) {
                        EditAction.MOVE -> saveControlPosition(
                            edited.upId, edited.bounds.left, edited.bounds.top, orientation
                        )

                        EditAction.RESIZE -> {
                            edited.finishResize()
                            saveScaleFromRatio(scaleKey, edited.resizeRatio)
                            saveControlPosition(
                                edited.upId, edited.bounds.left, edited.bounds.top, orientation
                            )
                            post { refreshControls() }
                        }

                        EditAction.OPACITY -> saveOpacity(opacityKey, edited.opacity)
                    }
                    dpadBeingConfigured = null
                }
            }
        }
        overlayJoysticks.forEach {
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> if (joystickBeingConfigured == null &&
                    it.bounds.contains(fingerPositionX, fingerPositionY)
                ) {
                    joystickBeingConfigured = it
                    if (editAction == EditAction.RESIZE) {
                        it.beginResize(
                            event.getX(pointerIndex),
                            width,
                            getIntPerOrient("controlScale-${it.joystickId}", orientation, 50),
                        )
                    }
                    joystickBeingConfigured!!.onConfigureTouch(event, editAction)
                }

                MotionEvent.ACTION_MOVE -> if (joystickBeingConfigured != null) {
                    joystickBeingConfigured!!.onConfigureTouch(event, editAction)
                    invalidate()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (joystickBeingConfigured != null) {
                    val edited = joystickBeingConfigured!!
                    val scaleKey = "controlScale-${edited.joystickId}$orientation"
                    val opacityKey = "controlOpacity-${edited.joystickId}$orientation"
                    when (editAction) {
                        EditAction.MOVE -> saveControlPosition(
                            edited.joystickId, edited.bounds.left, edited.bounds.top, orientation
                        )

                        EditAction.RESIZE -> {
                            edited.finishResize()
                            saveScaleFromRatio(scaleKey, edited.resizeRatio)
                            saveControlPosition(
                                edited.joystickId, edited.bounds.left, edited.bounds.top, orientation
                            )
                            post { refreshControls() }
                        }

                        EditAction.OPACITY -> saveOpacity(opacityKey, edited.opacity)
                    }
                    joystickBeingConfigured = null
                }
            }
        }

        // TaTaCon drum: move, resize or change its opacity.
        overlayTaTaCon?.let { tatacon ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> if (tataconBeingConfigured == null &&
                    buttonBeingConfigured == null &&
                    dpadBeingConfigured == null &&
                    joystickBeingConfigured == null &&
                    tatacon.isOnArtwork(fingerPositionX, fingerPositionY)
                ) {
                    tataconBeingConfigured = tatacon
                    tatacon.onConfigureTouch(event, editAction)
                }

                MotionEvent.ACTION_MOVE -> if (tataconBeingConfigured != null) {
                    tatacon.onConfigureTouch(event, editAction)
                    invalidate()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (tataconBeingConfigured != null) {
                    when (editAction) {
                        EditAction.MOVE ->
                            saveTaTaConPosition(
                                orientation,
                                tatacon.bounds.centerX(),
                                tatacon.bounds.centerY()
                            )

                        EditAction.RESIZE -> {
                            // Width goes into the usual scale value, the height is kept as an
                            // aspect factor relative to the width.
                            val ratioX = tatacon.resizeRatio
                            val ratioY = tatacon.resizeRatioY
                            saveScaleFromRatio("controlScale-tatacon$orientation", ratioX)
                            saveAspectFromRatio("controlAspect-tatacon", orientation, ratioY / ratioX)
                            saveTaTaConPosition(
                                orientation,
                                tatacon.bounds.centerX(),
                                tatacon.bounds.centerY()
                            )
                            post { refreshControls() }
                        }

                        EditAction.OPACITY ->
                            saveOpacity("controlOpacity-tatacon$orientation", tatacon.customOpacity)
                    }
                    tataconBeingConfigured = null
                }
            }
        }
        return true
    }

    /**
     * Stores a new per-control scale so that the control ends up [ratio] times as big as it is
     * now. The scale slider value v gives a size factor of (v + 50) / 100.
     */
    private fun saveScaleFromRatio(key: String, ratio: Float) {
        // [key] may carry the "-Portrait" suffix; without a value of its own it starts from the
        // shared one, like the code that builds the controls does.
        val old = if (preferences.contains(key)) {
            preferences.getInt(key, 50)
        } else {
            preferences.getInt(key.removeSuffix("-Portrait"), 50)
        }
        val new = Math.round((old + 50) * ratio - 50).coerceIn(0, 150)
        preferences.edit().putInt(key, new).apply()
    }

    /** Multiplies the stored height/width factor of a control by [multiplier]. */
    private fun saveAspectFromRatio(key: String, orientation: String, multiplier: Float) {
        val old = getFloatPerOrient(key, orientation, 1.0f)
        preferences.edit()
            .putFloat(key + orientation, (old * multiplier).coerceIn(0.25f, 4.0f))
            .apply()
    }

    /** Stores a per-control opacity (0..100, like the global opacity slider). */
    private fun saveOpacity(key: String, opacity255: Int) {
        preferences.edit().putInt(key, opacity255 * 100 / 255).apply()
    }

    // ---------------------------------------------------------------------------------
    // TaTaCon (Taiko drum)
    // ---------------------------------------------------------------------------------

    private fun pressTaTaCon(
        t: InputOverlayDrawableTaTaCon,
        region: TaTaConRegion,
        pointerId: Int
    ): Boolean {
        if (t.getTrackId(region) != -1) return false
        t.setTrackId(region, pointerId)
        t.setPressedState(region, true)
        NativeLibrary.onGamePadEvent(
            NativeLibrary.TOUCHSCREEN_DEVICE,
            t.getLegacyId(region),
            NativeLibrary.ButtonState.PRESSED
        )
        triggerTaTaConHapticFeedback()
        return true
    }

    private fun releaseTaTaCon(t: InputOverlayDrawableTaTaCon, region: TaTaConRegion) {
        t.setPressedState(region, false)
        NativeLibrary.onGamePadEvent(
            NativeLibrary.TOUCHSCREEN_DEVICE,
            t.getLegacyId(region),
            NativeLibrary.ButtonState.RELEASED
        )
        t.setTrackId(region, -1)
    }

    private fun releaseAllTaTaCon(t: InputOverlayDrawableTaTaCon) {
        for (region in TaTaConRegion.values()) {
            if (t.getTrackId(region) != -1 || t.getPressedState(region)) {
                releaseTaTaCon(t, region)
            }
        }
        tataconSwallowedPointers.clear()
    }

    /**
     * The rim covers the whole screen, so it must not fire when the touch is on top of another
     * overlay control (the drum head is not guarded, like in the Dolphin version).
     */
    private fun isTaTaConBlocked(region: TaTaConRegion, x: Int, y: Int): Boolean {
        if (region != TaTaConRegion.RIM_LEFT && region != TaTaConRegion.RIM_RIGHT) return false
        for (button in overlayButtons) {
            if (button.bounds.contains(x, y)) return true
        }
        for (dpad in overlayDpads) {
            if (dpad.bounds.contains(x, y)) return true
        }
        for (joystick in overlayJoysticks) {
            if (joystick.bounds.contains(x, y)) return true
        }
        return false
    }

    /**
     * Handles one MotionEvent for the drum.
     *
     * @return IDs of the pointers owned by the drum for this event. The caller skips them.
     */
    private fun handleTaTaConTouch(t: InputOverlayDrawableTaTaCon, event: MotionEvent): Set<Int> {
        val consumed = HashSet<Int>(tataconSwallowedPointers)
        for (region in TaTaConRegion.values()) {
            val id = t.getTrackId(region)
            if (id != -1) consumed.add(id)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index).toInt()
                val y = event.getY(index).toInt()
                val region = t.getRegionAt(x, y)
                if (region != null && !isTaTaConBlocked(region, x, y)) {
                    val pid = event.getPointerId(index)
                    if (!pressTaTaCon(t, region, pid)) {
                        // Region already held by another finger: swallow this one.
                        tataconSwallowedPointers.add(pid)
                    }
                    consumed.add(pid)
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val current = TaTaConRegion.values().firstOrNull { t.getTrackId(it) == pid }
                        ?: continue
                    val x = event.getX(i).toInt()
                    val y = event.getY(i).toInt()
                    val region = t.getRegionAt(x, y)?.takeIf { !isTaTaConBlocked(it, x, y) }
                    if (region != current) {
                        releaseTaTaCon(t, current)
                        if (region != null) pressTaTaCon(t, region, pid)
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                TaTaConRegion.values().firstOrNull { t.getTrackId(it) == pid }
                    ?.let { releaseTaTaCon(t, it) }
                tataconSwallowedPointers.remove(pid)
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseAllTaTaCon(t)
                consumed.clear()
                invalidate()
            }
        }
        return consumed
    }

    /**
     * Shows or hides the drum while the game is running (used by the Taiko hotkey button).
     * Only the drum is rebuilt; the other controls are left alone, so this is safe to call
     * from inside onTouch. The state is saved so the toggle dialog stays in sync.
     */
    private fun toggleTaTaCon() {
        val enable = overlayTaTaCon == null
        preferences.edit().putBoolean("buttonToggle17", enable).apply()
        if (enable) {
            val orientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    "-Portrait"
                } else {
                    ""
                }
            createTaTaCon(orientation)
        } else {
            overlayTaTaCon?.let { releaseAllTaTaCon(it) }
            overlayTaTaCon = null
        }
        hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    private fun createTaTaCon(orientation: String) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val minDimension = min(screenWidth, screenHeight)

        // Same global size / opacity sliders as the other overlay controls, plus the
        // per-control "controlScale-tatacon" slider.
        val sizeFactor = (getIntPerOrient("controlScale", orientation, 50) + 50) / 100f *
            (getIntPerOrient("controlScale-tatacon", orientation, 50) + 50) / 100f
        val opacity = getOpacityPerOrient("tatacon", orientation)

        val artwork = InputOverlayDrawableTaTaCon.cropArtwork(
            ButtonPack.load(context, R.drawable.tatacon)
                ?: BitmapFactory.decodeResource(resources, R.drawable.tatacon)
        )
        val targetWidth = (minDimension * TATACON_SIZE_RATIO * sizeFactor).toInt()
        // Height relative to the natural proportions, changed with Resize in the layout editor.
        val aspect = getFloatPerOrient("controlAspect-tatacon", orientation, 1.0f)
        val targetHeight = (targetWidth.toFloat() * artwork.height / artwork.width * aspect)
            .toInt()
            .coerceAtLeast(1)
        val bitmap = Bitmap.createScaledBitmap(artwork, targetWidth, targetHeight, true)

        val tatacon = InputOverlayDrawableTaTaCon(
            resources,
            bitmap,
            TATACON_BUTTON_HEAD_UP_LEFT,
            TATACON_BUTTON_HEAD_DOWN_LEFT,
            TATACON_BUTTON_HEAD_UP_RIGHT,
            TATACON_BUTTON_HEAD_DOWN_RIGHT,
            TATACON_BUTTON_RIM_LEFT,
            TATACON_BUTTON_RIM_RIGHT
        )

        // Default: centered horizontally, drum head in the lower part of the screen.
        val defaultX = (screenWidth - targetWidth) / 2
        val defaultY = (screenHeight * TATACON_HEAD_Y_RATIO -
            targetHeight * TATACON_HEAD_OFFSET_RATIO).toInt()
        val defaultCenterX = (defaultX + targetWidth / 2f) / screenWidth
        val defaultCenterY = (defaultY + targetHeight / 2f) / screenHeight
        val centerX = preferences.getFloat("tatacon$orientation-CX", defaultCenterX)
        val centerY = preferences.getFloat("tatacon$orientation-CY", defaultCenterY)

        val drawableX = (centerX * screenWidth - targetWidth / 2f).toInt()
        val drawableY = (centerY * screenHeight - targetHeight / 2f).toInt()
        tatacon.setPosition(drawableX, drawableY)
        tatacon.setBounds(drawableX, drawableY, drawableX + targetWidth, drawableY + targetHeight)
        tatacon.setCustomOpacity(opacity)

        // The rim is accepted over the whole screen. This also swallows touches that would
        // otherwise reach the 3DS touchscreen; shrink this Rect if you want to keep part of
        // the touchscreen usable while the drum is shown.
        tatacon.setRimHitBounds(Rect(0, 0, screenWidth, screenHeight))
        overlayTaTaCon = tatacon
    }

    private fun saveTaTaConPosition(orientation: String, centerX: Int, centerY: Int) {
        val metrics = resources.displayMetrics
        preferences.edit()
            .putFloat("tatacon$orientation-CX", centerX.toFloat() / metrics.widthPixels)
            .putFloat("tatacon$orientation-CY", centerY.toFloat() / metrics.heightPixels)
            .apply()
    }

    /**
     * Haptic feedback for a drum hit. Hits inside the debounce window are ignored so a second
     * finger does not cancel the running vibration; rapid hits get a softer, longer pulse.
     */
    private fun triggerTaTaConHapticFeedback() {
        if (!EmulationMenuSettings.hapticFeedback) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            v.vibrate(80L)
            return
        }

        val now = SystemClock.uptimeMillis()
        val delta = now - lastTaTaConHapticTime
        if (delta < HAPTIC_TATACON_DEBOUNCE_MS) return
        lastTaTaConHapticTime = now

        val strength = HAPTIC_TATACON_STRENGTH
        val norm = strength / 255f
        val duration: Long
        val amplitude: Int
        if (delta < HAPTIC_TATACON_RAPID_WINDOW_MS) {
            duration = 50L + Math.round(norm * 15f)
            amplitude = maxOf(1, Math.round(strength * 0.33f))
        } else {
            duration = 45L + Math.round(norm * 40f)
            amplitude = strength
        }
        v.vibrate(VibrationEffect.createOneShot(duration, amplitude))
    }

    private fun addOverlayControls(orientation: String) {
        if (getBoolPerOrient("buttonToggle0", orientation, true)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_a,
                    R.drawable.button_a_pressed,
                    NativeLibrary.ButtonType.BUTTON_A,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle1", orientation, true)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_b,
                    R.drawable.button_b_pressed,
                    NativeLibrary.ButtonType.BUTTON_B,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle2", orientation, true)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_x,
                    R.drawable.button_x_pressed,
                    NativeLibrary.ButtonType.BUTTON_X,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle3", orientation, true)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_y,
                    R.drawable.button_y_pressed,
                    NativeLibrary.ButtonType.BUTTON_Y,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle4", orientation, true)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_l,
                    R.drawable.button_l_pressed,
                    NativeLibrary.ButtonType.TRIGGER_L,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle5", orientation, true)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_r,
                    R.drawable.button_r_pressed,
                    NativeLibrary.ButtonType.TRIGGER_R,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle6", orientation, false)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_zl,
                    R.drawable.button_zl_pressed,
                    NativeLibrary.ButtonType.BUTTON_ZL,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle7", orientation, false)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_zr,
                    R.drawable.button_zr_pressed,
                    NativeLibrary.ButtonType.BUTTON_ZR,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle8", orientation, true)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_start,
                    R.drawable.button_start_pressed,
                    NativeLibrary.ButtonType.BUTTON_START,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle9", orientation, true)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_select,
                    R.drawable.button_select_pressed,
                    NativeLibrary.ButtonType.BUTTON_SELECT,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle10", orientation, true)) {
            overlayDpads.add(
                initializeOverlayDpad(
                    context,
                    R.drawable.dpad,
                    R.drawable.dpad_pressed_one_direction,
                    R.drawable.dpad_pressed_two_directions,
                    NativeLibrary.ButtonType.DPAD_UP,
                    NativeLibrary.ButtonType.DPAD_DOWN,
                    NativeLibrary.ButtonType.DPAD_LEFT,
                    NativeLibrary.ButtonType.DPAD_RIGHT,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle11", orientation, true)) {
            overlayJoysticks.add(
                initializeOverlayJoystick(
                    context,
                    R.drawable.stick_main_range,
                    R.drawable.stick_main,
                    R.drawable.stick_main_pressed,
                    NativeLibrary.ButtonType.STICK_LEFT,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle12", orientation, false)) {
            overlayJoysticks.add(
                initializeOverlayJoystick(
                    context,
                    R.drawable.stick_c_range,
                    R.drawable.stick_c,
                    R.drawable.stick_c_pressed,
                    NativeLibrary.ButtonType.STICK_C,
                    orientation
                )
            )
        }
        if (getBoolPerOrient("buttonToggle13", orientation, false)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_home,
                    R.drawable.button_home_pressed,
                    NativeLibrary.ButtonType.BUTTON_HOME,
                    orientation
                )
            )
        }

        if (getBoolPerOrient("buttonToggle14", orientation, false)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_swap,
                    R.drawable.button_swap_pressed,
                    NativeLibrary.ButtonType.BUTTON_SWAP,
                    orientation
                )
            )
        }

        if (getBoolPerOrient("buttonToggle15", orientation, false)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_turbo,
                    R.drawable.button_turbo_pressed,
                    NativeLibrary.ButtonType.BUTTON_TURBO,
                    orientation
                )
            )
        }

        if (getBoolPerOrient("buttonToggle16", orientation, false)) {
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.button_combo,
                    R.drawable.button_combo_pressed,
                    Hotkey.COMBO_BUTTON.button,
                    orientation
                )
            )
        }
        // Taiko drum, shown and hidden by the "Taiko Drum" entry of the toggle dialog.
        if (getBoolPerOrient("buttonToggle17", orientation, false)) {
            createTaTaCon(orientation)
        }

        // The on-screen button that toggles the drum ("Taiko hotkey" entry of the dialog).
        if (getBoolPerOrient("buttonToggle18", orientation, false)) {
            ensureTaikoHotkeyPosition(orientation)
            overlayButtons.add(
                initializeOverlayButton(
                    context,
                    R.drawable.taiko,
                    R.drawable.taiko_pressed,
                    NativeLibrary.ButtonType.BUTTON_TAIKO,
                    orientation
                )
            )
        }
    }

    /**
     * Writes the default position of the Taiko hotkey the first time it is shown. A button that
     * was never placed has no position key and would otherwise start in the top left corner.
     */
    private fun ensureTaikoHotkeyPosition(orientation: String) {
        val xKey = NativeLibrary.ButtonType.BUTTON_TAIKO.toString() + orientation + "-X"
        val yKey = NativeLibrary.ButtonType.BUTTON_TAIKO.toString() + orientation + "-Y"
        if (preferences.contains(xKey) || preferences.contains(yKey)) return
        val xRes = if (orientation.isEmpty()) {
            R.integer.N3DS_BUTTON_TAIKO_X
        } else {
            R.integer.N3DS_BUTTON_TAIKO_PORTRAIT_X
        }
        val yRes = if (orientation.isEmpty()) {
            R.integer.N3DS_BUTTON_TAIKO_Y
        } else {
            R.integer.N3DS_BUTTON_TAIKO_PORTRAIT_Y
        }
        val metrics = resources.displayMetrics
        preferences.edit()
            .putFloat(xKey, resources.getInteger(xRes).toFloat() / 1000 * metrics.widthPixels)
            .putFloat(yKey, resources.getInteger(yRes).toFloat() / 1000 * metrics.heightPixels)
            .apply()
    }

    fun refreshControls() {
        // Release any drum key that is still held before the drum is rebuilt.
        overlayTaTaCon?.let { releaseAllTaTaCon(it) }
        overlayTaTaCon = null
        // Remove all the overlay buttons from the HashSet.
        overlayButtons.clear()
        overlayDpads.clear()
        overlayJoysticks.clear()
        val orientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                "-Portrait"
            } else {
                ""
            }

        // Add all the enabled overlay items back to the HashSet.
        if (EmulationMenuSettings.showOverlay) {
            addOverlayControls(orientation)
        }
        invalidate()
    }

    private fun saveControlPosition(sharedPrefsId: Int, x: Int, y: Int, orientation: String) {
        preferences.edit()
            .putFloat("$sharedPrefsId$orientation-X", x.toFloat())
            .putFloat("$sharedPrefsId$orientation-Y", y.toFloat())
            .apply()
    }

    fun setIsInEditMode(isInEditMode: Boolean) {
        this.isInEditMode = isInEditMode
    }

    private fun defaultOverlay() {
        if (!preferences.getBoolean("OverlayInit", false)) {
            // It's possible that a user has created their overlay before this was added
            // Only change the overlay if the 'A' button is not in the upper corner.
            val aButtonPosition = preferences.getFloat(
                NativeLibrary.ButtonType.BUTTON_A.toString() + "-X",
                0f
            )
            if (aButtonPosition == 0f) {
                defaultOverlayLandscape()
            }

            val aButtonPositionPortrait = preferences.getFloat(
                NativeLibrary.ButtonType.BUTTON_A.toString() + "-Portrait" + "-X",
                0f
            )
            if (aButtonPositionPortrait == 0f) {
                defaultOverlayPortrait()
            }
        }

        preferences.edit()
            .putBoolean("OverlayInit", true)
            .apply()
    }

    /**
     * Resets the whole layout to its defaults: positions, per-control scales and per-control
     * opacities. The global scale and opacity sliders and the shown-controls list are kept.
     */
    fun resetLayoutToDefault() {
        val editor = preferences.edit()
        for (key in preferences.all.keys) {
            if (key.startsWith("controlScale-") ||
                key.startsWith("controlOpacity-") ||
                key.startsWith("controlAspect-")
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
        buttonBeingConfigured = null
        dpadBeingConfigured = null
        joystickBeingConfigured = null
        tataconBeingConfigured = null
        resetButtonPlacement()
    }

    fun resetButtonPlacement() {
        preferences.edit()
            .remove("tatacon-CX").remove("tatacon-CY")
            .remove("tatacon-Portrait-CX").remove("tatacon-Portrait-CY")
            .apply()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            defaultOverlayLandscape()
        } else {
            defaultOverlayPortrait()
        }
        refreshControls()
    }

    private fun defaultOverlayLandscape() {
        // Get screen size
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context as Activity).windowManager.defaultDisplay
        }
        var displayWidth: Float
        var displayHeight: Float
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayMetrics = (context as Activity).windowManager.maximumWindowMetrics.bounds
            displayWidth = displayMetrics.width().toFloat()
            displayHeight = displayMetrics.height().toFloat()
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getMetrics(displayMetrics)
            displayWidth = displayMetrics.widthPixels.toFloat()
            displayHeight = displayMetrics.heightPixels.toFloat()
        }
        val cutout = ViewCompat.getRootWindowInsets(this)?.displayCutout
        val marginsX = (cutout?.safeInsetLeft?.plus(cutout.safeInsetRight)) ?: 0
        val marginsY = (cutout?.safeInsetTop?.plus(cutout.safeInsetBottom)) ?: 0
        var maxX = displayWidth - marginsX
        var maxY = displayHeight - marginsY
        // Height and width changes depending on orientation. Use the larger value for height.
        if (maxY > maxX) {
            val tmp = maxX
            maxX = maxY
            maxY = tmp
        }

        // Each value is a percent from max X/Y stored as an int. Have to bring that value down
        // to a decimal before multiplying by MAX X/Y.
        preferences.edit()
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_A.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_A_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_A.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_A_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_B.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_B_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_B.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_B_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_X.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_X_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_X.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_X_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_Y.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_Y_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_Y.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_Y_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_ZL.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_ZL_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_ZL.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_ZL_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_ZR.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_ZR_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_ZR.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_ZR_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.DPAD_UP.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_UP_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.DPAD_UP.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_UP_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.TRIGGER_L.toString() + "-X",
                resources.getInteger(R.integer.N3DS_TRIGGER_L_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.TRIGGER_L.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_TRIGGER_L_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.TRIGGER_R.toString() + "-X",
                resources.getInteger(R.integer.N3DS_TRIGGER_R_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.TRIGGER_R.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_TRIGGER_R_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_START.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_START_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_START.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_START_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_SELECT.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_SELECT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_SELECT.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_SELECT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_HOME.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_HOME_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_HOME.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_HOME_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_TAIKO.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_TAIKO_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_TAIKO.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_TAIKO_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.STICK_C.toString() + "-X",
                resources.getInteger(R.integer.N3DS_STICK_C_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.STICK_C.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_STICK_C_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.STICK_LEFT.toString() + "-X",
                resources.getInteger(R.integer.N3DS_STICK_MAIN_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.STICK_LEFT.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_STICK_MAIN_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_SWAP.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_SWAP_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_SWAP.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_SWAP_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_TURBO.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_TURBO_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_TURBO.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_TURBO_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                Hotkey.COMBO_BUTTON.button.toString() + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_COMBO_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                Hotkey.COMBO_BUTTON.button.toString() + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_COMBO_Y).toFloat() / 1000 * maxY
            )
            .apply()
    }

    private fun defaultOverlayPortrait() {
        // Get screen size
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context as Activity).windowManager.defaultDisplay
        }
        var displayWidth: Float
        var displayHeight: Float
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayMetrics = (context as Activity).windowManager.maximumWindowMetrics.bounds
            displayWidth = displayMetrics.width().toFloat()
            displayHeight = displayMetrics.height().toFloat()
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getMetrics(displayMetrics)
            displayWidth = displayMetrics.widthPixels.toFloat()
            displayHeight = displayMetrics.heightPixels.toFloat()
        }
        val cutout = ViewCompat.getRootWindowInsets(this)?.displayCutout
        val marginsX = (cutout?.safeInsetLeft?.plus(cutout.safeInsetRight)) ?: 0
        val marginsY = (cutout?.safeInsetTop?.plus(cutout.safeInsetBottom)) ?: 0
        var maxX = displayWidth - marginsX
        var maxY = displayHeight - marginsY
        // Height and width changes depending on orientation. Use the larger value for height.
        if (maxY < maxX) {
            val tmp = maxX
            maxX = maxY
            maxY = tmp
        }
        val portrait = "-Portrait"

        // Each value is a percent from max X/Y stored as an int. Have to bring that value down
        // to a decimal before multiplying by MAX X/Y.
        preferences.edit()
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_A.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_A_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_A.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_A_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_B.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_B_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_B.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_B_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_X.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_X_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_X.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_X_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_Y.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_Y_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_Y.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_Y_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_ZL.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_ZL_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_ZL.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_ZL_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_ZR.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_ZR_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_ZR.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_ZR_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.DPAD_UP.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_UP_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.DPAD_UP.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_UP_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.TRIGGER_L.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_TRIGGER_L_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.TRIGGER_L.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_TRIGGER_L_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.TRIGGER_R.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_TRIGGER_R_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.TRIGGER_R.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_TRIGGER_R_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_START.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_START_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_START.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_START_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_SELECT.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_SELECT_PORTRAIT_X)
                    .toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_SELECT.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_SELECT_PORTRAIT_Y)
                    .toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_HOME.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_HOME_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_HOME.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_HOME_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_TAIKO.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_TAIKO_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_TAIKO.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_TAIKO_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.STICK_C.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_STICK_C_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.STICK_C.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_STICK_C_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.STICK_LEFT.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_STICK_MAIN_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.STICK_LEFT.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_STICK_MAIN_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_SWAP.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_SWAP_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_SWAP.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_SWAP_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_TURBO.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_TURBO_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                NativeLibrary.ButtonType.BUTTON_TURBO.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_TURBO_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .putFloat(
                Hotkey.COMBO_BUTTON.button.toString() + portrait + "-X",
                resources.getInteger(R.integer.N3DS_BUTTON_COMBO_PORTRAIT_X).toFloat() / 1000 * maxX
            )
            .putFloat(
                Hotkey.COMBO_BUTTON.button.toString() + portrait + "-Y",
                resources.getInteger(R.integer.N3DS_BUTTON_COMBO_PORTRAIT_Y).toFloat() / 1000 * maxY
            )
            .apply()
    }

    override fun isInEditMode(): Boolean = isInEditMode

    companion object {
        // Drum head: left half = D-pad left (up) / D-pad right (down),
        //            right half = A (up) / B (down). Rim (outside the head): L / R.
        private val TATACON_BUTTON_HEAD_UP_LEFT = NativeLibrary.ButtonType.DPAD_LEFT
        private val TATACON_BUTTON_HEAD_DOWN_LEFT = NativeLibrary.ButtonType.DPAD_RIGHT
        private val TATACON_BUTTON_HEAD_UP_RIGHT = NativeLibrary.ButtonType.BUTTON_A
        private val TATACON_BUTTON_HEAD_DOWN_RIGHT = NativeLibrary.ButtonType.BUTTON_B
        private val TATACON_BUTTON_RIM_LEFT = NativeLibrary.ButtonType.TRIGGER_L
        private val TATACON_BUTTON_RIM_RIGHT = NativeLibrary.ButtonType.TRIGGER_R

        /** Size of the drum artwork relative to the shorter screen dimension. */
        private const val TATACON_SIZE_RATIO = 0.75f
        /** Vertical position of the drum head relative to the screen height. */
        private const val TATACON_HEAD_Y_RATIO = 0.70f
        /** Vertical position of the drum head relative to the drum artwork height. */
        private const val TATACON_HEAD_OFFSET_RATIO = 0.381f

        private const val HAPTIC_TATACON_DEBOUNCE_MS = 35L
        private const val HAPTIC_TATACON_RAPID_WINDOW_MS = 85L
        private const val HAPTIC_TATACON_STRENGTH = 255
        private var lastTaTaConHapticTime = 0L

        // Overlay layout settings are stored per game (see OverlayPrefs).
        private val preferences
            get() = OverlayPrefs.prefs

        // Settings that are not part of the layout stay global.
        private val globalPreferences
            get() = OverlayPrefs.global

		// ---------- per-orientation ----------
		private fun getIntPerOrient(key: String, orientation: String, def: Int): Int {
			val perOrientKey = key + orientation
			return if (preferences.contains(perOrientKey)) preferences.getInt(perOrientKey, def)
			else preferences.getInt(key, def)
		}
		private fun getFloatPerOrient(key: String, orientation: String, def: Float): Float {
			val perOrientKey = key + orientation
			return if (preferences.contains(perOrientKey)) preferences.getFloat(perOrientKey, def)
			else preferences.getFloat(key, def)
		}
		private fun getBoolPerOrient(key: String, orientation: String, def: Boolean): Boolean {
			val perOrientKey = key + orientation
			return if (preferences.contains(perOrientKey)) preferences.getBoolean(perOrientKey, def)
			else preferences.getBoolean(key, def)
		}
		private fun getOpacityPerOrient(controlId: String, orientation: String): Int {
			val perControlOrient = "controlOpacity-$controlId$orientation"
			if (preferences.contains(perControlOrient)) return preferences.getInt(perControlOrient, 50) * 255 / 100
			val perControl = "controlOpacity-$controlId"
			if (preferences.contains(perControl)) return preferences.getInt(perControl, 50) * 255 / 100
			val globalOrient = "controlOpacity$orientation"
			if (preferences.contains(globalOrient)) return preferences.getInt(globalOrient, 50) * 255 / 100
			return preferences.getInt("controlOpacity", 50) * 255 / 100
		}

        /**
         * Resizes a [Bitmap] by a given scale factor
         *
         * @param context       Context for getting the drawable/vector drawable
         * @param drawableId    The ID of the drawable to scale.
         * @param scale         The scale factor for the bitmap.
         * @return The scaled [Bitmap]
         */
        private fun getBitmap(context: Context, drawableId: Int, scale: Float): Bitmap {
            // An image of the same name in the installed button pack replaces the built-in one.
            ButtonPack.load(context, drawableId)?.let { return resizeBitmap(context, it, scale) }

            try {
                val bitmap = BitmapFactory.decodeResource(context.resources, drawableId)
                return resizeBitmap(context, bitmap, scale)
            } catch (_: NullPointerException) {
            }

            val vectorDrawable = ContextCompat.getDrawable(context, drawableId) as VectorDrawable

            val bitmap = Bitmap.createBitmap(
                (vectorDrawable.intrinsicWidth * scale).toInt(),
                (vectorDrawable.intrinsicHeight * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )

            val scaledBitmap = resizeBitmap(context, bitmap, scale)
            val canvas = Canvas(scaledBitmap)
            vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
            vectorDrawable.draw(canvas)
            return scaledBitmap
        }

        /**
         * Resizes a [Bitmap] by a given scale factor
         *
         * @param context The current [Context]
         * @param bitmap  The [Bitmap] to scale.
         * @param scale   The scale factor for the bitmap.
         * @return The scaled [Bitmap]
         */
        private fun resizeBitmap(context: Context, bitmap: Bitmap, scale: Float): Bitmap {
            // Determine the button size based on the smaller screen dimension.
            // This makes sure the buttons are the same size in both portrait and landscape.
            val dm = context.resources.displayMetrics
            val minDimension = min(dm.widthPixels, dm.heightPixels)
            return Bitmap.createScaledBitmap(
                bitmap,
                (minDimension * scale).toInt(),
                (minDimension * scale).toInt(),
                true
            )
        }

        /**
         * Initializes an InputOverlayDrawableButton, given by resId, with all of the
         * parameters set for it to be properly shown on the InputOverlay.
         *
         *
         * This works due to the way the X and Y coordinates are stored within
         * the [SharedPreferences].
         *
         *
         * In the input overlay configuration menu,
         * once a touch event begins and then ends (ie. Organizing the buttons to one's own liking for the overlay).
         * the X and Y coordinates of the button at the END of its touch event
         * (when you remove your finger/stylus from the touchscreen) are then stored
         * within a SharedPreferences instance so that those values can be retrieved here.
         *
         *
         * This has a few benefits over the conventional way of storing the values
         * (ie. within the Citra ini file).
         *
         *  * No native calls
         *  * Keeps Android-only values inside the Android environment
         *
         *
         *
         * Technically no modifications should need to be performed on the returned
         * InputOverlayDrawableButton. Simply add it to the HashSet of overlay items and wait
         * for Android to call the onDraw method.
         *
         * @param context      The current [Context].
         * @param defaultResId The resource ID of the [Drawable] to get the [Bitmap] of (Default State).
         * @param pressedResId The resource ID of the [Drawable] to get the [Bitmap] of (Pressed State).
         * @param buttonId     Identifier for determining what type of button the initialized InputOverlayDrawableButton represents.
         * @return An [InputOverlayDrawableButton] with the correct drawing bounds set.
         */
        private fun initializeOverlayButton(
            context: Context,
            defaultResId: Int,
            pressedResId: Int,
            buttonId: Int,
            orientation: String
        ): InputOverlayDrawableButton {
            // Resources handle for fetching the initial Drawable resource.
            val res = context.resources

            // Decide scale based on button ID and user preference
            var scale: Float = when (buttonId) {
                NativeLibrary.ButtonType.BUTTON_HOME,
                NativeLibrary.ButtonType.BUTTON_START,
                NativeLibrary.ButtonType.BUTTON_SELECT,
                NativeLibrary.ButtonType.BUTTON_SWAP -> 0.08f

                NativeLibrary.ButtonType.BUTTON_TURBO,
                NativeLibrary.ButtonType.BUTTON_TAIKO -> 0.10f

                NativeLibrary.ButtonType.TRIGGER_L,
                NativeLibrary.ButtonType.TRIGGER_R,
                NativeLibrary.ButtonType.BUTTON_ZL,
                NativeLibrary.ButtonType.BUTTON_ZR -> 0.18f

                else -> 0.11f
            }
            // per-orientation fallback: orientation key -> global key
            scale *= (getIntPerOrient("controlScale", orientation, 50) + 50).toFloat()
            scale /= 100f

            scale *= (getIntPerOrient("controlScale-$buttonId", orientation, 50) + 50).toFloat()
            scale /= 100f

            // per-orientation opacity
            val opacity: Int = getOpacityPerOrient(buttonId.toString(), orientation)

            // Initialize the InputOverlayDrawableButton.
            val defaultStateBitmap = getBitmap(context, defaultResId, scale)
            val pressedStateBitmap = getBitmap(context, pressedResId, scale)
            val overlayDrawable =
                InputOverlayDrawableButton(
                    res,
                    defaultStateBitmap,
                    pressedStateBitmap,
                    buttonId,
                    opacity
                )

            // The X and Y coordinates of the InputOverlayDrawableButton on the InputOverlay.
            // These were set in the input overlay configuration menu.
            val xKey = "$buttonId$orientation-X"
            val yKey = "$buttonId$orientation-Y"
            val drawableX = preferences.getFloat(xKey, 0f).toInt()
            val drawableY = preferences.getFloat(yKey, 0f).toInt()
            val width = overlayDrawable.width
            val height = overlayDrawable.height

            // Now set the bounds for the InputOverlayDrawableButton.
            // This will dictate where on the screen (and the what the size) the InputOverlayDrawableButton will be.
            overlayDrawable.setBounds(drawableX, drawableY, drawableX + width, drawableY + height)

            // Need to set the image's position
            overlayDrawable.setPosition(drawableX, drawableY)
            return overlayDrawable
        }

        /**
         * Initializes an [InputOverlayDrawableDpad]
         *
         * @param context                   The current [Context].
         * @param defaultResId              The [Bitmap] resource ID of the default sate.
         * @param pressedOneDirectionResId  The [Bitmap] resource ID of the pressed sate in one direction.
         * @param pressedTwoDirectionsResId The [Bitmap] resource ID of the pressed sate in two directions.
         * @param buttonUp                  Identifier for the up button.
         * @param buttonDown                Identifier for the down button.
         * @param buttonLeft                Identifier for the left button.
         * @param buttonRight               Identifier for the right button.
         * @return the initialized [InputOverlayDrawableDpad]
         */
        private fun initializeOverlayDpad(
            context: Context,
            defaultResId: Int,
            pressedOneDirectionResId: Int,
            pressedTwoDirectionsResId: Int,
            buttonUp: Int,
            buttonDown: Int,
            buttonLeft: Int,
            buttonRight: Int,
            orientation: String
        ): InputOverlayDrawableDpad {
            // Resources handle for fetching the initial Drawable resource.
            val res = context.resources

            // Decide scale based on button ID and user preference
            var scale = 0.22f
            scale *= (getIntPerOrient("controlScale", orientation, 50) + 50).toFloat()
            scale /= 100f

            scale *= (
                getIntPerOrient(
                    "controlScale-" + NativeLibrary.ButtonType.DPAD,
                    orientation,
                    50
                ) + 50
                ).toFloat()

            scale /= 100f

            val opacity: Int = getOpacityPerOrient(NativeLibrary.ButtonType.DPAD.toString(), orientation)

            // Initialize the InputOverlayDrawableDpad.
            val defaultStateBitmap = getBitmap(context, defaultResId, scale)
            val pressedOneDirectionStateBitmap = getBitmap(context, pressedOneDirectionResId, scale)
            val pressedTwoDirectionsStateBitmap =
                getBitmap(context, pressedTwoDirectionsResId, scale)
            val overlayDrawable = InputOverlayDrawableDpad(
                res,
                defaultStateBitmap,
                pressedOneDirectionStateBitmap,
                pressedTwoDirectionsStateBitmap,
                buttonUp,
                buttonDown,
                buttonLeft,
                buttonRight,
                opacity
            )

            // The X and Y coordinates of the InputOverlayDrawableDpad on the InputOverlay.
            // These were set in the input overlay configuration menu.
            val drawableX = preferences.getFloat("$buttonUp$orientation-X", 0f).toInt()
            val drawableY = preferences.getFloat("$buttonUp$orientation-Y", 0f).toInt()
            val width = overlayDrawable.width
            val height = overlayDrawable.height

            // Now set the bounds for the InputOverlayDrawableDpad.
            // This will dictate where on the screen (and the what the size) the InputOverlayDrawableDpad will be.
            overlayDrawable.setBounds(drawableX, drawableY, drawableX + width, drawableY + height)

            // Need to set the image's position
            overlayDrawable.setPosition(drawableX, drawableY)
            return overlayDrawable
        }

        /**
         * Initializes an [InputOverlayDrawableJoystick]
         *
         * @param context         The current [Context]
         * @param resOuter        Resource ID for the outer image of the joystick (the static image that shows the circular bounds).
         * @param defaultResInner Resource ID for the default inner image of the joystick (the one you actually move around).
         * @param pressedResInner Resource ID for the pressed inner image of the joystick.
         * @param joystick        Identifier for which joystick this is.
         * @return the initialized [InputOverlayDrawableJoystick].
         */
        private fun initializeOverlayJoystick(
            context: Context,
            resOuter: Int,
            defaultResInner: Int,
            pressedResInner: Int,
            joystick: Int,
            orientation: String
        ): InputOverlayDrawableJoystick {
            // Resources handle for fetching the initial Drawable resource.
            val res = context.resources

            // Decide scale based on user preference
            var scale = 0.275f
            scale *= (getIntPerOrient("controlScale", orientation, 50) + 50).toFloat()
            scale /= 100f

            scale *= (getIntPerOrient("controlScale-$joystick", orientation, 50) + 50).toFloat()
            scale /= 100f

            val opacity: Int = getOpacityPerOrient(joystick.toString(), orientation)

            // Initialize the InputOverlayDrawableJoystick.
            val bitmapOuter = getBitmap(context, resOuter, scale)
            val bitmapInnerDefault = getBitmap(context, defaultResInner, scale)
            val bitmapInnerPressed = getBitmap(context, pressedResInner, scale)

            // The X and Y coordinates of the InputOverlayDrawableButton on the InputOverlay.
            // These were set in the input overlay configuration menu.
            val drawableX = preferences.getFloat("$joystick$orientation-X", 0f).toInt()
            val drawableY = preferences.getFloat("$joystick$orientation-Y", 0f).toInt()

            // Decide inner scale based on joystick ID
            var outerScale = 1f
            if (joystick == NativeLibrary.ButtonType.STICK_C) {
                outerScale = 2f
            }

            // Now set the bounds for the InputOverlayDrawableJoystick.
            // This will dictate where on the screen (and the what the size) the InputOverlayDrawableJoystick will be.
            val outerSize = bitmapOuter.width
            val outerRect = Rect(
                drawableX,
                drawableY,
                drawableX + (outerSize / outerScale).toInt(),
                drawableY + (outerSize / outerScale).toInt()
            )
            val innerRect =
                Rect(0, 0, (outerSize / outerScale).toInt(), (outerSize / outerScale).toInt())

            // Send the drawableId to the joystick so it can be referenced when saving control position.
            val overlayDrawable = InputOverlayDrawableJoystick(
                res,
                bitmapOuter,
                bitmapInnerDefault,
                bitmapInnerPressed,
                outerRect,
                innerRect,
                joystick,
                opacity
            )

            // Need to set the image's position
            overlayDrawable.setPosition(drawableX, drawableY)
            return overlayDrawable
        }
    }
}