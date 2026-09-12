package org.elixir_lang.annotator.unicode_security

import com.intellij.openapi.util.TextRange
import org.elixir_lang.psi.quoting.QuotingDialect
import org.elixir_lang.psi.quoting.QuotingDialect.*
import java.text.Normalizer
import java.util.BitSet

/**
 * What Elixir's tokenizer rejects for Unicode security reasons, as it did in the release a [QuotingDialect] stands for:
 * bidirectional formatting and line break characters in comments and quoted text, and identifiers with a restricted
 * code point or a mix of scripts.
 */
internal object UnicodeSecurityCheck {
    class Problem(val range: TextRange, val message: String)

    fun inComment(text: CharSequence, dialect: QuotingDialect): List<Problem> =
        characters(text) { character ->
            when {
                isBidi(character) && dialect >= V1_13 ->
                    "invalid bidirectional formatting character in comment: ${escaped(character)}"

                isLineBreak(character) && dialect >= V1_19 ->
                    "invalid line break character in comment: ${escaped(character)}"

                else -> null
            }
        }

    fun inQuoted(text: CharSequence, dialect: QuotingDialect): List<Problem> =
        characters(text) { character ->
            when {
                isBidi(character) && dialect >= V1_13 -> inString("invalid bidirectional formatting character", character)
                isLineBreak(character) && dialect >= V1_20 -> inString("invalid line break character", character)
                else -> null
            }
        }

    fun hasBidiOrLineBreak(text: CharSequence): Boolean = text.any { isBidi(it) || isLineBreak(it) }

    fun inIdentifier(text: CharSequence, dialect: QuotingDialect): Problem? {
        val table = IdentifierTable.forUnicode(unicodeVersion(dialect) ?: return null)
        val codePoints = mutableListOf<Int>()
        var scriptSet: BitSet? = null
        var offset = 0

        while (offset < text.length) {
            val original = Character.codePointAt(text, offset)
            var codePoint = original
            val first = offset == 0

            when {
                isAsciiLetter(codePoint) -> scriptSet = intersect(scriptSet, table.latin)
                codePoint == '_'.code || codePoint in '0'.code..'9'.code || codePoint == '@'.code -> Unit
                codePoint == '?'.code || codePoint == '!'.code -> {
                    codePoints.add(codePoint)
                    offset++
                    break
                }
                codePoint <= 127 -> break
                else -> {
                    val accepted = table.classesOf(codePoint) and
                        (if (first) IdentifierTable.UPPER or IdentifierTable.START else IdentifierTable.UPPER or IdentifierTable.START or IdentifierTable.CONTINUE)

                    if (accepted == 0) {
                        if (codePoint != MICRO_SIGN) {
                            return Problem(
                                TextRange(offset, offset + Character.charCount(codePoint)),
                                "unexpected token: \"${String(Character.toChars(codePoint))}\" (code point U+%04X)".format(codePoint)
                            )
                        }

                        codePoint = GREEK_SMALL_LETTER_MU
                    }

                    scriptSet = intersect(scriptSet, table.scriptSetOf(codePoint))
                }
            }

            codePoints.add(codePoint)
            offset += Character.charCount(original)
        }

        if (scriptSet == null || !scriptSet.isEmpty) return null

        val normalized = Normalizer
            .normalize(String(codePoints.toIntArray(), 0, codePoints.size), Normalizer.Form.NFC)
        val normalizedCodePoints = normalized.codePoints().toArray()
        val accepted = if (dialect >= V1_18) {
            chunksSingle(normalizedCodePoints, table)
        } else {
            highlyRestrictive(normalizedCodePoints, table)
        }

        return if (accepted) null else Problem(TextRange(0, offset), "invalid mixed-script identifier found: $normalized")
    }

    private const val MICRO_SIGN = 0x00B5
    private const val GREEK_SMALL_LETTER_MU = 0x03BC

    private fun unicodeVersion(dialect: QuotingDialect): String? =
        when (dialect) {
            V1_11, V1_12, V1_13 -> null
            V1_14 -> "14.0"
            V1_15 -> "15.0"
            V1_16_0, V1_16_2, V1_17 -> "15.1"
            V1_18 -> "16.0"
            V1_19, V1_20 -> "17.0"
        }

    private fun isAsciiLetter(codePoint: Int) = codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code

    private fun isBidi(character: Char) = character.code in 0x202A..0x202E || character.code in 0x2066..0x2069

    private fun isLineBreak(character: Char) =
        when (character.code) {
            0x000B, 0x000C, 0x0085, 0x2028, 0x2029 -> true
            else -> false
        }

    private fun escaped(character: Char) = "\\u%04X".format(character.code)

    private fun inString(prefix: String, character: Char) =
        "$prefix in string: ${escaped(character)}. If you want to use such character, use it in its escaped ${escaped(character)} form instead"

    private inline fun characters(text: CharSequence, message: (Char) -> String?): List<Problem> =
        text.indices.mapNotNull { index -> message(text[index])?.let { Problem(TextRange(index, index + 1), it) } }

    /** `null` is the set of every script. */
    private fun intersect(left: BitSet?, right: BitSet?): BitSet? =
        when {
            left == null -> right
            right == null -> left
            else -> (left.clone() as BitSet).apply { and(right) }
        }

    /** Like the tokenizer's `codepoint_to_scriptset`: a code point it does not tokenize belongs to every script. */
    private fun chunkScriptSetOf(codePoint: Int, table: IdentifierTable): BitSet? =
        if (isAsciiLetter(codePoint)) table.latin else table.scriptSetOf(codePoint)

    private fun chunksSingle(codePoints: IntArray, table: IdentifierTable): Boolean {
        var chunk: BitSet? = null

        for (codePoint in codePoints) {
            if (codePoint == '_'.code) {
                if (chunk != null && chunk.isEmpty) return false
                chunk = null
            } else {
                chunk = intersect(chunk, chunkScriptSetOf(codePoint, table))
            }
        }

        return chunk == null || !chunk.isEmpty
    }

    private fun highlyRestrictive(codePoints: IntArray, table: IdentifierTable): Boolean =
        table.highlyRestrictive.any { restrictive ->
            codePoints.all { codePoint -> chunkScriptSetOf(codePoint, table)?.intersects(restrictive) ?: true }
        }
}
