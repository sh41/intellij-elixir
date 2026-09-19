package org.elixir_lang.injection.markdown

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * A documentation attribute whose value is an expression starting with a string must not inject into that
 * string: `isValidHost` rejects it and the platform throws.
 *
 * Injection is driven through `enumerateEx` on every element because `getInjectedPsiFiles` returns `null` for
 * an invalid host instead of throwing.
 */
class ExpressionDocInjectorTest : PlatformTestCase() {
    fun testLineConcatenated() = assertNoInjection("@doc \"text\" <> \"more\"")
    fun testLineAccessed() = assertNoInjection("@doc \"text\"[0]")
    fun testLinePiped() = assertNoInjection("@doc \"text\" |> String.trim()")
    fun testHeredocConcatenated() = assertNoInjection("@doc \"\"\"\n  text\n  \"\"\" <> \"more\"")
    fun testModuledocPiped() =
        assertNoInjection("@moduledoc \"README.md\" |> File.read!() |> String.split(\"<!-- MDOC !-->\") |> Enum.fetch!(1)")
    fun testTypedocConcatenated() = assertNoInjection("@typedoc \"text\" <> \"more\"")
    fun testDeprecatedConcatenated() = assertNoInjection("@doc deprecated: \"text\" <> \"more\"")

    fun testLine() = assertMarkdown("@doc \"text\"")
    fun testHeredoc() = assertMarkdown("@doc \"\"\"\n  text\n  \"\"\"")
    fun testDeprecated() = assertMarkdown("@doc deprecated: \"text\"")

    private fun assertNoInjection(doc: String) {
        val languages = injectedLanguages(doc)

        assertEmpty("`$doc` must not inject, but injected $languages", languages)
    }

    private fun assertMarkdown(doc: String) {
        val languages = injectedLanguages(doc)

        assertTrue("`$doc` must inject Markdown, but injected $languages", MarkdownLanguage.INSTANCE in languages)
    }

    private fun injectedLanguages(doc: String): List<Language> {
        myFixture.configureByText("doc.ex", "defmodule Doc do\n  $doc\n  def f, do: 1\nend\n")
        val file = myFixture.file
        val manager = InjectedLanguageManager.getInstance(project)
        val languages = mutableListOf<Language>()

        val (_, loggedErrors) = captureLoggedErrors {
            PsiTreeUtil.collectElements(file) { true }.forEach { element ->
                manager.enumerateEx(element, file, true) { injectedPsi, _ -> languages.add(injectedPsi.language) }
            }
            myFixture.doHighlighting()
        }

        assertEmpty("`$doc` logged errors: ${loggedErrors.map { it.message }}", loggedErrors)

        return languages
    }
}
