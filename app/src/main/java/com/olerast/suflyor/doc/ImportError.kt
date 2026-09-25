package com.olerast.suflyor.doc

/**
 * Why an import failed. The importers are plain JVM code without Android resources: the UI turns an error into
 * text in its language (ui/Messages.kt), the journal and the tests see the English [toString].
 */
sealed interface ImportError {
    data object EmptyFile : ImportError
    data class FileTooBig(val maxMb: Int) : ImportError
    data object TextFileTooBig : ImportError
    data object LegacyDoc : ImportError
    data object EncryptedOffice : ImportError
    data object UnknownZip : ImportError
    data object NoText : ImportError
    data object NotText : ImportError
    data object PastedTextTooLong : ImportError

    /** [kChars] is known when the whole text was extracted, null when extraction stopped early at the limit. */
    data class TextTooLong(val kChars: Int?, val maxKChars: Int) : ImportError
    data object NotRtf : ImportError
    data object ZipTooManyFiles : ImportError
    data object ZipTooLarge : ImportError
    data object UnsafeDocument : ImportError
    data object NotDocx : ImportError
    data object NotOdt : ImportError
    data object PdfUnsupported : ImportError
    data object PdfPassword : ImportError
    data object PdfNoTextLayer : ImportError

    /** The app handing the document over gave nothing to read. */
    data object CannotOpen : ImportError
}

class ImportException(val error: ImportError) : Exception(error.toString())

/** Something to check after an import, shown under the script. Saved with the script as its [code]. */
sealed interface ImportWarning {
    val code: String

    data object PdfParagraphsRebuilt : ImportWarning {
        override val code get() = "pdf_paragraphs"
    }

    /** DOCX tables come out as their cells in reading order. */
    data class DocxTables(val count: Int) : ImportWarning {
        override val code get() = "docx_tables:$count"
    }

    /** A warning saved before 0.4 as a sentence that matches none of the kinds above: shown as it is. */
    data class Raw(val text: String) : ImportWarning {
        override val code get() = "raw:$text"
    }

    companion object {
        private val LEGACY_TABLES = Regex("таблицы \\((\\d+)\\)")

        /** Null for a code this version doesn't know (saved by a newer one). */
        fun fromCode(code: String): ImportWarning? = when {
            code == "pdf_paragraphs" -> PdfParagraphsRebuilt
            code.startsWith("docx_tables:") -> code.substringAfter(':').toIntOrNull()?.let(::DocxTables)
            code.startsWith("raw:") -> Raw(code.removePrefix("raw:"))
            else -> null
        }

        /** Scripts saved before 0.4 keep their warnings as the Russian sentences the importers wrote then. */
        fun fromLegacyText(text: String): ImportWarning {
            if (text.startsWith("Абзацы в PDF")) return PdfParagraphsRebuilt
            val tables = LEGACY_TABLES.find(text)?.groupValues?.get(1)?.toIntOrNull()
            return if (tables != null) DocxTables(tables) else Raw(text)
        }
    }
}
