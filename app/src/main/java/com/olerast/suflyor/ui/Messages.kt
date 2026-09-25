package com.olerast.suflyor.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.olerast.suflyor.R
import com.olerast.suflyor.doc.ScriptDocument

/** Scripts saved before 0.4 carry these Russian format names instead of codes. */
private const val LEGACY_TEXT = "Текст"
private const val LEGACY_SAMPLE = "встроенный"

/** Format shown next to a script: typed and built-in scripts are translated, file formats (DOCX, PDF…) are not. */
@Composable
fun formatLabel(format: String): String = when (format) {
    ScriptDocument.FORMAT_TEXT, LEGACY_TEXT -> stringResource(R.string.format_typed)
    ScriptDocument.FORMAT_SAMPLE, LEGACY_SAMPLE -> stringResource(R.string.format_sample)
    else -> format
}

fun formatLabel(context: Context, format: String): String = when (format) {
    ScriptDocument.FORMAT_TEXT, LEGACY_TEXT -> context.getString(R.string.format_typed)
    ScriptDocument.FORMAT_SAMPLE, LEGACY_SAMPLE -> context.getString(R.string.format_sample)
    else -> format
}
