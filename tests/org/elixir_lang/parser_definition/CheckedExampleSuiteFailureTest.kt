package org.elixir_lang.parser_definition

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import junit.framework.TestCase
import junit.framework.TestResult
import junit.framework.TestSuite

/**
 * `judgedSuite` used to return the bare `TestSuite` it was filling - skipping the `Judged` wrapper that calls
 * `Judge.open()` - on every early return, which fires whenever the reference quoter fails partway through. Every
 * example added before that point then shared a `Judge` whose fixture was never opened, and crashed on the
 * uninitialized `lateinit` instead of running normally: one quoter hiccup taking hundreds of unrelated results with it.
 */
class CheckedExampleSuiteFailureTest : TestCase() {
    fun testEveryExampleBeforeAFailingQuoteStillRunsNormally() {
        val examples = listOf(example("first"), example("second"), example("third"))
        var calls = 0
        val quote = { _: String -> calls++; if (calls == 2) throw RuntimeException("daemon died") else answer() }

        val suite = CheckedExampleTestCase.judgedSuite(TestSuite(), examples, quote) as Judged
        val result = TestResult()

        suite.run(result)

        // "first" ran for real (and passed, since `1` quotes to itself and every release accepts it); "third" was
        // never reached, since the quoter fails on "second" first. Only the quoter's own warning test fails.
        assertEquals("errors", 0, result.errorCount())
        assertEquals("failures (the quoter's own warning)", 1, result.failureCount())
        assertEquals("tests run (\"first\" plus the warning, not \"third\")", 2, result.runCount())
    }

    private fun example(name: String) =
        CheckedExampleTestCase.Example(name = name, source = "1", expectsMessage = true, unlikeElixir = null)

    /** `Code.string_to_quoted("1")` is `{:ok, 1}` - a number quotes to itself, no `__block__` needed. */
    private fun answer(): OtpErlangTuple =
        OtpErlangTuple(arrayOf<OtpErlangObject>(OtpErlangAtom("ok"), OtpErlangLong(1)))
}
