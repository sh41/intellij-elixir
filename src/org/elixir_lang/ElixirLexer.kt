package org.elixir_lang

import com.intellij.lexer.LookAheadLexer
import com.intellij.lexer.MergingLexerAdapter
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.TokenSet
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirTypes

class ElixirLexer(private val elixirFlexLexerAdapter: ElixirFlexLexerAdapter) :
        LookAheadLexer(MergingLexerAdapter(elixirFlexLexerAdapter, FRAGMENTS)) {
    @JvmOverloads
    constructor(project: Project?, languageLevel: ElixirLanguageLevel? = null) :
            this(ElixirFlexLexerAdapter(project, languageLevel))
    constructor(): this(null)

    fun stackSize(): Int = elixirFlexLexerAdapter.stackSize()

    companion object {
        val FRAGMENTS = TokenSet.create(
                ElixirTypes.ATOM_FRAGMENT,
                ElixirTypes.FRAGMENT
        )
    }
}
