package org.elixir_lang.injection.markdown

import org.elixir_lang.PlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Highlighting a file whose documentation contains an indented Elixir code block must not report
 * anything. The platform logs `Cannot restore <element> from injected` when a smart pointer into one of
 * those injected code blocks cannot be restored, and a logged error fails the test on its own.
 */
class DocumentationCodeBlockHighlightingTest : PlatformTestCase() {
    private fun highlightCorpusFile(relativePath: String) {
        val corpus = System.getenv(CORPUS_ENVIRONMENT_VARIABLE)

        assertNotNull(
            "$CORPUS_ENVIRONMENT_VARIABLE is not set. The Gradle test task sets it when " +
                    ".github/ci-versions.json declares a corpus for Elixir ${System.getenv("ELIXIR_VERSION")}",
            corpus
        )

        // The corpus root holds one checkout directory per source repository, named `<repo>@<sha>`
        val elixir = Files.list(Path.of(corpus).resolve("elixir-lang")).use { it.findFirst() }
        assertTrue("No elixir-lang checkout under $corpus", elixir.isPresent)

        val path = elixir.get().resolve(relativePath)
        assertTrue("$path is missing from the corpus", Files.isRegularFile(path))

        myFixture.configureByText(path.fileName.toString(), Files.readString(path))
        myFixture.doHighlighting()
    }

    // Every file named here must exist in each corpus `.github/ci-versions.json` declares, back to the
    // oldest Elixir: a missing one fails its leg rather than skipping. `Calendar.Duration` is the
    // tempting counter-example - it reproduces this, and it only arrived in 1.17.
    fun testEex() = highlightCorpusFile("lib/eex/lib/eex.ex")
    fun testAccess() = highlightCorpusFile("lib/elixir/lib/access.ex")
    fun testAgent() = highlightCorpusFile("lib/elixir/lib/agent.ex")
    fun testApplication() = highlightCorpusFile("lib/elixir/lib/application.ex")
    fun testBitwise() = highlightCorpusFile("lib/elixir/lib/bitwise.ex")
    fun testCode() = highlightCorpusFile("lib/elixir/lib/code.ex")
    fun testCollectable() = highlightCorpusFile("lib/elixir/lib/collectable.ex")

    // Under test/, not lib/: a doc sample here can itself contain `describe`/`test`/`defmodule` text, in
    // a file the project file index marks as test source content - the shape TestLineMarkerProvider sees
    fun testDocTest() = highlightCorpusFile("lib/ex_unit/test/ex_unit/doc_test_test.exs")
    fun testIExHelpers() = highlightCorpusFile("lib/iex/test/iex/helpers_test.exs")

    companion object {
        private const val CORPUS_ENVIRONMENT_VARIABLE = "ELIXIR_PARSING_CORPUS"
    }
}
