package org.elixir_lang.mix

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.stabBodyChildExpressions
import org.elixir_lang.psi.impl.childExpressions

object Deps {
    fun from(depsListElement: PsiElement, isDependency: Boolean): Sequence<Dep> =
            when (depsListElement) {
                is ElixirTuple -> fromTuple(depsListElement, isDependency)
                is Call -> fromCall(depsListElement, isDependency)
                else -> emptySequence()
            }


    // `ecto_dep()` in `ecto_sql` `deps`
    private fun fromCall(depsListElement: Call, isDependency: Boolean): Sequence<Dep> =
            depsListElement
                    .reference?.let { it as PsiPolyVariantReference }
                    ?.multiResolve(false)
                    ?.asSequence()
                    ?.filter(ResolveResult::isValidResult)
                    ?.mapNotNull(ResolveResult::getElement)
                    ?.filterIsInstance<Call>()
                    ?.filter { CallDefinitionClause.`is`(it) }
                    ?.flatMap { fromCallDefinitionClause(it, isDependency) }
                    .orEmpty()

    private fun fromCallDefinitionClause(callDefinitionClause: Call, isDependency: Boolean): Sequence<Dep> =
            callDefinitionClause
                    .stabBodyChildExpressions()
                    ?.flatMap { fromChildExpression(it, isDependency) }
                    ?: emptySequence()

    private tailrec fun fromChildExpression(childExpression: PsiElement, isDependency: Boolean): Sequence<Dep> {
        ProgressManager.checkCanceled()

        return when (childExpression) {
            is Call -> fromChildExpression(childExpression, isDependency)
            // Not `stripAccessExpression()`: it returns an access expression without exactly one child unchanged, and
            // this tail call would revisit it forever.
            is ElixirAccessExpression ->
                fromChildExpression(childExpression.children.singleOrNull() ?: return emptySequence(), isDependency)
            is ElixirTuple -> fromTuple(childExpression, isDependency)
            else -> {
                emptySequence()
            }
        }
    }

    private fun fromChildExpression(childExpression: Call, isDependency: Boolean): Sequence<Dep> =
        if (childExpression.isCalling(KERNEL, "if")) {
            fromIf(childExpression, isDependency)
        } else {
            emptySequence()
        }

    private fun fromTuple(tuple: ElixirTuple, isDependency: Boolean): Sequence<Dep> =
            Dep.from(tuple, isDependency)?.let { sequenceOf(it) }.orEmpty()

    private fun fromIf(`if`: Call, isDependency: Boolean): Sequence<Dep> {
        val branchStabSequences = `if`.doBlock?.let { doBlock ->
            val trueStab = doBlock.stab
            val falseStab = doBlock
                    .blockList
                    ?.blockItemList?.singleOrNull { it.blockIdentifier.text == "else" }
                    ?.stab

            sequenceOf(trueStab, falseStab).filterNotNull()
        } ?: emptySequence()

        return branchStabSequences
                .flatMap { stab -> stab.stabBody?.childExpressions().orEmpty() }
                .flatMap { fromChildExpression(it, isDependency) }
    }
}
