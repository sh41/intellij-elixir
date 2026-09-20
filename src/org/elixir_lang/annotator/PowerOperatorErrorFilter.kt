package org.elixir_lang.annotator

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import org.elixir_lang.ElixirLanguage

/**
 * Hides the parser's complaints where a release before 1.13 stops at a `**`. Its tokenizer reads no further, so its
 * one error is the `**` [VersionedSyntax] reports in Elixir's own words, and the grammar's own complaints are all
 * consequences of a split it never makes.
 */
internal class PowerOperatorErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (!element.language.isKindOf(ElixirLanguage)) return true

        val stop = powerStop(element) ?: return true

        // Elixir reports one error per file; anything strictly before where a `**` stops it is real and unrelated,
        // and is what Elixir actually reports first. At or after that point, every error is a consequence of the
        // split `**` was never given, `VersionedSyntax`'s own report there included.
        return element.textRange.startOffset < stop
    }
}
