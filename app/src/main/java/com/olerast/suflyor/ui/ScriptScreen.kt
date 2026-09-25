package com.olerast.suflyor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.olerast.suflyor.App
import com.olerast.suflyor.R
import com.olerast.suflyor.overlay.CameraTarget

@Composable
fun ScriptScreen(
    readiness: Readiness,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRehearse: () -> Unit,
    onStartOverlay: (CameraTarget) -> Unit,
    onFixReadiness: () -> Unit,
    onDelete: () -> Unit,
) {
    val app = App.instance
    @Suppress("UNUSED_VARIABLE")
    val layoutVersion = app.scripts.layoutVersion // recompose when the layout is rebuilt
    val model = app.scripts.model
    val doc = app.scripts.document
    val state = rememberEngineState()
    val context = LocalContext.current
    val targets = remember { CameraTarget.entries.filter { it.isAvailable(context) } }
    var target by remember { mutableStateOf(CameraTarget.from(app.settings.overlayTarget).takeIf { it in targets } ?: CameraTarget.CAMERA) }
    var confirmDelete by remember { mutableStateOf(false) }
    val wpm = app.settings.autoScrollWpm

    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar(doc.title, onBack) {
            IconButton(onClick = onEdit) { Ic(R.drawable.ic_edit, stringResource(R.string.script_cd_edit), tint = Palette.TextSecondary) }
            IconButton(onClick = { confirmDelete = true }) {
                Ic(R.drawable.ic_delete, stringResource(R.string.common_delete), tint = Palette.TextSecondary)
            }
        }

        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(24.dp)).background(Color.Black),
        ) {
            Prompter(
                model = model,
                nextToken = state.nextToken,
                fontSp = app.settings.fontSp,
                linesAbove = 1f,
                modifier = Modifier.fillMaxSize(),
                onWordTap = { app.engine.jumpToToken(it) },
            )
            if (state.position > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                    RoundIcon(
                        R.drawable.ic_replay,
                        stringResource(R.string.common_cd_restart),
                        onClick = { app.engine.restart() },
                        size = 36.dp,
                    )
                }
            }
        }

        Text(
            stringResource(
                R.string.script_stats,
                formatDuration(model.durationSeconds(wpm)),
                wpm,
                pluralStringResource(R.plurals.words_count, model.spokenTokens, model.spokenTokens),
                formatLabel(doc.format),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
        doc.warnings.forEach {
            Text(
                it.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!readiness.overlayVoice) {
                Card(Modifier.fillMaxWidth(), onClick = onFixReadiness) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_warning, null, tint = Palette.Accent, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(if (!readiness.mic) R.string.script_setup_mic else R.string.script_setup_a11y),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            ActionButton(stringResource(R.string.script_action_overlay), R.drawable.ic_layers, primary = true) { onStartOverlay(target) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { t ->
                    Pill(stringResource(t.label), t == target) {
                        target = t
                        app.settings.overlayTarget = t.name
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            ActionButton(stringResource(R.string.script_action_rehearse), R.drawable.ic_mic, onClick = onRehearse)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.Surface,
            title = { Text(stringResource(R.string.script_delete_title)) },
            text = { Text(stringResource(R.string.script_delete_message, doc.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.common_delete), color = Palette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel), color = Palette.Text) }
            },
        )
    }
}
