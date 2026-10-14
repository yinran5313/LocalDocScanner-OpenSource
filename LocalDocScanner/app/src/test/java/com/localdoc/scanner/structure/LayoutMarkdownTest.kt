package com.localdoc.scanner.structure

import com.localdoc.scanner.ocr.OcrTextBox
import org.junit.Assert.*
import org.junit.Test

class LayoutMarkdownTest {
    @Test fun twoColumnsReadEachColumnBeforeMovingRightButKeepSpanningHeadings() {
        val title=OcrTextBox("标题",.1f,.01f,.9f,.1f)
        val left=(0..3).map { OcrTextBox("左$it",.1f,.25f+it*.1f,.45f,.3f+it*.1f) }
        val right=(0..3).map { OcrTextBox("右$it",.55f,.25f+it*.1f,.9f,.3f+it*.1f) }
        assertEquals(listOf(title)+left+right,LayoutMarkdown.ordered(listOf(title)+left+right))
        assertEquals(listOf("标题","左0","右0"),LayoutMarkdown.ordered(listOf(title)+left+right,"SINGLE").take(3).map { it.text })
    }
    @Test fun markdownTablesKeepMissingCellsAndEscapeSeparators() {
        val text=LayoutMarkdown.table(listOf(listOf("名称","金额"),listOf("A|B"),listOf("C","2")))
        assertTrue(text.contains("A\\|B |  |")); assertTrue(text.contains("| C | 2 |"))
    }
}
