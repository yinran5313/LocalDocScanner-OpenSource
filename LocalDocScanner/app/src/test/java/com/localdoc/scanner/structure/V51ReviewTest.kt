package com.localdoc.scanner.structure

import com.localdoc.scanner.cv.PortraitSizes
import org.junit.Assert.*
import org.junit.Test

class V51ReviewTest {
    @Test fun numbersWithIdentitySemanticsOrLeadingZeroesStayText() {
        val rows = listOf(listOf("学号","金额","税率","日期","编码"),listOf("20260101","12.50","13%","2026-10-13","0012"),listOf("20260102","-2.30","6%","2026-10-14","0045"))
        assertEquals(mapOf(0 to "TEXT",1 to "NUMBER",2 to "PERCENT",3 to "DATE",4 to "TEXT"),ColumnInference.suggest(rows))
    }
    @Test fun invoiceChecksRejectInconsistentTotalsAndItemProducts() {
        val rows = listOf(listOf("名称","数量","单价","金额"),listOf("纸","2","10","21"))
        val issues = InvoiceReview.issues(mapOf("金额(不含税)" to "100","税额" to "13","价税合计" to "114"),rows)
        assertTrue(issues.any { it.contains("价税合计") }); assertTrue(issues.any { it.contains("商品第1行") })
        assertTrue(InvoiceReview.issues(mapOf("金额(不含税)" to "100","税额" to "13","价税合计" to "113"),emptyList()).isEmpty())
    }
    @Test fun manualCorrectionCannotLeaveAnOldPassingCheckInExports() {
        val fields=mapOf("金额(不含税)" to "100","税额" to "12","价税合计" to "112","税率(%)" to "13","勾稽校验" to "通过")
        assertTrue(InvoiceReview.revalidated(fields,emptyList())["勾稽校验"]!!.contains("税率"))
    }
    @Test fun totalMustNeverBecomeThePretaxAmount() {
        val fields = InvoiceReview.fields("电子发票\n发票号码：12345678901234567890\n小写：113.00\n税额：13.00")
        assertEquals("",fields["金额(不含税)"]); assertEquals("113.00",fields["价税合计"])
    }
    @Test fun itemTableExcludesInvoiceHeaderAndTotalsWithoutDroppingRawText() {
        val rows = listOf(listOf("发票号码","123"),listOf("名称","数量","金额"),listOf("纸","2","20"),listOf("合计","","20"))
        assertEquals(rows.subList(1,3),InvoiceReview.itemRows(rows))
    }
    @Test fun studentFieldsStopAtFollowingLabels() {
        val result = StructureExtractor.extract(StructureExtractor.Kind.STUDENT_CARD,"姓名 张三 学校 测试大学\n学号 001234\n专业 数学")
        assertEquals("张三",result.fields.first { it.key == "name" }.value)
        assertEquals("001234",result.fields.first { it.key == "studentId" }.value)
    }
    @Test fun portraitCropStaysInsideSourceAtEveryOffset() {
        for ((w,h) in listOf(1000 to 500,500 to 1000,32 to 32)) for (s in PortraitSizes.sizes) for (x in listOf(0f,.5f,1f)) for (y in listOf(0f,.5f,1f)) {
            val crop = PortraitSizes.crop(w,h,s,x,y)
            assertTrue(crop.left>=0 && crop.top>=0 && crop.left+crop.width<=w && crop.top+crop.height<=h)
        }
    }
}
