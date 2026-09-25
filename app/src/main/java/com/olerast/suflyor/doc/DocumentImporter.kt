package com.olerast.suflyor.doc

/** Picks an importer by file content first (magic bytes), then by extension / MIME type. */
object DocumentImporter {
    enum class Kind { TXT, MARKDOWN, DOCX, ODT, RTF, HTML, PDF, LEGACY_DOC, ENCRYPTED_OFFICE, UNKNOWN_ZIP }

    /** Containers (PDF, DOCX, ODT) may be big because of pictures; the text inside is what is capped. */
    const val MAX_BYTES = 40 * 1024 * 1024

    /** Plain-text formats: several MB of text is already far beyond any script. */
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /** HTML is decoded to a String first (two bytes per char), so it gets less room than binary containers. */
    const val MAX_MARKUP_BYTES = 24 * 1024 * 1024

    /** About 40 000 words — five hours of speech. Longer texts are not scripts and would make the app sluggish. */
    const val MAX_CHARS = 300_000

    fun detect(bytes: ByteArray, name: String?, mime: String?): Kind {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        fun startsWith(vararg sig: Int) = bytes.size >= sig.size && sig.indices.all { bytes[it] == sig[it].toByte() }
        return when {
            startsWith(0x25, 0x50, 0x44, 0x46) -> Kind.PDF // %PDF
            startsWith(0xD0, 0xCF, 0x11, 0xE0) -> if (isEncryptedOffice(bytes)) Kind.ENCRYPTED_OFFICE else Kind.LEGACY_DOC
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
        val kind = detect(bytes, name, mime)
        // RTF and HTML carry pictures inline (hex, base64), so only plain text formats get the tighter limit;
        // their parsers skip the pictures in one linear pass and the extracted text is capped by MAX_CHARS.
        val limit = when (kind) {
            Kind.TXT, Kind.MARKDOWN -> MAX_TEXT_BYTES
            Kind.HTML -> MAX_MARKUP_BYTES
            else -> MAX_BYTES
        }
        if (bytes.size > limit) throw ImportException("Слишком большой текстовый файл для сценария")
        val doc = when (kind) {
            Kind.PDF -> pdf(bytes, title)
            Kind.DOCX -> DocxImporter.parse(bytes, title)
            Kind.ODT -> OdtImporter.parse(bytes, title)
            Kind.RTF -> RtfImporter.parse(bytes, title)
            Kind.HTML -> HtmlImporter.parse(TextDecoding.decode(bytes), title)
            Kind.MARKDOWN -> MarkdownImporter.parse(TextDecoding.decode(bytes), title)
            Kind.TXT -> PlainTextImporter.parse(requireText(TextDecoding.decode(bytes)), title)
            Kind.LEGACY_DOC -> throw ImportException(
                "Старый формат .doc (Word 97–2003) не поддерживается. Пересохрани файл как .docx или .pdf.",
            )
            Kind.ENCRYPTED_OFFICE -> throw ImportException("Документ защищён паролем. Сними пароль и открой снова.")
            Kind.UNKNOWN_ZIP -> throw ImportException("Это архив, но не DOCX и не ODT")
        }
        if (doc.isEmpty) throw ImportException("В файле не нашлось текста")
        return checkSize(doc)
    }

    fun fromPlainText(text: String, title: String): ScriptDocument {
        if (text.length > MAX_CHARS * 2) throw ImportException("Слишком длинный текст для сценария")
        val looksLikeMarkdown = Regex("(?m)^(#{1,6} |[-*] |> )|\\*\\*[^*\\n]{1,500}\\*\\*").containsMatchIn(text)
        return if (looksLikeMarkdown) MarkdownImporter.parse(text, title) else PlainTextImporter.parse(text, title)
    }

    fun checkSize(doc: ScriptDocument): ScriptDocument {
        val chars = doc.paragraphs.sumOf { it.text.length }
        if (chars > MAX_CHARS) {
            throw ImportException("Текст слишком длинный: ${chars / 1000} тыс. знаков, суфлёр принимает до ${MAX_CHARS / 1000} тыс.")
        }
        return doc
    }

    /** A "text" file that is mostly control characters is a binary file with an unknown extension. */
    private fun requireText(text: String): String {
        val head = text.take(8192)
        if (head.isEmpty()) return text
        val controls = head.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
        if (head.contains('\u0000') || controls * 20 > head.length) throw ImportException("Это не текстовый файл")
        return text
    }

    /** Password-protected DOCX/XLSX are OLE2 containers with an "EncryptedPackage" stream (UTF-16LE name). */
    private fun isEncryptedOffice(bytes: ByteArray): Boolean {
        val marker = "EncryptedPackage".toByteArray(Charsets.UTF_16LE)
        val limit = minOf(bytes.size, 256 * 1024) - marker.size
        outer@ for (i in 0..limit) {
            for (k in marker.indices) if (bytes[i + k] != marker[k]) continue@outer
            return true
        }
        return false
    }
}
