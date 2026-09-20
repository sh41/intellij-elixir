package org.elixir_lang.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLexer
import org.elixir_lang.parser.isHexadecimalDigit
import org.elixir_lang.psi.DotCall
import org.elixir_lang.psi.ElixirAnonymousFunction
import org.elixir_lang.psi.ElixirAssociationsBase
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirBitString
import org.elixir_lang.psi.ElixirBracketArguments
import org.elixir_lang.psi.ElixirCharToken
import org.elixir_lang.psi.ElixirContainerAssociationOperation
import org.elixir_lang.psi.ElixirDoBlock
import org.elixir_lang.psi.ElixirEnclosedHexadecimalEscapeSequence
import org.elixir_lang.psi.ElixirEscapedCharacter
import org.elixir_lang.psi.ElixirHeredoc
import org.elixir_lang.psi.ElixirInterpolatedSigilHeredoc
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.ElixirKeywordPair
import org.elixir_lang.psi.ElixirKeywords
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirLiteralSigilHeredoc
import org.elixir_lang.psi.ElixirMapArguments
import org.elixir_lang.psi.ElixirMapOperation
import org.elixir_lang.psi.ElixirMatchedMultiplicationOperation
import org.elixir_lang.psi.ElixirMatchedTernaryOperation
import org.elixir_lang.psi.ElixirMatchedTwoOperation
import org.elixir_lang.psi.ElixirMatchedUnaryOperation
import org.elixir_lang.psi.ElixirMultiplicationInfixOperator
import org.elixir_lang.psi.ElixirNoParenthesesKeywordPair
import org.elixir_lang.psi.ElixirNullaryRangeOperation
import org.elixir_lang.psi.ElixirParenthesesArguments
import org.elixir_lang.psi.ElixirParentheticalStab
import org.elixir_lang.psi.ElixirQuoteHexadecimalEscapeSequence
import org.elixir_lang.psi.ElixirRelativeIdentifier
import org.elixir_lang.psi.ElixirTernaryInfixOperator
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.ElixirTwoInfixOperator
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.ElixirUnmatchedMultiplicationOperation
import org.elixir_lang.psi.ElixirUnmatchedTernaryOperation
import org.elixir_lang.psi.ElixirUnmatchedTwoOperation
import org.elixir_lang.psi.ElixirUnmatchedUnaryOperation
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.psi.QualifiedNoArgumentsCall
import org.elixir_lang.psi.QualifiedNoParenthesesCall
import org.elixir_lang.psi.QualifiedParenthesesCall
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import org.elixir_lang.psi.UnqualifiedNoParenthesesCall
import org.elixir_lang.psi.UnqualifiedParenthesesCall
import org.elixir_lang.language_level.ElixirLanguageFeature.*
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import java.text.BreakIterator
import java.text.Normalizer

/**
 * Reports, as errors, syntax that the module's Elixir release rejects and another release accepts, with the message
 * that release gives.
 */
internal class VersionedSyntax : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val problem = problem(element) { ElixirLanguageLevelResolver.languageLevelFor(element) } ?: return
        if (Injection.of(element) == Injection.UNCOMPILED) return
        // Everything past the `**` is unread, so only the `**` itself is reported there.
        powerStop(element)?.let { if (problem.range.startOffset > it) return }

        holder.error(problem.range, problem.message, problem.tooltip)
    }

    private class Problem(val range: TextRange, val message: String, val tooltip: String? = null)

    private fun problem(element: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? =
        when (element) {
            is ElixirNullaryRangeOperation ->
                if (!NULLARY_RANGE.isSufficient(languageLevel())) {
                    Problem(element.textRange, syntaxErrorBefore("'..'"))
                } else {
                    null
                }
            is ElixirAssociationsBase -> mapEntry(element, languageLevel)
            is ElixirKeywordPair, is ElixirNoParenthesesKeywordPair -> keywordKey(element, languageLevel)
            is ElixirMatchedMultiplicationOperation, is ElixirUnmatchedMultiplicationOperation ->
                operatorArity(element, languageLevel)
            is ElixirMatchedTernaryOperation, is ElixirUnmatchedTernaryOperation -> stepOperator(element, languageLevel)
            is ElixirRelativeIdentifier -> graphemeCluster(element, languageLevel)
            is ElixirEscapedCharacter -> escapedCharacter(element, languageLevel)
            is ElixirQuoteHexadecimalEscapeSequence -> hexadecimalEscape(element, languageLevel)
            is ElixirHeredoc, is ElixirInterpolatedSigilHeredoc, is ElixirLiteralSigilHeredoc ->
                heredocTerminatorAfterContent(element, languageLevel)
            is PsiFile -> powerPair(element, languageLevel)
            else -> if (element.firstChild == null) leaf(element, languageLevel) else null
        }

    /** Before 1.12 Elixir rejects a heredoc terminator after content where it stands, once the opening line is valid. */
    private fun heredocTerminatorAfterContent(heredoc: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        if (
            HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT.isSufficient(languageLevel()) ||
            hasContentAfterOpening(heredoc)
        ) {
            return null
        }
        val range = misplacedHeredocTerminator(heredoc) ?: return null

        return Problem(
            range,
            "invalid location for heredoc terminator, please escape token or move it to its own line: " +
                range.subSequence(heredoc.containingFile.viewProvider.contents)
        )
    }

    private fun leaf(leaf: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? =
        when (leaf.node.elementType) {
            ElixirTypes.POWER_OPERATOR -> power(leaf, leaf, languageLevel)
            ElixirTypes.IDENTIFIER_TOKEN ->
                if (leaf.text == "**") power(leaf, leaf, languageLevel) else notNfc(leaf, languageLevel)
            ElixirTypes.ALIAS_TOKEN -> notNfc(leaf, languageLevel)
            ElixirTypes.ATOM_FRAGMENT -> atomFragment(leaf, languageLevel)
            ElixirTypes.LITERAL_SIGIL_NAME, ElixirTypes.INTERPOLATING_SIGIL_NAME -> sigilName(leaf, languageLevel)
            ElixirTypes.END -> endBeforeTypeOperator(leaf, languageLevel)
            else -> null
        }

    /**
     * Before 1.13 Elixir's tokenizer reads `**` as two `*`, so `x.**` is `x.*` followed by `*`. [first] and [last] are
     * the same leaf where one token spells the whole of it, and the two `*` otherwise.
     */
    private fun power(first: PsiElement, last: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        if (POWER_OPERATOR.isSufficient(languageLevel())) return null

        val range = TextRange(first.textRange.startOffset, last.textRange.endOffset)
        // Broken source leaves the leaves loose, so the dot before it says this names a function as well as the PSI.
        val named = first.parent is ElixirRelativeIdentifier ||
            previousCodeLeaf(first)?.node?.elementType == ElixirTypes.DOT_OPERATOR
        val token = if (named) {
            parenthesesToken(last) ?: tokenAfterPower(last, languageLevel) ?: return null
        } else {
            "'*'"
        }

        if (endsWithBackslash(last)) return Problem(range, INVALID_ESCAPE_AT_END)

        return Problem(range, syntaxErrorBefore(token))
    }

    /**
     * The first `**` a release before 1.13 cannot read, which is two `*` there. Reported from the file, since Elixir's
     * tokenizer stops at the first one it cannot read wherever it sits, and the two `*` can land in different children.
     */
    /** Where [powerPair] reports, for the callers that must stay silent past it. */
    internal fun powerStart(file: PsiElement, languageLevel: () -> ElixirLanguageLevel): Int? =
        powerPair(file, languageLevel)?.range?.startOffset

    private fun powerPair(file: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        if (POWER_OPERATOR.isSufficient(languageLevel())) return null

        return starPairs(file).firstNotNullOfOrNull { (first, second) -> power(first, second, languageLevel) }
    }

    /**
     * Elixir names the `)` where an argument list follows `x.**`, since it reads the parentheses as an operand and a
     * keyword list or a second argument cannot be one. A single expression can, so there is nothing to report.
     */
    private fun parenthesesToken(power: PsiElement): String? {
        val opening = nextCodeLeaf(power)?.takeIf { it.node.elementType == ElixirTypes.OPENING_PARENTHESIS } ?: return null
        var depth = 0
        // A comma or keyword-pair colon only ends a single expression at parentheses-depth 1; inside a list, tuple
        // or map nested there it is part of that literal, not a second argument. `%{}` opens with the same token
        // as `{}`, so no separate count is needed for it.
        var bracketDepth = 0

        for (leaf in generateSequence(opening) { PsiTreeUtil.nextLeaf(it) }) {
            when (leaf.node.elementType) {
                ElixirTypes.OPENING_PARENTHESIS -> depth++
                ElixirTypes.CLOSING_PARENTHESIS -> if (--depth == 0) return null
                ElixirTypes.OPENING_BRACKET, ElixirTypes.OPENING_CURLY -> bracketDepth++
                ElixirTypes.CLOSING_BRACKET, ElixirTypes.CLOSING_CURLY -> bracketDepth--
                ElixirTypes.COMMA, ElixirTypes.KEYWORD_PAIR_COLON -> if (depth == 1 && bracketDepth == 0) return "')'"
                else -> {}
            }
        }

        return null
    }

    /** The token Elixir names when the `*` after `x.*` has no operand, or null where that token is not known. */
    private fun tokenAfterPower(power: PsiElement, languageLevel: () -> ElixirLanguageLevel): String? {
        val next = nextCodeLeaf(power)
        if (next == null || isFinalBackslash(next) || next.parent is ElixirInterpolation) return ""

        val key = PsiTreeUtil.getParentOfType(next, ElixirKeywordKey::class.java)
        if (key != null) return keywordKeyName(key.text, key.interpolated(), languageLevel)
        // The pair is unparsed when the `**` broke the expression holding it, so read the key off the leaves.
        looseKeywordKey(next)?.let { return keywordKeyName(it, "#{" in it, languageLevel) }

        val type = when (next.node.elementType) {
            // The lexer makes some operators before `/` identifiers.
            ElixirTypes.IDENTIFIER_TOKEN -> tokenType(next.text)
            ElixirTypes.NOT_OPERATOR -> return if (isNotIn(next)) erlangAtom("not in", languageLevel()) else null
            // Elixir does not name `..` there when `/` or `//` follows, spaces aside.
            ElixirTypes.RANGE_OPERATOR -> if (isBeforeDivision(next)) return null else ElixirTypes.RANGE_OPERATOR
            else -> next.node.elementType
        }

        // Elixir's tokenizer first reports a closer or `end` without an opener, and a `do` without an `end`.
        if ((type in CLOSERS || type == ElixirTypes.END) && (next.parent is PsiErrorElement || next.parent is PsiFile)) return null
        if (type == ElixirTypes.DO && !hasEndAfter(next)) return null
        // An operator before `/` on the same line is a reference, which starts the operand.
        if (type in OPERATORS && isDivisionOnSameLine(next)) return null

        return if (type in ENDS_OPERAND) erlangAtom(next.text, languageLevel()) else null
    }

    /** Whether a `do` is closed. Broken source leaves the `end` outside the block, so the whole file is searched. */
    private fun hasEndAfter(doLeaf: PsiElement): Boolean =
        generateSequence(PsiTreeUtil.nextLeaf(doLeaf)) { PsiTreeUtil.nextLeaf(it) }
            .any { it.node.elementType == ElixirTypes.END }

    private fun isBeforeDivision(range: PsiElement): Boolean {
        val following = generateSequence(PsiTreeUtil.nextLeaf(range)) { PsiTreeUtil.nextLeaf(it) }
            .firstOrNull { it !is PsiWhiteSpace || it.text.any { character -> character != ' ' && character != '\t' } }

        return when (following?.node?.elementType) {
            ElixirTypes.DIVISION_OPERATOR, ElixirTypes.TERNARY_OPERATOR -> true
            else -> false
        }
    }

    private fun isDivisionOnSameLine(operator: PsiElement): Boolean =
        generateSequence(PsiTreeUtil.nextLeaf(operator)) { PsiTreeUtil.nextLeaf(it) }
            .firstOrNull { it.textLength > 0 && (it !is PsiWhiteSpace || '\n' in withoutLineContinuations(it.text)) }
            ?.node?.elementType == ElixirTypes.DIVISION_OPERATOR

    /** Elixir's tokenizer reads `not`, spaces, tabs or line continuations, and `in` as one token. */
    private fun isNotIn(not: PsiElement): Boolean {
        val gap = generateSequence(PsiTreeUtil.nextLeaf(not)) { PsiTreeUtil.nextLeaf(it) }.takeWhile { it is PsiWhiteSpace }.toList()
        val following = gap.lastOrNull()?.let { PsiTreeUtil.nextLeaf(it) }

        return following?.node?.elementType == ElixirTypes.IN_OPERATOR &&
            gap.all { space -> withoutLineContinuations(space.text).all { it == ' ' || it == '\t' } }
    }

    private fun keywordKeyName(text: String, interpolated: Boolean, languageLevel: () -> ElixirLanguageLevel): String? {
        return when {
            text.first() == '\"' || text.first() == '\'' -> quotedKeyName(text, interpolated)
            // 1.11 accepts a `..//` key.
            text == "..//" ->
                if (languageLevel().let { STEP_OPERATOR.isSufficient(it) && !POWER_OPERATOR.isSufficient(it) }) {
                    erlangAtom(text, languageLevel())
                } else {
                    null
                }
            text in KEYS_NAMED_OTHERWISE -> null
            else -> erlangAtom(text, languageLevel())
        }
    }

    /** The binary a quoted key tokenizes to, as Erlang prints it. */
    private fun quotedKeyName(text: String, interpolated: Boolean): String? {
        if (text.length < 2 || text.last() != text.first()) return null
        if (interpolated) return null

        return unescape(text.substring(1, text.length - 1))?.let { "[${erlangBinary(it)}]" }
    }

    private fun ElixirKeywordKey.interpolated(): Boolean =
        PsiTreeUtil.findChildOfType(this, ElixirInterpolation::class.java) != null

    /**
     * The text of the key [next] starts, or null where nothing before the next separator closes one with a `:`.
     *
     * A `:` only ends [next]'s own key at nesting depth 0; one nested inside a bracket, brace or parenthesis
     * belongs to something else entirely, as in `x.**(%{a: 1})`'s `a:`, and is skipped rather than matched or
     * stopped on - the same balanced-pair counting also carries `looseKeywordKey` through a quoted key's own
     * internal escape braces, like `"a\u{41}"`'s, since those are the same token types. A comma or a real `EOL`
     * token (not whitespace) at depth 0 ends [next] with no key found, since neither can be part of one - as in
     * `[a: x.**y,b: 1]`'s `,` or an unindented `x.**y` followed by `b: 1` on the next line.
     */
    private fun looseKeywordKey(next: PsiElement): String? {
        val text = StringBuilder()
        var depth = 0

        for (leaf in generateSequence(next) { PsiTreeUtil.nextLeaf(it) }) {
            when (leaf.node.elementType) {
                ElixirTypes.OPENING_PARENTHESIS, ElixirTypes.OPENING_BRACKET, ElixirTypes.OPENING_CURLY -> depth++
                ElixirTypes.CLOSING_PARENTHESIS, ElixirTypes.CLOSING_BRACKET, ElixirTypes.CLOSING_CURLY -> depth--
                ElixirTypes.KEYWORD_PAIR_COLON -> if (depth == 0) return text.toString().takeIf { it.isNotEmpty() }
                ElixirTypes.COMMA, ElixirTypes.EOL -> if (depth == 0) return null
                else -> {}
            }
            if (leaf is PsiWhiteSpace) return null

            text.append(leaf.text)
        }

        return null
    }

    private fun atomFragment(fragment: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? =
        when (fragment.parent) {
            is ElixirAtom ->
                when (fragment.text) {
                    "**" ->
                        if (!POWER_OPERATOR.isSufficient(languageLevel())) {
                            Problem(fragment.textRange, syntaxErrorBefore(""))
                        } else {
                            null
                        }
                    "..//" ->
                        if (!STEP_ATOM.isSufficient(languageLevel())) {
                            Problem(fragment.textRange, syntaxErrorBefore("'/'"))
                        } else {
                            null
                        }
                    else -> notNfc(fragment, languageLevel)
                }
            is ElixirKeywordKey -> notNfc(fragment, languageLevel)
            else -> null
        }

    private fun notNfc(word: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        val text = word.text
        if (
            text.all { it.code < 128 } ||
            Normalizer.isNormalized(text, Normalizer.Form.NFC) ||
            NORMALIZED_IDENTIFIERS.isSufficient(languageLevel())
        ) {
            return null
        }

        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val tooltip = HtmlBuilder()
            .append(HtmlChunk.p().addText(NFC))
            .append(HtmlChunk.p().addText("Got: \"$text\" (code points ${codePoints(text)})"))
            .append(HtmlChunk.p().addText("Expected: \"$normalized\" (code points ${codePoints(normalized)})"))
            .append(HtmlChunk.p().addText("Syntax error before: $text"))
            .wrapWithHtmlBody()
            .toString()

        return Problem(word.textRange, NFC, tooltip)
    }

    /** Before 1.15 a sigil name is one letter; 1.15 and 1.16 take more uppercase letters, but not digits. */
    private fun sigilName(name: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        val text = name.text
        if (text.length < 2) return null

        val index = when {
            !MULTI_LETTER_SIGIL_NAMES.isSufficient(languageLevel()) -> 1
            !DIGITS_IN_SIGIL_NAMES.isSufficient(languageLevel()) ->
                text.indexOfFirst(Char::isDigit).takeIf { it > 0 } ?: return null
            else -> return null
        }
        val offset = name.textRange.startOffset + index
        val character = text[index]

        return Problem(
            TextRange(offset, offset + 1),
            "invalid sigil delimiter: \"$character\" " +
                "(column ${column(name.containingFile.viewProvider.contents, offset)}, " +
                "code point U+${"%04X".format(character.code)}). " +
                "The available delimiters are: //, ||, \"\", '', (), [], {}, <>"
        )
    }

    /** Before 1.12 a word followed by `:` was never a keyword, so `end::` left its block open. */
    private fun endBeforeTypeOperator(end: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        val text = end.containingFile.viewProvider.contents
        if (
            !text.startsWith("::", end.textRange.endOffset) ||
            RESERVED_WORD_BEFORE_TYPE_OPERATOR.isSufficient(languageLevel())
        ) {
            return null
        }

        val block = end.parent
        if (block !is ElixirDoBlock && block !is ElixirAnonymousFunction) return null

        // Each later `end` closes the block before it, so the outermost block inside the enclosing container stays open.
        var outermost: PsiElement = block
        var closer: String? = null

        for (ancestor in generateSequence(block.parent, PsiElement::getParent)) {
            if (ancestor is PsiFile) break
            closer = closer(ancestor)
            if (closer != null) break
            if (ancestor is ElixirDoBlock || ancestor is ElixirAnonymousFunction) outermost = ancestor
        }

        val opener = outermost.node.findChildByType(TokenSet.create(ElixirTypes.DO, ElixirTypes.FN)) ?: return null
        val line = line(text, opener.startOffset)

        return Problem(
            end.textRange,
            if (closer == null) {
                "missing terminator: end (for \"${opener.text}\" starting at line $line)"
            } else {
                "unexpected token: $closer. The \"${opener.text}\" at line $line is missing terminator \"end\""
            }
        )
    }

    private fun closer(element: PsiElement): String? =
        when (element) {
            is ElixirBitString -> ">>"
            is ElixirBracketArguments, is ElixirList -> "]"
            is ElixirInterpolation, is ElixirTuple, is ElixirMapArguments -> "}"
            is ElixirParenthesesArguments, is ElixirParentheticalStab -> ")"
            else -> null
        }

    /**
     * A `.` key needs 1.13; before 1.12 Elixir's tokenizer read a sign after a call name and a space as a unary
     * operator, even when `:` followed it.
     */
    private fun keywordKey(pair: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        val key = pair.firstChild as? ElixirKeywordKey ?: return null
        val colon = key.nextSibling?.takeIf { it.node.elementType == ElixirTypes.KEYWORD_PAIR_COLON } ?: return null
        val rejected = when (key.text) {
            "." -> !DOT_KEYWORD_KEY.isSufficient(languageLevel())
            "+", "-" -> isSignAfterCallName(key) && !SIGN_KEYWORD_KEY_AFTER_CALL.isSufficient(languageLevel())
            else -> false
        }

        return if (rejected) {
            Problem(
                colon.textRange,
                unexpectedToken(
                    ':'.code,
                    column(colon.containingFile.viewProvider.contents, colon.textRange.startOffset),
                )
            )
        } else {
            null
        }
    }

    private fun isSignAfterCallName(key: PsiElement): Boolean {
        val previous = PsiTreeUtil.prevVisibleLeaf(key) ?: return false
        // Before 1.13 [power] reports the key after `x.**`, and Elixir reports `x.%` before the key.
        if (previous.text == "**" || previous.text == "%") return false
        if (previous.node.elementType != ElixirTypes.IDENTIFIER_TOKEN && !endsCallName(previous)) return false

        val between = key.containingFile.viewProvider.contents.subSequence(previous.textRange.endOffset, key.textRange.startOffset)

        return between.isNotEmpty() && between.all { it == ' ' || it == '\t' }
    }

    private fun endsCallName(leaf: PsiElement): Boolean {
        val identifier = PsiTreeUtil.getParentOfType(leaf, ElixirRelativeIdentifier::class.java) ?: return false

        return PsiTreeUtil.lastChild(identifier) == leaf &&
            PsiTreeUtil.findChildOfType(identifier, ElixirInterpolation::class.java) == null
    }

    /**
     * Before 1.17 a map entry without `=>` is a variable, or a call without arguments or with parentheses, and from 1.13
     * also `foo bar` or `...a`.
     */
    private fun mapEntry(base: ElixirAssociationsBase, languageLevel: () -> ElixirLanguageLevel): Problem? {
        if (MAP_ENTRY_WITHOUT_ASSOCIATION.isSufficient(languageLevel())) return null

        // Elixir stops first at a space between `%` and `{`, which InvalidConstruct reports.
        val arguments = PsiTreeUtil.getParentOfType(base, ElixirMapArguments::class.java)
        if (arguments?.parent is ElixirMapOperation && arguments.prevSibling is PsiWhiteSpace) return null

        val entry = base.children.firstOrNull { !isMapEntry(it, languageLevel) } ?: return null
        if (unwrap(entry) is ElixirNullaryRangeOperation && !NULLARY_RANGE.isSufficient(languageLevel())) return null

        val next = nextCodeLeaf(entry) ?: return null
        val eol = hasNewlineBetween(entry, next)

        return Problem(entry.textRange, syntaxErrorBefore(if (eol) "eol" else "'${next.text}'"))
    }

    private fun isMapEntry(entry: PsiElement, languageLevel: () -> ElixirLanguageLevel): Boolean =
        when (entry) {
            is ElixirContainerAssociationOperation,
            is ElixirVariable,
            is UnqualifiedNoArgumentsCall<*>,
            is QualifiedNoArgumentsCall<*>,
            is UnqualifiedParenthesesCall<*>,
            is QualifiedParenthesesCall<*>,
            is DotCall<*> -> true
            is UnqualifiedNoParenthesesCall<*>, is QualifiedNoParenthesesCall<*> ->
                CALL_AND_ELLIPSIS_MAP_ENTRIES.isSufficient(languageLevel())
            is ElixirMatchedUnaryOperation, is ElixirUnmatchedUnaryOperation ->
                entry.firstChild.text == "..." && CALL_AND_ELLIPSIS_MAP_ENTRIES.isSufficient(languageLevel())
            else -> false
        }

    /**
     * The lexer makes an operator before `/` an identifier, as Elixir's tokenizer does only after `&` before 1.13, and
     * only without a line continuation before the `/` before 1.20.
     */
    private fun operatorArity(operation: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        val operator = operation.children.firstOrNull { it is ElixirMultiplicationInfixOperator && it.text == "/" } ?: return null
        // Not the operation's first child: `**` splits before 1.13, so the name can sit inside a nested operation.
        val operand = PsiTreeUtil
            .getParentOfType(previousCodeLeaf(operator), UnqualifiedNoArgumentsCall::class.java, false)
            ?.takeIf { it.textRange.endOffset <= operator.textRange.startOffset }
            ?: return null
        val name = operand.text
        if (name == "..." || name in SAME_ON_EVERY_RELEASE || (name.any { it.isLetterOrDigit() || it == '_' } && name !in WORD_OPERATORS)) {
            return null
        }

        val between = operation.containingFile.viewProvider.contents
            .subSequence(operand.textRange.endOffset, operator.textRange.startOffset)

        if (between.contains("\\\n")) {
            // Before 1.20 Elixir reads `..` there as a range, which has no operands before 1.14.
            if (name == "..") {
                return if (!NULLARY_RANGE.isSufficient(languageLevel())) {
                    Problem(operand.textRange, syntaxErrorBefore("'..'"))
                } else {
                    null
                }
            }
            // Before 1.13 [power] reports the `**` itself.
            if (name == "**" && !POWER_OPERATOR.isSufficient(languageLevel())) return null
            if (ESCAPED_NEWLINE_BEFORE_ARITY.isSufficient(languageLevel())) return null

            return when (name) {
                "..//" if STEP_OPERATOR.isSufficient(languageLevel()) ->
                    Problem(
                        operand.textRange,
                        unexpectedToken(
                            '.'.code,
                            column(operand.containingFile.viewProvider.contents, operand.textRange.startOffset),
                        )
                    )
                "..//", "/", "not", in UNARY_OPERATORS -> Problem(operator.textRange, syntaxErrorBefore("'/'"))
                // After an operand the operator is binary, so Elixir names the `/` that should have been its operand.
                else ->
                    if (isAfterOperand(operand, languageLevel)) {
                        Problem(operator.textRange, syntaxErrorBefore("'/'"))
                    } else {
                        Problem(operand.textRange, syntaxErrorBefore(erlangAtom(name, languageLevel())))
                    }
            }
        }

        // A newline between `&` and the operator ends the capture, but a line continuation does not.
        val captured = previousCodeLeaf(operand)
            ?.let { it.node.elementType == ElixirTypes.CAPTURE_OPERATOR && !hasNewlineBetween(it, operand) } == true

        return if (name in UNARY_OPERATORS && !captured && !UNARY_OPERATOR_REFERENCE.isSufficient(languageLevel())) {
            Problem(operator.textRange, syntaxErrorBefore("'/'"))
        } else {
            null
        }
    }

    /** Whether an identifier, or `x.**` from 1.13, comes directly before [operand]. */
    private fun isAfterOperand(operand: PsiElement, languageLevel: () -> ElixirLanguageLevel): Boolean {
        val previous = generateSequence(PsiTreeUtil.prevLeaf(operand)) { PsiTreeUtil.prevLeaf(it) }
            .firstOrNull { it !is PsiWhiteSpace && it.textLength > 0 }
            ?: return false

        return if (previous.text == "**" && previous.parent is ElixirRelativeIdentifier) {
            POWER_OPERATOR.isSufficient(languageLevel())
        } else {
            previous.node.elementType == ElixirTypes.IDENTIFIER_TOKEN
        }
    }

    private fun stepOperator(operation: PsiElement, languageLevel: () -> ElixirLanguageLevel): Problem? {
        val operator = operation.children.firstOrNull { it is ElixirTernaryInfixOperator } ?: return null

        return if (STEP_OPERATOR.isSufficient(languageLevel()) && !isRange(operation.firstChild)) {
            Problem(operator.textRange, STEP)
        } else {
            null
        }
    }

    private fun isRange(element: PsiElement?): Boolean =
        when (val unwrapped = unwrap(element)) {
            is ElixirMatchedTwoOperation, is ElixirUnmatchedTwoOperation ->
                unwrapped.children.any { it is ElixirTwoInfixOperator && it.text == ".." }
            else -> false
        }

    /** Elixir 1.13 to 1.17 crash turning a quoted call name into an atom when a grapheme cluster has several code points. */
    private fun graphemeCluster(
        identifier: ElixirRelativeIdentifier,
        languageLevel: () -> ElixirLanguageLevel,
    ): Problem? {
        val line = identifier.line ?: return null
        val body = line.lineBody?.text ?: return null
        if (body.all { it.code < 128 }) return null

        val clusters = BreakIterator.getCharacterInstance().apply { setText(body) }
        var start = clusters.first()
        var end = clusters.next()

        while (end != BreakIterator.DONE) {
            if (Character.codePointCount(body, start, end) > 1) {
                return if (GRAPHEME_CLUSTER_CRASH_IN_QUOTED_CALL_NAME.isSufficient(languageLevel())) {
                    Problem(line.textRange, NOT_A_LIST_OF_CHARACTERS)
                } else {
                    null
                }
            }

            start = end
            end = clusters.next()
        }

        return null
    }

    /** Elixir unescapes a quoted call name from 1.18, so `\x` or `\u` without digits there is then an error. */
    private fun escapedCharacter(escaped: ElixirEscapedCharacter, languageLevel: () -> ElixirLanguageLevel): Problem? {
        if (PsiTreeUtil.getParentOfType(escaped, ElixirRelativeIdentifier::class.java) == null) return null

        val message = when (escaped.lastChild?.text) {
            "x" -> INVALID_HEX_ESCAPE
            "u" -> INVALID_UNICODE_ESCAPE
            else -> return null
        }

        return if (UNESCAPED_QUOTED_REMOTE_CALL_NAME.isSufficient(languageLevel())) {
            Problem(escaped.textRange, message)
        } else {
            null
        }
    }

    /** 1.20 removed `\xH` and `\x{H*}`; an invalid code point in one is reported by [InvalidConstruct]. */
    private fun hexadecimalEscape(
        escape: ElixirQuoteHexadecimalEscapeSequence,
        languageLevel: () -> ElixirLanguageLevel,
    ): Problem? {
        if (!escape.hexadecimalEscapePrefix.text.endsWith("x")) return null
        if (PsiTreeUtil.getParentOfType(escape, ElixirCharToken::class.java) != null) return null

        val digits = PsiTreeUtil.collectElements(escape) { it.node.elementType == ElixirTypes.VALID_HEXADECIMAL_DIGITS }
            .singleOrNull()
            ?.text
            ?: return null
        val enclosed = PsiTreeUtil.getChildOfType(escape, ElixirEnclosedHexadecimalEscapeSequence::class.java) != null
        if (!enclosed && digits.length != 1) return null

        val codePoint = digits.toLongOrNull(16) ?: return null
        if (!isUnicodeScalarValue(codePoint)) return null

        return if (HEXADECIMAL_ESCAPE_NEEDS_TWO_DIGITS.isSufficient(languageLevel())) {
            Problem(escape.textRange, INVALID_HEX_ESCAPE)
        } else {
            null
        }
    }


    private fun codePoints(text: String): String = text.codePoints().toArray().joinToString(" ") { "0x%04X".format(it) }


    private companion object {
        const val NFC = "Elixir expects unquoted Unicode atoms, variables, and calls to be in NFC form."
        const val NOT_A_LIST_OF_CHARACTERS = "errors were found at the given arguments: * 1st argument: not a list of characters"
        const val STEP =
            "the range step operator (//) must immediately follow the range definition operator (..), for example: 1..9//2. " +
                "If you wanted to define a default argument, use (\\\\) instead. Syntax error before: '//'"
    }
}

private val UNARY_OPERATORS = setOf("!", "&", "+", "-", "@", "^", "~~~")
private val WORD_OPERATORS = setOf("and", "in", "not", "or", "when")

/** With a line continuation before `/`, valid on every release (`<<>>`, `{}`, `%{}`) or rejected on every release (`=>`, `//`). */
private val SAME_ON_EVERY_RELEASE = setOf("%{}", "//", "<<>>", "=>", "{}")

private val CLOSERS = TokenSet.create(
    ElixirTypes.CLOSING_BIT, ElixirTypes.CLOSING_BRACKET, ElixirTypes.CLOSING_CURLY, ElixirTypes.CLOSING_PARENTHESIS,
)

/** The operators among [ENDS_OPERAND], which before `/` on the same line are references. */
private val OPERATORS = TokenSet.create(
    ElixirTypes.AND_SYMBOL_OPERATOR, ElixirTypes.AND_WORD_OPERATOR, ElixirTypes.ARROW_OPERATOR, ElixirTypes.COMPARISON_OPERATOR,
    ElixirTypes.IN_MATCH_OPERATOR, ElixirTypes.IN_OPERATOR, ElixirTypes.MATCH_OPERATOR, ElixirTypes.MULTIPLICATION_OPERATOR,
    ElixirTypes.OR_SYMBOL_OPERATOR, ElixirTypes.OR_WORD_OPERATOR, ElixirTypes.PIPE_OPERATOR, ElixirTypes.RANGE_OPERATOR,
    ElixirTypes.RELATIONAL_OPERATOR, ElixirTypes.THREE_OPERATOR, ElixirTypes.TWO_OPERATOR, ElixirTypes.TYPE_OPERATOR,
    ElixirTypes.WHEN_OPERATOR,
)

/** The tokens after `x.**` that Elixir rejects before 1.13 and accepts from 1.13. */
private val ENDS_OPERAND = TokenSet.orSet(
    OPERATORS,
    TokenSet.create(
        ElixirTypes.AFTER, ElixirTypes.ASSOCIATION_OPERATOR, ElixirTypes.CATCH, ElixirTypes.CLOSING_BIT, ElixirTypes.CLOSING_BRACKET,
        ElixirTypes.CLOSING_CURLY, ElixirTypes.CLOSING_PARENTHESIS, ElixirTypes.COMMA, ElixirTypes.DO, ElixirTypes.DOT_OPERATOR,
        ElixirTypes.ELSE, ElixirTypes.END, ElixirTypes.RESCUE, ElixirTypes.SEMICOLON,
    ),
)

/** Keys after `x.**` that Elixir before 1.13 accepts, rejects with another message, or that another rule reports. */
private val KEYS_NAMED_OTHERWISE = setOf("/", "//", "::", "=>", ".", "**")
private val KEY_ESCAPES = mapOf(
    'a' to '\u0007', 'b' to '\u0008', 'd' to '\u007F', 'e' to '\u001B', 'f' to '\u000C', 'n' to '\n', 'r' to '\r', 's' to ' ',
    't' to '\t', 'v' to '\u000B', '0' to '\u0000',
)
private val ERLANG_STRING_ESCAPES = mapOf(
    '\\' to "\\\\", '\"' to "\\\"", '\t' to "\\t", '\n' to "\\n", '\r' to "\\r", '\u000B' to "\\v", '\u0008' to "\\b",
    '\u000C' to "\\f", '\u001B' to "\\e",
)


private fun tokenType(text: String): IElementType? = ElixirLexer().run {
    start(text)
    tokenType
}

/** As Erlang prints a binary: a string when every character is printable in Latin-1 or escaped, else its bytes. */
private fun erlangBinary(text: String): String =
    when {
        text.isEmpty() -> "<<>>"
        text.all { it in ERLANG_STRING_ESCAPES || it.code in 32..126 || it.code in 160..255 } -> {
            val escaped = text.map { ERLANG_STRING_ESCAPES[it] ?: it.toString() }.joinToString("")

            if (text.any { it.code > 127 }) "<<\"$escaped\"/utf8>>" else "<<\"$escaped\">>"
        }
        else -> "<<${text.toByteArray(Charsets.UTF_8).joinToString(",") { (it.toInt() and 0xFF).toString() }}>>"
    }

/** Elixir's unescaping of a quoted keyword key, or null for a hexadecimal escape this does not read. */
private fun unescape(text: String): String? {
    val content = StringBuilder()
    var index = 0

    while (index < text.length) {
        val character = text[index++]

        if (character != '\\') {
            content.append(character)
        } else {
            val escape = text.getOrNull(index++) ?: return null
            val digits = when (escape) {
                'x' -> text.substring(index).takeWhile(::isHexadecimalDigit).takeIf { it.length == 2 }
                'u' -> text.substring(index).takeWhile(::isHexadecimalDigit).takeIf { it.length == 4 }
                    ?: text.substring(index).takeIf { it.startsWith("{") }?.substringBefore("}", "")?.let { "$it}" }
                else -> null
            }

            when {
                escape != 'x' && escape != 'u' -> content.append(KEY_ESCAPES[escape] ?: escape)
                digits == null -> return null
                else -> {
                    content.appendCodePoint(digits.trim('{', '}').toIntOrNull(16) ?: return null)
                    index += digits.length
                }
            }
        }
    }

    return content.toString()
}

