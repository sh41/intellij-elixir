package org.elixir_lang.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.Operator

/**
 * Elixir's tokenizer has no operand left to shift once an infix operator is the last real token in the file, so it
 * reports the syntax error at the operator itself, with nothing further to name. The grammar instead leaves a
 * zero-width [PsiErrorElement] naming the rule it expected next, right after the operator - which, with nothing
 * left to consume, is the end of the file. An annotation's range must stay inside the element being visited, so
 * this visits the operator, rather than the error element, to report there.
 */
internal class MissingOperand : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val operator = element as? Operator ?: return
        val error = (element.nextSibling as? PsiErrorElement)?.takeIf { it.textLength == 0 } ?: return
        if (hasRealContentAfter(error)) return
        if (Injection.of(element) == Injection.UNCOMPILED) return

        // A release before 1.13 stops reading at its own `**`; anything at or after it is a consequence of the split
        // it never makes, which VersionedSyntax already reports in Elixir's own words, not a second, real error.
        val stop = powerStop(element)
        if (stop != null && element.textRange.startOffset >= stop) return

        holder.error(element.textRange, syntaxErrorBefore(""))
    }
}

/** Whether [errorElement] is the zero-width trailer an infix operator's own rule leaves with nothing left to shift. */
internal fun isMissingOperandTrailer(errorElement: PsiErrorElement): Boolean =
    errorElement.textLength == 0 && errorElement.prevSibling is Operator

internal fun hasRealContentAfter(element: PsiElement): Boolean =
    generateSequence(PsiTreeUtil.nextLeaf(element)) { PsiTreeUtil.nextLeaf(it) }
        .any { it !is PsiWhiteSpace && it !is PsiComment }
