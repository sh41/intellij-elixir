package org.elixir_lang.annotator

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirInterpolatedSigilLine
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirLiteralSigilLine

/** Hides the grammar's own recovery error for the gap [InvalidConstruct] already reports in Elixir's own words. */
internal class UnterminatedQuoteErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (!element.language.isKindOf(ElixirLanguage)) return true

        val quote = PsiTreeUtil.getParentOfType(
            element,
            ElixirLine::class.java,
            ElixirInterpolatedSigilLine::class.java,
            ElixirLiteralSigilLine::class.java,
            ElixirAtom::class.java,
        ) ?: return true

        return !isUnterminatedQuote(quote)
    }
}
