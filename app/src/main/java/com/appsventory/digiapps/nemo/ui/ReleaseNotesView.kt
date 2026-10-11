package com.appsventory.digiapps.nemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.ui.theme.BgDark
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.DividerDark
import com.appsventory.digiapps.nemo.ui.theme.TextMuted
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary
import com.appsventory.digiapps.nemo.update.MdBlock
import com.appsventory.digiapps.nemo.update.MdListItem
import com.appsventory.digiapps.nemo.update.MdRun

/**
 * Renders [blocks] (from [com.appsventory.digiapps.nemo.update.ReleaseMarkdown]) as formatted UI.
 * This is plain Compose text — release-note HTML is never interpreted.
 * It does NOT scroll by itself; put it inside the scrolling container.
 */
@Composable
fun ReleaseNotesContent(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block -> BlockView(block, isFirst = index == 0) }
    }
}

@Composable
private fun BlockView(block: MdBlock, isFirst: Boolean) {
    when (block) {
        is MdBlock.Heading -> HeadingView(block, isFirst)
        is MdBlock.Paragraph ->
            Text(
                rememberAnnotated(block.runs),
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        is MdBlock.ListBlock ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { ListItemView(it) }
            }
        is MdBlock.CodeBlock ->
            Text(
                block.text,
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgDark)
                    .padding(10.dp)
            )
        is MdBlock.Quote ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(CyanAccent.copy(alpha = 0.6f))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.blocks.forEachIndexed { i, b -> BlockView(b, isFirst = i == 0) }
                }
            }
        MdBlock.Rule ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(DividerDark))
    }
}

@Composable
private fun HeadingView(h: MdBlock.Heading, isFirst: Boolean) {
    val size = when (h.level) {
        1 -> 20.sp
        2 -> 17.sp
        3 -> 15.sp
        else -> 14.sp
    }
    val weight = if (h.level <= 2) FontWeight.Bold else FontWeight.SemiBold
    Column(
        Modifier.padding(top = if (isFirst) 0.dp else if (h.level <= 2) 8.dp else 4.dp)
    ) {
        Text(
            rememberAnnotated(h.runs),
            color = if (h.level >= 4) TextSecondary else TextPrimary,
            fontSize = size,
            fontWeight = weight,
            lineHeight = size * 1.3f,
            modifier = Modifier.semantics { heading() }
        )
        if (h.level <= 2) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(DividerDark))
        }
    }
}

@Composable
private fun ListItemView(item: MdListItem) {
    val marker = when {
        item.checked != null -> if (item.checked) "☑" else "☐"
        item.ordinal != null -> "${item.ordinal}."
        item.depth == 0 -> "•"
        item.depth == 1 -> "◦"
        else -> "▪"
    }
    Row(Modifier.fillMaxWidth().padding(start = (item.depth * 18).dp)) {
        Text(
            marker,
            color = TextMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp)
        )
        Text(
            rememberAnnotated(item.runs),
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun rememberAnnotated(runs: List<MdRun>): AnnotatedString {
    val accent = CyanAccent
    val codeBg = BgDark
    return remember(runs, accent, codeBg) {
        buildAnnotatedString {
            for (r in runs) {
                val style = SpanStyle(
                    fontWeight = if (r.bold) FontWeight.Bold else null,
                    fontStyle = if (r.italic) FontStyle.Italic else null,
                    textDecoration = if (r.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (r.code) FontFamily.Monospace else null,
                    fontSize = if (r.code) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    background = if (r.code) codeBg else androidx.compose.ui.graphics.Color.Unspecified
                )
                val link = r.link
                if (link != null) {
                    // only http / https / mailto ever reach here (see ReleaseMarkdown)
                    pushLink(
                        LinkAnnotation.Url(
                            link,
                            TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
                        )
                    )
                    withStyle(style) { append(r.text) }
                    pop()
                } else {
                    withStyle(style) { append(r.text) }
                }
            }
        }
    }
}
