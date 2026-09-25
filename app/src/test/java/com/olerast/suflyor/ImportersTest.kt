package com.olerast.suflyor

import com.olerast.suflyor.doc.DocumentImporter
import com.olerast.suflyor.doc.DocxImporter
import com.olerast.suflyor.doc.HtmlImporter
import com.olerast.suflyor.doc.ImportError
import com.olerast.suflyor.doc.ImportException
import com.olerast.suflyor.doc.ImportWarning
import com.olerast.suflyor.doc.MarkdownImporter
import com.olerast.suflyor.doc.OdtImporter
import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.PdfTextLayout
import com.olerast.suflyor.doc.PlainTextImporter
import com.olerast.suflyor.doc.RtfImporter
import com.olerast.suflyor.doc.Sax
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.doc.TextDecoding
import com.olerast.suflyor.script.PhraseBreaker
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.SpeechLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class ImportersTest {
    private val ru = "Привет, мир! Это проверка кодировки: ёжик и щука."

    @Test
    fun decodesCommonRussianEncodings() {
        assertEquals(ru, TextDecoding.decode(ru.toByteArray(Charsets.UTF_8)))
        assertEquals(ru, TextDecoding.decode(ru.toByteArray(Charset.forName("windows-1251"))))
        assertEquals(ru, TextDecoding.decode(ru.toByteArray(Charset.forName("KOI8-R"))))
        assertEquals(ru, TextDecoding.decode(ru.toByteArray(Charsets.UTF_16LE)))
        assertEquals(ru, TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ru.toByteArray()))
    }

    @Test
    fun plainTextParagraphs() {
        val lines = PlainTextImporter.parse("Первая строка\nВторая строка\nТретья", "t")
        assertEquals(3, lines.paragraphs.size)
        val blocks = PlainTextImporter.parse("Абзац один,\nпродолжение.\n\nАбзац два.", "t")
        assertEquals(listOf("Абзац один, продолжение.", "Абзац два."), blocks.paragraphs.map { it.text })
    }

    @Test
    fun markdownKeepsHeadingsAndBoldDropsMarkup() {
        val md = """
            ---
            tags: [script]
            ---
            # Вступление
            Привет, это **важно** и ==очень== *просто*. Ссылка: [канал](https://t.me/x), [[Заметка|алиас]].
            ~~удалённый текст~~ Остаток.

            - первый пункт
            - второй пункт
            <!-- комментарий -->
            %% obsidian comment %%
        """.trimIndent()
        val doc = MarkdownImporter.parse(md, "t")
        assertEquals(Paragraph.Kind.HEADING, doc.paragraphs[0].kind)
        assertEquals("Вступление", doc.paragraphs[0].text)
        val body = doc.paragraphs[1]
        assertEquals("Привет, это важно и очень просто. Ссылка: канал, алиас. Остаток.", body.text)
        assertEquals(listOf("важно", "очень"), body.emphasis.map { body.text.substring(it.first, it.last + 1) })
        assertEquals(listOf("первый пункт", "второй пункт"), doc.paragraphs.drop(2).map { it.text })
    }

    @Test
    fun docxParagraphsHeadingsBoldAndDeletions() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val document = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="$w"><w:body>
              <w:p><w:pPr><w:pStyle w:val="1"/></w:pPr><w:r><w:t>Заголовок</w:t></w:r></w:p>
              <w:p><w:r><w:t xml:space="preserve">Обычный </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>жирный</w:t></w:r>
                <w:del><w:r><w:delText>удалено</w:delText></w:r></w:del><w:r><w:t xml:space="preserve"> текст</w:t></w:r></w:p>
              <w:p><w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>Не</w:t></w:r><w:r><w:tab/><w:t>жирный</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()
        val styles = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:styles xmlns:w="$w"><w:style w:type="paragraph" w:styleId="1"><w:name w:val="heading 1"/></w:style></w:styles>
        """.trimIndent()
        val doc = DocxImporter.parse(zip("word/document.xml" to document, "word/styles.xml" to styles), "t")
        assertEquals(listOf("Заголовок", "Обычный жирный текст", "Не жирный"), doc.paragraphs.map { it.text })
        assertEquals(Paragraph.Kind.HEADING, doc.paragraphs[0].kind)
        val p = doc.paragraphs[1]
        assertEquals(listOf("жирный"), p.emphasis.map { p.text.substring(it.first, it.last + 1) })
        assertTrue(doc.paragraphs[2].emphasis.isEmpty())
        assertEquals(DocumentImporter.Kind.DOCX, DocumentImporter.detect(zip("word/document.xml" to document), "x.bin", null))
    }

    @Test
    fun odtParagraphs() {
        val content = """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text>
              <text:h>Тема</text:h><text:p>Раз<text:s/>два<text:note><text:note-body><text:p>сноска</text:p></text:note-body></text:note></text:p>
            </office:text></office:body></office:document-content>
        """.trimIndent()
        val doc = OdtImporter.parse(zip("content.xml" to content), "t")
        assertEquals(listOf("Тема", "Раз два"), doc.paragraphs.map { it.text })
    }

    @Test
    fun rtfWithCodepageUnicodeAndBold() {
        val rtf = "{\\rtf1\\ansi\\ansicpg1251{\\fonttbl{\\f0 Arial;}}\\f0 \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2 {\\b \\u1084?\\u1080?\\u1088?}!\\par Второй\\par}"
        val doc = RtfImporter.parse(rtf.toByteArray(Charsets.UTF_8), "t")
        // Non-ASCII typed directly into RTF is not valid RTF; only the first paragraph is checked for escapes.
        val p = doc.paragraphs[0]
        assertEquals("Привет мир!", p.text)
        assertEquals(listOf("мир"), p.emphasis.map { p.text.substring(it.first, it.last + 1) })
    }

    @Test
    fun htmlBlocksAndEntities() {
        val html = "<html><head><title>T</title><style>p{}</style></head><body><h1>Тема</h1><p>Раз&nbsp;&laquo;два&raquo; <b>три</b></p><p>Четыре<br>пять</p></body></html>"
        val doc = HtmlImporter.parse(html, "t")
        assertEquals(listOf("Тема", "Раз «два» три", "Четыре", "пять"), doc.paragraphs.map { it.text })
        assertEquals(Paragraph.Kind.HEADING, doc.paragraphs[0].kind)
    }

    @Test
    fun rtfFontCharsetOverridesDocumentCodepage() {
        // WordPad on an English Windows: \ansicpg1252, Cyrillic text in a \fcharset204 font.
        val rtf = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fnil\\fcharset0 Calibri;}{\\f1\\fnil\\fcharset204 Calibri;}}" +
            "\\f1 \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2 \\f0 caf\\'e9\\par}"
        val doc = RtfImporter.parse(rtf.toByteArray(Charsets.ISO_8859_1), "t")
        assertEquals("Привет café", doc.paragraphs[0].text)
    }

    @Test
    fun hostileMarkupStaysLinear() {
        val html = "<p>" + "<".repeat(20_000) + "a <!-- x" + "<script".repeat(2_000) + "</p>"
        val started = System.nanoTime()
        HtmlImporter.parse(html, "t")
        val md = "[".repeat(20_000) + "**".repeat(10_000) + "~~".repeat(10_000) + "<!--" + "%%x".repeat(1)
        MarkdownImporter.parse(md, "t")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        // Closed comments still disappear; an unclosed one keeps the text.
        assertEquals(listOf("Раз три"), MarkdownImporter.parse("Раз <!-- два --> три", "t").paragraphs.map { it.text })
        assertEquals(listOf("<p>Текст</p>"), HtmlImporter.parse("<script>alert(1)</script><p>Текст</p><!-- c -->", "t").paragraphs.map { "<p>${it.text}</p>" })
    }

    @Test
    fun pdfLinesBecomeParagraphs() {
        val page = listOf(
            "Это длинная строка текста, которая переносится на следующую стро-",
            "ку и продолжается дальше почти до самого конца этой строки текста,",
            "а потом заканчивается коротко.",
            "12",
            "Новый абзац начинается здесь и тоже идёт на всю ширину строки.",
        ).joinToString("\n")
        val out = PdfTextLayout.paragraphs(listOf(page))
        assertEquals(
            "Это длинная строка текста, которая переносится на следующую строку и продолжается дальше почти " +
                "до самого конца этой строки текста, а потом заканчивается коротко.",
            out[0],
        )
        assertEquals(2, out.size)
    }

    @Test
    fun phraseBreaking() {
        val text = "Это очень важный момент, который стоит запомнить навсегда. Да."
        val positions = PhraseBreaker.breakPositions(text, 7)
        val chars = text.toCharArray()
        positions.forEach { chars[it] = '\n' }
        assertEquals(listOf("Это очень важный момент,", "который стоит запомнить навсегда.", "Да."), String(chars).lines())
    }

    @Test
    fun layoutOffsetsStayConsistent() {
        val doc = ScriptDocument("t", "t", listOf(Paragraph("Раз два, три четыре пять шесть семь восемь девять десять.", emphasis = listOf(4..6))))
        val m = ScriptLayout.build(doc, phraseMode = true, maxWords = 4)
        m.tokens.forEach { t -> assertEquals(t.norm, m.displayText.substring(t.start, t.end).lowercase()) }
        assertEquals("два", m.displayText.substring(m.emphasis[0].first, m.emphasis[0].last + 1))
        assertTrue(m.displayText.contains('\n'))
    }

    @Test
    fun markdownLongSpansAndHostileLines() {
        val cut = "вырезано ".repeat(150)
        assertEquals(
            listOf("Вступление ОСТАВИТЬ конец"),
            MarkdownImporter.parse("Вступление ~~$cut~~ ОСТАВИТЬ ~~коротко~~ конец", "t").paragraphs.map { it.text },
        )
        val bold = MarkdownImporter.parse("**${"жирно ".repeat(200)}** обычные **кратко**", "t").paragraphs[0]
        assertTrue(!bold.text.contains('*') && bold.emphasis.any { it.first == 0 })
        val image = "Вступление ![кадр](data:image/png;base64,${"A".repeat(6000)}) конец"
        assertEquals(listOf("Вступление конец"), MarkdownImporter.parse(image, "t").paragraphs.map { it.text })
        assertEquals("C#", MarkdownImporter.parse("# C#", "t").paragraphs[0].text)
        assertEquals("Заголовок", MarkdownImporter.parse("## Заголовок ##", "t").paragraphs[0].text)
        assertEquals(listOf("курсив и snake_case и 2*3*4"), MarkdownImporter.parse("*курсив* и _snake_case_ и 2*3*4", "t").paragraphs.map { it.text })

        val started = System.nanoTime()
        MarkdownImporter.parse("# a" + " ".repeat(20_000) + "b\n\n|--" + " ".repeat(20_000) + "x\n\n" + "*a ".repeat(100_000), "t")
        MarkdownImporter.parse("[a](x ".repeat(100_000) + "![a](x ".repeat(100_000), "t")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun htmlDeclarationsDataUrisAndOptionalHead() {
        val html = "<!DOCTYPE html><html><head><meta charset=utf-8><title>Мой</title>\n<body>" +
            "<p>Первый <img src=\"data:image/png;base64,${"A".repeat(6000)}\"> абзац.</p>" +
            "<![if !supportLists]>1. <![endif]><p>Второй.</p><noscript>без закрытия<p>Третий.</p></body></html>"
        val doc = HtmlImporter.parse(html, "t")
        assertEquals("Мой", doc.title)
        assertEquals(listOf("Первый абзац.", "1.", "Второй.", "без закрытия", "Третий."), doc.paragraphs.map { it.text })
        val xhtml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><html><body><p>Текст</p></body></html>"
        assertEquals(listOf("Текст"), HtmlImporter.parse(xhtml, "t").paragraphs.map { it.text })
    }

    @Test
    fun docxWithDtdIsRefusedWhereverItHides() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val body = "<w:document xmlns:w=\"$w\"><w:body><w:p><w:r><w:t>&b;</w:t></w:r></w:p></w:body></w:document>"
        val dtd = "<!DOCTYPE w:document [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">]>"
        // The text check only sees the first 4 KB; the parser must still refuse a DTD after a long comment.
        val late = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!--${"x".repeat(5000)}-->$dtd$body"
        for (androidLike in listOf(false, true)) {
            Sax.onlyLexicalGuard = androidLike
            try {
                val error = runCatching { DocxImporter.parse(zip("word/document.xml" to late), "t") }.exceptionOrNull()
                assertTrue("$error", error is ImportException && error.error == ImportError.UnsafeDocument)
            } finally {
                Sax.onlyLexicalGuard = false
            }
        }
    }

    @Test
    fun secondReviewRegressions() {
        // Link targets with parentheses (Wikipedia) and deep quote / rule lines.
        assertEquals(
            listOf("См. Меркурий и ."),
            MarkdownImporter.parse("См. [Меркурий](https://en.wikipedia.org/wiki/Mercury_(planet)) и ![фото](photo_(1).jpg).", "t")
                .paragraphs.map { it.text },
        )
        MarkdownImporter.parse(">".repeat(5000) + "x\n\n" + "> ".repeat(5000) + "\n\n" + "- ".repeat(5000) + "x\n\n" + "* ".repeat(5000), "t")
        assertEquals(listOf("цитата"), MarkdownImporter.parse("> > цитата", "t").paragraphs.map { it.text })
        assertTrue(MarkdownImporter.parse("* * *", "t").paragraphs.isEmpty())

        // "<body" mentioned inside a closed head must not end the head early.
        val html = "<html><head><style>/* <body> reset */ body{margin:0}</style><!-- <body> --><title>Статья</title></head>" +
            "<body><p>Текст</p></body></html>"
        assertEquals(listOf("Текст"), HtmlImporter.parse(html, "t").paragraphs.map { it.text })

        // DOCX: the text limit is an ImportException on the JVM too; nesting depth is capped.
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val long = "<w:document xmlns:w=\"$w\"><w:body><w:p><w:r><w:t>${"слово ".repeat(120_000)}</w:t></w:r></w:p></w:body></w:document>"
        val error = runCatching { DocxImporter.parse(zip("word/document.xml" to long), "t") }.exceptionOrNull()
        assertTrue("$error", error is ImportException && error.error is ImportError.TextTooLong)
        val nested = "<w:document xmlns:w=\"$w\"><w:body>" + "<w:p>".repeat(100_000) + "<w:r><w:t>глубоко</w:t></w:r>" +
            "</w:p>".repeat(100_000) + "</w:body></w:document>"
        assertEquals(listOf("глубоко"), DocxImporter.parse(zip("word/document.xml" to nested), "t").paragraphs.map { it.text })
    }

    @Test
    fun rtfWithAbsurdFontNumbersStillImports() {
        val rtf = "{\\rtf1\\ansi{\\fonttbl{\\f99999999999\\fnil\\fcharset99999999999 Arial;}}\\f0 Hello\\par}"
        assertEquals(listOf("Hello"), RtfImporter.parse(rtf.toByteArray(Charsets.ISO_8859_1), "t").paragraphs.map { it.text })
    }

    @Test
    fun importErrorsAndDefaultTitle() {
        fun importFile(bytes: ByteArray, name: String?) =
            DocumentImporter.import(bytes, name, null, "Untitled") { _, _ -> error("no PDF here") }
        fun errorOf(block: () -> Any) = (runCatching(block).exceptionOrNull() as? ImportException)?.error

        assertEquals(ImportError.EmptyFile, errorOf { importFile(ByteArray(0), "a.txt") })
        assertEquals(ImportError.NoText, errorOf { importFile(" \n\n ".toByteArray(), "a.txt") })
        assertEquals(ImportError.NotText, errorOf { importFile(ByteArray(100) { 1 }, "a.bin") })
        val ole2 = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0)
        assertEquals(ImportError.LegacyDoc, errorOf { importFile(ole2, "a.doc") })
        assertEquals(ImportError.UnknownZip, errorOf { importFile(zip("mimetype" to "x"), "a.zip") })
        assertEquals(ImportError.NotRtf, errorOf { RtfImporter.parse("{\\rt".toByteArray(), "t") })
        val long = "слово ".repeat(60_000)
        assertEquals(ImportError.TextTooLong(359, 300), errorOf { DocumentImporter.checkSize(DocumentImporter.fromPlainText(long, "t")) })

        // The caller's title (in the UI language) only when the file has no name.
        assertEquals("Untitled", importFile("Text".toByteArray(), null).title)
        assertEquals("notes", importFile("Text".toByteArray(), "notes.txt").title)
    }

    @Test
    fun warningCodesAndLegacySentences() {
        for (warning in listOf(ImportWarning.PdfParagraphsRebuilt, ImportWarning.DocxTables(3), ImportWarning.Raw("Что-то: raw:1"))) {
            assertEquals(warning, ImportWarning.fromCode(warning.code))
        }
        assertNull(ImportWarning.fromCode("from_a_newer_version"))

        // What 0.3 saved with the script.
        assertEquals(
            ImportWarning.PdfParagraphsRebuilt,
            ImportWarning.fromLegacyText("Абзацы в PDF восстановлены по строкам — проверь, как разбился текст."),
        )
        assertEquals(
            ImportWarning.DocxTables(2),
            ImportWarning.fromLegacyText("В документе есть таблицы (2): их ячейки показаны по порядку, как обычный текст."),
        )
        assertEquals(ImportWarning.Raw("Другое"), ImportWarning.fromLegacyText("Другое"))

        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val table = "<w:document xmlns:w=\"$w\"><w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>ячейка</w:t></w:r></w:p></w:tc></w:tr>" +
            "</w:tbl></w:body></w:document>"
        assertEquals(listOf(ImportWarning.DocxTables(1)), DocxImporter.parse(zip("word/document.xml" to table), "t").warnings)
    }

    @Test
    fun builtInSampleInBothLanguages() {
        for ((dir, lang, bold) in listOf(
            Triple("values", SpeechLang.EN, "Read a couple of lines"),
            Triple("values-ru", SpeechLang.RU, "Прочитай пару строк"),
        )) {
            val doc = MarkdownImporter.parse(resourceString(dir, "sample_script"), resourceString(dir, "sample_title"))
            assertEquals(Paragraph.Kind.HEADING, doc.paragraphs[0].kind)
            assertEquals(5, doc.paragraphs.size)
            assertEquals(listOf(bold), doc.paragraphs.flatMap { p -> p.emphasis.map { p.text.substring(it.first, it.last + 1) } })
            assertTrue(doc.paragraphs[3].text.startsWith("["))
            assertEquals(lang, SpeechLang.detect(doc))
        }
        // Lint isn't part of the build: every string of this area needs its Russian twin.
        assertEquals(resourceNames("values"), resourceNames("values-ru"))
    }

    private fun resourceElements(dir: String): List<Element> {
        val file = listOf("src/main/res", "app/src/main/res").map { File(it, "$dir/strings_import.xml") }.first { it.exists() }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
    }

    private fun resourceNames(dir: String) = resourceElements(dir).map { it.getAttribute("name") }.toSet()

    /** A string as the app sees it: the resource compiler resolves these escapes. */
    private fun resourceString(dir: String, name: String): String =
        resourceElements(dir).first { it.tagName == "string" && it.getAttribute("name") == name }.textContent
            .replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"")

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
