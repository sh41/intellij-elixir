package org.elixir_lang.parser_definition

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangTuple
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.UITestUtil
import com.intellij.testFramework.UsefulTestCase.assertEmpty
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl
import com.intellij.testFramework.recordErrorsLoggedInTheCurrentThreadAndReportThemAsFailures
import com.intellij.testFramework.runInEdtAndWait
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import junit.extensions.TestSetup
import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestSuite
import org.elixir_lang.annotator.InvalidConstruct
import org.elixir_lang.annotator.InvalidToken
import org.elixir_lang.annotator.VersionedSyntax
import org.elixir_lang.inspection.KeywordPairColonInsteadOfTypeOperator
import org.elixir_lang.inspection.KeywordsNotAtEnd
import org.elixir_lang.inspection.MatchOperatorInsteadOfTypeOperator
import org.elixir_lang.inspection.NoParenthesesManyStrict
import org.elixir_lang.inspection.NoParenthesesStrict
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.junit.Assert.fail
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import org.junit.runner.manipulation.Filterable
import org.junit.runner.manipulation.NoTestsRemainException


/**
 * One test per example, judged by the Elixir under test rather than by a frozen expectation: where that Elixir accepts
 * the source, the plugin must parse it to the same quoted form and report no error; where it rejects the source, the
 * plugin must report an error, at Elixir's position where the example asks for it and with Elixir's message where an
 * annotator owns the wording.
 *
 * An example the plugin differs from by design carries the releases and the reason on its own line, and is asserted to
 * fail on those releases, so a difference that goes away is noticed.
 *
 * The fixture is [Judge]'s and outlives every test, which is what makes thousands of one-line examples affordable.
 */
class CheckedExampleTestCase internal constructor(
    private val judge: Judge,
    internal val example: Example,
    internal val answer: OtpErlangTuple,
) : TestCase(example.name) {
    override fun runTest() = judge.judge(example, answer)

    companion object {
        private val EXAMPLES = Path.of("testData", "org", "elixir_lang", "annotator", "quoter_agreement", "sources.jsonl")
        private val SNIPPETS =
            Path.of("testData", "org", "elixir_lang", "parser_definition", "elixir_snippets", "snippets.jsonl")
        private const val CORPUS_ENVIRONMENT_VARIABLE = "ELIXIR_PARSING_CORPUS"

        @JvmStatic
        fun suite(): Test {
            val suite = TestSuite(CheckedExampleTestCase::class.java.name)

            if (System.getenv(CORPUS_ENVIRONMENT_VARIABLE).isNullOrEmpty()) {
                suite.addTest(
                    TestSuite.warning(
                        "$CORPUS_ENVIRONMENT_VARIABLE is not set. The Gradle test task sets it when " +
                            ".github/ci-versions.json declares a corpus for Elixir ${System.getenv("ELIXIR_VERSION")}"
                    )
                )
            }

            return judgedSuite(suite, examples(), Quoter::quote)
        }

        /**
         * Fills [suite] with one [CheckedExampleTestCase] per example, sharing one [Judge], and returns it wrapped in
         * [Judged] - always, including every early return, so a [quote] failure partway through never leaves an
         * example bound to a [Judge] whose fixture [Judged.setUp] was never called to open.
         */
        internal fun judgedSuite(suite: TestSuite, examples: List<Example>, quote: (String) -> OtpErlangTuple?): Test {
            val judge = Judge()

            for (example in examples) {
                val answer = try {
                    quote(example.source)
                } catch (e: Throwable) {
                    suite.addTest(TestSuite.warning("The reference quoter could not judge the examples: $e"))
                    return Judged(suite, judge)
                } ?: run {
                    suite.addTest(TestSuite.warning("The reference quoter did not answer for ${example.name}"))
                    return Judged(suite, judge)
                }

                suite.addTest(CheckedExampleTestCase(judge, example, answer))
            }

            return Judged(suite, judge)
        }

        /**
         * Every example, from all three sources: the hand-edited ones, the snippets taken from Elixir's own tests,
         * and the corpus of the release under test. They differ only in where the text comes from and what an
         * example is called, so one suite judges them all the same way.
         */
        private fun examples(): List<Example> = sources() + snippets() + corpus()

        private fun sources(): List<Example> =
            Files.readAllLines(EXAMPLES).filter { it.isNotBlank() }.map { line ->
                val json = JsonParser.parseString(line).asJsonObject

                Example(
                    name = json.get("hash").asString,
                    source = StringUtil.convertLineSeparators(json.get("source").asString),
                    expectsMessage = json.get("expect")?.asString != "position",
                    unlikeElixir = json.getAsJsonObject("unlike_elixir")?.let(::UnlikeElixir),
                )
            }

        /** Named by origin as well as hash, since a failing snippet is looked up in Elixir's tests by file and line. */
        private fun snippets(): List<Example> =
            Files.readAllLines(SNIPPETS).filter { it.isNotBlank() }.map { line ->
                val json = JsonParser.parseString(line).asJsonObject
                val origin = json.getAsJsonObject("origin")

                Example(
                    name = "${json.get("hash").asString} ${origin.get("file").asString}:${origin.get("line").asInt}",
                    source = StringUtil.convertLineSeparators(json.get("source").asString),
                    expectsMessage = true,
                    unlikeElixir = null,
                )
            }

        /** Empty where the leg declares no corpus; [suite] turns that into a warning rather than silence. */
        private fun corpus(): List<Example> {
            val root = System.getenv(CORPUS_ENVIRONMENT_VARIABLE)?.takeIf { it.isNotEmpty() } ?: return emptyList()

            return Files.walk(Path.of(root)).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().let { name -> name.endsWith(".ex") || name.endsWith(".exs") } }
                    .map { FileUtil.toSystemIndependentName(Path.of(root).relativize(it).toString()) to it }
                    .toList()
            }
                .sortedBy { it.first }
                .map { (relativePath, path) ->
                    Example(
                        name = relativePath,
                        source = StringUtil.convertLineSeparators(Files.readString(path)).trim(),
                        expectsMessage = true,
                        unlikeElixir = null,
                    )
                }
        }
    }

    internal class Example(
        val name: String,
        val source: String,
        val expectsMessage: Boolean,
        val unlikeElixir: UnlikeElixir?,
    )

    /** The releases the plugin differs from Elixir on by design, and why. */
    internal class UnlikeElixir(json: JsonObject) {
        private val releases: List<String> = json.getAsJsonArray("releases").map { it.asString }
        val reason: String = json.get("reason").asString

        /** As the known-failure lists matched: `1.18` is every 1.18 patch release, `1.18.4` only that one. */
        fun applies(elixirUnderTest: String): Boolean =
            releases.any { elixirUnderTest == it || elixirUnderTest.startsWith("$it.") }
    }
}

/**
 * Opens [Judge]'s fixture before the suite and closes it after, so that one fixture serves every example. A fixture
 * per example pays `LightPlatformTestCase.doSetup`'s rescan, and `UsefulTestCase`'s temp directory and leaking-thread
 * wait, once per example, which over thousands of one-line sources is a sixth of what the suite costs.
 */
internal class Judged(suite: TestSuite, private val judge: Judge) : TestSetup(suite), Filterable {
    override fun setUp() = judge.open()

    /** `LightIdeaTestFixtureImpl.tearDown` runs `checkEditorsReleased`, which is the check a shared fixture needs. */
    override fun tearDown() = judge.close()

    /**
     * `JUnit38ClassRunner` filters a bare `TestSuite` only, so a decorated one silently runs everything unless it
     * filters itself.
     */
    override fun filter(filter: Filter) {
        val suite = fTest as TestSuite
        val filtered = TestSuite(suite.name)

        for (index in 0 until suite.testCount()) {
            val test = suite.testAt(index)
            val description = when (test) {
                is TestCase -> Description.createTestDescription(test.javaClass, test.name)
                else -> Description.createSuiteDescription(test.javaClass)
            }

            if (filter.shouldRun(description)) filtered.addTest(test)
        }

        if (filtered.testCount() == 0) throw NoTestsRemainException()

        fTest = filtered
    }
}

/**
 * Holds the fixture every example is judged against.
 *
 * Deliberately not a `BasePlatformTestCase`: that is a `junit.framework.TestCase`, which is what Gradle's test
 * detection keys on, so a fixture holder written as one is collected as a test class of its own and fails the build
 * with "No tests found". It therefore builds the fixture the way `BasePlatformTestCase.createMyFixture` does and
 * drives it from [open] and [close], on the event dispatch thread, since the checks configure files and highlight.
 */
internal class Judge {
    private lateinit var fixture: CodeInsightTestFixture
    private lateinit var example: CheckedExampleTestCase.Example
    private lateinit var answer: OtpErlangTuple

    private val myFixture: CodeInsightTestFixture get() = fixture

    fun open() {
        UITestUtil.replaceIdeEventQueueSafely()

        onEventDispatchThread {
            val factory = IdeaTestFixtureFactory.getFixtureFactory()

            fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder(null, "checkedExamples").fixture,
                LightTempDirTestFixtureImpl(true),
            )
            fixture.setUp()
            ElixirLanguageLevelResolver.overrideLanguageLevel(fixture.project, languageLevel())
            // Registered against the project for the fixture's lifetime, so once is both enough and all it would take.
            fixture.enableInspections(*INSPECTIONS)
        }
    }

    fun close() = onEventDispatchThread {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(fixture.project, null)
        } finally {
            fixture.tearDown()
        }
    }

    fun judge(example: CheckedExampleTestCase.Example, answer: OtpErlangTuple) {
        this.example = example
        this.answer = answer

        // Per example, as `UsefulTestCase` is per test, so a logged error fails the example that logged it.
        onEventDispatchThread { recordErrorsLoggedInTheCurrentThreadAndReportThemAsFailures(::checkUnlessItDiffersByDesign) }
    }

    private fun onEventDispatchThread(runnable: () -> Unit) = runInEdtAndWait(runnable)

    private fun checkUnlessItDiffersByDesign() {
        val unlikeElixir = example.unlikeElixir?.takeIf { it.applies(elixirUnderTest()) } ?: return check()

        try {
            check()
        } catch (expected: AssertionError) {
            return
        }

        fail("${example.name} no longer differs from Elixir ${elixirUnderTest()}: ${unlikeElixir.reason}")
    }

    private fun check() {
        val file = myFixture.configureByText("example.ex", example.source)

        if (status() == "ok") {
            assertEmpty("Elixir ${elixirUnderTest()} accepts ${escape(example.source)}", errors())
            Quoter.assertQuotedCorrectly(file)
        } else {
            assertReported()
        }
    }

    /** Elixir's own wording where an annotator owns it, its position otherwise: both are the plugin's job, in its place. */
    private fun assertReported() {
        val errors = errors()
        val rejection = Quoter.rejection(answer)

        if (errors.isEmpty()) {
            fail("Elixir ${elixirUnderTest()} rejects ${escape(example.source)} with $rejection, but the plugin reports no error")
        }

        val annotated = annotatorMessages()

        if (annotated.isNotEmpty()) {
            val elixir = message() ?: return

            if (!agrees(elixir, annotated.first())) {
                fail(
                    "Elixir ${elixirUnderTest()} rejects ${escape(example.source)} with ${escape(elixir)}, " +
                        "but the annotators report ${annotated.map(::escape)}"
                )
            }
        } else {
            val range = offsetRange() ?: return
            val insertion = insertionPoint(range.first)

            if (!errors.any { it.startOffset <= range.last && it.endOffset >= insertion }) {
                fail(
                    "Elixir ${elixirUnderTest()} rejects ${escape(example.source)} at offset $range ($rejection), " +
                        "but the plugin reports ${errors.map { "${it.startOffset}..${it.endOffset}: ${escape(it.description)}" }}"
                )
            }
        }
    }

    /**
     * The earliest offset that names the same place as [offset]. A zero-width "expected" error cannot sit on the
     * token Elixir names: `PsiBuilderImpl.balanceWhiteSpaces` binds every `ErrorItem` with `DEFAULT_RIGHT_BINDER`,
     * which is the left edge of the whitespace run, so the platform anchors it at the end of the last token the
     * parser consumed. That binder is hard-coded, so the same place has two offsets and both are Elixir's.
     */
    private fun insertionPoint(offset: Int): Int {
        val text = myFixture.file.viewProvider.contents
        var start = offset

        while (start > 0) {
            if (text[start - 1].isWhitespace()) {
                start--
                continue
            }

            // `balanceWhiteSpaces` walks `isWhitespaceOrComment`, so a comment is as transparent as a space.
            val comment = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(start - 1), PsiComment::class.java, false)
                ?: break
            start = comment.textRange.startOffset
        }

        return start
    }

    private fun errors(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.ERROR && it.description != null }

    /**
     * What the annotators that give Elixir's own wording report, in the order their ranges start. Highlighting cannot
     * say which report is theirs, and the parser's errors are the grammar's wording, which Elixir has no counterpart for.
     */
    private fun annotatorMessages(): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        val holder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnnotationHolder::class.java)) { _, _, arguments ->
            var start = -1
            val message = arguments[1] as String

            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnnotationBuilder::class.java)) { builder, method, builderArguments ->
                when (method.name) {
                    "range" -> {
                        start = (builderArguments[0] as TextRange).startOffset
                        builder
                    }
                    "create" -> {
                        found.add(start to message)
                        null
                    }
                    else -> builder
                }
            }
        } as AnnotationHolder
        val annotators = listOf(VersionedSyntax(), InvalidConstruct(), InvalidToken())

        myFixture.file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                for (annotator in annotators) annotator.annotate(element, holder)
                super.visitElement(element)
            }
        })

        return found.sortedBy { it.first }.map { it.second }
    }

    private fun status(): String = (answer.elementAt(0) as OtpErlangAtom).atomValue()

    /** Elixir's message as the annotators word it, or null when it has none to compare. */
    private fun message(): String? =
        when (status()) {
            "error" -> {
                val error = answer.elementAt(1) as OtpErlangTuple
                val token = String((error.elementAt(2) as OtpErlangBinary).binaryValue(), Charsets.UTF_8)
                val message = error.elementAt(1)

                if (message is OtpErlangTuple) Quoter.errorMessage(message, token) else Quoter.errorMessage(message, token) + token
            }
            else -> null
        }

    /**
     * Where Elixir stopped: a single offset where it gives a column, the whole line where it gives only that -
     * Elixir does not know the column any more precisely than the plugin does then, so neither should the check.
     * Null where it reports no position at all (a raise).
     */
    private fun offsetRange(): IntRange? {
        if (status() != "error") return null
        val metadata = (answer.elementAt(1) as OtpErlangTuple).elementAt(0)
        val document = myFixture.getDocument(myFixture.file)

        fun wholeLine(line: Int): IntRange {
            val zeroBased = (line - 1).coerceIn(0, document.lineCount - 1)
            return document.getLineStartOffset(zeroBased)..document.getLineEndOffset(zeroBased)
        }

        return when (metadata) {
            is OtpErlangLong -> wholeLine(metadata.intValue())
            is OtpErlangList -> {
                val line = metadata.keyword("line") ?: return null
                val column = metadata.keyword("column") ?: return wholeLine(line)
                val start = document.getLineStartOffset((line - 1).coerceIn(0, document.lineCount - 1))
                val offset = (start + column - 1).coerceAtMost(document.textLength)

                offset..offset
            }
            else -> null
        }
    }

    /** Missing-terminator errors lead their metadata with `opening_delimiter` and `expected_delimiter`, so read by key. */
    private fun OtpErlangList.keyword(key: String): Int? =
        asSequence()
            .filterIsInstance<OtpErlangTuple>()
            .firstOrNull { (it.elementAt(0) as? OtpErlangAtom)?.atomValue() == key }
            ?.let { (it.elementAt(1) as? OtpErlangLong)?.intValue() }

    companion object {
        /** Every ERROR-level inspection of syntax. Ones that resolve names are left off: an unresolved name is not a syntax error. */
        private val INSPECTIONS: Array<InspectionProfileEntry> by lazy {
            arrayOf(
                KeywordPairColonInsteadOfTypeOperator(),
                KeywordsNotAtEnd(),
                MatchOperatorInsteadOfTypeOperator(),
                NoParenthesesManyStrict(),
                NoParenthesesStrict(),
                LocalInspectionEP.LOCAL_INSPECTION.extensionList.first { it.shortName == "NonAsciiCharacters" }.instantiateTool(),
            )
        }

        private fun languageLevel(): ElixirLanguageLevel =
            ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION"), System.getenv("ERLANG_VERSION"))

        private fun elixirUnderTest(): String = System.getenv("ELIXIR_VERSION") ?: "of no SDK"

        /** The whole message, the message with its lines joined, or its first line when the rest is a hint. */
        private fun agrees(elixir: String, reported: String): Boolean =
            reported == elixir || reported == joinLines(elixir) || reported == elixir.substringBefore("\n")

        private fun joinLines(message: String): String = message.trim().split(Regex("\\s+")).joinToString(" ")

        private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
    }
}
