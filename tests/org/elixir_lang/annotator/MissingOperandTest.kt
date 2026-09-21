package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType

/**
 * Expected positions and messages were taken from `Code.string_to_quoted/1` on 1.20.4/OTP 29.0.6: with nothing
 * left to shift after an infix operator, Elixir's tokenizer reports the syntax error at the operator itself,
 * naming nothing further.
 */
class MissingOperandTest : BasePlatformTestCase() {
    fun testReportsAtTheOperatorForEveryOperatorShape() {
        val cases = listOf(
            "1 +" to 2,
            "x.." to 1,
            "foo .." to 4,
            "0b1_0and" to 5,
            "0o7and" to 3,
        )

        for ((source, operatorStart) in cases) {
            myFixture.configureByText("missing_operand_${source.hashCode()}.${ElixirFileType.INSTANCE.defaultExtension}", source)
            val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)

            assertTrue("exactly one error for $source, got $errors", errors.size == 1)
            assertEquals("message for $source", "syntax error before: ", errors[0].description)
            assertEquals("start offset for $source", operatorStart, errors[0].startOffset)
        }
    }

    fun testDoesNotFireWithAValidOperand() {
        myFixture.configureByText(ElixirFileType.INSTANCE, "1 + 2")

        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    /** Real content after the operator is a different defect - the grammar's own report stays until that is fixed. */
    fun testDoesNotRedirectWhenRealContentFollows() {
        myFixture.configureByText(ElixirFileType.INSTANCE, "1 + )")

        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)

        assertTrue("an error among $errors", errors.isNotEmpty())
        assertTrue(
            "no error claiming 'syntax error before: ' at the '+' among $errors",
            errors.none { it.description == "syntax error before: " && it.startOffset == 2 }
        )
    }
}
