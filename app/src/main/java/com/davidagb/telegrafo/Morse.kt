package com.davidagb.telegrafo

import java.text.Normalizer
import kotlin.math.roundToLong

object Morse {

    val TABLE: Map<Char, String> = linkedMapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'Ñ' to "--.--", 'O' to "---", 'P' to ".--.", 'Q' to "--.-",
        'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--",
        'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '¿' to "..-.-", '!' to "-.-.--",
        '¡' to "--...-", '\'' to ".----.", '"' to ".-..-.", '/' to "-..-.", '(' to "-.--.",
        ')' to "-.--.-", '&' to ".-...", ':' to "---...", ';' to "-.-.-.", '=' to "-...-",
        '+' to ".-.-.", '-' to "-....-", '_' to "..--.-", '$' to "...-..-", '@' to ".--.-."
    )

    private val REVERSE: Map<String, Char> = TABLE.entries.associate { it.value to it.key }
    private val MARKS = Regex("\\p{Mn}+")

    /** Una letra con su código, o un hueco entre palabras. ch/code nulos = sin traducción. */
    data class Token(val gap: Boolean = false, val ch: String? = null, val code: String? = null)

    data class Segment(val onMs: Long, val offMs: Long, val tokenIndex: Int, val first: Boolean)

    /** Mayúsculas y sin tildes, salvo la Ñ que tiene código propio. */
    fun normalize(s: String): String {
        val u = s.uppercase()
        if (u == "Ñ") return u
        return Normalizer.normalize(u, Normalizer.Form.NFD).replace(MARKS, "")
    }

    fun codeFor(c: Char): String? = normalize(c.toString()).singleOrNull()?.let { TABLE[it] }

    fun fromText(text: String): List<Token> {
        val out = ArrayList<Token>()
        var gap = true
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp)) {
                if (!gap) out += Token(gap = true)
                gap = true
                continue
            }
            val raw = String(Character.toChars(cp))
            val n = normalize(raw).ifEmpty { raw }
            out += Token(ch = n, code = n.singleOrNull()?.let { TABLE[it] })
            gap = false
        }
        if (out.lastOrNull()?.gap == true) out.removeAt(out.lastIndex)
        return out
    }

    /** Admite también ·, —, – y | que llegan al pegar mensajes de otras apps. */
    fun clean(c: Char): Char = when (c) {
        '·', '•', '∙', '⋅' -> '.'
        '−', '—', '–', '_' -> '-'
        '|' -> '/'
        else -> c
    }

    fun fromMorse(text: String): List<Token> {
        val out = ArrayList<Token>()
        val cleaned = text.map { clean(it) }.joinToString("")
        for (word in cleaned.split('/')) {
            val letters = word.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (letters.isEmpty()) continue
            if (out.isNotEmpty()) out += Token(gap = true)
            for (code in letters) {
                val ok = code.all { it == '.' || it == '-' }
                out += Token(ch = if (ok) REVERSE[code]?.toString() else null, code = if (ok) code else null)
            }
        }
        return out
    }

    fun toMorseString(tokens: List<Token>): String =
        tokens.filter { it.gap || it.code != null }.joinToString(" ") { if (it.gap) "/" else it.code!! }

    fun toTextString(tokens: List<Token>): String =
        tokens.joinToString("") { if (it.gap) " " else it.ch ?: "" }

    /** Punto 1 unidad, raya 3, entre signos 1, entre letras 3, entre palabras 7. */
    fun timeline(tokens: List<Token>, unitMs: Double): Pair<List<Segment>, Long> {
        val segs = ArrayList<Segment>()
        var t = 0.0
        tokens.forEachIndexed { i, tok ->
            if (tok.gap) {
                t += 4 * unitMs
                return@forEachIndexed
            }
            val code = tok.code ?: return@forEachIndexed
            code.forEachIndexed { j, s ->
                val len = (if (s == '.') 1 else 3) * unitMs
                segs += Segment(t.roundToLong(), (t + len).roundToLong(), i, j == 0)
                t += len + unitMs
            }
            t += 2 * unitMs
        }
        return segs to t.roundToLong()
    }
}
