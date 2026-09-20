package org.elixir_lang

import com.intellij.lexer.FlexAdapter
import com.intellij.openapi.project.Project
import org.elixir_lang.language_level.ElixirLanguageLevel

import java.io.Reader

/**
 * [languageLevel] is the release the text is written for: a few spellings, such as `**`, are one token in some
 * releases and two in others. `null` assumes [ElixirLanguageLevel.FALLBACK], which is what highlighting outside a
 * parse gets.
 */
class ElixirFlexLexerAdapter @JvmOverloads constructor(
    project: Project?,
    languageLevel: ElixirLanguageLevel? = null,
) : FlexAdapter(ElixirFlexLexer(null as Reader?)) {
    init {
        flex.project = project
        flex.setLanguageLevel(languageLevel)
    }

    override fun getFlex(): ElixirFlexLexer = super.getFlex() as ElixirFlexLexer

    /**
     * Clears the [ElixirFlexLexer] state stack before each lex so that leftover
     * `pushAndBegin`/`popAndBegin` state from a previous document cannot corrupt the next one.
     *
     * This is the canonical place for this reset: [ElixirFlexLexer] is JFlex-generated and
     * cannot be customised to clear the stack automatically on `reset()`.
     */
    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        flex.clearStack()
        super.start(buffer, startOffset, endOffset, initialState)
    }

    fun stackSize(): Int = flex.stackSize()
}
