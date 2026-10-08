package com.hermes.android.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A compact Markdown renderer for chat bubbles.
 *
 * Ported from the `hermes-lan-chat` web client's `markdown.js`: fenced code
 * blocks with a language tag and copy button, tables, headings, lists, quotes
 * and horizontal rules, plus inline code / bold / italic / links.
 *
 * Everything renders through `Text`, so untrusted model output is never
 * interpreted as markup — there is no HTML passthrough to escape.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    // Model-provided links must actually open: the label was styled like a
    // link while the URL was discarded, so reference material looked clickable
    // and was not.
    val context = LocalContext.current
    val openLink: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                )
            )
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.Paragraph -> Text(
                    inlineStyled(block.text, color, openLink),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )

                is MdBlock.Heading -> Text(
                    inlineStyled(block.text, color, openLink),
                    fontSize = when (block.level) {
                        1 -> 21.sp
                        2 -> 19.sp
                        else -> 17.sp
                    },
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier.padding(top = 4.dp),
                )

                is MdBlock.Bullet -> Row(Modifier.padding(start = 4.dp)) {
                    Text("•", color = color, modifier = Modifier.width(14.dp))
                    Text(
                        inlineStyled(block.text, color, openLink),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color,
                    )
                }

                is MdBlock.Ordered -> Row(Modifier.padding(start = 4.dp)) {
                    Text(
                        "${block.index}.",
                        color = color,
                        modifier = Modifier.width(22.dp),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        inlineStyled(block.text, color, openLink),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color,
                    )
                }

                is MdBlock.Quote -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Box(
                        Modifier.width(3.dp).heightIn(min = 18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inlineStyled(block.text, color.copy(alpha = 0.85f), openLink),
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                    )
                }

                is MdBlock.Rule -> HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                is MdBlock.Table -> TableBlock(block, color, openLink)
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.language,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.clickable {
                    clipboard.setText(AnnotatedString(block.code))
                    copied = true
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制代码",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    if (copied) "已复制" else "复制",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        // Horizontal scroll keeps long lines intact instead of wrapping mid-token.
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(
                block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, color: Color, openLink: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            block.headers.forEach { h ->
                Text(
                    inlineStyled(h, color, openLink),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f).padding(7.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        block.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        inlineStyled(cell, color, openLink),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f).padding(7.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }
}

// Compiled once: these run per line on every streamed delta, and building a
// Regex per call showed up while a long reply was streaming.
private val INLINE = Regex(
    "(`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|\\*[^*\\n]+\\*|\\[[^\\]\\n]+\\]\\(https?://[^\\s)]+\\))"
)
private val LINK = Regex("^\\[([^\\]]+)]\\((.*)\\)$")

/** Label and URL of one `[label](url)` token, or null when it is not a link. */
internal fun linkLabelUrl(token: String): Pair<String, String>? =
    LINK.find(token)?.let { m ->
        val url = m.groupValues[2]
        if (url.isEmpty()) null else m.groupValues[1] to url
    }

/** Applies inline code / bold / italic / link styling to one line of text. */
@Composable
private fun inlineStyled(raw: String, color: Color, openLink: (String) -> Unit): AnnotatedString {
    if (!raw.contains('`') && !raw.contains('*') && !raw.contains('[')) {
        return AnnotatedString(raw)
    }
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
    val pattern = INLINE
    var cursor = 0
    for (m in pattern.findAll(raw)) {
        append(raw.substring(cursor, m.range.first))
        val v = m.value
        when {
            v.startsWith("`") -> withSpan(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(v.substring(1, v.length - 1))
            }

            v.startsWith("**") -> withSpan(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(v.substring(2, v.length - 2))
            }

            v.startsWith("*") -> withSpan(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(v.substring(1, v.length - 1))
            }

            else -> {
                val (label, url) = linkLabelUrl(v) ?: (v to "")
                if (url.isNotEmpty()) {
                    withLink(LinkAnnotation.Clickable(url) { openLink(url) }) {
                        withSpan(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            )
                        ) { append(label) }
                    }
                } else {
                    withSpan(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )
                    ) { append(label) }
                }
            }
        }
        cursor = m.range.last + 1
    }
    append(raw.substring(cursor))
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withSpan(
    style: SpanStyle,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    val start = length
    block()
    addStyle(style, start, length)
}

// ------------------------------------------------------------------ parsing --

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val index: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
}

private val FENCE = Regex("^\\s*(`{3,}|~{3,})(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.+)$")
private val ORDERED = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
private val ROW = Regex("^\\s*\\|(.+)\\|\\s*$")
private val TABLE_RULE = Regex("^\\s*\\|[\\s:|-]+\\|\\s*$")
private val RULE = Regex("^[-*_]{3,}$")
private val STARTS_BLOCK = Regex("^\\s*(?:`{3}|~{3}|#{1,6}\\s|[-*+]\\s|\\d+[.)]\\s|>\\s|\\|)")

/**
 * Block-level parse mirroring markdown.js: fences first, then tables, then the rest.
 *
 * A single cursor advances through the line list — every branch consumes what it
 * matched and the loop continues from there. (An earlier version drove the loop
 * with `for (i0 in lines.indices)` while advancing a separate `i` inside, so each
 * outer iteration re-consumed the tail: N lines produced O(N^2) output.)
 */
private fun parseMarkdown(input: String): List<MdBlock> {
    val lines = input.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    val n = lines.size
    var i = 0

    while (i < n) {
        val line = lines[i]
        val fence = FENCE.find(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2].trim().substringBefore(' ').ifEmpty { "code" }
            val body = mutableListOf<String>()
            i++
            val close = Regex("^\\s*" + Regex.escape(marker[0].toString()) + "{" + marker.length + ",}\\s*$")
            while (i < n && !close.matches(lines[i])) body.add(lines[i++])
            if (i < n) i++
            out.add(MdBlock.Code(language, body.joinToString("\n")))
            continue
        }

        if (line.isBlank()) { i++; continue }

        val tableHead = ROW.find(line)
        val tableRule = TABLE_RULE.matches(lines.getOrNull(i + 1).orEmpty())
        if (tableHead != null && tableRule) {
            val headers = tableHead.groupValues[1].split('|').map { it.trim() }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < n) {
                val r = ROW.find(lines[i]) ?: break
                rows.add(r.groupValues[1].split('|').map { it.trim() })
                i++
            }
            out.add(MdBlock.Table(headers, rows))
            continue
        }

        val heading = HEADING.find(line)
        if (heading != null) {
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            i++
            continue
        }

        val bullet = BULLET.find(line)
        if (bullet != null) {
            out.add(MdBlock.Bullet(bullet.groupValues[1]))
            i++
            continue
        }

        val ordered = ORDERED.find(line)
        if (ordered != null) {
            out.add(
                MdBlock.Ordered(
                    ordered.groupValues[1].toIntOrNull() ?: 1,
                    ordered.groupValues[2]
                )
            )
            i++
            continue
        }

        if (line.startsWith("> ")) {
            out.add(MdBlock.Quote(line.removePrefix("> ")))
            i++
            continue
        }

        if (RULE.matches(line)) {
            out.add(MdBlock.Rule)
            i++
            continue
        }

        // Paragraph: consume until a blank line or the start of another block.
        val para = mutableListOf<String>()
        do {
            para.add(lines[i++])
        } while (
            i < n &&
                lines[i].isNotBlank() &&
                !STARTS_BLOCK.containsMatchIn(lines[i])
        )
        out.add(MdBlock.Paragraph(para.joinToString("\n")))
    }
    return out
}