package org.elixir_lang.injection.markdown

import com.intellij.openapi.util.TextRange
import org.elixir_lang.injection.PsiLanguageInjectionHost.isDocumentation
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.psi.Parent

class LiteralTextEscaper(parent: Parent) : com.intellij.psi.LiteralTextEscaper<Parent>(parent) {
    /**
     * Host offset per decoded offset, filled by [decode] when it drops text; `null` when it does not.
     *
     * Past the decoded text every entry is the end of the decoded range, so the table never decreases:
     * `InjectedLanguageUtil.hostToInjectedUnescaped` binary-searches it over the *host* length, which is
     * longer than the decoded text by every character dropped.
     */
    private var offsets: IntArray? = null

    override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
        val contentRanges = markdownContentRanges(rangeInsideHost)

        if (contentRanges.isEmpty()) {
            offsets = null
            outChars.append(rangeInsideHost.substring(myHost.text))

            return true
        }

        val hostText = myHost.text
        val offsets = IntArray(rangeInsideHost.length + 1)
        offsets.fill(rangeInsideHost.endOffset)
        var offsetInDecoded = 0

        for (contentRange in contentRanges) {
            for (offsetInContent in 0 until contentRange.length) {
                offsets[offsetInDecoded++] = contentRange.startOffset + offsetInContent
            }

            outChars.append(contentRange.substring(hostText))
        }

        this.offsets = offsets

        return true
    }

    override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
        val offsets = this.offsets ?: return rangeInsideHost.startOffset + offsetInDecoded

        return if (offsetInDecoded < offsets.size) offsets[offsetInDecoded] else -1
    }

    override fun getRelevantTextRange(): TextRange =
        when (val host = myHost) {
            is HeredocLiteral -> markdownRangeInHost(host) ?: TextRange.from(1, 0)
            is ElixirLine -> host.lineBody?.textRangeInParent ?: TextRange.from(1, 0)
            else -> super.getRelevantTextRange()
        }

    override fun isOneLine(): Boolean = false

    /**
     * The Markdown [rangeInsideHost] covers, clipped to it; empty when it covers none, which is how the
     * Elixir injected into a documentation code block is recognised and decoded verbatim.
     *
     * Clipping rather than matching the range against the registered places matters because
     * `InjectionRegistrarImpl.reparse` hands back a range grown from the shred's own marker, which is
     * greedy at both ends, and against a host in the uncommitted tree. A grown Markdown range still covers
     * its own content, and a code block's indent and prompt are not host ranges at all, so a grown Elixir
     * range still covers none.
     */
    private fun markdownContentRanges(rangeInsideHost: TextRange): List<TextRange> {
        val host = myHost as? HeredocLiteral ?: return emptyList()

        if (!isDocumentation(host)) return emptyList()

        return markdownInjection(host)
            .contentRanges
            .mapNotNull { contentRange -> contentRange.intersection(rangeInsideHost) }
            .filterNot { it.isEmpty }
    }
}
