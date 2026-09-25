package com.olerast.suflyor.doc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decodes text files of unknown encoding: BOMs, strict UTF-8, UTF-16 without BOM, then Cyrillic single-byte guesses. */
object TextDecoding {
    private val CP1251: Charset = Charset.forName("windows-1251")
    private val KOI8R: Charset = Charset.forName("KOI8-R")

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        utf16WithoutBom(bytes)?.let { return it }
        strictUtf8(bytes)?.let { return it }
        val cp1251 = String(bytes, CP1251)
        val koi8 = String(bytes, KOI8R)
        return if (cyrillicScore(koi8) > cyrillicScore(cp1251)) koi8 else cp1251
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** In UTF-16 text (Latin or Cyrillic) every high byte is 0x00 or 0x04; single-byte encodings never look like that. */
    private fun utf16WithoutBom(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        val n = minOf(bytes.size, 4000) / 2 * 2
        var highEven = 0
        var highOdd = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt()
            if (b == 0 || b == 4) {
                if (i % 2 == 0) highEven++ else highOdd++
            }
        }
        val half = n / 2
        return when {
            highOdd > half * 0.7 && highEven < half * 0.1 -> String(bytes, Charsets.UTF_16LE)
            highEven > half * 0.7 && highOdd < half * 0.1 -> String(bytes, Charsets.UTF_16BE)
            else -> null
        }
    }

    /** Frequent lowercase Russian letters score up, uppercase letters inside words score down. */
    private fun cyrillicScore(s: String): Int {
        var score = 0
        var prevLetter = false
        for (ch in s) {
            if (ch in "оеаинтсрвлкмдпу") score += 2
            if (ch in 'А'..'Я' && prevLetter) score -= 3
            prevLetter = ch.isLetter()
        }
        return score
    }
}

object PlainTextImporter {
    fun parse(text: String, title: String, format: String = "TXT"): ScriptDocument {
        val src = text.replace("\r\n", "\n").replace('\r', '\n')
        val hasBlankLines = Regex("\n[ \t ]*\n").containsMatchIn(src.trim())
        val paragraphs = mutableListOf<Paragraph>()
        val pb = ParagraphBuilder()
        fun flush() {
            pb.build()?.let { paragraphs += it }
            pb.clear()
        }
        for (line in src.split('\n')) {
            if (line.isBlank()) {
                flush()
                continue
            }
            if (hasBlankLines) {
                // Blocks are separated by blank lines; single line breaks inside a block are soft wraps.
                pb.append(" ")
                pb.append(line)
            } else {
                // No blank lines at all: every line is its own paragraph (typical for notes apps).
                pb.append(line)
                flush()
            }
        }
        flush()
        return ScriptDocument(title, format, paragraphs)
    }
}
