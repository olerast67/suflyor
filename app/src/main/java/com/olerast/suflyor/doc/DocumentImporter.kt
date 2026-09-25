package com.olerast.suflyor.doc

/** Picks an importer by file content first (magic bytes), then by extension / MIME type. */
object DocumentImporter {
    enum class Kind { TXT, MARKDOWN, DOCX, ODT, RTF, HTML, PDF, LEGACY_DOC, UNKNOWN_ZIP }

    const val MAX_BYTES = 40 * 1024 * 1024

    fun detect(bytes: ByteArray, name: String?, mime: String?): Kind {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        fun startsWith(vararg sig: Int) = bytes.size >= sig.size && sig.indices.all { bytes[it] == sig[it].toByte() }
        return when {
            startsWith(0x25, 0x50, 0x44, 0x46) -> Kind.PDF // %PDF
            startsWith(0xD0, 0xCF, 0x11, 0xE0) -> Kind.LEGACY_DOC // OLE2: .doc
            startsWith(0x50, 0x4B, 0x03, 0x04) -> {
                val names = runCatching { Zip.entryNames(bytes) }.getOrDefault(emptyList())
                when {
                    "word/document.xml" in names -> Kind.DOCX
                    "content.xml" in names -> Kind.ODT
                    else -> Kind.UNKNOWN_ZIP
                }
            }
            startsWith(0x7B, 0x5C, 0x72, 0x74, 0x66) -> Kind.RTF // {\rtf
            ext in setOf("md", "markdown", "mdown", "mkd") || mime == "text/markdown" || mime == "text/x-markdown" -> Kind.MARKDOWN
            ext in setOf("html", "htm", "xhtml") || mime == "text/html" -> Kind.HTML
            else -> {
                val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).trimStart().lowercase()
                if (head.startsWith("<!doctype html") || head.startsWith("<html")) Kind.HTML else Kind.TXT
            }
        }
    }

    /**
     * @param pdf PDF text extraction is platform specific, so the caller supplies it.
     */
    fun import(bytes: ByteArray, name: String?, mime: String?, pdf: (ByteArray, String) -> ScriptDocument): ScriptDocument {
        if (bytes.isEmpty()) throw ImportException("Файл пустой")
        if (bytes.size > MAX_BYTES) throw ImportException("Файл больше ${MAX_BYTES / 1024 / 1024} МБ — это точно сценарий?")
        val title = name?.substringBeforeLast('.')?.ifBlank { null } ?: "Без названия"
        val doc = when (detect(bytes, name, mime)) {
            Kind.PDF -> pdf(bytes, title)
            Kind.DOCX -> DocxImporter.parse(bytes, title)
            Kind.ODT -> OdtImporter.parse(bytes, title)
            Kind.RTF -> RtfImporter.parse(bytes, title)
            Kind.HTML -> HtmlImporter.parse(TextDecoding.decode(bytes), title)
            Kind.MARKDOWN -> MarkdownImporter.parse(TextDecoding.decode(bytes), title)
            Kind.TXT -> PlainTextImporter.parse(TextDecoding.decode(bytes), title)
            Kind.LEGACY_DOC -> throw ImportException(
                "Старый формат .doc (Word 97–2003) не поддерживается. Пересохрани файл как .docx или .pdf.",
            )
            Kind.UNKNOWN_ZIP -> throw ImportException("Это архив, но не DOCX и не ODT")
        }
        if (doc.isEmpty) throw ImportException("В файле не нашлось текста")
        return doc
    }

    fun fromPlainText(text: String, title: String): ScriptDocument {
        val looksLikeMarkdown = Regex("(?m)^(#{1,6} |[-*] |> )|\\*\\*[^*]+\\*\\*").containsMatchIn(text)
        return if (looksLikeMarkdown) MarkdownImporter.parse(text, title) else PlainTextImporter.parse(text, title)
    }
}
