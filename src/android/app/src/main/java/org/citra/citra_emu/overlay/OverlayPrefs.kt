// SPDX-License-Identifier: GPL-2.0-or-later

package org.citra.citra_emu.overlay

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication

/**
 * Per-game storage for the touch overlay layout.
 *
 * The overlay settings (which controls are shown, scales, opacity and positions) live in a
 * SharedPreferences file of their own for each game. The first time a game is started its file
 * is created as a copy of the current global overlay settings, so every game starts with the
 * layout you had before and can be changed independently from then on.
 *
 * Everything that is not an overlay layout setting (button sliding, haptics, "isTouchEnabled",
 * ...) stays in the global preferences.
 */
object OverlayPrefs {
    private const val INIT_MARKER = "overlayPrefsInitialized"

    // "<button id>[-Portrait]-X" / "-Y", e.g. "700-Portrait-X"
    private val POSITION_KEY = Regex("^\\d+(-Portrait)?-[XY]$")

    private var gameKey: String? = null

    private val appContext: Context
        get() = CitraApplication.appContext

    /** The global (all games) preferences. */
    val global: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(appContext)

    /** The overlay preferences of the running game, or the global ones when no game is set. */
    val prefs: SharedPreferences
        get() {
            val key = gameKey ?: return global
            return forGame(key)
        }

    /** Call when a game is about to start, before the overlay is created. */
    fun setGame(titleId: Long, path: String) {
        val key = if (titleId != 0L) {
            String.format("%016X", titleId)
        } else {
            // Homebrew without a title id: fall back to something derived from the path.
            "P" + String.format("%08X", path.hashCode())
        }
        gameKey = key
        val perGame = forGame(key)
        if (!perGame.getBoolean(INIT_MARKER, false)) {
            copyOverlaySettings(global, perGame)
        }
    }

    private fun forGame(key: String): SharedPreferences =
        appContext.getSharedPreferences("overlay_$key", Context.MODE_PRIVATE)

    private fun isOverlayKey(key: String): Boolean =
        key.startsWith("buttonToggle") ||
            key.startsWith("controlScale") ||
            key == "controlOpacity" ||
            key == "OverlayInit" ||
            key.startsWith("tatacon") ||
            POSITION_KEY.matches(key)

    private fun copyOverlaySettings(from: SharedPreferences, to: SharedPreferences) {
        val editor = to.edit()
        for ((key, value) in from.all) {
            if (!isOverlayKey(key)) continue
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.putBoolean(INIT_MARKER, true)
        editor.apply()
    }
}
