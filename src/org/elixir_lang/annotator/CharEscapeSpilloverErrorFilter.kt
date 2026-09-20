package org.elixir_lang.annotator

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.psi.ElixirCharToken

/**
 * Hides the parser's error for a token [spilledCharEscapeToken] already folded into a `?\x`/`?\u` error that
 * [InvalidConstruct] reports in Elixir's own words.
 */
internal class CharEscapeSpilloverErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (!element.language.isKindOf(ElixirLanguage)) return true

        val leaf = element.firstChild?.takeIf { it.nextSibling == null } ?: return true
        val previous = PsiTreeUtil.prevLeaf(leaf)?.takeIf { it.textRange.endOffset == leaf.textRange.startOffset }
            ?: return true
        val charToken = PsiTreeUtil.getParentOfType(previous, ElixirCharToken::class.java, false) ?: return true

        return spilledCharEscapeToken(charToken)?.second != leaf
    }
}
