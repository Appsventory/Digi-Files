package com.appsventory.digiapps.nemo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMarkdownTest {

    private fun plain(runs: List<MdRun>) = runs.joinToString("") { it.text }

    private val sample = """
        ## What's new

        Faster **scanning** and a *smoother* grid. See `FileOps` for details.

        ## Fixes
        - Fixed crash on **SMB** reconnect
        - Fixed `##` in names
          - nested item
        1. first
        2. second

        ```kotlin
        ## not a heading
        val x = 1
        ```

        ### Notes
        Visit [the repo](https://github.com/Appsventory/Digi-Files) or https://example.com/a.
    """.trimIndent()

    @Test
    fun h2_isHeading_notRawMarkdown() {
        val blocks = ReleaseMarkdown.parse(sample)
        val h = blocks.first() as MdBlock.Heading
        assertEquals(2, h.level)
        assertEquals("What's new", plain(h.runs))
        // no block anywhere keeps the literal "## " prefix as heading text
        blocks.filterIsInstance<MdBlock.Heading>().forEach { assertTrue(!plain(it.runs).startsWith("#")) }
    }

    @Test
    fun multipleHeadingLevels() {
        val levels = ReleaseMarkdown.parse(sample).filterIsInstance<MdBlock.Heading>().map { it.level }
        assertEquals(listOf(2, 2, 3), levels)
        val h1 = ReleaseMarkdown.parse("# Title") .first() as MdBlock.Heading
        assertEquals(1, h1.level)
    }

    @Test
    fun hashInsideFencedCode_staysCode() {
        val code = ReleaseMarkdown.parse(sample).filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("## not a heading\nval x = 1", code.text)
    }

    @Test
    fun hashWithoutSpace_isNotHeading() {
        val b = ReleaseMarkdown.parse("#hashtag and #123").first()
        assertTrue(b is MdBlock.Paragraph)
    }

    @Test
    fun boldItalicInlineCode() {
        val p = ReleaseMarkdown.parse("a **b** *c* ***d*** `e` ~~f~~").first() as MdBlock.Paragraph
        val r = p.runs
        assertTrue(r.any { it.text == "b" && it.bold && !it.italic })
        assertTrue(r.any { it.text == "c" && it.italic && !it.bold })
        assertTrue(r.any { it.text == "d" && it.italic && it.bold })
        assertTrue(r.any { it.text == "e" && it.code })
        assertTrue(r.any { it.text == "f" && it.strike })
        assertEquals("a b c d e f", plain(r))
    }

    @Test
    fun snakeCaseAndStrayDelimiters_stayLiteral() {
        assertEquals("use snake_case_name here", plain((ReleaseMarkdown.parse("use snake_case_name here").first() as MdBlock.Paragraph).runs))
        assertEquals("2 * 3 * 4", plain((ReleaseMarkdown.parse("2 * 3 * 4").first() as MdBlock.Paragraph).runs))
        assertEquals("unclosed **bold", plain((ReleaseMarkdown.parse("unclosed **bold").first() as MdBlock.Paragraph).runs))
    }

    @Test
    fun nestedEmphasis() {
        val p = ReleaseMarkdown.parse("**bold *and italic* text**").first() as MdBlock.Paragraph
        assertTrue(p.runs.any { it.text == "and italic" && it.bold && it.italic })
        assertEquals("bold and italic text", plain(p.runs))
    }

    @Test
    fun lists_bulletsNumberedNested() {
        val list = ReleaseMarkdown.parse(sample).filterIsInstance<MdBlock.ListBlock>()
        assertEquals(1, list.size) // bullets + numbered are adjacent, one block
        val items = list.single().items
        assertEquals(5, items.size)
        assertNull(items[0].ordinal)
        assertEquals(0, items[0].depth)
        assertEquals(1, items[2].depth)
        assertEquals("nested item", plain(items[2].runs))
        assertEquals(1, items[3].ordinal)
        assertEquals(2, items[4].ordinal)
        assertTrue(items[0].runs.any { it.text == "SMB" && it.bold })
        assertTrue(items[1].runs.any { it.text == "##" && it.code })
    }

    @Test
    fun taskList() {
        val items = (ReleaseMarkdown.parse("- [x] done\n- [ ] todo").first() as MdBlock.ListBlock).items
        assertEquals(true, items[0].checked)
        assertEquals(false, items[1].checked)
        assertEquals("done", plain(items[0].runs))
    }

    @Test
    fun links_onlySafeSchemes() {
        val p = ReleaseMarkdown.parse(sample).filterIsInstance<MdBlock.Paragraph>().last()
        assertTrue(p.runs.any { it.text == "the repo" && it.link == "https://github.com/Appsventory/Digi-Files" })
        // trailing '.' is not part of the bare URL
        assertTrue(p.runs.any { it.text == "https://example.com/a" && it.link == "https://example.com/a" })
        val bad = ReleaseMarkdown.parse("[click](javascript:alert(1))").first() as MdBlock.Paragraph
        assertTrue(bad.runs.all { it.link == null })
        assertEquals("click", plain(bad.runs))
    }

    @Test
    fun html_isLiteralText_notInterpreted() {
        val p = ReleaseMarkdown.parse("<script>alert(1)</script> <b>x</b>").first() as MdBlock.Paragraph
        assertEquals("<script>alert(1)</script> <b>x</b>", plain(p.runs))
        assertTrue(p.runs.all { it.link == null && !it.bold })
    }

    @Test
    fun htmlComment_hidden_brIsLineBreak() {
        val b = ReleaseMarkdown.parse("<!-- hidden -->\nline<br>two")
        assertEquals(1, b.size)
        assertEquals("line\ntwo", plain((b.first() as MdBlock.Paragraph).runs))
    }

    @Test
    fun paragraphs_blankLinesSeparate_singleNewlineBreaks() {
        val b = ReleaseMarkdown.parse("one\ntwo\n\nthree")
        assertEquals(2, b.size)
        assertEquals("one\ntwo", plain((b[0] as MdBlock.Paragraph).runs))
        assertEquals("three", plain((b[1] as MdBlock.Paragraph).runs))
    }

    @Test
    fun crlf_isNormalised() {
        val b = ReleaseMarkdown.parse("## A\r\n\r\ntext\r\n- x\r\n")
        assertTrue(b[0] is MdBlock.Heading)
        assertTrue(b[1] is MdBlock.Paragraph)
        assertTrue(b[2] is MdBlock.ListBlock)
    }

    @Test
    fun quoteRuleAndTable() {
        val b = ReleaseMarkdown.parse("> quoted **text**\n\n---\n\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertTrue(b[0] is MdBlock.Quote)
        assertTrue(b[1] is MdBlock.Rule)
        val table = b[2] as MdBlock.CodeBlock
        assertEquals("table", table.language)
        assertTrue(table.text.contains("| 1 | 2 |"))
    }

    @Test
    fun setextHeading() {
        val h = ReleaseMarkdown.parse("Title\n=====").first() as MdBlock.Heading
        assertEquals(1, h.level)
        assertEquals("Title", plain(h.runs))
    }

    @Test
    fun unterminatedFence_runsToEnd_noCrash() {
        val b = ReleaseMarkdown.parse("```\n## x\nstill code")
        assertEquals("## x\nstill code", (b.single() as MdBlock.CodeBlock).text)
    }

    @Test
    fun emptyAndBlank() {
        assertTrue(ReleaseMarkdown.parse(null).isEmpty())
        assertTrue(ReleaseMarkdown.parse("  \n ").isEmpty())
    }
}
