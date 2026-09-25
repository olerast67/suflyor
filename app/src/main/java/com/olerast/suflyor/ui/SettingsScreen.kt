package com.olerast.suflyor.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.DisposableEffect
import com.olerast.suflyor.overlay.KeyAction
import com.olerast.suflyor.overlay.KeyBindings
import com.olerast.suflyor.overlay.PrompterTileService
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.olerast.suflyor.App
import com.olerast.suflyor.BuildConfig
import com.olerast.suflyor.R
import com.olerast.suflyor.script.SpeechLang

@Composable
fun SettingsScreen(
    readiness: Readiness,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
    onJournal: () -> Unit,
) {
    val app = App.instance
    val s = app.settings
    val context = LocalContext.current
    var lead by remember { mutableIntStateOf(s.leadWords) }
    var wordHighlight by remember { mutableStateOf(s.wordHighlight) }
    var hotwords by remember { mutableStateOf(s.useHotwords) }
    var speechLang by remember { mutableStateOf(s.speechLang) }
    var phraseMode by remember { mutableStateOf(s.phraseMode) }
    var maxWords by remember { mutableIntStateOf(s.maxWords) }
    var font by remember { mutableIntStateOf(s.fontSp) }
    var lines by remember { mutableIntStateOf(s.overlayLines) }
    var alpha by remember { mutableIntStateOf(s.overlayAlpha) }
    var diagnostics by remember { mutableStateOf(s.showDiagnostics) }
    var wpm by remember { mutableIntStateOf(s.autoScrollWpm) }
    var source by remember { mutableIntStateOf(s.audioSource) }
    var rate by remember { mutableIntStateOf(s.sampleRate) }
    var keyControl by remember { mutableStateOf(s.keyControl) }
    var volumeKeys by remember { mutableStateOf(s.volumeKeys) }
    var countdown by remember { mutableIntStateOf(s.countdownSec) }
    var autoRotate by remember { mutableStateOf(s.autoRotate) }
    @Suppress("UNUSED_VARIABLE")
    val keyRevision = KeyLearning.revision // re-read bindings after a key is assigned
    val bindings = s.keyBindings
    DisposableEffect(Unit) { onDispose { KeyLearning.action = null } }

    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(R.string.settings_title), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle(stringResource(R.string.settings_section_readiness))
            ReadyRow(
                ok = readiness.mic,
                title = stringResource(R.string.settings_mic_title),
                subtitle = stringResource(R.string.settings_mic_hint),
                action = stringResource(R.string.settings_action_allow),
                onAction = onRequestPermissions,
            )
            ReadyRow(
                ok = readiness.a11yRunning,
                title = stringResource(R.string.settings_a11y_title),
                subtitle = stringResource(
                    if (readiness.a11yEnabled && !readiness.a11yRunning) {
                        R.string.settings_a11y_not_running
                    } else {
                        R.string.settings_a11y_hint
                    },
                ),
                action = stringResource(R.string.settings_action_open),
                onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
            if (!readiness.a11yRunning) {
                Text(
                    stringResource(R.string.settings_a11y_restricted, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.settings_app_info),
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Accent,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).clickable {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },
                )
            }
            ReadyRow(
                ok = readiness.notifications,
                title = stringResource(R.string.settings_notifications_title),
                subtitle = stringResource(R.string.settings_notifications_hint),
                action = stringResource(R.string.settings_action_allow),
                onAction = onRequestPermissions,
            )

            SectionTitle(stringResource(R.string.settings_section_voice))
            // Auto names the language it picked for the current script, so a wrong guess is visible here.
            val detected = remember(speechLang, app.scripts.layoutVersion) { s.speechLangFor(app.scripts.document) }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.settings_speech_lang_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (speechLang == AUTO) {
                        stringResource(R.string.settings_speech_lang_auto_hint, stringResource(speechLangName(detected)))
                    } else {
                        stringResource(R.string.settings_speech_lang_fixed_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                )
            }
            Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AUTO to R.string.speech_lang_auto,
                    SpeechLang.RU.code to R.string.speech_lang_ru,
                    SpeechLang.EN.code to R.string.speech_lang_en,
                ).forEach { (value, label) ->
                    Pill(stringResource(label), speechLang == value) {
                        if (speechLang != value) {
                            speechLang = value
                            s.speechLang = value
                            // Word matching depends on the language, so the current script is laid out again.
                            app.scripts.relayout()
                        }
                    }
                }
            }
            StepperRow(
                stringResource(R.string.settings_lead_title),
                pluralStringResource(R.plurals.settings_lead_value, lead, lead),
                stringResource(R.string.settings_lead_hint),
                onMinus = { lead = (lead - 1).coerceAtLeast(0); s.leadWords = lead },
                onPlus = { lead = (lead + 1).coerceAtMost(4); s.leadWords = lead },
            )
            ToggleRow(stringResource(R.string.settings_highlight_title), stringResource(R.string.settings_highlight_hint), wordHighlight) {
                wordHighlight = it
                s.wordHighlight = it
            }
            ToggleRow(stringResource(R.string.settings_hotwords_title), stringResource(R.string.settings_hotwords_hint), hotwords) {
                hotwords = it
                s.useHotwords = it
            }

            SectionTitle(stringResource(R.string.settings_section_text))
            ToggleRow(stringResource(R.string.settings_phrase_title), stringResource(R.string.settings_phrase_hint), phraseMode) {
                phraseMode = it
                s.phraseMode = it
                app.scripts.relayout()
            }
            StepperRow(
                stringResource(R.string.settings_max_words_title), stringResource(R.string.settings_max_words_value, maxWords), null,
                onMinus = { maxWords = (maxWords - 1).coerceAtLeast(3); s.maxWords = maxWords; app.scripts.relayout() },
                onPlus = { maxWords = (maxWords + 1).coerceAtMost(14); s.maxWords = maxWords; app.scripts.relayout() },
            )
            StepperRow(
                stringResource(R.string.settings_font_title), "$font", null,
                onMinus = { font = (font - 2).coerceAtLeast(14); s.fontSp = font },
                onPlus = { font = (font + 2).coerceAtMost(48); s.fontSp = font },
            )

            SectionTitle(stringResource(R.string.settings_section_tile))
            Text(
                stringResource(R.string.settings_tile_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (Build.VERSION.SDK_INT >= 33) {
                Text(
                    stringResource(R.string.settings_tile_add),
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.OnAccent,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp))
                        .background(Palette.Accent).clickable { requestAddTile(context) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            } else {
                Text(
                    stringResource(R.string.settings_tile_manual),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            SectionTitle(stringResource(R.string.settings_section_keys))
            ToggleRow(stringResource(R.string.settings_keys_title), stringResource(R.string.settings_keys_hint), keyControl) {
                keyControl = it
                s.keyControl = it
            }
            ToggleRow(
                stringResource(R.string.settings_volume_keys_title),
                stringResource(R.string.settings_volume_keys_hint),
                volumeKeys,
            ) {
                volumeKeys = it
                s.volumeKeys = it
            }
            KeyAction.entries.forEach { action ->
                val keys = bindings.filterValues { it == action }.keys.map { KeyBindings.keyName(context, it) }
                val learning = KeyLearning.action == action
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(action.label), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (learning) {
                                stringResource(R.string.settings_key_waiting)
                            } else {
                                keys.joinToString(", ").ifEmpty { stringResource(R.string.settings_key_none) }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (learning) Palette.Accent else Palette.TextMuted,
                        )
                    }
                    Pill(stringResource(if (learning) R.string.common_cancel else R.string.settings_key_assign), learning) {
                        KeyLearning.action = if (KeyLearning.action == action) null else action
                    }
                }
            }
            Text(
                stringResource(R.string.settings_keys_reset),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Accent,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable {
                    s.keyBindings = KeyBindings.DEFAULT
                    KeyLearning.revision++
                },
            )

            SectionTitle(stringResource(R.string.settings_section_overlay))
            ToggleRow(stringResource(R.string.settings_rotate_title), stringResource(R.string.settings_rotate_hint), autoRotate) {
                autoRotate = it
                s.autoRotate = it
            }
            StepperRow(
                stringResource(R.string.settings_lines_title), "$lines", null,
                onMinus = { lines = (lines - 1).coerceAtLeast(1); s.overlayLines = lines },
                onPlus = { lines = (lines + 1).coerceAtMost(8); s.overlayLines = lines },
            )
            StepperRow(
                stringResource(R.string.settings_opacity_title),
                stringResource(R.string.settings_percent_value, alpha),
                stringResource(R.string.settings_opacity_hint),
                onMinus = { alpha = (alpha - 8).coerceAtLeast(24); s.overlayAlpha = alpha },
                onPlus = { alpha = (alpha + 8).coerceAtMost(96); s.overlayAlpha = alpha },
            )
            ToggleRow(stringResource(R.string.settings_diag_title), stringResource(R.string.settings_diag_hint), diagnostics) {
                diagnostics = it
                s.showDiagnostics = it
            }

            SectionTitle(stringResource(R.string.settings_section_autoscroll))
            StepperRow(
                stringResource(R.string.settings_wpm_title), "$wpm", stringResource(R.string.settings_wpm_hint),
                onMinus = { wpm = (wpm - 10).coerceAtLeast(60); s.autoScrollWpm = wpm },
                onPlus = { wpm = (wpm + 10).coerceAtMost(260); s.autoScrollWpm = wpm },
            )
            StepperRow(
                stringResource(R.string.settings_countdown_title),
                if (countdown == 0) {
                    stringResource(R.string.settings_countdown_off)
                } else {
                    stringResource(R.string.settings_countdown_value, countdown)
                },
                stringResource(R.string.settings_countdown_hint),
                onMinus = { countdown = (countdown - 1).coerceAtLeast(0); s.countdownSec = countdown },
                onPlus = { countdown = (countdown + 1).coerceAtMost(5); s.countdownSec = countdown },
            )

            SectionTitle(stringResource(R.string.settings_section_mic))
            Text(
                stringResource(R.string.settings_audio_source),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION to R.string.settings_source_speech,
                    MediaRecorder.AudioSource.MIC to R.string.settings_source_standard,
                    MediaRecorder.AudioSource.UNPROCESSED to R.string.settings_source_raw,
                ).forEach { (value, label) ->
                    Pill(stringResource(label), source == value) {
                        source = value
                        s.audioSource = value
                    }
                }
            }
            Text(
                stringResource(R.string.settings_sample_rate),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(48000, 16000).forEach { value ->
                    Pill(stringResource(R.string.settings_rate_khz, value / 1000), rate == value) {
                        rate = value
                        s.sampleRate = value
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_section_about))
            // Android 13+ has a per-app language screen; older versions have none, and the app follows the phone's language.
            if (Build.VERSION.SDK_INT >= 33) {
                Column(
                    Modifier.fillMaxWidth().clickable { openAppLanguageSettings(context) }.padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_language_value),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextMuted,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onJournal).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ic(R.drawable.ic_list, null, tint = Palette.TextSecondary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.journal_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.settings_log_hint), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
                }
            }
            Text(
                stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

/** [com.olerast.suflyor.Settings.speechLang] value that picks the language from the script's alphabet. */
private const val AUTO = "auto"

/** Language name for running text ("now: English"); the pills use the names written in their own language. */
@StringRes
private fun speechLangName(lang: SpeechLang): Int = when (lang) {
    SpeechLang.RU -> R.string.settings_speech_lang_name_ru
    SpeechLang.EN -> R.string.settings_speech_lang_name_en
}

/** Which action waits for a key press on the settings screen (MainActivity routes the next key here). */
object KeyLearning {
    var action by mutableStateOf<KeyAction?>(null)
    var revision by mutableIntStateOf(0)

    /** @return true if the key was taken for the action being assigned. */
    fun offer(keyCode: Int): Boolean {
        val a = action ?: return false
        val s = App.instance.settings
        s.keyBindings = s.keyBindings.toMutableMap().apply { put(keyCode, a) }
        action = null
        revision++
        return true
    }
}

private fun requestAddTile(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
    sbm.requestAddTileService(
        ComponentName(context, PrompterTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_layers),
        context.mainExecutor,
    ) { result ->
        val msg = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.settings_tile_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.settings_tile_already_added
            else -> null
        }
        if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

private fun openAppLanguageSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val app = Uri.fromParts("package", context.packageName, null)
    // Some vendor builds drop this screen; their App info page still has the Language entry.
    runCatching { context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, app)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)) } }
}

@Composable
private fun ReadyRow(ok: Boolean, title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Ic(if (ok) R.drawable.ic_check else R.drawable.ic_warning, null, tint = if (ok) Palette.Success else Palette.Accent, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        }
        if (!ok) {
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.OnAccent,
                modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Accent)
                    .clickable(onClick = onAction).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
