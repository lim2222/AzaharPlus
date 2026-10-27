// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.fragments

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnClickListener
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.EmulationNavigationDirections
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.databinding.DialogCheckboxBinding
import org.citra.citra_emu.databinding.DialogSliderBinding
import org.citra.citra_emu.databinding.FragmentEmulationBinding
import org.citra.citra_emu.display.PortraitScreenLayout
import org.citra.citra_emu.display.ScreenAdjustmentUtil
import org.citra.citra_emu.display.ScreenLayout
import org.citra.citra_emu.display.SecondaryDisplayLayout
import org.citra.citra_emu.features.hotkeys.Hotkey
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.SettingsViewModel
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.AmiiboDatabase.Companion.amiibos
import org.citra.citra_emu.utils.AmiiboDatabase.Companion.amiibos_series
import org.citra.citra_emu.utils.AmiiboUsageDatabase
import org.citra.citra_emu.utils.AmiiboUsageDatabase.Companion.amiibos_usages
import org.citra.citra_emu.utils.BuildUtil
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.DirectoryInitialization.DirectoryInitializationState
import org.citra.citra_emu.utils.EmulationLifecycleUtil
import org.citra.citra_emu.utils.EmulationMenuSettings
import org.citra.citra_emu.utils.GameHelper
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.ViewUtils
import org.citra.citra_emu.viewmodel.EmulationViewModel
import org.citra.citra_emu.overlay.OverlayPrefs
import org.citra.citra_emu.overlay.InputOverlay

class EmulationFragment :
    Fragment(),
    SurfaceHolder.Callback,
    Choreographer.FrameCallback {
    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    private val overlayPrefs: SharedPreferences
        get() = OverlayPrefs.prefs

    private lateinit var emulationState: EmulationState
    private var perfStatsUpdater: Runnable? = null

    private val emulationActivity: EmulationActivity
        get() = (requireActivity() as EmulationActivity)

    private var _binding: FragmentEmulationBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<EmulationFragmentArgs>()

    private lateinit var game: Game
    private lateinit var screenAdjustmentUtil: ScreenAdjustmentUtil

    private val emulationViewModel: EmulationViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val settings get() = settingsViewModel.settings

    private val onPause = Runnable { togglePause() }
    private val onShutdown = Runnable { emulationState.stop() }

    // Only used if a game is passed through intent on google play variant
    private var gameFd: Int? = null

    // State of the dragging of the edit layout toolbar
    private var draggingEditToolbar = false
    // Guards the two toggle groups against reacting to each other's clearChecked() call.
    private var syncingEditButtons = false
    // True while the background image forces the custom layout, to stop updateBackground() from
    // re-entering itself.
    private var switchingToCustomLayout = false
    // The background image that is on screen / being read, so a resume or a re-entry into the game
    // only reloads when the saved image differs from the visible one.
    private var shownBackgroundUri: String? = null
    private var loadingBackgroundUri: String? = null
    private var editToolbarTouchX = 0f
    private var editToolbarTouchY = 0f
    private var editToolbarStartX = 0f
    private var editToolbarStartY = 0f

    // Edit layout: screens (custom layout) and background image
    private val screenEditHandler = Handler(Looper.getMainLooper())
    private var screenPushPending = false
    private var workRects: List<Rect> = emptyList() // 0 = top screen, 1 = bottom screen
    private var selectedScreen = -1
    private var screenAccumX = 0f
    private var screenAccumY = 0f

    private val pushScreenRunnable = Runnable {
        screenPushPending = false
        pushSelectedScreen()
    }

    // The background picker is registered on the ActivityResultRegistry of the activity that is
    // running now instead of with Fragment.registerForActivityResult(). That one binds its launcher
    // to the registry of the activity the fragment was created with and keeps it, and this fragment
    // survives rotations (retainInstance = true). After a rotation the picker still opens, but the
    // result is delivered to the new activity, whose registry does not know that launcher, so the
    // pick was dropped and the image that was already on screen stayed there.
    private var pickBackgroundRegistry: ActivityResultRegistry? = null
    private var pickBackground: ActivityResultLauncher<Array<String>>? = null

    /** The background picker, bound to the registry of the current activity. */
    private fun backgroundPicker(): ActivityResultLauncher<Array<String>>? {
        val registry = activity?.activityResultRegistry ?: return null
        if (pickBackgroundRegistry === registry) return pickBackground
        // Another activity is running now (a rotation happened), and its registry is the one the
        // result is delivered to, so the registration has to be made on it before the picker opens.
        // The launcher of the previous registry is dropped; that registry dies with the activity.
        pickBackgroundRegistry = registry
        pickBackground = registry.register(
            BACKGROUND_PICK_KEY,
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? -> onBackgroundPicked(uri) }
        Log.info("[BG] picker registered on the current activity registry")
        return pickBackground
    }

    private fun onBackgroundPicked(uri: Uri?) {
        if (uri == null) {
            Log.info("[BG] picker cancelled")
            return
        }
        // A rotation can destroy the fragment view while the picker is open; the image is
        // saved anyway and restoreBackground() / onResume() pick it up for the new view.
        val ctx = context ?: CitraApplication.appContext
        // Keep read access after a restart of the app.
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Not offered by the provider; the image still loads until the app is closed.
            Log.info("[BG] no persistable permission: " + e.message)
        }
        // One image per orientation, like the scale, the opacity and the placement.
        val key = backgroundUriKey()
        overlayPrefs.edit().putString(key, uri.toString()).apply()
        Log.info("[BG] picked uri=$uri key=$key")
        loadBackground(uri)
    }

    private val screenEditListener = object : InputOverlay.ScreenEditListener {
        override fun onEditDragStart(mode: InputOverlay.ScreenEditMode, x: Float, y: Float) {
            screenAccumX = 0f
            screenAccumY = 0f
            when (mode) {
                InputOverlay.ScreenEditMode.SCREEN_MOVE,
                InputOverlay.ScreenEditMode.SCREEN_SIZE -> {
                    workRects = customScreenRects()
                    val px = x.toInt()
                    val py = y.toInt()
                    // The bottom screen is drawn last, so it wins where they overlap.
                    selectedScreen = when {
                        workRects[1].contains(px, py) -> 1
                        workRects[0].contains(px, py) -> 0
                        else -> -1
                    }
                    binding.surfaceInputOverlay.setScreenOutlines(workRects, selectedScreen)
                }

                InputOverlay.ScreenEditMode.BG_MOVE,
                InputOverlay.ScreenEditMode.BG_SIZE ->
                    if (!binding.backgroundImage.hasBitmap) {
                        Toast.makeText(
                            requireContext(),
                            "Choose a background image with BG first",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                InputOverlay.ScreenEditMode.NONE -> {}
            }
        }

        override fun onEditDrag(mode: InputOverlay.ScreenEditMode, dx: Float, dy: Float) {
            when (mode) {
                InputOverlay.ScreenEditMode.SCREEN_MOVE,
                InputOverlay.ScreenEditMode.SCREEN_SIZE -> {
                    if (selectedScreen < 0) return
                    screenAccumX += dx
                    screenAccumY += dy
                    val stepX = screenAccumX.toInt()
                    val stepY = screenAccumY.toInt()
                    if (stepX == 0 && stepY == 0) return
                    screenAccumX -= stepX
                    screenAccumY -= stepY

                    val rect = workRects[selectedScreen]
                    if (mode == InputOverlay.ScreenEditMode.SCREEN_MOVE) {
                        // Horizontal drag = X, vertical drag = Y
                        rect.offsetTo(
                            (rect.left + stepX).coerceAtLeast(0),
                            (rect.top + stepY).coerceAtLeast(0)
                        )
                    } else {
                        // Horizontal drag = width, vertical drag = height; top left stays
                        val newWidth = (rect.width() + stepX).coerceAtLeast(minScreenSizePx)
                        val newHeight = (rect.height() + stepY).coerceAtLeast(minScreenSizePx)
                        rect.set(rect.left, rect.top, rect.left + newWidth, rect.top + newHeight)
                    }
                    binding.surfaceInputOverlay.setScreenOutlines(workRects, selectedScreen)
                    binding.backgroundImage.setHoles(workRects)
                    scheduleScreenPush()
                }

                InputOverlay.ScreenEditMode.BG_MOVE ->
                    binding.backgroundImage.moveBy(dx, dy)

                InputOverlay.ScreenEditMode.BG_SIZE -> {
                    val view = binding.backgroundImage
                    if (view.width > 0 && view.height > 0) {
                        // Dragging half the view doubles the size on that axis.
                        view.scaleBy(
                            (1f + dx / (view.width / 2f)).coerceAtLeast(0.1f),
                            (1f + dy / (view.height / 2f)).coerceAtLeast(0.1f)
                        )
                    }
                }

                InputOverlay.ScreenEditMode.NONE -> {}
            }
        }

        override fun onEditDragEnd(mode: InputOverlay.ScreenEditMode) {
            when (mode) {
                InputOverlay.ScreenEditMode.SCREEN_MOVE,
                InputOverlay.ScreenEditMode.SCREEN_SIZE -> {
                    // Final value: save it for this game and apply it.
                    screenEditHandler.removeCallbacks(pushScreenRunnable)
                    screenPushPending = false
                    pushSelectedScreen()
                    updateBackground()
                }

                InputOverlay.ScreenEditMode.BG_MOVE,
                InputOverlay.ScreenEditMode.BG_SIZE -> saveBackgroundTransform()

                InputOverlay.ScreenEditMode.NONE -> {}
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is EmulationActivity) {
            NativeLibrary.setEmulationActivity(context)
        } else {
            throw IllegalStateException("EmulationFragment must have EmulationActivity parent")
        }
    }

    /**
     * Initialize anything that doesn't depend on the layout / views in here.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AmiiboUsageDatabase.initialize()
        AmiiboUsageDatabase.initialize2()
        val intent = requireActivity().intent
        var intentUri: Uri? = intent.data
        val oldIntentInfo = Pair(
            intent.getStringExtra("SelectedGame"),
            intent.getStringExtra("SelectedTitle")
        )
        var intentGame: Game? = null
        intentUri = if (intentUri == null && oldIntentInfo.first != null) {
            Uri.parse(oldIntentInfo.first)
        } else {
            intentUri
        }
        if (intentUri != null) {
            if (!BuildUtil.isGooglePlayBuild) {
                val intentUriString = intentUri.toString()
                // We need to build a special path as the incoming URI may be SAF exclusive
                Log.warning(
                    "[EmulationFragment] Cannot determine native path of URI \"" +
                        intentUriString + "\", using file descriptor instead."
                )
                if (!intentUriString.startsWith("!")) {
                    gameFd =
                        requireContext().contentResolver.openFileDescriptor(
                            intentUri,
                            "r"
                        )?.detachFd()
                    intentUri = if (gameFd != null) {
                        Uri.parse("fd://" + gameFd.toString())
                    } else {
                        null
                    }
                }
            }
            intentGame =
                intentUri?.let {
                    // isInstalled, addedToLibrary and mediaType do not matter here
                    GameHelper.getGame(
                        it,
                        isInstalled = false,
                        addedToLibrary = false,
                        mediaType = Game.MediaType.GAME_CARD
                    )
                }
        }

        val insertedCartridge = preferences.getString("insertedCartridge", "")
        NativeLibrary.setInsertedCartridge(insertedCartridge ?: "")

        try {
            game = args.game ?: intentGame!!
            OverlayPrefs.setGame(game.titleId, game.path)
            // The screens are saved per game, hand them to the core before it boots.
            applySavedScreenRects()
        } catch (e: NullPointerException) {
            Toast.makeText(
                requireContext(),
                R.string.no_game_present,
                Toast.LENGTH_SHORT
            ).show()
            requireActivity().finish()
            return
        }

        Log.info("[EmulationFragment] Starting application " + game.path)

        // So this fragment doesn't restart on configuration changes; i.e. rotation.
        retainInstance = true
        emulationState = EmulationState(game.path)
        screenAdjustmentUtil =
            ScreenAdjustmentUtil(requireContext(), requireActivity().windowManager, settings)
        EmulationLifecycleUtil.addPauseResumeHook(onPause)
        EmulationLifecycleUtil.addShutdownHook(onShutdown)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmulationBinding.inflate(inflater)        
        binding.inGameMenu.menu.findItem(R.id.menu_secondary_screen_layout).isVisible =
            emulationActivity.secondaryDisplayManager.availableDisplays.isNotEmpty()
        binding.inGameMenu.menu.findItem(R.id.menu_landscape_screen_layout).isVisible =
            CitraApplication.appContext.resources.configuration.orientation !=
            Configuration.ORIENTATION_PORTRAIT
        binding.inGameMenu.menu.findItem(R.id.menu_portrait_screen_layout).isVisible =
            CitraApplication.appContext.resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        return binding.root
    }

    // This is using the correct scope, lint is just acting up
    @SuppressLint("UnsafeRepeatOnLifecycleDetector")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (requireActivity().isFinishing) {
            return
        }

        binding.editToolbar.setOnTouchListener { v, event -> onEditToolbarTouch(v, event) }
        binding.surfaceEmulation.holder.addCallback(this)
        binding.doneControlConfig.setOnClickListener {
            binding.doneControlConfig.visibility = View.GONE
            binding.editToolbar.visibility = View.GONE
            // Back to the default mode so the next edit session starts with "Move".
            resetEditMode()
            binding.surfaceInputOverlay.setIsInEditMode(false)
        }
        binding.editActionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingEditButtons) return@addOnButtonCheckedListener
            // Only one of the two groups may have a selection at a time.
            syncingEditButtons = true
            binding.editScreenGroup.clearChecked()
            syncingEditButtons = false
            applyEditMode(checkedId)
        }
        binding.editScreenGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingEditButtons) return@addOnButtonCheckedListener
            syncingEditButtons = true
            binding.editActionGroup.clearChecked()
            syncingEditButtons = false
            applyEditMode(checkedId)
        }
        binding.btnEditReset.setOnClickListener {
            binding.surfaceInputOverlay.resetLayoutToDefault()
            // Bring the background image back to its default placement too, otherwise a
            // background that was moved or zoomed out of sight could not be recovered.
            binding.backgroundImage.resetTransform()
            saveBackgroundTransform()
            updateBackground()
        }
        binding.btnEditBg.setOnClickListener { openBackgroundPicker() }
        binding.btnEditBgRemove.setOnClickListener { removeBackground() }
        binding.surfaceInputOverlay.screenEditListener = screenEditListener
        // Paint the wallpaper from the input overlay: it is drawn above the emulation surface,
        // while the background view itself can end up behind it and never show.
        binding.surfaceInputOverlay.backgroundRenderer = binding.backgroundImage
        binding.backgroundImage.mirror = binding.surfaceInputOverlay
        // Repaint the overlay when the image, its placement or the screens change.
        binding.backgroundImage.addOnLayoutChangeListener { _, left, top, right, bottom, oLeft,
            oTop, oRight, oBottom ->
            if (right - left != oRight - oLeft || bottom - top != oBottom - oTop) {
                updateBackground()
            }
        }
        restoreBackground()

        // Show/hide the "Stats" overlay
        updateShowPerformanceOverlay()

        val position = IntSetting.PERFORMANCE_OVERLAY_POSITION.int
        updateStatsPosition(position)

        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.drawerLayout.addDrawerListener(object : DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                binding.surfaceInputOverlay.dispatchTouchEvent(
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis() + 100,
                        MotionEvent.ACTION_UP,
                        0f,
                        0f,
                        0
                    )
                )
            }

            override fun onDrawerOpened(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                binding.surfaceInputOverlay.isClickable = false
                binding.surfaceInputOverlay.isFocusable = false
                binding.surfaceInputOverlay.isFocusableInTouchMode = false
            }

            override fun onDrawerClosed(drawerView: View) {
                binding.drawerLayout.setDrawerLockMode(EmulationMenuSettings.drawerLockMode)
                binding.surfaceInputOverlay.isClickable = true
                binding.surfaceInputOverlay.isFocusable = true
                binding.surfaceInputOverlay.isFocusableInTouchMode = true
            }

            override fun onDrawerStateChanged(newState: Int) {
                // No op
            }
        })
        binding.inGameMenu.menu.findItem(R.id.menu_lock_drawer).apply {
            val titleId =
                if (EmulationMenuSettings.drawerLockMode == DrawerLayout.LOCK_MODE_LOCKED_CLOSED) {
                    R.string.unlock_drawer
                } else {
                    R.string.lock_drawer
                }
            val iconId =
                if (EmulationMenuSettings.drawerLockMode == DrawerLayout.LOCK_MODE_UNLOCKED) {
                    R.drawable.ic_unlocked
                } else {
                    R.drawable.ic_lock
                }

            title = getString(titleId)
            icon = ResourcesCompat.getDrawable(
                resources,
                iconId,
                requireContext().theme
            )
        }

        binding.inGameMenu.getHeaderView(0).apply {
            val titleView = findViewById<TextView>(R.id.text_game_title)
            val iconView = findViewById<ImageView>(R.id.game_icon)

            titleView.text = game.title

            GameIconUtils.loadGameIcon(requireActivity(), game, iconView)
        }

        binding.inGameMenu.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.menu_emulation_pause -> {
                    if (emulationState.isPaused) {
                        emulationState.unpause()
                        it.title = resources.getString(R.string.pause_emulation)
                        it.icon = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_pause,
                            requireContext().theme
                        )
                    } else {
                        emulationState.pause()
                        it.title = resources.getString(R.string.resume_emulation)
                        it.icon = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_play,
                            requireContext().theme
                        )
                    }
                    true
                }

                R.id.menu_emulation_savestates -> {
                    showSavestateMenu()
                    true
                }

                R.id.menu_overlay_options -> {
                    showOverlayMenu()
                    true
                }

                R.id.menu_amiibo -> {
                    showAmiiboMenu()
                    true
                }

                R.id.menu_landscape_screen_layout -> {
                    showLandscapeScreenLayoutMenu()
                    true
                }

                R.id.menu_portrait_screen_layout -> {
                    showPortraitScreenLayoutMenu()
                    true
                }

                R.id.menu_secondary_screen_layout -> {
                    showSecondaryScreenLayoutMenu()
                    true
                }

                R.id.menu_swap_screens -> {
                    screenAdjustmentUtil.swapScreen(true)
                    true
                }

                R.id.menu_rotate_upright -> {
                    screenAdjustmentUtil.toggleScreenUpright()
                    true
                }

                R.id.menu_lock_drawer -> {
                    when (EmulationMenuSettings.drawerLockMode) {
                        DrawerLayout.LOCK_MODE_UNLOCKED -> {
                            EmulationMenuSettings.drawerLockMode =
                                DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                            it.title = resources.getString(R.string.unlock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_lock,
                                requireContext().theme
                            )
                        }

                        DrawerLayout.LOCK_MODE_LOCKED_CLOSED -> {
                            EmulationMenuSettings.drawerLockMode = DrawerLayout.LOCK_MODE_UNLOCKED
                            it.title = resources.getString(R.string.lock_drawer)
                            it.icon = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_unlocked,
                                requireContext().theme
                            )
                        }
                    }
                    true
                }

                R.id.menu_cheats -> {
                    val action = EmulationNavigationDirections
                        .actionGlobalCheatsActivity(NativeLibrary.getRunningTitleId())
                    binding.root.findNavController().navigate(action)
                    true
                }

                R.id.menu_settings -> {
                    SettingsActivity.launch(
                        requireContext(),
                        SettingsFile.FILE_NAME_CONFIG,
                        ""
                    )

                    true
                }

                R.id.menu_multiplayer -> {
                    emulationActivity.displayMultiplayerDialog()
                    true
                }

                R.id.menu_exit -> {
                    emulationState.pause()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.emulation_close_game)
                        .setMessage(R.string.emulation_close_game_message)
                        .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                            EmulationLifecycleUtil.closeGame()
                        }
                        .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                            emulationState.unpause()
                        }
                        .setOnCancelListener { emulationState.unpause() }
                        .show()
                    true
                }

                else -> true
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!emulationViewModel.emulationStarted.value) {
                        return
                    }

                    if (binding.drawerLayout.isOpen) {
                        binding.drawerLayout.close()
                    } else {
                        binding.drawerLayout.open()
                    }
                }
            }
        )

        GameIconUtils.loadGameIcon(requireActivity(), game, binding.loadingImage)
        binding.loadingTitle.text = game.title

        viewLifecycleOwner.lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.shaderProgress.collectLatest {
                        if (it > 0 && it != emulationViewModel.totalShaders.value) {
                            binding.loadingProgressIndicator.isIndeterminate = false
                            binding.loadingProgressText.visibility = View.VISIBLE
                            binding.loadingProgressText.text = String.format(
                                "%d/%d",
                                emulationViewModel.shaderProgress.value,
                                emulationViewModel.totalShaders.value
                            )

                            if (it < binding.loadingProgressIndicator.max) {
                                binding.loadingProgressIndicator.progress = it
                            }
                        }

                        if (it == emulationViewModel.totalShaders.value) {
                            binding.loadingText.setText(R.string.loading)
                            binding.loadingProgressIndicator.isIndeterminate = true
                            binding.loadingProgressText.visibility = View.GONE
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.totalShaders.collectLatest {
                        binding.loadingProgressIndicator.max = it
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.shaderMessage.collectLatest {
                        if (it != "") {
                            binding.loadingText.text = it
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    emulationViewModel.emulationStarted.collectLatest { started ->
                        if (started) {
                            ViewUtils.hideView(binding.loadingIndicator)
                            ViewUtils.showView(binding.surfaceInputOverlay)
                            // The layout is per orientation and the orientation may have changed
                            // while booting, so re-evaluate the background once the game is up.
                            binding.backgroundImage.post { updateBackground() }
                            binding.inGameMenu.menu.findItem(R.id.menu_emulation_savestates)
                                .setVisible(NativeLibrary.getSavestateInfo() != null)
                            binding.drawerLayout.setDrawerLockMode(
                                EmulationMenuSettings.drawerLockMode
                            )
                        }
                    }
                }
            }
        }

        setInsets()
    }

    fun isDrawerOpen(): Boolean = binding.drawerLayout.isOpen

    private fun togglePause() {
        if (emulationState.isPaused) {
            emulationState.unpause()
        } else {
            emulationState.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(this)
        // Returning from the image picker must always show the picked image, even when the
        // pick callback ran too late for the view that launched it.
        if (_binding != null) reloadBackgroundIfNeeded()
        if (NativeLibrary.isRunning()) {
            emulationState.unpause()

            // If the overlay is enabled, we need to update the position if changed
            val position = IntSetting.PERFORMANCE_OVERLAY_POSITION.int
            updateStatsPosition(position)

            binding.inGameMenu.menu.findItem(R.id.menu_emulation_pause)?.let { menuItem ->
                menuItem.title = resources.getString(R.string.pause_emulation)
                menuItem.icon = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_pause,
                    requireContext().theme
                )
            }
            return
        }

        if (DirectoryInitialization.areCitraDirectoriesReady()) {
            emulationState.run(emulationActivity.isActivityRecreated)
        } else {
            setupCitraDirectoriesThenStartEmulation()
        }
    }

    override fun onPause() {
        if (NativeLibrary.isRunning()) {
            emulationState.pause()
        }
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }

    override fun onDetach() {
        NativeLibrary.clearEmulationActivity()
        super.onDetach()
    }

    override fun onDestroy() {
        if (::emulationState.isInitialized && requireActivity().isFinishing) {
            emulationState.stop()
        }
        EmulationLifecycleUtil.removeHook(onPause)
        EmulationLifecycleUtil.removeHook(onShutdown)
        if (gameFd != null) {
            ParcelFileDescriptor.adoptFd(gameFd!!).close()
            gameFd = null
        }
        super.onDestroy()
    }

    private fun setupCitraDirectoriesThenStartEmulation() {
        val directoryInitializationState = DirectoryInitialization.start()
        if (directoryInitializationState ===
            DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED
        ) {
            emulationState.run(emulationActivity.isActivityRecreated)
        } else if (directoryInitializationState ===
            DirectoryInitializationState.EXTERNAL_STORAGE_PERMISSION_NEEDED
        ) {
            Toast.makeText(context, R.string.write_permission_needed, Toast.LENGTH_SHORT)
                .show()
        } else if (directoryInitializationState ===
            DirectoryInitializationState.CANT_FIND_EXTERNAL_STORAGE
        ) {
            Toast.makeText(
                context,
                R.string.external_storage_not_mounted,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showSavestateMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_emulation_savestates)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_savestates, popupMenu.menu)

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_emulation_save_state -> {
                    showStateSubmenu(true)
                    true
                }

                R.id.menu_emulation_load_state -> {
                    showStateSubmenu(false)
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showStateSubmenu(isSaving: Boolean) {
        val savestates = NativeLibrary.getSavestateInfo()

        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_emulation_savestates)
        )

        popupMenu.menu.apply {
            for (i in 0 until NativeLibrary.SAVESTATE_SLOT_COUNT) {
                val slot = i
                var enableClick = isSaving
                val text = if (slot == NativeLibrary.QUICKSAVE_SLOT) {
                    getString(R.string.emulation_quicksave_slot)
                } else {
                    getString(R.string.emulation_empty_state_slot, slot)
                }

                add(text).setEnabled(enableClick).setOnMenuItemClickListener {
                    if (isSaving) {
                        NativeLibrary.saveState(slot)
                        Toast.makeText(
                            context,
                            getString(R.string.saving),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        NativeLibrary.loadState(slot)
                        binding.drawerLayout.close()
                        Toast.makeText(
                            context,
                            getString(R.string.loading),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }
        }

        savestates?.forEach {
            var text: String
            if (it.slot == NativeLibrary.QUICKSAVE_SLOT) {
                text = getString(R.string.emulation_occupied_quicksave_slot, it.time)
            } else {
                text = getString(R.string.emulation_occupied_state_slot, it.slot, it.time)
            }
            popupMenu.menu.getItem(it.slot).setTitle(text).setEnabled(true)
        }

        popupMenu.show()
    }

    private fun showLoadStateSubmenu() {
        val savestates = NativeLibrary.getSavestateInfo()

        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_emulation_savestates)
        )

        popupMenu.menu.apply {
            for (i in 0 until NativeLibrary.SAVESTATE_SLOT_COUNT) {
                val slot = i + 1
                val text = getString(R.string.emulation_empty_state_slot, slot)
                add(text).setEnabled(false).setOnMenuItemClickListener {
                    NativeLibrary.loadState(slot)
                    true
                }
            }
        }

        savestates?.forEach {
            val text = getString(R.string.emulation_occupied_state_slot, it.slot, it.time)
            popupMenu.menu[it.slot - 1].setTitle(text).setEnabled(true)
        }

        popupMenu.show()
    }

    private fun displaySavestateWarning() {
        if (preferences.getBoolean("savestateWarningShown", false)) {
            return
        }

        val dialogCheckboxBinding = DialogCheckboxBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.savestates)
            .setMessage(R.string.savestate_warning_message)
            .setView(dialogCheckboxBinding.root)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                preferences.edit()
                    .putBoolean("savestateWarningShown", dialogCheckboxBinding.checkBox.isChecked)
                    .apply()
            }
            .show()
    }

    private fun showOverlayMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_overlay_options)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_overlay_options, popupMenu.menu)

        popupMenu.menu.apply {
            findItem(R.id.menu_show_overlay).isChecked = EmulationMenuSettings.showOverlay
            findItem(R.id.menu_performance_overlay_show).isChecked =
                BooleanSetting.PERF_OVERLAY_ENABLE.boolean
            findItem(R.id.menu_haptic_feedback).isChecked = EmulationMenuSettings.hapticFeedback
            findItem(R.id.menu_emulation_joystick_rel_center).isChecked =
                EmulationMenuSettings.joystickRelCenter
            findItem(R.id.menu_emulation_dpad_slide_enable).isChecked =
                EmulationMenuSettings.dpadSlide
        }

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_show_overlay -> {
                    EmulationMenuSettings.showOverlay = !EmulationMenuSettings.showOverlay
                    binding.surfaceInputOverlay.refreshControls()
                    true
                }

                R.id.menu_performance_overlay_show -> {
                    BooleanSetting.PERF_OVERLAY_ENABLE.boolean =
                        !BooleanSetting.PERF_OVERLAY_ENABLE.boolean
                    settings.saveSetting(
                        BooleanSetting.PERF_OVERLAY_ENABLE,
                        SettingsFile.FILE_NAME_CONFIG
                    )
                    updateShowPerformanceOverlay()
                    true
                }

                R.id.menu_haptic_feedback -> {
                    EmulationMenuSettings.hapticFeedback = !EmulationMenuSettings.hapticFeedback
                    true
                }

                R.id.menu_emulation_edit_layout -> {
                    editControlsPlacement()
                    binding.drawerLayout.close()
                    true
                }

                R.id.menu_emulation_toggle_controls -> {
                    showToggleControlsDialog()
                    true
                }

                R.id.menu_emulation_adjust_scale_reset_all -> {
                    resetAllScales()
                    true
                }

                R.id.menu_emulation_adjust_scale -> {
                    showAdjustScaleDialog("controlScale")
                    true
                }

                R.id.menu_emulation_adjust_scale_button_a -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_A)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_b -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_B)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_x -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_X)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_y -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_Y)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_l -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.TRIGGER_L)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_r -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.TRIGGER_R)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_zl -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZL)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_zr -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZR)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_start -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_START)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_select -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_SELECT)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_dpad -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.DPAD)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_circlepad -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.STICK_LEFT)
                    true
                }

                R.id.menu_emulation_adjust_scale_controller_c -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.STICK_C)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_home -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_HOME)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_swap -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_SWAP)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_turbo -> {
                    showAdjustScaleDialog("controlScale-" + NativeLibrary.ButtonType.BUTTON_TURBO)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_combo -> {
                    showAdjustScaleDialog("controlScale-" + Hotkey.COMBO_BUTTON.button)
                    true
                }

                R.id.menu_emulation_adjust_scale_button_tatacon -> {
                    showAdjustScaleDialog("controlScale-tatacon")
                    true
                }

                R.id.menu_emulation_adjust_opacity -> {
                    showAdjustOpacityDialog()
                    true
                }

                R.id.menu_emulation_joystick_rel_center -> {
                    EmulationMenuSettings.joystickRelCenter =
                        !EmulationMenuSettings.joystickRelCenter
                    true
                }

                R.id.menu_emulation_button_sliding -> {
                    showButtonSlidingMenu()
                    true
                }

                R.id.menu_emulation_dpad_slide_enable -> {
                    EmulationMenuSettings.dpadSlide = !EmulationMenuSettings.dpadSlide
                    true
                }

                R.id.menu_emulation_reset_overlay -> {
                    showResetOverlayDialog()
                    true
                }

                else -> true
            }
        }

        popupMenu.show()
    }

    private fun showAmiiboMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_amiibo)
        )

        val generate = popupMenu.menu.addSubMenu(0, 0, 0, "Generate File")

        popupMenu.menu.add(0, R.id.menu_emulation_amiibo_load, 0, R.string.menu_emulation_amiibo_load)

        if(hasPrevious) {
            val prev = popupMenu.menu.add(2, 0, 0, "Previous Amiibo")
            prev.tooltipText = "@previous"
        }

        val gameList = amiibos_usages[NativeLibrary.getProgramId()]?.sorted()

        if (gameList != null) {
            val generateRec = generate.addSubMenu("Recommended")
            val loadRec = popupMenu.menu.addSubMenu("Recommended")

            for (i in gameList)
            {
                val text = amiibos[i] + "    [" + amiibos_series[i.substring(13, 15)] + "]"

                val genItem = generateRec.add(1, 0, 0, text)
                genItem.tooltipText = i

                val loadItem = loadRec.add(2, 0, 0, text)
                loadItem.tooltipText = i
            }
        }

        val generateFull = generate.addSubMenu("Full List")
        val loadFull = popupMenu.menu.addSubMenu("Full List")

        popupMenu.menu.add(0, R.id.menu_emulation_amiibo_remove, 0, R.string.menu_emulation_amiibo_remove)

        for (i in amiibos)
        {
            val text = i.value + "    [" + amiibos_series[i.key.substring(13, 15)] + "]"

            val genItem = generateFull.add(1, 0, 0, text)
            genItem.tooltipText = i.key

            val loadItem = loadFull.add(2, 0, 0, text)
            loadItem.tooltipText = i.key
        }

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_emulation_amiibo_load -> {
                    emulationActivity.openAmiiboFileLauncher.launch(false)
                }

                R.id.menu_emulation_amiibo_remove -> {
                    NativeLibrary.removeAmiibo()
                }

                else -> when (it.groupId) {
                    1 -> {
                        val rand = (1000..9999).random()
                        val name = "${amiibos[it.tooltipText]} [$rand]"

                        emulationActivity.amiiboId = "${it.tooltipText}"
                        emulationActivity.amiiboSaver.launch(name)
                    }

                    2 -> {
                        val ret = NativeLibrary.loadAmiibo("${it.tooltipText}")
                        if(ret) hasPrevious = true
                    }
                }
            }

            true
        }

        popupMenu.show()
    }

    private fun showButtonSlidingMenu() {
        val editor = preferences.edit()

        val buttonSlidingModes = mutableListOf<String>()
        buttonSlidingModes.add(getString(R.string.emulation_button_sliding_disabled))
        buttonSlidingModes.add(getString(R.string.emulation_button_sliding_enabled))
        buttonSlidingModes.add(getString(R.string.emulation_button_sliding_alternative))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_button_sliding)
            .setSingleChoiceItems(
                buttonSlidingModes.toTypedArray(),
                EmulationMenuSettings.buttonSlide
            ) { _: DialogInterface?, which: Int ->
                EmulationMenuSettings.buttonSlide = which
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                editor.apply()
            }
            .show()
    }

    private fun showLandscapeScreenLayoutMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_landscape_screen_layout)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_landscape_screen_layout, popupMenu.menu)

        val layoutOptionMenuItem = when (IntSetting.SCREEN_LAYOUT.int) {
            ScreenLayout.ORIGINAL.int ->
                R.id.menu_screen_layout_original

            ScreenLayout.SINGLE_SCREEN.int ->
                R.id.menu_screen_layout_single

            ScreenLayout.SIDE_SCREEN.int ->
                R.id.menu_screen_layout_sidebyside

            ScreenLayout.HYBRID_SCREEN.int ->
                R.id.menu_screen_layout_hybrid

            ScreenLayout.CUSTOM_LAYOUT.int ->
                R.id.menu_screen_layout_custom

            else -> R.id.menu_screen_layout_largescreen
        }
        popupMenu.menu.findItem(layoutOptionMenuItem).setChecked(true)

        popupMenu.setOnMenuItemClickListener {
            val handled = when (it.itemId) {
                R.id.menu_screen_layout_largescreen -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.LARGE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_single -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.SINGLE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_sidebyside -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.SIDE_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_hybrid -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.HYBRID_SCREEN.int)
                    true
                }

                R.id.menu_screen_layout_original -> {
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.ORIGINAL.int)
                    true
                }

                R.id.menu_screen_layout_custom -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.emulation_adjust_custom_layout,
                        Toast.LENGTH_LONG
                    ).show()
                    screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.CUSTOM_LAYOUT.int)
                    true
                }

                else -> true
            }
            // A background image needs the custom layout; keep the user's choice here and just
            // hide the image instead of overriding the layout they picked.
            if (handled) updateBackground(ensureLayout = false)
            handled
        }

        popupMenu.show()
    }

    private fun showPortraitScreenLayoutMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_portrait_screen_layout)
        )

        popupMenu.menuInflater.inflate(R.menu.menu_portrait_screen_layout, popupMenu.menu)

        val layoutOptionMenuItem = when (IntSetting.PORTRAIT_SCREEN_LAYOUT.int) {
            PortraitScreenLayout.TOP_FULL_WIDTH.int ->
                R.id.menu_portrait_layout_top_full

            PortraitScreenLayout.ORIGINAL.int ->
                R.id.menu_portrait_layout_original

            PortraitScreenLayout.CUSTOM_PORTRAIT_LAYOUT.int ->
                R.id.menu_portrait_layout_custom

            else ->
                R.id.menu_portrait_layout_top_full
        }

        popupMenu.menu.findItem(layoutOptionMenuItem).setChecked(true)

        popupMenu.setOnMenuItemClickListener {
            val handled = when (it.itemId) {
                R.id.menu_portrait_layout_top_full -> {
                    screenAdjustmentUtil.changePortraitOrientation(
                        PortraitScreenLayout.TOP_FULL_WIDTH.int
                    )
                    true
                }

                R.id.menu_portrait_layout_original -> {
                    screenAdjustmentUtil.changePortraitOrientation(
                        PortraitScreenLayout.ORIGINAL.int
                    )
                    true
                }

                R.id.menu_portrait_layout_custom -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.emulation_adjust_custom_layout,
                        Toast.LENGTH_LONG
                    ).show()
                    screenAdjustmentUtil.changePortraitOrientation(
                        PortraitScreenLayout.CUSTOM_PORTRAIT_LAYOUT.int
                    )
                    true
                }

                else -> true
            }
            // A background image needs the custom layout; keep the user's choice here and just
            // hide the image instead of overriding the layout they picked.
            if (handled) updateBackground(ensureLayout = false)
            handled
        }

        popupMenu.show()
    }

    private fun showSecondaryScreenLayoutMenu() {
        val popupMenu = PopupMenu(
            requireContext(),
            binding.inGameMenu.findViewById(R.id.menu_secondary_screen_layout)
        )
        popupMenu.menuInflater.inflate(R.menu.menu_secondary_screen_layout, popupMenu.menu)

        var selectedLayout = IntSetting.SECONDARY_DISPLAY_LAYOUT.int
        val chooserMenu = popupMenu.menu.findItem(R.id.menu_secondary_choose)
        val enableSecondaryCheckbox = popupMenu.menu.findItem(R.id.menu_enable_secondary_layout)
        chooserMenu?.subMenu?.removeGroup(R.id.menu_secondary_management_display_group)
        val displays =
            emulationActivity.secondaryDisplayManager.availableDisplays

        if (selectedLayout == SecondaryDisplayLayout.NONE.int ||
            !BooleanSetting.ENABLE_SECONDARY_DISPLAY.boolean
        ) {
            BooleanSetting.ENABLE_SECONDARY_DISPLAY.boolean = false
            enableSecondaryCheckbox.isChecked = false
            chooserMenu.isVisible = false
            popupMenu.menu.setGroupEnabled(R.id.menu_secondary_layout_group, false)
        } else {
            popupMenu.menu.setGroupEnabled(R.id.menu_secondary_layout_group, true)
            chooserMenu.isVisible = (displays.size > 1)
        }
        val layoutOptionMenuItem = when (selectedLayout) {
            SecondaryDisplayLayout.NONE.int ->
                R.id.menu_secondary_layout_opposite

            SecondaryDisplayLayout.REVERSE_PRIMARY.int ->
                R.id.menu_secondary_layout_opposite

            SecondaryDisplayLayout.TOP_SCREEN.int ->
                R.id.menu_secondary_layout_top

            SecondaryDisplayLayout.BOTTOM_SCREEN.int ->
                R.id.menu_secondary_layout_bottom

            SecondaryDisplayLayout.HYBRID.int ->
                R.id.menu_secondary_layout_hybrid

            SecondaryDisplayLayout.LARGE_SCREEN.int ->
                R.id.menu_secondary_layout_largescreen

            SecondaryDisplayLayout.ORIGINAL.int ->
                R.id.menu_secondary_layout_original

            else ->
                R.id.menu_secondary_layout_side_by_side
        }
        popupMenu.menu.findItem(layoutOptionMenuItem).isChecked = true
        // Add the available secondary displays to the display chooser list
        // Use the display ID as the menu ID - since generated menu IDs are all > 1,000,000 this
        // *should* result in unique ids
        if (displays.size > 1 && selectedLayout != SecondaryDisplayLayout.NONE.int) {
            val current = emulationActivity.secondaryDisplayManager.currentDisplayId
            chooserMenu.isVisible = true
            displays.forEachIndexed { index, display ->
                chooserMenu?.subMenu?.add(
                    R.id.menu_secondary_management_display_group,
                    display.displayId,
                    index,
                    "Display ${display.displayId} - ${display.name}"
                )?.apply {
                    isChecked = (display.displayId == current)
                }
            }
            chooserMenu.subMenu?.setGroupCheckable(
                R.id.menu_secondary_management_display_group,
                true,
                true
            )
        }

        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_enable_secondary_layout -> {
                    if (!it.isChecked) {
                        screenAdjustmentUtil.enableSecondaryDisplay(selectedLayout)
                    } else {
                        screenAdjustmentUtil.disableSecondaryDisplay()
                    }
                    emulationActivity.secondaryDisplayManager.updateDisplay()
                    showSecondaryScreenLayoutMenu() // reopen menu to get new behaviors
                    true
                }

                R.id.menu_secondary_layout_opposite -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.REVERSE_PRIMARY.int
                    )
                    true
                }

                R.id.menu_secondary_layout_top -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.TOP_SCREEN.int
                    )
                    true
                }

                R.id.menu_secondary_layout_bottom -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.BOTTOM_SCREEN.int
                    )
                    true
                }

                R.id.menu_secondary_layout_side_by_side -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.SIDE_BY_SIDE.int
                    )
                    true
                }

                R.id.menu_secondary_layout_hybrid -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.HYBRID.int
                    )
                    true
                }

                R.id.menu_secondary_layout_original -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.ORIGINAL.int
                    )
                    true
                }

                R.id.menu_secondary_layout_largescreen -> {
                    screenAdjustmentUtil.changeSecondaryOrientation(
                        SecondaryDisplayLayout.LARGE_SCREEN.int
                    )
                    true
                }

                R.id.menu_secondary_choose -> {
                    true
                }

                else -> {
                    // display ID selection
                    // If we are clicking on a menu item that isn't one of the options above, it must
                    // be one of the dynamically generated menu items added to the secondary display
                    // choice list.
                    emulationActivity.secondaryDisplayManager.preferredDisplayId = it.itemId
                    emulationActivity.secondaryDisplayManager.updateDisplay()
                    true
                }
            }
        }
        popupMenu.show()
    }

    /**
     * Applies the edit mode selected in one of the two toolbar groups. [checkedId] is the id of
     * the checked button; the control modes (Move/Resize/Opacity) drive [InputOverlay.EditAction]
     * while the screen/background modes drive [InputOverlay.ScreenEditMode].
     */
    private fun applyEditMode(checkedId: Int) {
        val overlay = binding.surfaceInputOverlay
        val mode = when (checkedId) {
            R.id.btn_edit_smove -> InputOverlay.ScreenEditMode.SCREEN_MOVE
            R.id.btn_edit_ssize -> InputOverlay.ScreenEditMode.SCREEN_SIZE
            R.id.btn_edit_bg_move -> InputOverlay.ScreenEditMode.BG_MOVE
            R.id.btn_edit_bg_size -> InputOverlay.ScreenEditMode.BG_SIZE
            else -> InputOverlay.ScreenEditMode.NONE
        }
        overlay.setScreenEditMode(mode)
        if (mode == InputOverlay.ScreenEditMode.NONE) {
            overlay.setEditAction(
                when (checkedId) {
                    R.id.btn_edit_resize -> InputOverlay.EditAction.RESIZE
                    R.id.btn_edit_opacity -> InputOverlay.EditAction.OPACITY
                    else -> InputOverlay.EditAction.MOVE
                }
            )
        } else {
            // The screens and the background need the custom layout.
            ensureCustomLayout()
            if (mode == InputOverlay.ScreenEditMode.SCREEN_MOVE ||
                mode == InputOverlay.ScreenEditMode.SCREEN_SIZE
            ) {
                overlay.setScreenOutlines(customScreenRects(), -1)
            }
        }
    }

    /** Selects "Move" in the toolbar and drops the screen/background modes. */
    private fun resetEditMode() {
        syncingEditButtons = true
        binding.editScreenGroup.clearChecked()
        binding.editActionGroup.check(R.id.btn_edit_move)
        syncingEditButtons = false
        applyEditMode(R.id.btn_edit_move)
    }

    /** Drag the toolbar by the handle (or any part that is not a button). */
    private fun onEditToolbarTouch(toolbar: View, event: MotionEvent): Boolean {
        val parent = toolbar.parent as? View ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingEditToolbar = true
                editToolbarTouchX = event.rawX
                editToolbarTouchY = event.rawY
                editToolbarStartX = toolbar.x
                editToolbarStartY = toolbar.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!draggingEditToolbar) return false
                moveEditToolbar(
                    toolbar,
                    parent,
                    editToolbarStartX + event.rawX - editToolbarTouchX,
                    editToolbarStartY + event.rawY - editToolbarTouchY
                )
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!draggingEditToolbar) return false
                draggingEditToolbar = false
                saveEditToolbarPosition(toolbar, parent)
                return true
            }
        }
        return false
    }

    /** Moves the toolbar and keeps it completely on the screen. */
    private fun moveEditToolbar(toolbar: View, parent: View, x: Float, y: Float) {
        val maxX = (parent.width - toolbar.width).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - toolbar.height).coerceAtLeast(0).toFloat()
        toolbar.x = x.coerceIn(0f, maxX)
        toolbar.y = y.coerceIn(0f, maxY)
    }

    private fun editToolbarOrientation(): String =
        if (contextOrApp().resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        ) {
            "_portrait"
        } else {
            "_landscape"
        }

    /** The fragment context when attached, otherwise the application one (still correct). */
    private fun contextOrApp(): Context = context ?: CitraApplication.appContext

    /** The position is stored as a fraction of the screen size. */
    private fun saveEditToolbarPosition(toolbar: View, parent: View) {
        if (parent.width <= 0 || parent.height <= 0) return
        val suffix = editToolbarOrientation()
        preferences.edit()
            .putFloat("edit_toolbar${suffix}_X", (toolbar.x / parent.width).coerceIn(0f, 1f))
            .putFloat("edit_toolbar${suffix}_Y", (toolbar.y / parent.height).coerceIn(0f, 1f))
            .apply()
    }

    private fun restoreEditToolbarPosition() {
        val toolbar = binding.editToolbar
        val parent = toolbar.parent as? View ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val suffix = editToolbarOrientation()
        val savedX = preferences.getFloat("edit_toolbar${suffix}_X", Float.NaN)
        val savedY = preferences.getFloat("edit_toolbar${suffix}_Y", Float.NaN)
        if (savedX.isNaN() || savedY.isNaN()) return
        moveEditToolbar(toolbar, parent, savedX * parent.width, savedY * parent.height)
    }

    private val minScreenSizePx = 50

    private fun scheduleScreenPush() {
        if (screenPushPending) return
        screenPushPending = true
        // Applying a rectangle recomputes the framebuffer layout, so the preview is limited to a
        // few updates per second while dragging.
        screenEditHandler.postDelayed(pushScreenRunnable, 120L)
    }

    /**
     * Saves the selected screen in this game's own settings and applies it to the running game.
     * The global values in config.ini are left alone, so every game keeps its own layout.
     */
    private fun pushSelectedScreen() {
        val index = selectedScreen
        if (index < 0 || index >= workRects.size) return
        val rect = workRects[index]
        val portrait = NativeLibrary.isPortraitMode()
        val top = index == 0
        saveScreenRect(top, portrait, rect)
        NativeLibrary.setGameScreenRect(
            portrait,
            top,
            rect.left,
            rect.top,
            rect.width(),
            rect.height()
        )
    }

    /** Per game key prefix of one screen, e.g. "screenRect_landscape_top_". */
    private fun screenRectPrefix(top: Boolean, portrait: Boolean): String =
        "screenRect_" + (if (portrait) "portrait_" else "landscape_") +
            (if (top) "top_" else "bottom_")

    /** Stores one screen of the custom layout for this game. */
    private fun saveScreenRect(top: Boolean, portrait: Boolean, rect: Rect) {
        val prefix = screenRectPrefix(top, portrait)
        overlayPrefs.edit()
            .putInt(prefix + "x", rect.left)
            .putInt(prefix + "y", rect.top)
            .putInt(prefix + "width", rect.width())
            .putInt(prefix + "height", rect.height())
            .apply()
    }

    /** The rectangle saved for this game, or null when this screen was never edited in it. */
    private fun savedScreenRect(top: Boolean, portrait: Boolean): Rect? {
        val prefix = screenRectPrefix(top, portrait)
        if (!overlayPrefs.contains(prefix + "x")) return null
        val x = overlayPrefs.getInt(prefix + "x", 0)
        val y = overlayPrefs.getInt(prefix + "y", 0)
        return Rect(
            x,
            y,
            x + overlayPrefs.getInt(prefix + "width", 0),
            y + overlayPrefs.getInt(prefix + "height", 0)
        )
    }

    /**
     * Hands the screens saved for this game to the core before it boots. The core keeps them while
     * the game runs, so the config.ini reads done while playing (NativeLibrary.reloadSettings())
     * do not reset them. Screens without a saved rectangle keep their global config.ini value.
     */
    private fun applySavedScreenRects() {
        NativeLibrary.clearGameScreenRects()
        for (portrait in booleanArrayOf(false, true)) {
            for (top in booleanArrayOf(true, false)) {
                val rect = savedScreenRect(top, portrait) ?: continue
                NativeLibrary.setGameScreenRect(
                    portrait,
                    top,
                    rect.left,
                    rect.top,
                    rect.width(),
                    rect.height()
                )
            }
        }
    }

    /** The two screen rectangles of the custom layout, in view pixels (top, then bottom). */
    private fun customScreenRects(): List<Rect> = listOf(screenRect(true), screenRect(false))

    /**
     * One screen of the custom layout, in view pixels: the rectangle saved for this game, or the
     * global one from config.ini when the game has none yet, so a game starts from the global
     * layout and is independent from it after the first edit.
     */
    private fun screenRect(top: Boolean): Rect {
        val portrait = NativeLibrary.isPortraitMode()
        savedScreenRect(top, portrait)?.let { return it }
        val x: Int
        val y: Int
        val width: Int
        val height: Int
        if (portrait) {
            x = if (top) IntSetting.PORTRAIT_TOP_X.int else IntSetting.PORTRAIT_BOTTOM_X.int
            y = if (top) IntSetting.PORTRAIT_TOP_Y.int else IntSetting.PORTRAIT_BOTTOM_Y.int
            width = if (top) {
                IntSetting.PORTRAIT_TOP_WIDTH.int
            } else {
                IntSetting.PORTRAIT_BOTTOM_WIDTH.int
            }
            height = if (top) {
                IntSetting.PORTRAIT_TOP_HEIGHT.int
            } else {
                IntSetting.PORTRAIT_BOTTOM_HEIGHT.int
            }
        } else {
            x = if (top) IntSetting.LANDSCAPE_TOP_X.int else IntSetting.LANDSCAPE_BOTTOM_X.int
            y = if (top) IntSetting.LANDSCAPE_TOP_Y.int else IntSetting.LANDSCAPE_BOTTOM_Y.int
            width = if (top) {
                IntSetting.LANDSCAPE_TOP_WIDTH.int
            } else {
                IntSetting.LANDSCAPE_BOTTOM_WIDTH.int
            }
            height = if (top) {
                IntSetting.LANDSCAPE_TOP_HEIGHT.int
            } else {
                IntSetting.LANDSCAPE_BOTTOM_HEIGHT.int
            }
        }
        return Rect(x, y, x + width, y + height)
    }

    private fun isCustomLayout(): Boolean =
        if (NativeLibrary.isPortraitMode()) {
            IntSetting.PORTRAIT_SCREEN_LAYOUT.int == PortraitScreenLayout.CUSTOM_PORTRAIT_LAYOUT.int
        } else {
            IntSetting.SCREEN_LAYOUT.int == ScreenLayout.CUSTOM_LAYOUT.int
        }

    /** Moving/resizing the screens needs the custom layout, so switch to it when needed. */
    private fun ensureCustomLayout() {
        if (!changeToCustomLayout()) return
        Toast.makeText(requireContext(), "Switched to the custom layout", Toast.LENGTH_SHORT).show()
        updateBackground()
    }

    /**
     * Switches the current orientation to the custom layout. Returns whether the setting changed;
     * the caller is responsible for refreshing whatever depends on the screen rectangles.
     */
    private fun changeToCustomLayout(): Boolean {
        if (isCustomLayout()) return false
        if (NativeLibrary.isPortraitMode()) {
            screenAdjustmentUtil.changePortraitOrientation(
                PortraitScreenLayout.CUSTOM_PORTRAIT_LAYOUT.int
            )
        } else {
            screenAdjustmentUtil.changeScreenOrientation(ScreenLayout.CUSTOM_LAYOUT.int)
        }
        return true
    }

    private fun openBackgroundPicker() {
        // Looked up every time, so the picker always belongs to the running activity and its result
        // has a callback to come back to.
        val picker = backgroundPicker()
        if (picker == null) {
            Log.info("[BG] no activity to open the picker from")
            return
        }
        picker.launch(arrayOf("image/*"))
    }

    /** Reads the image on a background thread, scaled down so it cannot run out of memory. */
    private fun loadBackground(uri: Uri, retry: Boolean = true) {
        if (_binding == null) {
            // No view right now (a rotation is in progress): the saved image is loaded by
            // restoreBackground() / onResume() instead.
            Log.info("[BG] no view yet for $uri")
            return
        }
        val text = uri.toString()
        // A load of the same image is already on its way (onViewCreated + onResume).
        if (text == loadingBackgroundUri) return
        loadingBackgroundUri = text
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeScaledBitmap(uri, 2048) }
                if (bitmap == null) {
                    // Keep the saved image: a failed read (permissions, provider quirks) must not
                    // wipe the choice, and the image that is already on screen stays there.
                    Log.info("[BG] could not decode $uri (retry=$retry)")
                    if (retry) {
                        // Providers can be slow to hand the document out right after the picker
                        // closes, so one retry is worth it. Only retry while this is still the
                        // image saved for the current orientation.
                        _binding?.root?.postDelayed(
                            {
                                if (_binding != null && savedBackgroundUri() == text) {
                                    loadBackground(uri, false)
                                }
                            },
                            600
                        )
                    } else if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            "Could not load the background image",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                if (savedBackgroundUri() != text) {
                    // The image was replaced or removed while it was being read.
                    Log.info("[BG] dropping $text, it is not the image of this orientation anymore")
                    return@launch
                }
                binding.backgroundImage.setBitmap(bitmap)
                shownBackgroundUri = text
                Log.info(
                    "[BG] bitmap loaded " + bitmap.width + "x" + bitmap.height +
                        " key=" + backgroundUriKey() + " uri=$text"
                )
                updateBackground()
            } finally {
                if (loadingBackgroundUri == text) loadingBackgroundUri = null
            }
        }
    }

    /**
     * Decodes the picked image. Several strategies are tried because content providers give
     * unreliable streams for documents that were picked earlier: a plain stream, then a file
     * descriptor, then the file on disk for the "external storage" provider.
     */
    private fun decodeScaledBitmap(uri: Uri, maxSide: Int): Bitmap? {
        val context = CitraApplication.appContext
        val resolver = context.contentResolver

        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                }
                resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }?.let { return it }
            }
            Log.info("[BG] stream decode failed for $uri ($bounds)")
        } catch (e: Exception) {
            Log.info("[BG] stream decode failed for $uri: $e")
        }

        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
            }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                }
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                }?.let { return it }
            }
            Log.info("[BG] descriptor decode failed for $uri ($bounds)")
        } catch (e: Exception) {
            Log.info("[BG] descriptor decode failed for $uri: $e")
        }

        val path = documentFilePath(uri)
            ?: return null
        Log.info("[BG] falling back to the file at $path")
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            }
            return BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            Log.info("[BG] file decode failed for $path: $e")
        }
        return null
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxSide) sample *= 2
        return sample
    }

    /**
     * Path on disk of a document picked from the "external storage" provider
     * (content://com.android.externalstorage.documents/document/primary%3ADir%2Ffile.png).
     * Used as a last resort when the provider refuses to open the picked document again.
     */
    private fun documentFilePath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = uri.lastPathSegment ?: return null
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) return null
        val relative = parts[1]
        val root = Environment.getExternalStorageDirectory().path
        return when (parts[0]) {
            "primary" -> "$root/$relative"
            "home" -> "$root/Documents/$relative"
            else -> null
        }
    }

    /** The background image is stored per orientation, with the old global key as fallback. */
    private fun backgroundUriKey(): String = "bgUri" + editToolbarOrientation()

    /**
     * The image to show in this orientation: its own one, then the one of the other orientation
     * (so a single image still shows everywhere), then the key used before images were split.
     *
     * An empty value means the image was removed in this orientation, which must not fall back to
     * another one: the removal would otherwise bring the image of the other orientation back.
     */
    private fun savedBackgroundUri(): String? {
        val suffix = editToolbarOrientation()
        val other = if (suffix == "_portrait") "_landscape" else "_portrait"
        val own = overlayPrefs.getString("bgUri$suffix", null)
        if (own != null) return if (own.isEmpty()) null else own
        val others = overlayPrefs.getString("bgUri$other", null)
        if (others != null && others.isNotEmpty()) return others
        val legacy = overlayPrefs.getString("bgUri", null)
        return if (legacy.isNullOrEmpty()) null else legacy
    }

    private fun restoreBackground() {
        val saved = savedBackgroundUri()
        Log.info("[BG] restore uri=$saved key=" + backgroundUriKey())
        if (saved == null) return
        loadBackground(Uri.parse(saved))
    }

    /**
     * Drops the background image of this game in the current orientation, so the image goes away
     * right now. The key is emptied instead of being removed, because an image stored for the
     * other orientation would otherwise be picked up again by the fallback in
     * [savedBackgroundUri] and stay on screen. The other orientation, if it has an image of its
     * own, keeps it.
     */
    private fun removeBackground() {
        val key = backgroundUriKey()
        overlayPrefs.edit().putString(key, "").apply()
        // A read that is still running must not put the image back once it finishes.
        shownBackgroundUri = null
        loadingBackgroundUri = null
        _binding?.backgroundImage?.setBitmap(null)
        updateBackground()
        Log.info("[BG] image removed for $key")
    }

    /**
     * Called whenever the fragment is resumed: coming back from the image picker, from another
     * activity or from a rotation must always leave the image saved for this orientation on
     * screen. The pick callback can be too late for the view that handled the pick (a rotation
     * while the picker was open recreates it), so the state is checked again here.
     */
    private fun reloadBackgroundIfNeeded() {
        val saved = savedBackgroundUri() ?: return
        if (saved == shownBackgroundUri) return
        Log.info("[BG] resume: loading $saved (shown=$shownBackgroundUri)")
        loadBackground(Uri.parse(saved))
    }

    /**
     * Shows the background (with holes for the screens) when there is an image, hides it otherwise.
     * Call it whenever the image, the layout or the screen rectangles change.
     *
     * The holes are the rectangles of the custom layout, so the image only makes sense while that
     * layout is active. The layout is configured per orientation, and starting a game can change
     * the orientation, which used to make the image disappear; switch the current orientation to
     * the custom layout instead so the image keeps showing. [ensureLayout] is false when the user
     * just picked another layout from the menu: the choice is respected and the image is hidden.
     */
    private fun updateBackground(ensureLayout: Boolean = true) {
        val view = _binding?.backgroundImage ?: return
        if (!view.hasBitmap) {
            Log.info("[BG] hidden: no image loaded")
            view.visibility = View.GONE
            return
        }
        if (!isCustomLayout()) {
            // changeToCustomLayout() applies the setting and updates the framebuffer, which can
            // call back into this method. The guard keeps that from recursing.
            Log.info(
                "[BG] not custom layout. portrait=" + NativeLibrary.isPortraitMode() +
                    " screen layout=" + IntSetting.SCREEN_LAYOUT.int +
                    " portrait layout=" + IntSetting.PORTRAIT_SCREEN_LAYOUT.int +
                    " ensureLayout=" + ensureLayout + " switching=" + switchingToCustomLayout
            )
            if (ensureLayout && !switchingToCustomLayout) {
                switchingToCustomLayout = true
                try {
                    if (changeToCustomLayout() && isAdded) {
                        Toast.makeText(
                            requireContext(),
                            "Background image needs the custom layout, switched to it",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } finally {
                    switchingToCustomLayout = false
                }
            }
            if (!isCustomLayout()) {
                // Could not switch: hide the image instead of covering the game with it.
                Log.info(
                    "[BG] hidden: layout is not the custom one. " +
                        "Portrait: " + NativeLibrary.isPortraitMode() +
                        " screen layout: " + IntSetting.SCREEN_LAYOUT.int +
                        " portrait layout: " + IntSetting.PORTRAIT_SCREEN_LAYOUT.int
                )
                view.visibility = View.GONE
                return
            }
        }
        val rects = customScreenRects()
        view.setHoles(rects)
        applyBackgroundTransform()
        view.visibility = View.VISIBLE
        Log.info(
            "[BG] shown. img=" + view.bitmapWidth + "x" + view.bitmapHeight +
                " view=" + view.width + "x" + view.height +
                " portrait=" + NativeLibrary.isPortraitMode() +
                " suffix=" + editToolbarOrientation() +
                " t=" + view.translateX + "," + view.translateY +
                " s=" + view.scaleX2 + "," + view.scaleY2 +
                " holes=" + rects
        )
    }

    private fun saveBackgroundTransform() {
        val view = binding.backgroundImage
        val suffix = editToolbarOrientation()
        overlayPrefs.edit()
            .putFloat("bgTx$suffix", view.translateX)
            .putFloat("bgTy$suffix", view.translateY)
            .putFloat("bgSx$suffix", view.scaleX2)
            .putFloat("bgSy$suffix", view.scaleY2)
            .apply()
    }

    private fun applyBackgroundTransform() {
        val suffix = editToolbarOrientation()
        val tx = overlayPrefs.getFloat("bgTx$suffix", 0f)
        val ty = overlayPrefs.getFloat("bgTy$suffix", 0f)
        val sx = overlayPrefs.getFloat("bgSx$suffix", 1f)
        val sy = overlayPrefs.getFloat("bgSy$suffix", 1f)
        Log.info("[BG] transform suffix=$suffix tx=$tx ty=$ty sx=$sx sy=$sy")
        binding.backgroundImage.setTransform(tx, ty, sx, sy)
    }

    private fun editControlsPlacement() {
        if (binding.surfaceInputOverlay.isInEditMode) {
            binding.doneControlConfig.visibility = View.GONE
            binding.editToolbar.visibility = View.GONE
            resetEditMode()
            binding.surfaceInputOverlay.setIsInEditMode(false)
        } else {
            binding.doneControlConfig.visibility = View.VISIBLE
            binding.editToolbar.visibility = View.VISIBLE
            binding.editToolbar.post { restoreEditToolbarPosition() }
            resetEditMode()
            binding.surfaceInputOverlay.setIsInEditMode(true)
        }
    }

    private fun orientationSuffix(): String = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) "-Portrait" else ""

    private fun showToggleControlsDialog() {
        val orient = orientationSuffix()	
        val editor = overlayPrefs.edit()
        val enabledButtons = BooleanArray(19)
        enabledButtons.forEachIndexed { i: Int, _: Boolean ->
            // Buttons that are disabled by default
            var defaultValue = true
            when (i) {
                // TODO: Remove these magic numbers
                6, 7, 12, 13, 14, 15, 16, 17, 18 -> defaultValue = false
            }
            val perOrientKey = "buttonToggle${i}$orient"
            enabledButtons[i] = if (overlayPrefs.contains(perOrientKey)) {
                overlayPrefs.getBoolean(perOrientKey, defaultValue)
            } else {
                overlayPrefs.getBoolean("buttonToggle$i", defaultValue)
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_toggle_controls)
            .setMultiChoiceItems(
                R.array.n3dsButtons,
                enabledButtons
            ) { _: DialogInterface?, indexSelected: Int, isChecked: Boolean ->
                editor.putBoolean("buttonToggle${indexSelected}$orient", isChecked)
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                editor.apply()
                binding.surfaceInputOverlay.refreshControls()
            }
            .show()

        // Band-aid fix for strange dialog flickering issue
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayMetrics = requireActivity().windowManager.currentWindowMetrics
            val displayHeight = displayMetrics.bounds.height()
            // The layout visually breaks if we try to set the height directly rather than like this.
            // Why? Fuck you, that's why!
            val newAttributes = dialog.window?.attributes
            newAttributes?.height = (displayHeight * 0.85f).toInt()
            dialog.window?.attributes = newAttributes
        }
    }

    private fun showAdjustScaleDialog(target: String) {
        val orient = orientationSuffix()
        val perOrientTarget = target + orient
        val sliderBinding = DialogSliderBinding.inflate(layoutInflater)

        sliderBinding.apply {
            val sliderStart = 50
            val sliderMin = 0
            val sliderMax = 150
            slider.valueFrom = sliderMin.toFloat()
            slider.valueTo = sliderMax.toFloat()
            val current = if (overlayPrefs.contains(perOrientTarget)) overlayPrefs.getInt(perOrientTarget, sliderStart) else overlayPrefs.getInt(target, sliderStart)
            slider.value = current
                .toFloat()
                .coerceIn(slider.valueFrom..slider.valueTo)
            @SuppressLint("SetTextI18n")
            textValue.setText((slider.value + sliderStart).toInt().toString())
            textValue.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    val value = s.toString().toIntOrNull()
                    val realValue = value?.minus(sliderStart)
                    if (realValue == null || realValue < sliderMin || realValue > sliderMax) {
                        textInput.error = "Inappropriate Value"
                    } else {
                        textInput.error = null
                        slider.value = realValue.toFloat()
                    }
                }

                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            })
            slider.addOnChangeListener(
                Slider.OnChangeListener {
                        slider: Slider,
                        progress: Float,
                        _: Boolean
                    ->
                    if (textValue.text.toString() !=
                        (slider.value + sliderStart).toInt().toString()
                    ) {
                        textValue.setText((slider.value + sliderStart).toInt().toString())
                        textValue.setSelection(textValue.length())
                        setControlScale(slider.value.toInt(), perOrientTarget)
                    }
                }
            )
            textInput.suffixText = "%"
        }
        val previousProgress = sliderBinding.slider.value.toInt()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emulation_control_scale)
            .setView(sliderBinding.root)
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                setControlScale(previousProgress, perOrientTarget)
            }
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                setControlScale(sliderBinding.slider.value.toInt(), perOrientTarget)
            }
            .setNeutralButton(R.string.slider_default) { _: DialogInterface?, _: Int ->
                setControlScale(50, perOrientTarget)
            }
            .show()
    }

    private fun showAdjustOpacityDialog() {
        val orient = orientationSuffix()
        val perOrientKey = "controlOpacity$orient"
        val sliderBinding = DialogSliderBinding.inflate(layoutInflater)

        sliderBinding.apply {
            slider.valueFrom = 0f
            slider.valueTo = 100f
            val cur = if (overlayPrefs.contains(perOrientKey)) overlayPrefs.getInt(perOrientKey, 50) else overlayPrefs.getInt("controlOpacity", 50)
            slider.value = cur.toFloat()
            textValue.setText(slider.value.toInt().toString())

            textValue.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    val value = s.toString().toIntOrNull()
                    if (value == null || value < slider.valueFrom || value > slider.valueTo) {
                        textInput.error = "Inappropriate Value"
                    } else {
                        textInput.error = null
                        slider.value = value.toFloat()
                    }
                }

                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            })

            slider.addOnChangeListener { _: Slider, value: Float, _: Boolean ->

                if (textValue.text.toString() != slider.value.toInt().toString()) {
                    textValue.setText(slider.value.toInt().toString())
                    textValue.setSelection(textValue.length())
                    setControlOpacity(slider.value.toInt())
                }
            }

            textInput.suffixText = "%"
        }
		val previousProgress = sliderBinding.slider.value.toInt()
		
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.emulation_control_opacity)
			.setView(sliderBinding.root)
			.setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
				setControlOpacity(previousProgress, perOrientKey)
			}
			.setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
				setControlOpacity(sliderBinding.slider.value.toInt(), perOrientKey)
			}
			.setNeutralButton(R.string.slider_default) { _: DialogInterface?, _: Int ->
				setControlOpacity(50, perOrientKey)
			}
			.show()
    }

    private fun setControlScale(scale: Int, target: String) {
        overlayPrefs.edit()
            .putInt(target, scale)
            .apply()
        binding.surfaceInputOverlay.refreshControls()
    }

    private fun resetScale(target: String) {
        overlayPrefs.edit().putInt(
            target,
            50
        ).apply()
    }

	private fun resetAllScales() {
		val orient = orientationSuffix()
		resetScale("controlScale$orient")
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_A + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_B + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_X + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_Y + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.TRIGGER_L + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.TRIGGER_R + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZL + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_ZR + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_START + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_SELECT + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.DPAD + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.STICK_LEFT + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.STICK_C + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_HOME + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_SWAP + orient)
		resetScale("controlScale-" + NativeLibrary.ButtonType.BUTTON_TURBO + orient)
		resetScale("controlScale-" + Hotkey.COMBO_BUTTON.button + orient)
		resetScale("controlScale-tatacon$orient")

		// Reset aspect per orientation and clean up the legacy global key
		overlayPrefs.edit()
			.remove("controlAspect-tatacon$orient")
			.remove("controlAspect-tatacon")
			.remove("controlOpacity$orient")
			.apply()

		binding.surfaceInputOverlay.refreshControls()
	}

    private fun setControlOpacity(opacity: Int, target: String = "controlOpacity") {
		overlayPrefs.edit()
			.putInt(target, opacity)
			.apply()
		binding.surfaceInputOverlay.refreshControls()
    }

    private fun showResetOverlayDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.emulation_touch_overlay_reset))
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                resetInputOverlay()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetInputOverlay() {
        resetAllScales()
		val e = overlayPrefs.edit()
		overlayPrefs.all.keys.filter { it.startsWith("controlOpacity-") }.forEach { e.remove(it) }
		e.apply()
        overlayPrefs.edit()
            .putInt("controlOpacity", 50)
            .apply()

        val editor = overlayPrefs.edit()
        // TODO: This code sucks balls. We need to do this differently. -OS
        for (i in 0 until 19) {
            var defaultValue = true
            when (i) {
                6, 7, 12, 13, 14, 15, 16, 17, 18 -> defaultValue = false
            }
            editor.putBoolean("buttonToggle$i", defaultValue)
        }
        editor.apply()

        binding.surfaceInputOverlay.resetButtonPlacement()
    }

    fun updateShowPerformanceOverlay() {
        if (perfStatsUpdater != null) {
            perfStatsUpdateHandler.removeCallbacks(perfStatsUpdater!!)
        }

        if (BooleanSetting.PERF_OVERLAY_ENABLE.boolean) {
            @Suppress("UnusedVariable")
            val systemFps = 0
            val fps = 1
            val speed = 2
            val frametime = 3
            val timeSvc = 4
            val timeIpc = 5
            val timeGpu = 6
            val timeSwap = 7
            val timeRem = 8
            perfStatsUpdater = Runnable {
                val sb = StringBuilder()
                val perfStats = NativeLibrary.getPerfStats()
                val dividerString = "\u00A0\u2502 "
                if (perfStats[fps] > 0) {
                    if (BooleanSetting.PERF_OVERLAY_SHOW_FPS.boolean) {
                        sb.append(String.format("FPS:\u00A0%d", (perfStats[fps] + 0.5).toInt()))
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_FRAMETIME.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        sb.append(
                            String.format(
                                "Frame:\u00A0%.1fms (GPU: [CMD:\u00A0%.1fms SWP:\u00A0%.1fms] IPC:\u00A0%.1fms SVC:\u00A0%.1fms Rem:\u00A0%.1fms)",
                                (perfStats[frametime] * 1000.0f).toFloat(),
                                (perfStats[timeGpu] * 1000.0f).toFloat(),
                                (perfStats[timeSwap] * 1000.0f).toFloat(),
                                (perfStats[timeIpc] * 1000.0f).toFloat(),
                                (perfStats[timeSvc] * 1000.0f).toFloat(),
                                (perfStats[timeRem] * 1000.0f).toFloat()
                            )
                        )
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_SPEED.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        sb.append(
                            String.format(
                                "Speed:\u00A0%d%%",
                                (perfStats[speed] * 100.0 + 0.5).toInt()
                            )
                        )
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_APP_RAM_USAGE.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        val appRamUsage =
                            File("/proc/self/statm").readLines()[0].split(' ')[1].toLong() * 4096 /
                                1000000
                        sb.append("Process\u00A0RAM:\u00A0$appRamUsage\u00A0MB")
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_AVAILABLE_RAM.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        context?.let { ctx ->
                            val activityManager =
                                ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            val memInfo = ActivityManager.MemoryInfo()
                            activityManager.getMemoryInfo(memInfo)
                            val megabyteBytes = 1048576L
                            val availableRam = memInfo.availMem / megabyteBytes
                            sb.append("Available\u00A0RAM:\u00A0$availableRam\u00A0MB")
                        }
                    }

                    if (BooleanSetting.PERF_OVERLAY_SHOW_BATTERY_TEMP.boolean) {
                        if (sb.isNotEmpty()) sb.append(dividerString)
                        val batteryTemp = getBatteryTemperature()
                        val tempF = celsiusToFahrenheit(batteryTemp)
                        sb.append(String.format("%.1f°C/%.1f°F", batteryTemp, tempF))
                    }

                    if (BooleanSetting.PERF_OVERLAY_BACKGROUND.boolean) {
                        binding.performanceOverlayShowText.setBackgroundResource(
                            R.color.citra_transparent_black
                        )
                    } else {
                        binding.performanceOverlayShowText.setBackgroundResource(0)
                    }

                    binding.performanceOverlayShowText.text = sb.toString()
                }
                perfStatsUpdateHandler.postDelayed(perfStatsUpdater!!, 1000)
            }
            perfStatsUpdateHandler.post(perfStatsUpdater!!)
            binding.performanceOverlayShowText.visibility = View.VISIBLE
        } else {
            binding.performanceOverlayShowText.visibility = View.GONE
        }
    }

    private fun updateStatsPosition(position: Int) {
        val params =
            binding.performanceOverlayShowText.layoutParams as CoordinatorLayout.LayoutParams
        val padding = (20 * resources.displayMetrics.density).toInt() // 20dp
        params.setMargins(padding, 0, padding, 0)

        when (position) {
            0 -> {
                params.gravity = (Gravity.TOP or Gravity.START)
            }

            1 -> {
                params.gravity = (Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            }

            2 -> {
                params.gravity = (Gravity.TOP or Gravity.END)
            }

            3 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.START)
            }

            4 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            }

            5 -> {
                params.gravity = (Gravity.BOTTOM or Gravity.END)
            }
        }

        binding.performanceOverlayShowText.layoutParams = params
    }

    private fun getBatteryTemperature(): Float {
        try {
            val batteryIntent =
                requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            // Temperature in tenths of a degree Celsius
            val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            // Convert to degrees Celsius
            return temperature / 10.0f
        } catch (e: Exception) {
            return 0.0f
        }
    }

    private fun celsiusToFahrenheit(celsius: Float): Float = (celsius * 9 / 5) + 32

    override fun surfaceCreated(holder: SurfaceHolder) {
        // We purposely don't do anything here.
        // All work is done in surfaceChanged, which we are guaranteed to get even for surface creation.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.debug("[EmulationFragment] Surface changed. Resolution: " + width + "x" + height)
        emulationState.newSurface(holder.surface)
        _binding?.backgroundImage?.post { updateBackground() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        emulationState.clearSurface()
    }

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)
        NativeLibrary.doFrame()
    }

    private val applyCutoutInsets: (View, WindowInsetsCompat) -> WindowInsetsCompat = {
            v,
            windowInsets
        ->
        val cutout = windowInsets.displayCutout
        val mlp = v.layoutParams as ViewGroup.MarginLayoutParams?
        mlp?.setMargins(
            cutout?.safeInsetLeft ?: 0,
            cutout?.safeInsetTop ?: 0,
            cutout?.safeInsetRight ?: 0,
            cutout?.safeInsetBottom ?: 0
        )
        windowInsets
    }

    private fun setInsets() {
        if (!BooleanSetting.EXPAND_TO_CUTOUT_AREA.boolean) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.surfaceEmulation, applyCutoutInsets)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.surfaceInputOverlay, applyCutoutInsets)
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.performanceOverlayShowText,
            applyCutoutInsets
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.inGameMenu, applyCutoutInsets)
    }

    private class EmulationState(private val gamePath: String) {
        private var state: State
        private var surface: Surface? = null

        init {
            // Starting state is stopped.
            state = State.STOPPED
        }

        @get:Synchronized
        val isStopped: Boolean
            get() = state == State.STOPPED

        @get:Synchronized
        val isPaused: Boolean
            // Getters for the current state
            get() = state == State.PAUSED

        @get:Synchronized
        val isRunning: Boolean
            get() = state == State.RUNNING

        @Synchronized
        fun stop() {
            if (state != State.STOPPED) {
                Log.debug("[EmulationFragment] Stopping emulation.")
                state = State.STOPPED
                NativeLibrary.stopEmulation()
            } else {
                Log.warning("[EmulationFragment] Stop called while already stopped.")
            }
        }

        // State changing methods
        @Synchronized
        fun pause() {
            if (state != State.PAUSED) {
                state = State.PAUSED
                Log.debug("[EmulationFragment] Pausing emulation.")

                NativeLibrary.pauseEmulation()
                NativeLibrary.playTimeManagerStop()
            } else {
                Log.warning("[EmulationFragment] Pause called while already paused.")
            }
        }

        @Synchronized
        fun unpause() {
            if (state != State.RUNNING) {
                state = State.RUNNING
                Log.debug("[EmulationFragment] Unpausing emulation.")

                NativeLibrary.unPauseEmulation()
                NativeLibrary.playTimeManagerStart(NativeLibrary.playTimeManagerGetCurrentTitleId())
            } else {
                Log.warning("[EmulationFragment] Unpause called while already running.")
            }
        }

        @Synchronized
        fun run(isActivityRecreated: Boolean) {
            if (isActivityRecreated) {
                if (NativeLibrary.isRunning()) {
                    state = State.PAUSED
                }
            } else {
                Log.debug("[EmulationFragment] activity resumed or fresh start")
            }

            // If the surface is set, run now. Otherwise, wait for it to get set.
            if (surface != null) {
                runWithValidSurface()
            }
        }

        // Surface callbacks
        @Synchronized
        fun newSurface(surface: Surface?) {
            this.surface = surface
            if (this.surface != null) {
                runWithValidSurface()
            }
        }

        @Synchronized
        fun clearSurface() {
            if (surface == null) {
                Log.warning("[EmulationFragment] clearSurface called, but surface already null.")
            } else {
                surface = null
                Log.debug("[EmulationFragment] Surface destroyed.")
                when (state) {
                    State.RUNNING -> {
                        NativeLibrary.surfaceDestroyed()
                        state = State.PAUSED
                    }

                    State.PAUSED -> {
                        NativeLibrary.surfaceDestroyed()
                        Log.warning("[EmulationFragment] Surface cleared while emulation paused.")
                    }

                    else -> {
                        Log.warning("[EmulationFragment] Surface cleared while emulation stopped.")
                    }
                }
            }
        }

        private fun runWithValidSurface() {
            NativeLibrary.surfaceChanged(surface!!)
            when (state) {
                State.STOPPED -> {
                    Thread({
                        Log.debug("[EmulationFragment] Starting emulation thread.")
                        NativeLibrary.run(gamePath)
                    }, "NativeEmulation").start()
                }

                State.PAUSED -> {
                    Log.debug("[EmulationFragment] Resuming emulation.")
                    unpause()
                }

                else -> {
                    Log.debug("[EmulationFragment] Bug, run called while already running.")
                }
            }
            state = State.RUNNING
        }

        private enum class State {
            STOPPED,
            RUNNING,
            PAUSED
        }
    }

    companion object {
        /** Key of the background picker in the activity's ActivityResultRegistry. */
        private const val BACKGROUND_PICK_KEY = "emulation_background_pick"

        private val perfStatsUpdateHandler = Handler(Looper.myLooper()!!)
        private var hasPrevious = false
    }
}