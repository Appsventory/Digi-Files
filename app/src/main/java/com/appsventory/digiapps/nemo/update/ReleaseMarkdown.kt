package com.appsventory.digiapps.nemo.update

/**
 * Small, dependency-free Markdown parser for GitHub release notes.
 *
 * Output is a plain data model ([MdBlock] / [MdRun]); the UI turns it into Compose text.
 * Nothing here is ever treated as HTML: tags stay literal text (except `<br>`, which is a line break),
 * and links are only kept for http / https / mailto so release notes cannot smuggle other schemes.
 *
 * Supported: `#`..`######` and setext headings, paragraphs, bold / italic / bold-italic / strike,
 * inline code, fenced code blocks (``` and ~~~), bullet / numbered / task lists with nesting,
 * block quotes, horizontal rules, links, autolinks, images (shown as their alt text) and line breaks.
 * Tables are kept verbatim as preformatted text instead of being mangled. Single newlines inside a
 * paragraph are line breaks, which is how GitHub renders release bodies.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val runs: List<MdRun>) : MdBlock
    data class Paragraph(val runs: List<MdRun>) : MdBlock
    data class ListBlock(val items: List<MdListItem>) : MdBlock
    data class CodeBlock(val language: String?, val text: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
}

data class MdListItem(
    /** 0 = top level. */
    val depth: Int,
    /** null = bullet, otherwise the number to show. */
    val ordinal: Int?,
    /** null = not a task item, otherwise checked state. */
    val checked: Boolean?,
    val runs: List<MdRun>
)

data class MdRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    /** Only ever http / https / mailto. */
    val link: String? = null
)

object ReleaseMarkdown {

    private const val MAX_QUOTE_DEPTH = 4

    fun parse(source: String?): List<MdBlock> {
        if (source.isNullOrBlank()) return emptyList()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return parseBlocks(lines, 0)
    }

    // ───────────────────────────── blocks ─────────────────────────────

    private val fenceRe = Regex("""^ {0,3}(`{3,}|~{3,})\s*([^`\s]*)[^`]*$""")
    private val atxRe = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val ruleRe = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val setextH1Re = Regex("""^ {0,3}=+[ \t]*$""")
    private val setextH2Re = Regex("""^ {0,3}-+[ \t]*$""")
    private val bulletRe = Regex("""^([ \t]*)([-*+])[ \t]+(.*)$""")
    private val numberedRe = Regex("""^([ \t]*)(\d{1,9})[.)][ \t]+(.*)$""")
    private val quoteRe = Regex("""^ {0,3}>[ ]?(.*)$""")
    private val tableSepRe = Regex("""^[ \t]*\|?[ \t]*:?-{1,}:?[ \t]*(\|[ \t]*:?-{1,}:?[ \t]*)*\|?[ \t]*$""")
    private val htmlCommentRe = Regex("""^\s*<!--.*-->\s*$""")
    private val taskRe = Regex("""^\[([ xX])\][ \t]+(.*)$""")

    private fun parseBlocks(lines: List<String>, quoteDepth: Int): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (line.isBlank()) { i++; continue }
            if (htmlCommentRe.matches(line)) { i++; continue }

            // fenced code — `##` inside a fence is never a heading
            fenceRe.matchEntire(line)?.let { m ->
                val fence = m.groupValues[1]
                val lang = m.groupValues[2].ifBlank { null }
                val body = ArrayList<String>()
                i++
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.length >= fence.length && t.all { it == fence[0] }) { i++; break }
                    body += lines[i]
                    i++
                }
                out += MdBlock.CodeBlock(lang, body.joinToString("\n"))
                continue
            }

            // ATX heading
            atxRe.matchEntire(line)?.let { m ->
                out += MdBlock.Heading(m.groupValues[1].length, parseInline(m.groupValues[2].trim()))
                i++
                continue
            }

            if (ruleRe.matches(line)) { out += MdBlock.Rule; i++; continue }

            // block quote
            if (quoteRe.matches(line) && quoteDepth < MAX_QUOTE_DEPTH) {
                val inner = ArrayList<String>()
                while (i < lines.size) {
                    val qm = quoteRe.matchEntire(lines[i]) ?: break
                    inner += qm.groupValues[1]
                    i++
                }
                out += MdBlock.Quote(parseBlocks(inner, quoteDepth + 1))
                continue
            }

            // list
            if (bulletRe.matches(line) || numberedRe.matches(line)) {
                val (block, next) = parseList(lines, i)
                out += block
                i = next
                continue
            }

            // table → keep verbatim (monospace) rather than corrupting it
            if (line.contains('|') && i + 1 < lines.size && lines[i + 1].contains('-') &&
                tableSepRe.matches(lines[i + 1])
            ) {
                val rows = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    rows += lines[i].trimEnd()
                    i++
                }
                out += MdBlock.CodeBlock("table", rows.joinToString("\n"))
                continue
            }

            // paragraph (+ setext heading)
            val para = ArrayList<String>()
            para += line
            i++
            var setext = 0
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) break
                if (para.size >= 1 && setextH1Re.matches(l)) { setext = 1; i++; break }
                if (para.size >= 1 && setextH2Re.matches(l)) { setext = 2; i++; break }
                if (startsNewBlock(l)) break
                para += l
                i++
            }
            if (setext > 0) {
                out += MdBlock.Heading(setext, parseInline(para.joinToString(" ") { it.trim() }))
            } else {
                out += MdBlock.Paragraph(parseInline(joinParagraph(para)))
            }
        }
        return out
    }

    private fun startsNewBlock(l: String): Boolean =
        fenceRe.matches(l) || atxRe.matches(l) || ruleRe.matches(l) ||
            quoteRe.matches(l) || bulletRe.matches(l) || numberedRe.matches(l) ||
            htmlCommentRe.matches(l)

    /** Soft newlines are line breaks (GitHub release-body behaviour). Trailing `\` / 2 spaces are stripped. */
    private fun joinParagraph(lines: List<String>): String =
        lines.joinToString("\n") { it.trimStart().removeSuffix("\\").trimEnd() }

    private fun indentOf(s: String): Int {
        var n = 0
        for (c in s) { if (c == ' ') n++ else if (c == '\t') n += 4 else break }
        return n
    }

    private fun parseList(lines: List<String>, start: Int): Pair<MdBlock.ListBlock, Int> {
        class Raw(val indent: Int, val ordinal: Int?, val text: StringBuilder)

        val raws = ArrayList<Raw>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            val b = bulletRe.matchEntire(line)
            val n = numberedRe.matchEntire(line)
            when {
                b != null && !ruleRe.matches(line) ->
                    raws += Raw(indentOf(b.groupValues[1]), null, StringBuilder(b.groupValues[3]))
                n != null ->
                    raws += Raw(indentOf(n.groupValues[1]), n.groupValues[2].toIntOrNull() ?: 1, StringBuilder(n.groupValues[3]))
                line.isBlank() -> {
                    // a blank line only continues the list if another item follows
                    var j = i + 1
                    while (j < lines.size && lines[j].isBlank()) j++
                    if (j < lines.size && (bulletRe.matches(lines[j]) || numberedRe.matches(lines[j])) &&
                        !ruleRe.matches(lines[j])
                    ) { i = j; continue } else break
                }
                raws.isNotEmpty() && indentOf(line) > raws.last().indent &&
                    !fenceRe.matches(line.trim()) -> {
                    // continuation of the previous item
                    raws.last().text.append('\n').append(line.trim())
                }
                else -> break
            }
            i++
        }

        // indent → depth (stack of distinct indents)
        val indents = ArrayList<Int>()
        val counters = HashMap<Int, Int>()
        val items = ArrayList<MdListItem>()
        for (r in raws) {
            while (indents.isNotEmpty() && indents.last() > r.indent) {
                counters.remove(indents.size - 1)
                indents.removeAt(indents.size - 1)
            }
            if (indents.isEmpty() || indents.last() < r.indent) indents += r.indent
            val depth = indents.size - 1

            val shown: Int? = if (r.ordinal == null) {
                counters.remove(depth); null
            } else {
                val next = counters[depth] ?: r.ordinal
                counters[depth] = next + 1
                next
            }
            // deeper counters reset whenever a shallower item appears
            counters.keys.filter { it > depth }.forEach { counters.remove(it) }

            var text = r.text.toString()
            var checked: Boolean? = null
            taskRe.matchEntire(text.lineSequence().first())?.let { tm ->
                checked = tm.groupValues[1] != " "
                text = tm.groupValues[2] + text.substringAfter('\n', "").let { if (it.isEmpty()) "" else "\n$it" }
            }
            items += MdListItem(depth, shown, checked, parseInline(text))
        }
        return MdBlock.ListBlock(items) to i
    }

    // ───────────────────────────── inline ─────────────────────────────

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null
    )

    fun parseInline(text: String): List<MdRun> {
        val runs = ArrayList<MdRun>()
        inline(text, Style(), runs)
        return mergeRuns(runs)
    }

    private fun mergeRuns(runs: List<MdRun>): List<MdRun> {
        val out = ArrayList<MdRun>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == r.copy(text = "")) {
                out[out.size - 1] = last.copy(text = last.text + r.text)
            } else out += r
        }
        return out
    }

    private val punct = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val brRe = Regex("""^<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val autolinkAngleRe = Regex("""^<((?:https?://|mailto:)[^\s<>]+)>""", RegexOption.IGNORE_CASE)
    private val bareUrlRe = Regex("""^https?://[^\s<>]+""", RegexOption.IGNORE_CASE)

    private fun inline(s: String, style: Style, out: MutableList<MdRun>) {
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out += MdRun(decodeEntities(sb.toString()), style.bold, style.italic, style.strike, false, style.link)
                sb.setLength(0)
            }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]

            // escapes
            if (c == '\\' && i + 1 < s.length && s[i + 1] in punct) {
                sb.append(s[i + 1]); i += 2; continue
            }

            // code span
            if (c == '`') {
                var n = 0
                while (i + n < s.length && s[i + n] == '`') n++
                val close = findBacktickClose(s, i + n, n)
                if (close >= 0) {
                    flush()
                    var code = s.substring(i + n, close).replace('\n', ' ')
                    if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
                        code = code.substring(1, code.length - 1)
                    }
                    out += MdRun(code, style.bold, style.italic, style.strike, true, style.link)
                    i = close + n
                    continue
                }
                sb.append(s, i, i + n); i += n; continue
            }

            // <br> and <https://autolink>
            if (c == '<') {
                val rest = s.substring(i)
                val br = brRe.find(rest)
                if (br != null) {
                    sb.append('\n'); i += br.value.length; continue
                }
                val auto = autolinkAngleRe.find(rest)
                if (auto != null) {
                    flush()
                    val url = auto.groupValues[1]
                    out += MdRun(url, style.bold, style.italic, style.strike, false, safeLink(url))
                    i += auto.value.length
                    continue
                }
            }

            // image → alt text only (never fetched)
            if (c == '!' && i + 1 < s.length && s[i + 1] == '[') {
                val link = parseLinkAt(s, i + 1)
                if (link != null) {
                    flush()
                    val alt = link.label.ifBlank { "image" }
                    inline(alt, style, out)
                    i = link.end
                    continue
                }
            }

            // [label](url)
            if (c == '[') {
                val link = parseLinkAt(s, i)
                if (link != null) {
                    flush()
                    val safe = safeLink(link.url)
                    inline(link.label, if (safe != null) style.copy(link = safe) else style, out)
                    i = link.end
                    continue
                }
            }

            // bare URL
            if ((c == 'h' || c == 'H') && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                val m = bareUrlRe.find(s.substring(i))
                if (m != null) {
                    var url = m.value
                    while (url.isNotEmpty() && url.last() in ".,;:!?)'\"*_") url = url.dropLast(1)
                    if (url.length > 8) {
                        flush()
                        out += MdRun(url, style.bold, style.italic, style.strike, false, safeLink(url))
                        i += url.length
                        continue
                    }
                }
            }

            // strike
            if (c == '~' && s.startsWith("~~", i)) {
                val close = findCloser(s, i + 2, '~', 2)
                if (close > i + 2 && opensOk(s, i, 2)) {
                    flush()
                    inline(s.substring(i + 2, close), style.copy(strike = true), out)
                    i = close + 2
                    continue
                }
            }

            // emphasis
            if (c == '*' || c == '_') {
                var n = 0
                while (i + n < s.length && s[i + n] == c) n++
                val use = minOf(n, 3)
                if (opensOk(s, i, n, c)) {
                    val close = findCloser(s, i + n, c, use)
                    if (close > i + n) {
                        flush()
                        val st = when (use) {
                            1 -> style.copy(italic = true)
                            2 -> style.copy(bold = true)
                            else -> style.copy(bold = true, italic = true)
                        }
                        // extra delimiter chars beyond 3 stay literal
                        if (n > use) sb.append(c.toString().repeat(n - use))
                        flush()
                        inline(s.substring(i + n, close), st, out)
                        i = close + use
                        continue
                    }
                }
                sb.append(s, i, i + n); i += n; continue
            }

            sb.append(c)
            i++
        }
        flush()
    }

    /** Opening delimiter must be followed by non-space (and `_` must not be intra-word). */
    private fun opensOk(s: String, i: Int, n: Int, ch: Char = s[i]): Boolean {
        val after = i + n
        if (after >= s.length || s[after].isWhitespace()) return false
        if (ch == '_' && i > 0 && s[i - 1].isLetterOrDigit()) return false
        return true
    }

    private fun findBacktickClose(s: String, from: Int, n: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '`') {
                var r = 0
                while (i + r < s.length && s[i + r] == '`') r++
                if (r == n) return i
                i += r
            } else i++
        }
        return -1
    }

    /** Find the closing delimiter run for an emphasis opened with [n] chars of [ch]. */
    private fun findCloser(s: String, from: Int, ch: Char, n: Int): Int {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '`') {
                var k = 0
                while (i + k < s.length && s[i + k] == '`') k++
                val close = findBacktickClose(s, i + k, k)
                i = if (close >= 0) close + k else i + k
                continue
            }
            if (c == ch) {
                var r = 0
                while (i + r < s.length && s[i + r] == ch) r++
                val prevOk = i > from && !s[i - 1].isWhitespace()
                val nextOk = ch != '_' || i + r >= s.length || !s[i + r].isLetterOrDigit()
                if (r >= n && prevOk && nextOk) {
                    // for a longer run, the closing delimiter is its last n chars
                    return i + (r - n)
                }
                i += r
                continue
            }
            i++
        }
        return -1
    }

    private class LinkAt(val label: String, val url: String, val end: Int)

    private fun parseLinkAt(s: String, start: Int): LinkAt? {
        // start points at '['
        var depth = 0
        var i = start
        var labelEnd = -1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '`' -> {
                    var k = 0
                    while (i + k < s.length && s[i + k] == '`') k++
                    val close = findBacktickClose(s, i + k, k)
                    if (close >= 0) i = close + k - 1
                }
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { labelEnd = i; break } }
            }
            i++
        }
        if (labelEnd < 0 || labelEnd + 1 >= s.length || s[labelEnd + 1] != '(') return null
        var j = labelEnd + 2
        var parens = 1
        val urlStart = j
        while (j < s.length) {
            when (s[j]) {
                '(' -> parens++
                ')' -> { parens--; if (parens == 0) break }
                '\n' -> return null
            }
            j++
        }
        if (parens != 0) return null
        var raw = s.substring(urlStart, j).trim()
        // strip optional "title"
        raw.indexOf(" \"").takeIf { it > 0 }?.let { raw = raw.substring(0, it) }
        raw = raw.removePrefix("<").removeSuffix(">")
        return LinkAt(s.substring(start + 1, labelEnd), raw, j + 1)
    }

    private fun safeLink(url: String): String? {
        val u = url.trim()
        val lower = u.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) u else null
    }

    private fun decodeEntities(t: String): String =
        if (!t.contains('&')) t else t
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
}
