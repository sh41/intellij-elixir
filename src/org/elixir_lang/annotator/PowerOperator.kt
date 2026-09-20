package org.elixir_lang.annotator

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.language_level.ElixirLanguageFeature.POWER_OPERATOR
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.ElixirTypes

private val POWER_STOP = Key.create<CachedValue<Int>>("ELIXIR_POWER_OPERATOR_STOP")

/**
 * Where a release before 1.13 stops reading [element]'s file: at the `**` [VersionedSyntax] reports, since Elixir's
 * tokenizer gets no further and so neither the grammar nor an annotator has anything to say about what follows. Null
 * where the release has the operator, or where no `**` stops it.
 */
internal fun powerStop(element: PsiElement): Int? {
    if (ElixirLanguageLevelResolver.isAvailable(POWER_OPERATOR, element)) return null

    val file = element.containingFile ?: return null
    val stop = CachedValuesManager.getCachedValue(file, POWER_STOP) {
        CachedValueProvider.Result.create(
            VersionedSyntax().powerStart(file) { ElixirLanguageLevelResolver.languageLevelFor(file) } ?: -1,
            file,
        )
    }

    return stop.takeIf { it >= 0 }
}

/** Every `**` in [element], as the two `*` a release before 1.13 reads it as, in the order they appear. */
internal fun starPairs(element: PsiElement): Sequence<Pair<PsiElement, PsiElement>> =
    generateSequence(PsiTreeUtil.getDeepestFirst(element)) { PsiTreeUtil.nextLeaf(it) }
        .takeWhile { it.textRange.startOffset < element.textRange.endOffset }
        // A failed operand leaves a zero-width error element between the two `*`.
        .filter { it.textLength > 0 }
        .zipWithNext()
        .filter { (star, next) ->
            star.isStar() && next.isStar() && star.textRange.endOffset == next.textRange.startOffset
        }

private fun PsiElement.isStar(): Boolean = node?.elementType == ElixirTypes.MULTIPLICATION_OPERATOR
