package com.localdoc.scanner

import com.localdoc.scanner.structure.StructureExtractor
import com.localdoc.scanner.structure.StructureExtractor.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结构化抽取的纯逻辑单测：不依赖 Android，直接跑在 JVM 上 */
class StructureExtractorTest {

    @Test
    fun idCard_checksum() {
        assertTrue(StructureExtractor.isIdCardValid("11010519491231002X"))
        // 最后一位改成错的
        assertFalse(StructureExtractor.isIdCardValid("110105194912310021"))
        assertFalse(StructureExtractor.isIdCardValid("11010519491231002"))
    }

    @Test
    fun idCard_extract() {
        val text = """
            姓名 张三
            性别 男 民族 汉
            出生 1949年12月31日
            住址 北京市朝阳区某某路1号
            公民身份号码 11010519491231002X
        """.trimIndent()
        val r = StructureExtractor.extract(Kind.ID_CARD, text)
        assertEquals("张三", r.fields.first { it.key == "name" }.value)
        assertEquals("1949-12-31", r.fields.first { it.key == "birth" }.value)
        assertEquals("11010519491231002X", r.fields.first { it.key == "idNumber" }.value)
        assertEquals(true, r.fields.first { it.key == "idNumber" }.valid)
    }

    @Test
    fun bankCard_luhn() {
        assertTrue(StructureExtractor.isLuhnValid("4111111111111111"))
        assertTrue(StructureExtractor.isLuhnValid("6225880134567891"))
        assertFalse(StructureExtractor.isLuhnValid("1234567812345678"))
    }

    @Test
    fun bankCard_extract() {
        val r = StructureExtractor.extract(Kind.BANK_CARD, "卡号 4111 1111 1111 1111")
        val number = r.fields.first { it.key == "cardNumber" }
        assertEquals("4111 1111 1111 1111", number.value)
        assertEquals(true, number.valid)
        assertEquals("Visa", r.fields.first { it.key == "issuer" }.value)
    }

    @Test
    fun uscc_gb32100() {
        assertTrue(StructureExtractor.isUsccValid("91330100799655058B"))
        assertFalse(StructureExtractor.isUsccValid("91330100799655058C"))
    }

    @Test
    fun invoice_crossCheck() {
        val ok = StructureExtractor.extract(
            Kind.INVOICE,
            "发票代码 011002100311 发票号码 12345678 开票日期 2026年09月19日 金额 100.00 税率 13% 税额 13.00"
        )
        assertEquals(true, ok.fields.first { it.key == "crossCheck" }.valid)

        val bad = StructureExtractor.extract(
            Kind.INVOICE,
            "发票代码 011002100311 发票号码 12345678 金额 100.00 税率 13% 税额 99.00"
        )
        assertEquals(false, bad.fields.first { it.key == "crossCheck" }.valid)
    }

    @Test
    fun passport_mrz() {
        assertEquals('7', StructureExtractor.mrzCheckDigit("123456789"))

        val raw = """
            P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
            L898902C36UTO7408122F1204159ZE184226B<<<<<10
        """.trimIndent()
        val r = StructureExtractor.extract(Kind.PASSPORT, raw)
        assertEquals("ERIKSSON", r.fields.first { it.key == "surname" }.value)
        assertEquals("ANNA MARIA", r.fields.first { it.key == "given" }.value)
        assertEquals("L898902C3", r.fields.first { it.key == "passportNo" }.value)
        assertEquals(true, r.fields.first { it.key == "passportNo" }.valid)
        assertEquals("1974-08-12", r.fields.first { it.key == "birth" }.value)
        assertEquals("女", r.fields.first { it.key == "sex" }.value)
        assertEquals("2012-04-15", r.fields.first { it.key == "expiry" }.value)
    }

    @Test
    fun vehicleLicense_plate() {
        val r = StructureExtractor.extract(
            Kind.VEHICLE_LICENSE,
            "号牌号码 京A12345 所有人 李四 车辆识别代号 LSVAU0332A2123456 品牌型号 某某牌 注册日期 2020-05-06"
        )
        assertEquals("京A12345", r.fields.first { it.key == "plate" }.value)
        assertEquals("LSVAU0332A2123456", r.fields.first { it.key == "vin" }.value)
    }

    @Test
    fun json_and_csv_export() {
        val r = StructureExtractor.extract(Kind.BANK_CARD, "4111 1111 1111 1111")
        val json = StructureExtractor.toJson(r)
        assertTrue(json.contains("\"kind\": \"银行卡\""))
        assertTrue(json.contains("\"issuer\""))
        val csv = StructureExtractor.toCsv(r)
        assertTrue(csv.startsWith("字段,值,校验"))
    }
}
