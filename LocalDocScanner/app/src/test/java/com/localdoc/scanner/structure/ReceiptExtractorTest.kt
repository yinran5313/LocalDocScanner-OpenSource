package com.localdoc.scanner.structure

import org.junit.Assert.*
import org.junit.Test

class ReceiptExtractorTest {
    @Test fun currencyUnitsAndTestBannerDoNotHideReceiptFields() {
        val result = ReceiptExtractor.fields("TEST_RECEIPT_1017\n购物小票\n商户：拾页文具店\n日期：2026-10-17\n合计金额（人民币）：40.00")
        assertEquals("40.00", result["金额"])
        assertEquals("拾页文具店", result["商户"])
    }
    @Test fun paidAmountWinsOverSubtotalCashAndChange() {
        assertEquals("19.50", ReceiptExtractor.fields("文具店\n合计：20.00\n实付金额 (人民币): ￥19.50\n现金：50.00\n找零金额：30.50")["金额"])
    }
    @Test fun totalsWithGroupingAndSeparateCurrencyHaveStablePriority() {
        assertEquals("1234.50", ReceiptExtractor.fields("超市\n总计金额：CNY 1,234.50\n商品金额：2.00")["金额"])
        assertEquals("15.50", ReceiptExtractor.fields("超市\n合计金额(元)：15.50")["金额"])
        assertEquals("", ReceiptExtractor.fields("超市\n订单号 20261017\n没有可辨认的合计")["金额"])
    }
    @Test fun unlabelledMerchantSkipsGenericBanner() {
        assertEquals("星河便利店", ReceiptExtractor.fields("TEST_S18\n购物小票\n星河便利店\n应付：20.00")["商户"])
    }
}
