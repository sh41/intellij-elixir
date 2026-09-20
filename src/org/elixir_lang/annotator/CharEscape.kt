package org.elixir_lang.annotator

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.ElixirCharToken

/**
 * `\x` and `\u` are never escapes after `?`: Elixir warns and reads `?x` or `?u`, so what follows is a token of its
 * own that nothing can take, and it reports there. The plugin's lexer still tries to read a hexadecimal or Unicode
 * escape until it runs out of hexadecimal digits, so that following token is sometimes split across the char
 * token's own end - `?\xAz` lexes `A` inside the char token (a hex digit) and `z` as a separate leaf the grammar
 * has nowhere to put. Both belong to Elixir's one error, so this walks forward from the char token absorbing every
 * leaf immediately adjacent and identifier-shaped, the same as the plugin's own lexer would have.
 *
 * Returns the whole token's text and the last leaf it spans, or null where a digit starts the token: that is a
 * different Elixir message ("invalid character ... after number"), not handled here.
 */
internal fun spilledCharEscapeToken(charToken: ElixirCharToken): Pair<String, PsiElement>? {
    val text = charToken.text
    if (text.length < 3 || text[1] != '\\' || (text[2] != 'x' && text[2] != 'u')) return null

    val rest = text.substring(3)
    val identifierRun = rest.takeWhile { it.isLetterOrDigit() || it == '_' }

    // A punctuation character right after the escape - `{`, from the braced form - is a token of its own; Elixir
    // never merges it with anything else, so nothing here spills across the char token's boundary for it.
    if (identifierRun.isEmpty() && rest.isNotEmpty()) {
        val punctuation = charToken.containingFile.findElementAt(charToken.textRange.startOffset + 3) ?: charToken
        return rest.take(1) to punctuation
    }
    // A digit-starting run is a different Elixir message ("invalid character ... after number"), not this one.
    if (identifierRun.isNotEmpty() && identifierRun[0].isDigit()) return null

    val token = StringBuilder(identifierRun)
    var last: PsiElement = charToken

    // Only look beyond the char token when the identifier run filled all of `rest` - a shorter run means something
    // non-identifier followed inside the token itself, which already ends what Elixir would read as one token.
    if (identifierRun.length == rest.length) {
        var next = PsiTreeUtil.nextLeaf(last)

        while (
            next != null &&
            next.textRange.startOffset == last.textRange.endOffset &&
            next.text.isNotEmpty() &&
            next.text.all { it.isLetterOrDigit() || it == '_' }
        ) {
            token.append(next.text)
            last = next
            next = PsiTreeUtil.nextLeaf(last)
        }
    }

    return token.toString().takeIf { it.isNotEmpty() }?.let { it to last }
}
