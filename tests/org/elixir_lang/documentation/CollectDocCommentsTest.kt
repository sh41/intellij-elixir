package org.elixir_lang.documentation

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocCommentBase
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.psi.ElixirAccessExpression

class CollectDocCommentsTest : BasePlatformTestCase() {
    fun testModuleAndFunctionDocumentationIsCollected() {
        myFixture.configureByText(
            "documented.ex",
            """
            defmodule Documented do
              @moduledoc "Module"

              @doc "Function"
              def f, do: 1
            end
            """.trimIndent()
        )

        assertEquals(
            listOf("@moduledoc \"Module\"", "@doc \"Function\""),
            collectDocComments().map { (it as Comment).moduleAttribute.text }
        )
    }

    fun testHeredocHasNoDocumentation() {
        myFixture.configureByText("heredoc.ex", "\"\"\"\nbar\n\"\"\"")

        assertEmpty(collectDocComments())
    }

    /** Elixir rejects a heredoc whose terminator shares a line with its content. */
    fun testHeredocWithTerminatorAfterContentHasNoDocumentation() {
        myFixture.configureByText("heredoc.ex", "\"\"\"\nbar\"\"\"")
        val accessExpression = PsiTreeUtil.findChildOfType(myFixture.file, ElixirAccessExpression::class.java)

        assertNotNull("the heredoc did not parse to an access expression", accessExpression)
        assertFalse(
            "the access expression has exactly one child, so it no longer strips to itself",
            accessExpression!!.children.size == 1
        )
        assertEmpty(collectDocComments())
    }

    /** Cancelling the indicator makes a walk that never returns throw `ProcessCanceledException` instead of hanging the run. */
    private fun collectDocComments(): List<PsiDocCommentBase> {
        val comments = mutableListOf<PsiDocCommentBase>()
        val indicator = EmptyProgressIndicator()
        val cancellation = AppExecutorUtil.getAppScheduledExecutorService().schedule(indicator::cancel, 10, TimeUnit.SECONDS)

        try {
            ProgressManager.getInstance().runProcess(
                { ElixirDocumentationProvider().collectDocComments(myFixture.file) { comments.add(it) } },
                indicator
            )
        } finally {
            cancellation.cancel(false)
        }

        assertFalse("collectDocComments did not return within 10 seconds", indicator.isCanceled)

        return comments
    }
}
