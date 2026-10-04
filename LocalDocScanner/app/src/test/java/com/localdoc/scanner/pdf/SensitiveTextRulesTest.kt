package com.localdoc.scanner.pdf
import org.junit.Assert.*
import org.junit.Test
class SensitiveTextRulesTest {
    @Test fun sensitiveCandidatesAndUserKeywordsAreIdentified() {
        assertTrue(SensitiveTextRules.kinds("电话 138 0013 8000").contains("手机号"))
        assertTrue(SensitiveTextRules.kinds("证件11010519491231002X").contains("身份证号候选"))
        assertTrue(SensitiveTextRules.kinds("卡号4532015112830366").contains("银行卡号候选"))
        assertTrue(SensitiveTextRules.kinds("居住地址", listOf("地址")).contains("自定义关键词"))
    }
    @Test fun datesShortNumbersAndInvalidCardChecksumAreNotBankCards() {
        assertTrue(SensitiveTextRules.kinds("2026-10-04 12345 0000000000000000").isEmpty())
        assertFalse(SensitiveTextRules.kinds("4532015112830367").contains("银行卡号候选"))
        assertFalse(SensitiveTextRules.kinds("9138001380008").contains("手机号"))
    }
}
