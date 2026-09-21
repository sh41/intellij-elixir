package org.elixir_lang.injection.markdown

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.HeredocLiteral

/**
 * The Markdown and Elixir injections used to classify each heredoc line with their own, independently
 * tracked `listIndent`/`inException`, and could disagree about where a code block starts or ends. They
 * now share one classification ([classifyLines]), so these two shapes - previously enough to make them
 * disagree - cannot recur.
 */
class WalkClassificationInjectionTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/injection/markdown"

    private fun moduleDocHeredoc(fileName: String): HeredocLiteral {
        myFixture.configureByFile(fileName)

        return PsiTreeUtil.findChildrenOfType(myFixture.file, HeredocLiteral::class.java).first()
    }

    /**
     * `** (ArgumentError)` sets `inException = true` in both walks alike. The following `...> ` line reset
     * it in the Elixir walk but not in the Markdown walk, so the plain line after that disagreed: Markdown
     * kept it whole (still "inException"), Elixir carved a sub-range out of the same line for itself - the
     * overlapping host ranges `InjectionRegistrarImpl.registerDocument` evicts one of two injected
     * documents over.
     */
    fun testExceptionThenContinuationThenPlainLineDoesNotEvictEitherInjection() {
        moduleDocHeredoc("exception_continuation_plain_line.ex")
        myFixture.doHighlighting()

        val documentManager = PsiDocumentManager.getInstance(myFixture.project)
        val languages = InjectedLanguageManager.getInstance(myFixture.project)
            .getCachedInjectedDocumentsInRange(myFixture.file, TextRange(0, myFixture.file.textLength))
            .mapNotNull { window -> documentManager.getPsiFile(window)?.language?.id }

        assertContainsElements(languages, "Markdown", "Elixir")
    }

    /**
     * A list item's own indent can be zero - a list can start at the document's left margin - so
     * `listIndent` becomes `0`. The Markdown walk tested `listIndent > 0` to decide "still in a list",
     * which is false at `0`, so it read the list's own wrapped continuation line as a four-space-indented
     * code block and dropped its text into the code side; the Elixir walk tested `listIndent == -1` for
     * "not in a list", which is also false at `0`, so it left that text unclaimed. Dropped by both.
     */
    fun testZeroIndentListContinuationStaysInTheList() {
        val heredoc = moduleDocHeredoc("zero_indent_list.ex")
        val injection = markdownInjection(heredoc)

        val decoded = injection.contentRanges.joinToString("") { it.substring(heredoc.text) }

        assertTrue(
            "A zero-indent list's wrapped continuation line was dropped from the Markdown it belongs to: <$decoded>",
            decoded.contains("wrapped onto a continuation indented like a code block")
        )
    }
}
