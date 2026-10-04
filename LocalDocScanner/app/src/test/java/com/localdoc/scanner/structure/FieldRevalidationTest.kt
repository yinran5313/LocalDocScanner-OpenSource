package com.localdoc.scanner.structure
import org.junit.Assert.*
import org.junit.Test
class FieldRevalidationTest {
    @Test fun changingIdInvalidatesOldCheck() {
        val initial = StructureExtractor.extract(StructureExtractor.Kind.ID_CARD, "11010519491231002X")
        val changed = StructureExtractor.revalidate(initial, mapOf("idNumber" to "110105194912310020"))
        assertEquals(false, changed.fields.first { it.key == "idNumber" }.valid)
        assertTrue(StructureExtractor.toJson(changed).contains("\"valid\": false"))
    }
    @Test fun bankDependentsAndInvoiceCheckUseNewValues() {
        val bank = StructureExtractor.extract(StructureExtractor.Kind.BANK_CARD, "6222021234567890123")
        val changed = StructureExtractor.revalidate(bank, mapOf("cardNumber" to "4111111111111111"))
        assertEquals("Visa", changed.fields.first { it.key == "issuer" }.value)
        assertEquals(true, changed.fields.first { it.key == "cardNumber" }.valid)
        val invoice = StructureExtractor.extract(StructureExtractor.Kind.INVOICE, "金额100 税率13% 税额13")
        assertEquals(false, StructureExtractor.revalidate(invoice, mapOf("tax" to "12")).fields.first { it.key == "crossCheck" }.valid)
    }
}
