package com.notionwidget.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider

/** Background and text of a tag, with light and dark variants. */
class TagColors(val background: ColorProvider, val text: ColorProvider)

private fun tag(lightBg: Long, lightText: Long, darkBg: Long, darkText: Long = 0xFFE6E6E5) = TagColors(
    background = ColorProvider(day = Color(lightBg), night = Color(darkBg)),
    text = ColorProvider(day = Color(lightText), night = Color(darkText)),
)

/** Notion tag palette, keyed by the color name the API returns. */
private val NotionTagColors = mapOf(
    "default" to tag(0xFFE3E2E0, 0xFF32302C, 0xFF373737),
    "gray" to tag(0xFFE3E2E0, 0xFF32302C, 0xFF5A5A5A),
    "brown" to tag(0xFFEEE0DA, 0xFF442A1E, 0xFF603B2C),
    "orange" to tag(0xFFFADEC9, 0xFF49290E, 0xFF854C1D),
    "yellow" to tag(0xFFFDECC8, 0xFF402C1B, 0xFF89632A),
    "green" to tag(0xFFDBEDDB, 0xFF1C3829, 0xFF2B593F),
    "blue" to tag(0xFFD3E5EF, 0xFF183347, 0xFF28456C),
    "purple" to tag(0xFFE8DEEE, 0xFF412454, 0xFF492F64),
    "pink" to tag(0xFFF5E0E9, 0xFF4C2337, 0xFF69314C),
    "red" to tag(0xFFFFE2DD, 0xFF5D1715, 0xFF6E3630),
)

/** Colors for [notionColor]; null if it isn't a Notion color (shown as plain text). */
fun tagColors(notionColor: String?): TagColors? = notionColor?.let { NotionTagColors[it] }
