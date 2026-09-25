package com.olerast.suflyor.script

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument

/** Language the reader speaks: picks the recognizer model and the rules for comparing heard and written words. */
enum class SpeechLang(val code: String) {
    RU("ru"),
    EN("en");

    companion object {
        fun fromCode(code: String?): SpeechLang? = entries.firstOrNull { it.code == code }

        /**
         * Majority alphabet of what the reader will say: body paragraphs outside [stage directions].
         * Ties and empty scripts count as Russian.
         */
        fun detect(doc: ScriptDocument): SpeechLang {
            var cyrillic = 0
            var latin = 0
            for (p in doc.paragraphs) {
                if (p.kind != Paragraph.Kind.BODY) continue
                var note = false
                for (ch in p.text) {
                    when {
                        ch == '[' -> note = true
                        ch == ']' -> note = false
                        note || !ch.isLetter() -> Unit
                        else -> when (Character.UnicodeScript.of(ch.code)) {
                            Character.UnicodeScript.CYRILLIC -> cyrillic++
                            Character.UnicodeScript.LATIN -> latin++
                            else -> Unit
                        }
                    }
                }
            }
            return if (latin > cyrillic) EN else RU
        }
    }
}
