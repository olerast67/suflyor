package com.olerast.suflyor.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.olerast.suflyor.R
import com.olerast.suflyor.doc.ImportError
import com.olerast.suflyor.doc.ImportWarning
import com.olerast.suflyor.doc.ScriptDocument

/**
 * Format shown next to a script: typed and built-in scripts are translated, file formats (DOCX, PDF…) are not.
 * The repository turns the Russian names older versions saved into codes, so only codes arrive here.
 */
@Composable
fun formatLabel(format: String): String = when (format) {
    ScriptDocument.FORMAT_TEXT -> stringResource(R.string.format_typed)
    ScriptDocument.FORMAT_SAMPLE -> stringResource(R.string.format_sample)
    else -> format
}

fun formatLabel(context: Context, format: String): String = when (format) {
    ScriptDocument.FORMAT_TEXT -> context.getString(R.string.format_typed)
    ScriptDocument.FORMAT_SAMPLE -> context.getString(R.string.format_sample)
    else -> format
}

/** No `else` branch: a new import error doesn't compile until it has a message. */
fun ImportError.text(context: Context): String {
    fun s(@StringRes id: Int) = context.getString(id)
    return when (this) {
        ImportError.EmptyFile -> s(R.string.import_error_empty_file)
        is ImportError.FileTooBig -> context.getString(R.string.import_error_file_too_big, maxMb)
        ImportError.TextFileTooBig -> s(R.string.import_error_text_file_too_big)
        ImportError.LegacyDoc -> s(R.string.import_error_legacy_doc)
        ImportError.EncryptedOffice -> s(R.string.import_error_encrypted)
        ImportError.UnknownZip -> s(R.string.import_error_unknown_zip)
        ImportError.NoText -> s(R.string.import_error_no_text)
        ImportError.NotText -> s(R.string.import_error_not_text)
        ImportError.PastedTextTooLong -> s(R.string.import_error_pasted_too_long)
        is ImportError.TextTooLong -> if (kChars != null) {
            context.getString(R.string.import_error_text_too_long_count, kChars, maxKChars)
        } else {
            context.getString(R.string.import_error_text_too_long_max, maxKChars)
        }
        ImportError.NotRtf -> s(R.string.import_error_not_rtf)
        ImportError.ZipTooManyFiles -> s(R.string.import_error_zip_too_many_files)
        ImportError.ZipTooLarge -> s(R.string.import_error_zip_too_large)
        ImportError.UnsafeDocument -> s(R.string.import_error_unsafe)
        ImportError.NotDocx -> s(R.string.import_error_not_docx)
        ImportError.NotOdt -> s(R.string.import_error_not_odt)
        ImportError.PdfUnsupported -> s(R.string.import_error_pdf_unsupported)
        ImportError.PdfPassword -> s(R.string.import_error_pdf_password)
        ImportError.PdfNoTextLayer -> s(R.string.import_error_pdf_scan)
        ImportError.CannotOpen -> s(R.string.import_error_open_failed)
    }
}

@Composable
fun ImportWarning.text(): String = when (this) {
    ImportWarning.PdfParagraphsRebuilt -> stringResource(R.string.import_warning_pdf_paragraphs)
    is ImportWarning.DocxTables -> pluralStringResource(R.plurals.import_warning_docx_tables, count, count)
    is ImportWarning.Raw -> text
}
