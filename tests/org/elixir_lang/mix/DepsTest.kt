package org.elixir_lang.mix

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirDoBlock
import java.util.concurrent.TimeUnit

class DepsTest : PlatformTestCase() {
    /**
     * A `deps` helper cut off by a heredoc whose terminator shares a line with its content, which Elixir rejects: the
     * heredoc leaves an access expression without exactly one child in the helper's body. Closing the helper with `end`
     * parses differently and does not reach that step.
     */
    fun testDepsHelperEndingInHeredocWithTerminatorAfterContentHasNoDeps() {
        val psiFile = myFixture.configureByText(
            "mix.exs",
            "defmodule Sample.MixProject do\n" +
                    "  def project do\n" +
                    "    [deps: deps()]\n" +
                    "  end\n" +
                    "\n" +
                    "  defp deps do\n" +
                    "    [ecto_dep()]\n" +
                    "  end\n" +
                    "\n" +
                    "  defp ecto_dep do\n" +
                    "    \"\"\"\n" +
                    "bar\"\"\""
        )

        assertTrue(
            "no access expression without exactly one child in ecto_dep's body, so the fixture no longer reaches that step",
            PsiTreeUtil.findChildrenOfType(psiFile, ElixirAccessExpression::class.java).any { accessExpression ->
                accessExpression.children.size != 1 &&
                        PsiTreeUtil.getParentOfType(accessExpression, ElixirDoBlock::class.java)
                            ?.parent
                            ?.text
                            ?.startsWith("defp ecto_dep") == true
            }
        )

        val gatherer = DepGatherer()
        val indicator = EmptyProgressIndicator()
        val cancellation = AppExecutorUtil.getAppScheduledExecutorService().schedule(indicator::cancel, 10, TimeUnit.SECONDS)

        try {
            ProgressManager.getInstance().runProcess({ psiFile.accept(gatherer) }, indicator)
        } finally {
            cancellation.cancel(false)
        }

        assertFalse("gathering deps did not return within 10 seconds", indicator.isCanceled)
        assertEmpty(gatherer.depSet)
    }
}
