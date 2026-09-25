package com.olerast.suflyor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olerast.suflyor.App
import com.olerast.suflyor.R
import com.olerast.suflyor.session.SessionEngine

/** Full-screen prompter that follows the voice inside the app: practice before recording. */
@Composable
fun RehearsalScreen(onBack: () -> Unit) {
    val app = App.instance
    val state = rememberEngineState()
    @Suppress("UNUSED_VARIABLE")
    val layoutVersion = app.scripts.layoutVersion
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        if (app.engine.state.mode != SessionEngine.Mode.IN_APP) app.engine.start(SessionEngine.Mode.IN_APP)
        onDispose {
            view.keepScreenOn = false
            if (app.engine.state.mode == SessionEngine.Mode.IN_APP) app.engine.stop()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Ic(R.drawable.ic_back, stringResource(R.string.common_cd_back)) }
            val (color, label) = statusOf(state)
            StatusDot(color)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
            LevelMeter(state.levelDb, state.silencedBySystem == true || state.digitalSilence, Modifier.width(64.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Prompter(
                model = app.scripts.model,
                nextToken = state.nextToken,
                fontSp = app.settings.fontSp + 4,
                linesAbove = 1.5f,
                modifier = Modifier.fillMaxSize(),
                onWordTap = { app.engine.jumpToToken(it) },
            )
            if (state.countdown > 0) {
                Text(
                    state.countdown.toString(),
                    color = Palette.Accent,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(R.drawable.ic_replay, stringResource(R.string.common_cd_restart), { app.engine.restart() }, size = 48.dp)
            RoundIcon(R.drawable.ic_up, stringResource(R.string.common_cd_line_back), { app.engine.stepLine(-1) }, size = 48.dp)
            RoundIcon(
                if (state.paused) R.drawable.ic_play else R.drawable.ic_pause,
                stringResource(if (state.paused) R.string.common_cd_resume else R.string.common_cd_pause),
                { app.engine.togglePause() },
                size = 68.dp,
                bg = Palette.Accent,
                tint = Palette.OnAccent,
            )
            RoundIcon(R.drawable.ic_down, stringResource(R.string.common_cd_line_forward), { app.engine.stepLine(1) }, size = 48.dp)
            RoundIcon(
                if (state.scroll == SessionEngine.Scroll.VOICE) R.drawable.ic_mic else R.drawable.ic_speed,
                stringResource(R.string.common_cd_scroll_mode),
                {
                    app.engine.setScroll(
                        if (state.scroll == SessionEngine.Scroll.VOICE) SessionEngine.Scroll.AUTO else SessionEngine.Scroll.VOICE,
                    )
                },
                size = 48.dp,
            )
        }
    }
}

@Composable
fun statusOf(s: SessionEngine.State): Pair<Color, String> = when {
    s.starting -> Palette.TextMuted to stringResource(R.string.rehearsal_status_starting)
    !s.listening -> Palette.TextMuted to stringResource(R.string.rehearsal_status_idle)
    s.paused -> Palette.Accent to stringResource(R.string.rehearsal_status_paused)
    s.scroll == SessionEngine.Scroll.AUTO ->
        Palette.Accent to stringResource(R.string.rehearsal_status_auto, App.instance.settings.autoScrollWpm)
    s.silencedBySystem == true -> Palette.Danger to stringResource(R.string.rehearsal_status_mic_busy)
    s.autoFallback -> Palette.Danger to stringResource(R.string.rehearsal_status_fallback)
    else -> Palette.Success to stringResource(R.string.rehearsal_status_listening)
}
