package com.localdoc.scanner.office

import org.junit.Assert.*
import org.junit.Test

class BlankOfficeTemplatesTest {
    @Test fun newPresentationUsesChineseWithoutChangingRelationshipsOrUserText() {
        val xml="<a:t>Click to edit the title text format</a:t><a:t>Second Outline Level</a:t><a:t>Footer</a:t><a:t>My Footer note</a:t><r:link id=\"Footer\"/>"
        val localized=BlankOfficeTemplates.chinesePlaceholders(xml)
        assertTrue(localized.contains("<a:t>点击添加标题</a:t>"))
        assertTrue(localized.contains("<a:t>第二级正文</a:t>"))
        assertTrue(localized.contains("<a:t>页脚</a:t>"))
        assertTrue(localized.contains("<a:t>My Footer note</a:t>"))
        assertTrue(localized.contains("<r:link id=\"Footer\"/>"))
    }
}
