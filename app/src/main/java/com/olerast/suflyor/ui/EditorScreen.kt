package com.olerast.suflyor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument

/** Plain-text editor. Formatting is Markdown-light: **bold**, # heading, [note that is not read]. */
@Composable
fun EditorScreen(
    isNew: Boolean,
    initialTitle: String,
    initialText: String,
    onCancel: () -> Unit,
    onSave: (title: String, text: String) -> Unit,
) {
    // Saveable: typed text survives the app being killed in the background.
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    val canSave = text.isNotBlank()

    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(if (isNew) "Новый сценарий" else "Правка", onBack = onCancel) {
            TextButton(onClick = { if (canSave) onSave(title.ifBlank { "Без названия" }, text) }) {
                Text("Готово", color = if (canSave) Palette.Accent else Palette.TextMuted, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box {
                if (title.isEmpty()) Text("Название", style = MaterialTheme.typography.headlineSmall, color = Palette.TextMuted)
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = Palette.Text),
                    cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Palette.Outline)
            Box {
                if (text.isEmpty()) {
                    Text(
                        "Вставь или напиши текст. Каждый абзац — с новой строки.",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
                        color = Palette.TextMuted,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text, fontSize = 18.sp, lineHeight = 27.sp),
                    cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                )
            }
        }
        Text(
            "**жирный** — акцент · # заголовок · [пометка] — видна, но не читается",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** Turns a document back into the editor's Markdown-light text. */
fun ScriptDocument.toEditableText(): String = paragraphs.joinToString("\n\n") { p ->
    when (p.kind) {
        Paragraph.Kind.HEADING -> "# ${p.text}"
        Paragraph.Kind.BODY -> buildString {
            var last = 0
            for (r in p.emphasis.sortedBy { it.first }) {
                if (r.first < last || r.last >= p.text.length) continue
                append(p.text, last, r.first)
                append("**").append(p.text, r.first, r.last + 1).append("**")
                last = r.last + 1
            }
            append(p.text, last, p.text.length)
        }
    }
}
