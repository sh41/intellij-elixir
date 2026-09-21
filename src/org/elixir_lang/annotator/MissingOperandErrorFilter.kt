package org.elixir_lang.annotator

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import org.elixir_lang.ElixirLanguage

/** Hides the grammar's own report for the gap [MissingOperand] already explains at the operator, in Elixir's words. */
internal class MissingOperandErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (!element.language.isKindOf(ElixirLanguage)) return true
        if (!isMissingOperandTrailer(element)) return true

        return hasRealContentAfter(element)
    }
}
