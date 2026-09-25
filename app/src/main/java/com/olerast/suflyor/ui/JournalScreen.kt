package com.olerast.suflyor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olerast.suflyor.App
import com.olerast.suflyor.BuildConfig
import com.olerast.suflyor.R
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.overlay.PrompterAccessibilityService
import com.olerast.suflyor.speech.AudioCapture

@Composable
fun JournalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(DiagLog.text().lines()) }
    DisposableEffect(Unit) {
        val listener = { lines = DiagLog.text().lines() }
        DiagLog.addListener(listener)
        onDispose { DiagLog.removeListener(listener) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    fun fullText(): String = buildString {
        val s = App.instance.settings
        append("Суфлёр ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Источник ${AudioCapture.sourceName(s.audioSource)}, ${s.sampleRate} Гц, ")
        append("спецвозможности ${if (PrompterAccessibilityService.instance != null) "вкл" else "выкл"}\n\n")
        append(DiagLog.text())
    }

    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Журнал", onBack) {
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Журнал суфлёра", fullText()))
                Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
            }) { Ic(R.drawable.ic_copy, "Скопировать", tint = Palette.TextSecondary) }
            IconButton(onClick = {
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fullText()), "Отправить журнал"),
                )
            }) { Ic(R.drawable.ic_share, "Поделиться", tint = Palette.TextSecondary) }
            IconButton(onClick = { DiagLog.clear() }) { Ic(R.drawable.ic_delete, "Очистить", tint = Palette.TextSecondary) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp), state = listState) {
            items(lines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (line.contains(DiagLog.ERROR_PREFIX)) Palette.Danger else Palette.TextSecondary,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
