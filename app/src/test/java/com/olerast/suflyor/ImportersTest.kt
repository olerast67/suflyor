package com.olerast.suflyor

import com.olerast.suflyor.doc.DocumentImporter
import com.olerast.suflyor.doc.DocxImporter
import com.olerast.suflyor.doc.HtmlImporter
import com.olerast.suflyor.doc.MarkdownImporter
import com.olerast.suflyor.doc.OdtImporter
import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.PdfTextLayout
import com.olerast.suflyor.doc.PlainTextImporter
import com.olerast.suflyor.doc.RtfImporter
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.doc.TextDecoding
import com.olerast.suflyor.script.PhraseBreaker
import com.olerast.suflyor.script.ScriptLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
