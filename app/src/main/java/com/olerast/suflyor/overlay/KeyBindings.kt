package com.olerast.suflyor.overlay

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.olerast.suflyor.R
import com.olerast.suflyor.session.SessionEngine

enum class KeyAction(@StringRes val label: Int) {
    PAUSE(R.string.key_action_pause),
    BACK(R.string.key_action_back),
    FORWARD(R.string.key_action_forward);

    fun perform(engine: SessionEngine) = when (this) {
        PAUSE -> engine.togglePause()
        BACK -> engine.stepLine(-1)
        FORWARD -> engine.stepLine(1)
    }
}

/**
 * Which hardware key does what. Selfie remotes send volume keys or Enter, presentation clickers send Page Up/Down
 * or arrows, rings and headsets send media keys; all of them reach the accessibility service as key events.
 */
object KeyBindings {
    val DEFAULT: Map<Int, KeyAction> = mapOf(
        KeyEvent.KEYCODE_VOLUME_UP to KeyAction.PAUSE,
        KeyEvent.KEYCODE_VOLUME_DOWN to KeyAction.BACK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to KeyAction.PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY to KeyAction.PAUSE,
        KeyEvent.KEYCODE_MEDIA_PAUSE to KeyAction.PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK to KeyAction.PAUSE,
        KeyEvent.KEYCODE_SPACE to KeyAction.PAUSE,
        KeyEvent.KEYCODE_ENTER to KeyAction.PAUSE,
        KeyEvent.KEYCODE_DPAD_CENTER to KeyAction.PAUSE,
        KeyEvent.KEYCODE_BUTTON_A to KeyAction.PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT to KeyAction.FORWARD,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to KeyAction.FORWARD,
        KeyEvent.KEYCODE_PAGE_DOWN to KeyAction.FORWARD,
        KeyEvent.KEYCODE_DPAD_DOWN to KeyAction.FORWARD,
        KeyEvent.KEYCODE_DPAD_RIGHT to KeyAction.FORWARD,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to KeyAction.BACK,
        KeyEvent.KEYCODE_MEDIA_REWIND to KeyAction.BACK,
        KeyEvent.KEYCODE_PAGE_UP to KeyAction.BACK,
        KeyEvent.KEYCODE_DPAD_UP to KeyAction.BACK,
        KeyEvent.KEYCODE_DPAD_LEFT to KeyAction.BACK,
    )

    fun parse(s: String?): Map<Int, KeyAction> {
        if (s.isNullOrBlank()) return DEFAULT
        return s.split(',').mapNotNull { pair ->
            val (code, action) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val c = code.toIntOrNull() ?: return@mapNotNull null
            val a = KeyAction.entries.firstOrNull { it.name == action } ?: return@mapNotNull null
            c to a
        }.toMap()
    }

    fun format(map: Map<Int, KeyAction>): String = map.entries.joinToString(",") { "${it.key}:${it.value.name}" }

    fun isVolume(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN

    /** Names that are words get translated; labels printed on keys (Enter, Page Up, arrows, Play) stay as they are. */
    fun keyName(context: Context, code: Int): String = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> context.getString(R.string.key_name_volume_up)
        KeyEvent.KEYCODE_VOLUME_DOWN -> context.getString(R.string.key_name_volume_down)
        KeyEvent.KEYCODE_SPACE -> context.getString(R.string.key_name_space)
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_PAGE_UP -> "Page Up"
        KeyEvent.KEYCODE_PAGE_DOWN -> "Page Down"
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        KeyEvent.KEYCODE_DPAD_CENTER -> context.getString(R.string.key_name_dpad_center)
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
        KeyEvent.KEYCODE_MEDIA_NEXT -> context.getString(R.string.key_name_next_track)
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> context.getString(R.string.key_name_previous_track)
        KeyEvent.KEYCODE_HEADSETHOOK -> context.getString(R.string.key_name_headset)
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> context.getString(R.string.key_name_fast_forward)
        KeyEvent.KEYCODE_MEDIA_REWIND -> context.getString(R.string.key_name_rewind)
        KeyEvent.KEYCODE_BUTTON_A -> context.getString(R.string.key_name_gamepad_a)
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }
}
