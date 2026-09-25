package com.olerast.suflyor.doc

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import java.io.File

/**
 * PDF text through the platform text API: PdfRenderer on Android 15+, PdfRendererPreV on Android 12–14 with
 * the S SDK extension 13+ (updated through Google Play system updates). Scans without a text layer are reported.
 */
object PdfTextExtractor {
    /** Whether this device can read PDF text at all; the file picker hides PDFs otherwise. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 35 ||
        (Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)

    fun extract(context: Context, bytes: ByteArray, title: String): ScriptDocument {
        if (!isSupported()) throw ImportException(ImportError.PdfUnsupported)
        val tmp = File.createTempFile("import", ".pdf", context.cacheDir)
        try {
            tmp.writeBytes(bytes)
            val pages = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                try {
                    if (Build.VERSION.SDK_INT >= 35) readPlatform(pfd) else readPreV(pfd)
                } catch (e: SecurityException) {
                    throw ImportException(ImportError.PdfPassword)
                }
            }
            val paragraphs = PdfTextLayout.paragraphs(pages).mapNotNull { text ->
                ParagraphBuilder().apply { append(text) }.build()
            }
            if (paragraphs.isEmpty()) throw ImportException(ImportError.PdfNoTextLayer)
            return ScriptDocument(title, "PDF", paragraphs, listOf(ImportWarning.PdfParagraphsRebuilt))
        } finally {
            tmp.delete()
        }
    }

    private fun readPlatform(pfd: ParcelFileDescriptor): List<String> = PdfRenderer(pfd).use { r ->
        readPages(r.pageCount) { i -> r.openPage(i).use { page -> page.textContents.joinToString("\n") { it.text } } }
    }

    private fun readPreV(pfd: ParcelFileDescriptor): List<String> = PdfRendererPreV(pfd).use { r ->
        readPages(r.pageCount) { i -> r.openPage(i).use { page -> page.textContents.joinToString("\n") { it.text } } }
    }

    /** Stops early on a book-sized PDF instead of pulling all of it into memory first. */
    private inline fun readPages(count: Int, page: (Int) -> String): List<String> {
        val out = ArrayList<String>()
        var chars = 0
        for (i in 0 until count) {
            val text = page(i)
            chars += text.length
            if (chars > DocumentImporter.MAX_CHARS * 2) {
                throw ImportException(ImportError.TextTooLong(null, DocumentImporter.MAX_CHARS / 1000))
            }
            out += text
        }
        return out
    }
}

/** Rebuilds paragraphs from PDF lines: joins wrapped lines, undoes end-of-line hyphenation, drops bare page numbers. */
object PdfTextLayout {
    private val SENTENCE_END = Regex("[.!?…:»\"”)]$")

    fun paragraphs(pages: List<String>): List<String> {
        val lines = pages.flatMap { page ->
            page.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trim() }
        }.filterNot { it.matches(Regex("\\d{1,4}")) }
        val typical = lines.filter { it.isNotEmpty() }.map { it.length }.sorted().let { l ->
            if (l.isEmpty()) 0 else l[(l.size * 3) / 4]
        }
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out += cur.toString().trim()
            cur.setLength(0)
        }
        for (line in lines) {
            if (line.isEmpty()) {
                flush()
                continue
            }
            if (cur.isEmpty()) {
                cur.append(line)
            } else if (cur.endsWith("-") && cur.length >= 2 && cur[cur.length - 2].isLetter() && line.first().isLowerCase()) {
                cur.setLength(cur.length - 1)
                cur.append(line)
            } else {
                cur.append(' ').append(line)
            }
            // A short line that ends a sentence usually ends the paragraph.
            if (SENTENCE_END.containsMatchIn(line) && line.length < typical * 0.7) flush()
        }
        flush()
        return out
    }
}
