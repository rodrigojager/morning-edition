package dobra.collect.engine

/**
 * A collector is written in TypeScript; the engine runs JavaScript. This removes the TypeScript types before the
 * script runs, in common code (so the same step serves Android, the JVM and iOS later).
 *
 * The rule (recorded in AGENTS.md): collectors use **erasable TypeScript only**, the subset TypeScript's own
 * `erasableSyntaxOnly` option accepts, where removing the types leaves valid JavaScript. Type annotations,
 * interfaces, type aliases, generics, `as` / `satisfies`, non-null `!`, optional `?` markers, `declare`, overload
 * signatures, `implements` and member modifiers are removed. Syntax that would need code generation (`enum`,
 * `namespace`, parameter properties, decorators, `<T>value` casts, JSX) and modules (`import`, `export` lists) are
 * refused with their position, so the author can rewrite them.
 *
 * Removed text is replaced by spaces (line breaks are kept), so a line and column reported by the engine point at the
 * same place in the TypeScript source. Types inside template literal substitutions (`${...}`) are not removed.
 */
object TypeStripper {
    /** Returns [source] with its TypeScript types blanked out. Throws [TypeScriptSyntaxException]. */
    fun strip(source: String): String = Stripper(source).run()
}

/** The script uses TypeScript the stripper does not remove, or is not well-formed enough to read. */
class TypeScriptSyntaxException(val reason: String, val line: Int, val column: Int) :
    Exception("$reason (line $line, column $column)")

// ---- lexer ----

internal enum class TokKind { IDENT, NUM, STR, TEMPLATE, REGEX, PUNCT, EOF }

internal class Tok(
    val kind: TokKind,
    val text: String,
    val start: Int,
    val end: Int,
    /** A line break precedes this token. */
    val nlBefore: Boolean,
) {
    /** An `=` that assigns (not the `=` of a split `>=`). */
    var assign: Boolean = kind == TokKind.PUNCT && text == "="

    /** A `)` that closes the head of `if`, `while`, `for`, `switch` or `with`: a statement follows, not an operator. */
    var controlClose: Boolean = false

    fun isP(p: String) = kind == TokKind.PUNCT && text == p
    fun isId(id: String) = kind == TokKind.IDENT && text == id
}

private val PUNCTUATORS = listOf(
    "...", "===", "!==", "**=", "<<=", "&&=", "||=", "??=",
    "=>", "==", "!=", "<=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
    "**", "<<",
)

/** Keywords after which an expression starts (so `/` begins a regular expression and `{` an object). */
private val EXPRESSION_KEYWORDS = setOf(
    "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield",
    "await", "extends",
)

internal class Lexer(private val src: String, private var pos: Int = 0) {
    val toks = ArrayList<Tok>()

    fun lexAll(): List<Tok> {
        while (true) {
            val nl = skipTrivia()
            if (pos >= src.length) {
                toks.add(Tok(TokKind.EOF, "", pos, pos, nl))
                return toks
            }
            toks.add(next(nl))
        }
    }

    /** Lexes a `${ ... }` substitution starting after `${`; returns the position after its closing `}`. */
    fun lexSubstitution(): Int {
        var depth = 0
        while (true) {
            val nl = skipTrivia()
            if (pos >= src.length) fail("unterminated template literal", pos)
            val c = src[pos]
            if (c == '}' && depth == 0) return pos + 1
            val t = next(nl)
            if (t.isP("{")) depth++
            if (t.isP("}")) depth--
            toks.add(t)
        }
    }

    private fun fail(reason: String, at: Int): Nothing = throw syntaxError(src, reason, at)

    private fun skipTrivia(): Boolean {
        var nl = false
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\n' || c == '\r' || c == ' ' || c == ' ' -> { nl = true; pos++ }
                c.isWhitespace() || c == '﻿' -> pos++
                c == '/' && pos + 1 < src.length && src[pos + 1] == '/' -> {
                    while (pos < src.length && src[pos] != '\n' && src[pos] != '\r') pos++
                }
                c == '/' && pos + 1 < src.length && src[pos + 1] == '*' -> {
                    val close = src.indexOf("*/", pos + 2)
                    if (close < 0) fail("unterminated comment", pos)
                    for (k in pos until close) if (src[k] == '\n' || src[k] == '\r') nl = true
                    pos = close + 2
                }
                else -> return nl
            }
        }
        return nl
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$' || c == '\\'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '‌' || c == '‍'

    private fun next(nl: Boolean): Tok {
        val start = pos
        val c = src[pos]
        if (isIdentStart(c) || (c == '#' && pos + 1 < src.length && isIdentStart(src[pos + 1]))) {
            pos++
            while (pos < src.length && (isIdentPart(src[pos]) || src[pos] == '\\')) pos++
            return Tok(TokKind.IDENT, src.substring(start, pos), start, pos, nl)
        }
        if (c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit())) {
            val hex = c == '0' && pos + 1 < src.length && src[pos + 1].let { it == 'x' || it == 'X' }
            pos++
            while (pos < src.length) {
                val d = src[pos]
                if (d.isLetterOrDigit() || d == '_' || d == '.') {
                    pos++
                    if (!hex && (d == 'e' || d == 'E') && pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                } else {
                    break
                }
            }
            return Tok(TokKind.NUM, src.substring(start, pos), start, pos, nl)
        }
        if (c == '"' || c == '\'') {
            pos++
            while (true) {
                if (pos >= src.length || src[pos] == '\n') fail("unterminated string", start)
                val d = src[pos]
                if (d == '\\') { pos += 2; continue }
                pos++
                if (d == c) break
            }
            return Tok(TokKind.STR, src.substring(start, pos), start, pos, nl)
        }
        if (c == '`') {
            pos++
            while (true) {
                if (pos >= src.length) fail("unterminated template literal", start)
                val d = src[pos]
                if (d == '\\') { pos += 2; continue }
                if (d == '`') { pos++; break }
                if (d == '$' && pos + 1 < src.length && src[pos + 1] == '{') {
                    pos = Lexer(src, pos + 2).lexSubstitution()
                    continue
                }
                pos++
            }
            return Tok(TokKind.TEMPLATE, src.substring(start, pos), start, pos, nl)
        }
        if (c == '/' && regexAllowed()) {
            pos++
            var inClass = false
            while (true) {
                if (pos >= src.length || src[pos] == '\n') fail("unterminated regular expression", start)
                val d = src[pos]
                pos++
                when {
                    d == '\\' -> pos++
                    d == '[' -> inClass = true
                    d == ']' -> inClass = false
                    d == '/' && !inClass -> break
                }
            }
            while (pos < src.length && isIdentPart(src[pos])) pos++
            return Tok(TokKind.REGEX, src.substring(start, pos), start, pos, nl)
        }
        // `>` is always a token of its own (so `Array<Array<T>>` closes twice); `>=`, `>>` are split.
        val p = if (c == '>') ">" else PUNCTUATORS.firstOrNull { src.startsWith(it, pos) }
            ?.takeUnless { it == "?." && pos + 2 < src.length && src[pos + 2].isDigit() }
            ?: c.toString()
        pos += p.length
        val tok = Tok(TokKind.PUNCT, p, start, pos, nl)
        val prev = toks.lastOrNull()
        if (p == "=" && prev != null && prev.isP(">") && prev.end == start) tok.assign = false
        return tok
    }

    private fun regexAllowed(): Boolean {
        val prev = toks.lastOrNull() ?: return true
        return when (prev.kind) {
            TokKind.NUM, TokKind.STR, TokKind.TEMPLATE, TokKind.REGEX -> false
            TokKind.IDENT -> prev.text in EXPRESSION_KEYWORDS
            else -> prev.text !in setOf(")", "]", "++", "--")
        }
    }
}

internal fun syntaxError(src: String, reason: String, at: Int): TypeScriptSyntaxException {
    var line = 1
    var col = 1
    for (k in 0 until minOf(at, src.length)) {
        if (src[k] == '\n') { line++; col = 1 } else col++
    }
    return TypeScriptSyntaxException(reason, line, col)
}

// ---- stripper ----

private enum class FrameKind { BLOCK, PARAMS, CLASS, OBJECT, PAREN, BRACKET, FOR }

private class Frame(val kind: FrameKind, val control: Boolean = false) {
    var ternaries = 0

    // PARAMS
    var inDefault = false
    var atParamStart = true
    var constructorParams = false

    /** PARAMS of a declaration that may be an overload signature: where the declaration starts. */
    var declStart = -1

    // BLOCK, FOR
    var decl = false
    var declInit = false

    // CLASS
    var head = true
    var inInit = false
    var memberStart = -1

    // OBJECT
    var objHead = true
}

private val MEMBER_MODIFIERS = setOf("public", "private", "protected", "readonly", "override", "declare", "abstract")
private val PARAM_MODIFIERS = setOf("public", "private", "protected", "readonly", "override")
private val STATEMENT_KEYWORDS = setOf(
    "if", "for", "while", "return", "function", "class", "switch", "throw", "try", "do", "break", "continue",
)

private class Stripper(private val src: String) {
    private val toks: List<Tok> = Lexer(src).lexAll()
    private val match = IntArray(toks.size) { -1 }
    private val gone = BooleanArray(toks.size)
    private val out = src.toCharArray()
    private val stack = ArrayList<Frame>()
    private var prev = -1

    private var pendingParamsAt = -1
    private var pendingParamsDeclStart = -1
    private var pendingClassDepth = -1

    init {
        val open = ArrayList<Int>()
        for ((i, t) in toks.withIndex()) {
            if (t.kind != TokKind.PUNCT) continue
            when (t.text) {
                "(", "[", "{" -> open.add(i)
                ")", "]", "}" -> {
                    val o = open.removeLastOrNull() ?: fail("unbalanced '${t.text}'", i)
                    val expected = when (t.text) { ")" -> "("; "]" -> "["; else -> "{" }
                    if (toks[o].text != expected) fail("'${toks[o].text}' closed by '${t.text}'", i)
                    match[o] = i
                    match[i] = o
                }
            }
        }
        if (open.isNotEmpty()) fail("'${toks[open.last()].text}' is never closed", open.last())
    }

    fun run(): String {
        stack.add(Frame(FrameKind.BLOCK))
        var i = 0
        while (toks[i].kind != TokKind.EOF) {
            if (!gone[i]) {
                step(i)
                if (!gone[i]) prev = i
            }
            i++
        }
        return out.concatToString()
    }

    // ---- helpers ----

    private fun fail(reason: String, at: Int): Nothing = throw syntaxError(src, reason, toks[at].start)

    private fun tok(i: Int) = toks[minOf(i, toks.size - 1)]
    private fun isP(i: Int, p: String) = tok(i).isP(p)
    private fun isId(i: Int, id: String) = tok(i).isId(id)
    private fun isIdent(i: Int) = tok(i).kind == TokKind.IDENT
    private val prevTok: Tok? get() = if (prev >= 0) toks[prev] else null
    private val top: Frame get() = stack.last()

    /** Blanks tokens [from, to) and the text between them. */
    private fun blank(from: Int, to: Int) {
        if (to <= from) return
        for (k in from until to) gone[k] = true
        for (c in toks[from].start until toks[to - 1].end) {
            if (out[c] != '\n' && out[c] != '\r') out[c] = ' '
        }
    }

    private fun endsExpression(t: Tok?): Boolean {
        if (t == null) return false
        return when (t.kind) {
            TokKind.NUM, TokKind.STR, TokKind.TEMPLATE, TokKind.REGEX -> true
            TokKind.IDENT -> t.text !in EXPRESSION_KEYWORDS
            TokKind.PUNCT -> t.text in setOf(")", "]", "}", "++", "--") && !t.controlClose
            TokKind.EOF -> false
        }
    }

    private fun atStatementStart(i: Int): Boolean {
        if (top.kind != FrameKind.BLOCK) return false
        val p = prevTok ?: return true
        return p.isP(";") || p.isP("{") || p.isP("}") || toks[i].nlBefore
    }

    // ---- the pass ----

    private fun step(i: Int) {
        val t = toks[i]
        val f = top

        if (f.kind == FrameKind.CLASS && classMember(i)) return
        if (t.kind == TokKind.IDENT && atStatementStart(i) && statementLevel(i)) return

        // Expression-level TypeScript.
        if ((t.isId("as") || t.isId("satisfies")) && endsExpression(prevTok) && !t.nlBefore) {
            val end = parseType(i + 1)
            if (end < 0) fail("cannot read the type after '${t.text}'", i)
            blank(i, end)
            markAssign(end)
            return
        }
        if (t.isP("!") && endsExpression(prevTok) && !t.nlBefore) {
            blank(i, i + 1)
            return
        }
        if (t.isP("<") && angle(i)) return
        if (t.isP("@")) fail("decorators are not erasable TypeScript", i)

        when (t.kind) {
            TokKind.IDENT -> ident(i)
            TokKind.PUNCT -> punct(i)
            else -> {}
        }
    }

    /** `type`, `interface`, `declare`, `enum`, `namespace`, `abstract class`, `import`, `export`. */
    private fun statementLevel(i: Int): Boolean {
        val t = toks[i]
        when (t.text) {
            "type" -> if (isIdent(i + 1) && (tok(i + 2).isP("=") || isP(i + 2, "<"))) {
                blank(i, typeAlias(i))
                return true
            }
            "interface" -> if (isIdent(i + 1) && !tok(i + 1).nlBefore) {
                blank(i, interfaceDecl(i))
                return true
            }
            "declare" -> if (isIdent(i + 1) && !tok(i + 1).nlBefore) {
                blank(i, skipStatement(i, stopAtBrace = false))
                return true
            }
            "enum" -> if (isIdent(i + 1)) fail("'enum' is not erasable TypeScript; use a plain object", i)
            "namespace", "module" -> if ((isIdent(i + 1) || tok(i + 1).kind == TokKind.STR) && !tok(i + 1).nlBefore) {
                fail("'${t.text}' is not erasable TypeScript", i)
            }
            "abstract" -> if (isId(i + 1, "class")) {
                blank(i, i + 1)
                return true
            }
            "import" -> {
                if (isP(i + 1, "(") || isP(i + 1, ".")) return false
                if (isId(i + 1, "type")) {
                    blank(i, skipStatement(i, stopAtBrace = false))
                    return true
                }
                fail("a collector is a script and cannot import modules", i)
            }
            "export" -> {
                val n = tok(i + 1)
                when {
                    n.isId("type") || n.isId("interface") || n.isId("declare") -> {
                        blank(i, i + 1)
                        return statementLevelAfterExport(i + 1)
                    }
                    n.isId("default") -> {
                        blank(i, i + 2)
                        return true
                    }
                    n.isId("function") || n.isId("async") || n.isId("class") || n.isId("const") || n.isId("let") ||
                        n.isId("var") || n.isId("abstract") -> {
                        blank(i, i + 1)
                        return true
                    }
                    n.isId("enum") -> fail("'enum' is not erasable TypeScript; use a plain object", i + 1)
                    else -> fail("a collector is a script: export lists and re-exports are not allowed", i)
                }
            }
        }
        return false
    }

    private fun statementLevelAfterExport(i: Int): Boolean {
        val t = toks[i]
        val end = when (t.text) {
            "type" -> typeAlias(i)
            "interface" -> interfaceDecl(i)
            else -> skipStatement(i, stopAtBrace = false)
        }
        blank(i, end)
        return true
    }

    private fun typeAlias(i: Int): Int {
        var j = i + 2
        if (isP(j, "<")) j = typeParams(j).also { if (it < 0) fail("cannot read the type parameters", i) }
        if (!isP(j, "=")) fail("expected '=' in the type alias", j)
        j = parseType(j + 1)
        if (j < 0) fail("cannot read the type alias", i)
        if (isP(j, ";")) j++
        return j
    }

    private fun interfaceDecl(i: Int): Int {
        var j = i + 2
        if (isP(j, "<")) j = typeParams(j).also { if (it < 0) fail("cannot read the type parameters", i) }
        if (isId(j, "extends")) {
            do {
                j = parseType(j + 1)
                if (j < 0) fail("cannot read the interface's extends list", i)
            } while (isP(j, ","))
        }
        if (!isP(j, "{")) fail("expected '{' in the interface", j)
        return match[j] + 1
    }

    /**
     * The end (exclusive) of a statement or class member that is removed whole: up to a `;` (included), or a line
     * break where the statement cannot continue. With [stopAtBrace], stops before the `}` that closes the class.
     */
    private fun skipStatement(i: Int, stopAtBrace: Boolean): Int {
        var k = i + 1
        while (true) {
            val t = tok(k)
            if (t.kind == TokKind.EOF) return k
            if (t.isP("}") && stopAtBrace) return k
            if (t.isP(";")) return k + 1
            if (t.nlBefore && k > i + 1) {
                val p = toks[k - 1]
                val continues = p.kind == TokKind.PUNCT && p.text in setOf(":", ",", "|", "&", "=>", "=", "<", ".")
                val continuing = t.kind == TokKind.PUNCT && t.text in setOf("|", "&", ".", "=>", "{", ":", "=")
                if (!continues && !continuing && !t.isId("extends")) return k
            }
            k = if (t.kind == TokKind.PUNCT && t.text in setOf("(", "[", "{")) match[k] + 1 else k + 1
        }
    }

    private fun markAssign(i: Int) {
        if (isP(i, "=")) tok(i).assign = true
    }

    // ---- classes ----

    /** Handles a token at the top level of a class body; returns true when it was consumed. */
    private fun classMember(i: Int): Boolean {
        val f = top
        val t = toks[i]
        if (t.isP("}")) return false
        val memberLike = t.kind == TokKind.IDENT || t.kind == TokKind.STR || t.kind == TokKind.NUM ||
            t.isP("[") || t.isP("*")
        if (!f.head && t.nlBefore && memberLike && endsExpression(prevTok) && f.ternaries == 0) {
            // A field ended at the line break (automatic semicolon).
            f.head = true
            f.inInit = false
        }
        if (f.inInit) {
            if (t.isP(";")) {
                f.inInit = false
                f.head = true
                f.ternaries = 0
                return true
            }
            return false
        }
        if (f.head) {
            if (t.isP(";")) return true
            f.head = false
            f.memberStart = i
            var k = i
            var removeWhole = false
            while (isIdent(k) && tok(k).text in MEMBER_MODIFIERS && isModifierAt(k)) {
                if (tok(k).text == "declare" || tok(k).text == "abstract") removeWhole = true
                k++
            }
            if (removeWhole) {
                blank(i, skipStatement(k - 1, stopAtBrace = true))
                f.head = true
                return true
            }
            blank(i, k)
            if (isP(k, "[") && isIdent(k + 1) && isP(k + 2, ":")) {
                // Index signature: [key: string]: T;
                var end = match[k] + 1
                if (isP(end, ":")) end = parseType(end + 1)
                if (end < 0) fail("cannot read the index signature", k)
                if (isP(end, ";")) end++
                blank(k, end)
                f.head = true
                return true
            }
            if (k != i) return true // the next token goes through the normal path
            return false
        }
        when {
            t.isP("?") && (isP(i + 1, ":") || isP(i + 1, "(") || isP(i + 1, ";") || isP(i + 1, "=") ||
                isP(i + 1, "<") || tok(i + 1).nlBefore) -> {
                blank(i, i + 1)
                return true
            }
            t.isP(":") -> {
                val end = parseType(i + 1)
                if (end < 0) fail("cannot read the type of the class member", i)
                blank(i, end)
                markAssign(end)
                return true
            }
            t.isP("=") && t.assign -> {
                f.inInit = true
                return true
            }
            t.isP(";") -> {
                f.head = true
                return true
            }
            t.isP("<") -> {
                val end = typeParams(i)
                if (end > 0 && isP(end, "(")) {
                    blank(i, end)
                    return true
                }
                return false
            }
            t.isP("(") -> {
                val params = Frame(FrameKind.PARAMS)
                params.constructorParams = prevTok?.text == "constructor"
                params.declStart = f.memberStart
                stack.add(params)
                return true
            }
        }
        return false
    }

    private fun isModifierAt(k: Int): Boolean {
        val n = tok(k + 1)
        if (n.nlBefore || n.kind == TokKind.EOF) return false
        if (n.kind == TokKind.PUNCT && n.text in setOf("(", ":", "=", ";", "?", "!", "<", "}", ",", ")")) return false
        return true
    }

    // ---- identifiers ----

    private fun ident(i: Int) {
        val t = toks[i]
        val f = top
        if (prevTok?.isP(".") == true || prevTok?.isP("?.") == true) return
        if (t.text in STATEMENT_KEYWORDS && t.nlBefore && f.kind == FrameKind.BLOCK) f.decl = false
        when (t.text) {
            "function" -> {
                var j = i + 1
                if (isP(j, "*")) j++
                if (isIdent(j) && !isP(j, "(")) j++
                if (isP(j, "<")) {
                    val end = typeParams(j)
                    if (end < 0) fail("cannot read the type parameters", j)
                    blank(j, end)
                    j = end
                }
                if (isP(j, "(")) {
                    pendingParamsAt = j
                    pendingParamsDeclStart = if (atStatementStart(i) || prevTok?.isId("async") == true) i else -1
                }
            }
            "class" -> {
                var j = i + 1
                if (isIdent(j) && !isId(j, "extends") && !isId(j, "implements")) j++
                if (isP(j, "<")) {
                    val end = typeParams(j)
                    if (end < 0) fail("cannot read the type parameters", j)
                    blank(j, end)
                }
                pendingClassDepth = stack.size
            }
            "implements" -> if (pendingClassDepth == stack.size) {
                var j = i
                do {
                    j = parseType(j + 1)
                    if (j < 0) fail("cannot read the implements list", i)
                } while (isP(j, ","))
                blank(i, j)
            }
            "let", "const", "var" -> {
                if (t.text == "const" && isId(i + 1, "enum")) fail("'const enum' is not erasable TypeScript", i)
                val forHead = f.kind == FrameKind.FOR && prevTok?.isP("(") == true
                if (forHead || (f.kind == FrameKind.BLOCK && atStatementStart(i))) {
                    f.decl = true
                    f.declInit = false
                }
            }
            "of", "in" -> if (f.kind == FrameKind.FOR) f.decl = false
        }
        if (f.kind == FrameKind.PARAMS && f.atParamStart) {
            f.atParamStart = false
            if (t.text in PARAM_MODIFIERS && (isIdent(i + 1) || isP(i + 1, "{") || isP(i + 1, "["))) {
                fail("parameter properties are not erasable TypeScript; assign the field in the constructor", i)
            }
            if (t.text == "this" && isP(i + 1, ":")) {
                var end = parseType(i + 2)
                if (end < 0) fail("cannot read the type of 'this'", i)
                if (isP(end, ",")) end++
                blank(i, end)
                f.atParamStart = true
            }
        }
    }

    // ---- `<` ----

    /** Type parameters or type arguments at a `<`; returns true when they were removed. */
    private fun angle(i: Int): Boolean {
        val f = top
        val p = prevTok
        // Generic method in an object literal: m<T>(...)
        if (f.kind == FrameKind.OBJECT && f.objHead && p != null && endsExpression(p)) {
            val end = typeParams(i)
            if (end > 0 && isP(end, "(")) {
                blank(i, end)
                return true
            }
        }
        if (!endsExpression(p) || p?.isId("async") == true) {
            // Generic arrow function: <T>(x: T) => x
            val end = typeParams(i)
            if (end > 0 && isP(end, "(") && isArrowParams(end, f)) {
                blank(i, end)
                return true
            }
            if (end > 0 && !endsExpression(p)) fail("'<T>value' casts are not erasable here; use 'value as T'", i)
            return false
        }
        if (p != null && p.kind == TokKind.IDENT) {
            // Type arguments: f<T>(x), new Map<K, V>(), class C extends Base<T> {
            val end = typeArgs(i)
            if (end < 0) return false
            val next = tok(end)
            val inHeritage = pendingClassDepth == stack.size
            if (next.isP("(") || next.kind == TokKind.TEMPLATE ||
                (inHeritage && (next.isP("{") || next.isId("implements")))
            ) {
                blank(i, end)
                return true
            }
        }
        return false
    }

    private fun isArrowParams(open: Int, f: Frame): Boolean {
        val close = match[open]
        if (close < 0) return false
        val n = tok(close + 1)
        if (n.isP("=>") && !n.nlBefore) return true
        if (n.isP(":") && f.ternaries == 0) {
            val end = returnType(close + 2)
            return end > 0 && isP(end, "=>")
        }
        return false
    }

    // ---- punctuation ----

    private fun punct(i: Int) {
        val t = toks[i]
        val f = top
        when (t.text) {
            "(" -> openParen(i)
            "[" -> stack.add(Frame(FrameKind.BRACKET))
            "{" -> openBrace(i)
            ")" -> closeParen(i)
            "]" -> pop()
            "}" -> pop()
            "," -> when (f.kind) {
                FrameKind.PARAMS -> {
                    f.inDefault = false
                    f.atParamStart = true
                    f.ternaries = 0
                }
                FrameKind.OBJECT -> {
                    f.objHead = true
                    f.ternaries = 0
                }
                FrameKind.BLOCK, FrameKind.FOR -> if (f.decl) f.declInit = false
                else -> {}
            }
            ";" -> {
                f.ternaries = 0
                f.decl = false
            }
            "..." -> if (f.kind == FrameKind.OBJECT) f.objHead = false
            "=" -> if (t.assign) {
                when (f.kind) {
                    FrameKind.PARAMS -> f.inDefault = true
                    FrameKind.OBJECT -> f.objHead = false
                    FrameKind.BLOCK, FrameKind.FOR -> if (f.decl) f.declInit = true
                    else -> {}
                }
            }
            "?" -> question(i)
            ":" -> colon(i)
        }
        if (f.kind == FrameKind.PARAMS && t.text != ",") f.atParamStart = false
    }

    private fun question(i: Int) {
        val f = top
        if (f.kind == FrameKind.PARAMS && !f.inDefault) {
            blank(i, i + 1) // optional parameter
            return
        }
        f.ternaries++
    }

    private fun colon(i: Int) {
        val f = top
        if (f.ternaries > 0) {
            f.ternaries--
            return
        }
        val isType = when (f.kind) {
            FrameKind.PARAMS -> !f.inDefault
            FrameKind.BLOCK, FrameKind.FOR -> f.decl && !f.declInit
            FrameKind.OBJECT -> {
                f.objHead = false
                false
            }
            else -> false
        }
        if (!isType) return
        val end = parseType(i + 1)
        if (end < 0) fail("cannot read the type annotation", i)
        blank(i, end)
        markAssign(end)
    }

    private fun openParen(i: Int) {
        val f = top
        val p = prevTok
        val frame = when {
            i == pendingParamsAt -> Frame(FrameKind.PARAMS).also { it.declStart = pendingParamsDeclStart }
            p?.isId("catch") == true -> Frame(FrameKind.PARAMS)
            p?.isId("for") == true || (p?.isId("await") == true && prev > 0 && toks[prev - 1].isId("for")) ->
                Frame(FrameKind.FOR, control = true)
            p != null && p.kind == TokKind.IDENT && p.text in setOf("if", "while", "switch", "with") &&
                !(prev > 0 && toks[prev - 1].isP(".")) -> Frame(FrameKind.PAREN, control = true)
            (!endsExpression(p) || p?.isId("async") == true) && isArrowParams(i, f) -> Frame(FrameKind.PARAMS)
            f.kind == FrameKind.OBJECT && f.objHead -> Frame(FrameKind.PARAMS)
            else -> Frame(FrameKind.PAREN)
        }
        if (i == pendingParamsAt) pendingParamsAt = -1
        stack.add(frame)
    }

    private fun closeParen(i: Int) {
        val popped = pop()
        if (popped.control) toks[i].controlClose = true
        if (popped.kind != FrameKind.PARAMS) return
        var j = i + 1
        if (isP(j, ":")) {
            val end = returnType(j + 1)
            if (end < 0) fail("cannot read the return type", j)
            blank(j, end)
            j = end
        }
        // An overload signature (no body): removed whole.
        if (popped.declStart >= 0 && !isP(j, "{") && !isP(j, "=>")) {
            val end = if (isP(j, ";")) j + 1 else j
            blank(popped.declStart, end)
            if (top.kind == FrameKind.CLASS) top.head = true
        }
    }

    private fun openBrace(i: Int) {
        val f = top
        val p = prevTok
        if (pendingClassDepth == stack.size) {
            pendingClassDepth = -1
            stack.add(Frame(FrameKind.CLASS))
            return
        }
        val block = when {
            p == null -> true
            p.isP(")") || p.isP("=>") -> true
            p.kind == TokKind.IDENT && p.text in setOf("else", "try", "finally", "do", "static") -> true
            (p.isP(";") || p.isP("{") || p.isP("}")) && f.kind == FrameKind.BLOCK -> true
            p.isP(":") && f.kind == FrameKind.BLOCK -> true
            else -> false
        }
        stack.add(Frame(if (block) FrameKind.BLOCK else FrameKind.OBJECT))
    }

    private fun pop(): Frame {
        val popped = if (stack.size > 1) stack.removeAt(stack.size - 1) else top
        val f = top
        if (f.kind == FrameKind.CLASS && popped.kind == FrameKind.BLOCK && !f.inInit) f.head = true
        if (popped.kind == FrameKind.CLASS && pendingClassDepth > stack.size) pendingClassDepth = -1
        return popped
    }

    // ---- types ----

    private fun returnType(i0: Int): Int {
        var i = i0
        if (isId(i, "asserts") && isIdent(i + 1)) {
            i += 2
            if (!isId(i, "is")) return i
            return parseType(i + 1)
        }
        if (isIdent(i) && isId(i + 1, "is")) return parseType(i + 2)
        return parseType(i)
    }

    /** The end (exclusive) of the type starting at [i0], or -1. */
    private fun parseType(i0: Int): Int {
        var i = i0
        if (isP(i, "|") || isP(i, "&")) i++
        i = unionMember(i)
        if (i < 0) return -1
        while (isP(i, "|") || isP(i, "&")) {
            i = unionMember(i + 1)
            if (i < 0) return -1
        }
        if (isId(i, "extends") && !tok(i).nlBefore) {
            var j = unionMember(i + 1)
            while (j >= 0 && (isP(j, "|") || isP(j, "&"))) j = unionMember(j + 1)
            if (j < 0 || !isP(j, "?")) return -1
            j = parseType(j + 1)
            if (j < 0 || !isP(j, ":")) return -1
            return parseType(j + 1)
        }
        return i
    }

    private fun unionMember(i0: Int): Int {
        var i = i0
        while (isIdent(i) && tok(i).text in setOf("keyof", "unique", "readonly") && isTypeStart(i + 1)) i++
        if (isId(i, "infer") && isIdent(i + 1)) {
            i += 2
        } else if (isId(i, "typeof") && isIdent(i + 1)) {
            i += 2
            while (isP(i, ".") && isIdent(i + 1)) i += 2
            if (isP(i, "<") && !tok(i).nlBefore) i = typeArgs(i)
        } else {
            i = primaryType(i)
        }
        if (i < 0) return -1
        while (isP(i, "[") && !tok(i).nlBefore) i = match[i] + 1
        return i
    }

    private fun isTypeStart(i: Int): Boolean {
        val t = tok(i)
        return t.kind == TokKind.IDENT || t.kind == TokKind.STR || t.kind == TokKind.NUM ||
            t.kind == TokKind.TEMPLATE || (t.kind == TokKind.PUNCT && t.text in setOf("(", "[", "{", "<", "-"))
    }

    private fun primaryType(i: Int): Int {
        val t = tok(i)
        return when (t.kind) {
            TokKind.STR, TokKind.NUM, TokKind.TEMPLATE -> i + 1
            TokKind.PUNCT -> when (t.text) {
                "(" -> {
                    val close = match[i]
                    if (isP(close + 1, "=>")) parseType(close + 2) else close + 1
                }
                "[", "{" -> match[i] + 1
                "<" -> functionType(typeParams(i))
                "-" -> if (tok(i + 1).kind == TokKind.NUM) i + 2 else -1
                else -> -1
            }
            TokKind.IDENT -> when {
                t.text == "new" -> functionType(if (isP(i + 1, "<")) typeParams(i + 1) else i + 1)
                t.text == "abstract" && isId(i + 1, "new") ->
                    functionType(if (isP(i + 2, "<")) typeParams(i + 2) else i + 2)
                t.text in setOf("function", "return", "if", "else", "in", "instanceof", "of") -> -1
                else -> {
                    var j = i + 1
                    while (isP(j, ".") && isIdent(j + 1)) j += 2
                    if (isP(j, "<") && !tok(j).nlBefore) j = typeArgs(j)
                    j
                }
            }
            else -> -1
        }
    }

    /** `(params) => Type` starting at [i] (after any type parameters). */
    private fun functionType(i: Int): Int {
        if (i < 0 || !isP(i, "(")) return -1
        val close = match[i]
        if (!isP(close + 1, "=>")) return -1
        return parseType(close + 2)
    }

    /** `<A, B>` type arguments; the end (exclusive) or -1. */
    private fun typeArgs(i0: Int): Int {
        if (!isP(i0, "<")) return -1
        var i = i0 + 1
        while (true) {
            i = parseType(i)
            if (i < 0) return -1
            if (isP(i, ",")) {
                i++
                continue
            }
            return if (isP(i, ">")) i + 1 else -1
        }
    }

    /** `<const T extends U = V, ...>` type parameters; the end (exclusive) or -1. */
    private fun typeParams(i0: Int): Int {
        if (!isP(i0, "<")) return -1
        var i = i0 + 1
        while (true) {
            while (isIdent(i) && tok(i).text in setOf("const", "in", "out") && isIdent(i + 1)) i++
            if (!isIdent(i)) return -1
            i++
            if (isId(i, "extends")) {
                i = parseType(i + 1)
                if (i < 0) return -1
            }
            if (isP(i, "=")) {
                i = parseType(i + 1)
                if (i < 0) return -1
            }
            if (isP(i, ",")) {
                i++
                if (isP(i, ">")) return i + 1
                continue
            }
            return if (isP(i, ">")) i + 1 else -1
        }
    }
}
