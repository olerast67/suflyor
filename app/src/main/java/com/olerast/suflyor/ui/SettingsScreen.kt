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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.olerast.suflyor.App
import com.olerast.suflyor.BuildConfig
import com.olerast.suflyor.R

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
        TopBar("Настройки", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle("Готовность к работе поверх камеры")
            ReadyRow(
                ok = readiness.mic,
                title = "Микрофон",
                subtitle = "Лучше выбрать «Только во время использования»",
                action = "Разрешить",
                onAction = onRequestPermissions,
            )
            ReadyRow(
                ok = readiness.a11yRunning,
                title = "Служба «Суфлёр» в спецвозможностях",
                subtitle = if (readiness.a11yEnabled && !readiness.a11yRunning) "Включена, но не запущена — выключи и включи снова"
                else "Без неё Android глушит микрофон, пока другое приложение снимает видео",
                action = "Открыть",
                onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
            if (!readiness.a11yRunning) {
                Text(
                    "Если переключатель серый: Настройки → Приложения → Суфлёр → ⋮ → «Разрешить ограниченные настройки».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Text(
                    "Сведения о приложении",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Accent,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).clickable {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },
                )
            }
            ReadyRow(
                ok = readiness.notifications,
                title = "Уведомления",
                subtitle = "Значок в шторке, пока суфлёр слушает, и кнопка «Остановить»",
                action = "Разрешить",
                onAction = onRequestPermissions,
            )

            SectionTitle("Слежение за голосом")
            StepperRow(
                "Упреждение", "$lead сл.", "Насколько текст забегает вперёд распознанного, пока ты говоришь",
                onMinus = { lead = (lead - 1).coerceAtLeast(0); s.leadWords = lead },
                onPlus = { lead = (lead + 1).coerceAtMost(4); s.leadWords = lead },
            )
            ToggleRow("Подсвечивать следующее слово", "Иначе видна только текущая строка", wordHighlight) {
                wordHighlight = it
                s.wordHighlight = it
            }
            ToggleRow("Подсказывать слова сценария", "Точнее узнаёт редкие слова. Применится со следующего запуска", hotwords) {
                hotwords = it
                s.useHotwords = it
            }

            SectionTitle("Текст")
            ToggleRow("По строке на фразу", "Разбивать текст на фразы, которые говорятся на одном дыхании", phraseMode) {
                phraseMode = it
                s.phraseMode = it
                app.scripts.relayout()
            }
            StepperRow(
                "Слов в строке", "до $maxWords", null,
                onMinus = { maxWords = (maxWords - 1).coerceAtLeast(3); s.maxWords = maxWords; app.scripts.relayout() },
                onPlus = { maxWords = (maxWords + 1).coerceAtMost(14); s.maxWords = maxWords; app.scripts.relayout() },
            )
            StepperRow(
                "Размер текста", "$font", null,
                onMinus = { font = (font - 2).coerceAtLeast(14); s.fontSp = font },
                onPlus = { font = (font + 2).coerceAtMost(48); s.fontSp = font },
            )

            SectionTitle("Плитка в шторке")
            Text(
                "Опусти шторку и нажми «Суфлёр» — окно с текущим сценарием появится поверх открытого приложения. " +
                    "Нажми ещё раз, чтобы остановить.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (Build.VERSION.SDK_INT >= 33) {
                Text(
                    "Добавить плитку в шторку",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.OnAccent,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp))
                        .background(Palette.Accent).clickable { requestAddTile(context) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            } else {
                Text(
                    "Опусти шторку полностью → ✎ (изменить) → перетащи плитку «Суфлёр» наверх.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            SectionTitle("Пульт и кнопки")
            ToggleRow("Управлять кнопками", "Громкость, Bluetooth-пульт, кольцо, клавиатура — пока окно поверх камеры", keyControl) {
                keyControl = it
                s.keyControl = it
            }
            ToggleRow(
                "Кнопки громкости тоже",
                "Выключи, если пульт кнопкой громкости включает запись в камере",
                volumeKeys,
            ) {
                volumeKeys = it
                s.volumeKeys = it
            }
            KeyAction.entries.forEach { action ->
                val keys = bindings.filterValues { it == action }.keys.map { KeyBindings.keyName(it) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(action.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (KeyLearning.action == action) "Нажми кнопку на пульте…" else keys.joinToString(", ").ifEmpty { "не назначено" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (KeyLearning.action == action) Palette.Accent else Palette.TextMuted,
                        )
                    }
                    Pill(if (KeyLearning.action == action) "Отмена" else "Назначить", KeyLearning.action == action) {
                        KeyLearning.action = if (KeyLearning.action == action) null else action
                    }
                }
            }
            Text(
                "Вернуть кнопки по умолчанию",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Accent,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable {
                    s.keyBindings = KeyBindings.DEFAULT
                    KeyLearning.revision++
                },
            )

            SectionTitle("Окно поверх камеры")
            ToggleRow("Поворачивать для горизонтальной съёмки", "Телефон боком — текст встаёт у объектива и поворачивается", autoRotate) {
                autoRotate = it
                s.autoRotate = it
            }
            StepperRow(
                "Строк в окне", "$lines", null,
                onMinus = { lines = (lines - 1).coerceAtLeast(1); s.overlayLines = lines },
                onPlus = { lines = (lines + 1).coerceAtMost(8); s.overlayLines = lines },
            )
            StepperRow(
                "Плотность фона", "$alpha%", "Меньше — лучше видно себя сквозь окно",
                onMinus = { alpha = (alpha - 8).coerceAtLeast(24); s.overlayAlpha = alpha },
                onPlus = { alpha = (alpha + 8).coerceAtMost(96); s.overlayAlpha = alpha },
            )
            ToggleRow("Диагностика в окне", "Что слышит суфлёр и кто ещё пишет звук", diagnostics) {
                diagnostics = it
                s.showDiagnostics = it
            }

            SectionTitle("Автопрокрутка")
            StepperRow(
                "Скорость", "$wpm", "Слов в минуту: для режима «по скорости» и расчёта хронометража",
                onMinus = { wpm = (wpm - 10).coerceAtLeast(60); s.autoScrollWpm = wpm },
                onPlus = { wpm = (wpm + 10).coerceAtMost(260); s.autoScrollWpm = wpm },
            )
            StepperRow(
                "Отсчёт перед стартом", if (countdown == 0) "нет" else "$countdown с", "3-2-1 перед прокруткой по скорости",
                onMinus = { countdown = (countdown - 1).coerceAtLeast(0); s.countdownSec = countdown },
                onPlus = { countdown = (countdown + 1).coerceAtMost(5); s.countdownSec = countdown },
            )

            SectionTitle("Микрофон")
            Text("Источник звука", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION to "Для речи",
                    MediaRecorder.AudioSource.MIC to "Обычный",
                    MediaRecorder.AudioSource.UNPROCESSED to "Без обработки",
                ).forEach { (value, label) ->
                    Pill(label, source == value) {
                        source = value
                        s.audioSource = value
                    }
                }
            }
            Text("Частота", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(48000 to "48 кГц", 16000 to "16 кГц").forEach { (value, label) ->
                    Pill(label, rate == value) {
                        rate = value
                        s.sampleRate = value
                    }
                }
            }

            SectionTitle("О приложении")
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onJournal).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ic(R.drawable.ic_list, null, tint = Palette.TextSecondary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Журнал", style = MaterialTheme.typography.bodyLarge)
                    Text("Что происходило с микрофоном и распознаванием", style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
                }
            }
            Text(
                "Суфлёр ${BuildConfig.VERSION_NAME} · распознавание sherpa-onnx, модель alphacep (Apache-2.0)",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
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
        "Суфлёр",
        Icon.createWithResource(context, R.drawable.ic_layers),
        context.mainExecutor,
    ) { result ->
        val msg = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Плитка добавлена в шторку"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Плитка уже в шторке"
            else -> null
        }
        if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
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
