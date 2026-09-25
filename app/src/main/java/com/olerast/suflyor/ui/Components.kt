package com.olerast.suflyor.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.olerast.suflyor.App
import com.olerast.suflyor.overlay.PrompterView
import com.olerast.suflyor.script.ScriptModel
import com.olerast.suflyor.session.SessionEngine

@Composable
fun Ic(@DrawableRes res: Int, description: String?, modifier: Modifier = Modifier, tint: Color = Palette.Text, size: Dp = 24.dp) {
    Icon(painterResource(res), description, modifier.size(size), tint = tint)
}

/** Top bar: optional back arrow, title, trailing actions. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Ic(com.olerast.suflyor.R.drawable.ic_back, "Назад") }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.Accent,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = Palette.Surface,
        shape = shape,
        modifier = modifier.clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { content() }
}

/** Big bottom action: filled amber for the one main action, dark for the rest. */
@Composable
fun ActionButton(
    text: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val bg = if (primary) Palette.Accent else Palette.Surface
    val fg = if (primary) Palette.OnAccent else Palette.Text
    Row(
        modifier.fillMaxWidth().height(58.dp).clip(shape).background(bg).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ic(icon, null, tint = fg)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected) Palette.AccentSoft else Palette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Palette.Accent else Palette.TextSecondary,
        )
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Setting with a value and −/+ buttons. */
@Composable
fun StepperRow(title: String, value: String, subtitle: String? = null, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        }
        RoundIcon(com.olerast.suflyor.R.drawable.ic_minus, "Меньше", onMinus)
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        RoundIcon(com.olerast.suflyor.R.drawable.ic_plus, "Больше", onPlus)
    }
}

@Composable
fun RoundIcon(@DrawableRes res: Int, description: String, onClick: () -> Unit, size: Dp = 38.dp, bg: Color = Palette.SurfaceHigh, tint: Color = Palette.Text) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Ic(res, description, tint = tint, size = size * 0.55f) }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.TextSecondary,
                uncheckedTrackColor = Palette.SurfaceHigh,
                uncheckedBorderColor = Palette.Outline,
            ),
        )
    }
}

/** Latest [SessionEngine.State] as Compose state. */
@Composable
fun rememberEngineState(): SessionEngine.State {
    val engine = App.instance.engine
    var state by remember { mutableStateOf(engine.state) }
    DisposableEffect(engine) {
        val listener: (SessionEngine.State) -> Unit = { state = it }
        engine.addListener(listener)
        onDispose { engine.removeListener(listener) }
    }
    return state
}

/** The teleprompter text view (shared with the floating window) inside Compose. */
@Composable
fun Prompter(
    model: ScriptModel,
    nextToken: Int,
    fontSp: Int,
    linesAbove: Float,
    modifier: Modifier = Modifier,
    onWordTap: (Int) -> Unit,
) {
    val highlight = App.instance.settings.wordHighlight
    AndroidView(
        factory = { ctx ->
            PrompterView(ctx).apply {
                val d = ctx.resources.displayMetrics.density
                setPadding((20 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            }
        },
        update = { v ->
            v.onWordTap = onWordTap
            v.setTextSizeSp(fontSp.toFloat())
            if (v.tag !== model) {
                v.tag = model
                v.setScript(model)
            }
            v.linesAbove = linesAbove
            v.wordHighlight = highlight
            v.setProgress(nextToken)
        },
        modifier = modifier,
    )
}

/** Thin level meter; red when the microphone gives only silence. */
@Composable
fun LevelMeter(db: Float, muted: Boolean, modifier: Modifier = Modifier) {
    val fraction = if (muted) 1f else ((db + 60f) / 60f).coerceIn(0f, 1f)
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(
            Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (muted) Palette.Danger else Palette.Success),
        )
    }
}

fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

fun wordsLabel(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "слово"
        mod10 in 2..4 && mod100 !in 12..14 -> "слова"
        else -> "слов"
    }
    return "$n $word"
}
