package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * Expected positions were taken from `Code.string_to_quoted/1` on 1.12.3/OTP 24.3.4.6.
 */
class PowerOperatorErrorFilterTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /**
     * `powerStop` names one offset for the whole file - where the parser stopped reading because of a `**` no
     * release before 1.13 has - so hiding every error unconditionally hides errors before that point too, which
     * are real and are what Elixir actually reports first. Elixir stops at its first error; `2 ** 3` on line 2
     * here is not it.
     */
    fun testDoesNotHideAnErrorBeforeThePowerOperator() {
        val source = "a = (1 +)\nb = 2 ** 3"

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.12.3"))
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)

        assertTrue(
            "an error at the ')' (offset 8) among $errors",
            errors.any { it.startOffset <= 8 && it.endOffset >= 8 }
        )
    }

    fun testHidesAnErrorAtOrAfterThePowerOperator() {
        val source = "x = 2 ** 3"

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.12.3"))
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        val powerOffset = source.indexOf("**")

        assertTrue(
            "no error at or after the '**' (offset $powerOffset) among $errors",
            errors.none { it.startOffset > powerOffset }
        )
    }
}
