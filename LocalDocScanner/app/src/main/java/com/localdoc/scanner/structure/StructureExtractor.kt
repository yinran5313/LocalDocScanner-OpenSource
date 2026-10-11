package com.localdoc.scanner.structure

/**
 * 证件 / 票证的本地结构化抽取。
 *
 * 定位说明（重要）：这里做的是**字段抽取 + 校验位自验**，不是"验真"。
 * 发票真伪核验、公安实名核验必须联网，本应用零联网，所以不做也不打算做。
 *
 * 输入是 OCR 出的纯文本（PP-OCR 接入后自动填充；引擎未内置时允许手工粘贴）。
 * 全部计算在本机完成，无任何网络请求。
 */
object StructureExtractor {

    enum class Kind(val label: String) {
        ID_CARD("身份证"),
        BANK_CARD("银行卡"),
        INVOICE("增值税发票"),
        BUSINESS_LICENSE("营业执照"),
        PASSPORT("护照"),
        DRIVER_LICENSE("驾驶证"),
        VEHICLE_LICENSE("行驶证")
    }

    /** 一个抽取出来的字段。valid 为 null 表示这类字段没有校验位可言 */
    data class Field(
        val key: String,
        val label: String,
        val value: String,
        val valid: Boolean? = null
    )

    data class Result(
        val kind: Kind,
        val fields: List<Field>,
        val notes: List<String> = emptyList()
    )

    fun extract(kind: Kind, raw: String): Result = when (kind) {
        Kind.ID_CARD -> idCard(raw)
        Kind.BANK_CARD -> bankCard(raw)
        Kind.INVOICE -> invoice(raw)
        Kind.BUSINESS_LICENSE -> businessLicense(raw)
        Kind.PASSPORT -> passport(raw)
        Kind.DRIVER_LICENSE -> driverLicense(raw)
        Kind.VEHICLE_LICENSE -> vehicleLicense(raw)
    }

    /** Re-evaluate the displayed values, including dependent values, before every export. */
    fun revalidate(original: Result, edited: Map<String, String>): Result {
        val values = original.fields.associate { it.key to (edited[it.key] ?: it.value).trim() }.toMutableMap()
        val checks = mutableMapOf<String, Boolean?>()
        val notes = mutableListOf<String>()
        fun check(key: String, predicate: (String) -> Boolean) {
            checks[key] = values[key]?.takeIf(String::isNotBlank)?.let(predicate)
        }
        when (original.kind) {
            Kind.ID_CARD, Kind.DRIVER_LICENSE -> {
                check("idNumber", ::isIdCardValid)
                val id = values["idNumber"].orEmpty().uppercase()
                if (checks["idNumber"] == true) {
                    val birth = "${id.substring(6, 10)}-${id.substring(10, 12)}-${id.substring(12, 14)}"
                    val gender = if (id[16].digitToInt() % 2 == 0) "女" else "男"
                    if (values["birth"].isNullOrBlank()) values["birth"] = birth
                    else if (values["birth"] != birth) notes += "出生日期与号码不一致，请核对"
                    val key = if (original.kind == Kind.ID_CARD) "gender" else "sex"
                    if (values[key].isNullOrBlank()) values[key] = gender
                    else if (values[key] != gender) notes += "性别与号码不一致，请核对"
                }
            }
            Kind.BANK_CARD -> {
                check("cardNumber", ::isLuhnValid)
                val card = values["cardNumber"].orEmpty().filter(Char::isDigit)
                values["issuer"] = if (card.isBlank()) "" else bankName(card)
                values["length"] = if (card.isBlank()) "" else card.length.toString()
            }
            Kind.BUSINESS_LICENSE -> check("uscc", ::isUsccValid)
            Kind.INVOICE -> {
                check("code") { it.matches(Regex("[0-9]{10,12}")) }
                check("number") { it.matches(Regex("[0-9]{8,20}")) }
                val amount = values["amount"]?.toBigDecimalOrNull()
                val rate = values["rate"]?.toBigDecimalOrNull()
                val tax = values["tax"]?.toBigDecimalOrNull()
                val ok = if (amount != null && rate != null && tax != null) {
                    (amount * rate / java.math.BigDecimal(100) - tax).abs() <= java.math.BigDecimal("0.01")
                } else null
                checks["crossCheck"] = ok
                values["crossCheck"] = when (ok) { true -> "金额×税率 ≈ 税额，通过"; false -> "金额×税率 与 税额 对不上"; null -> "信息不足，未校验" }
            }
            Kind.VEHICLE_LICENSE -> {
                check("plate") { it.matches(Regex("[$PLATE_PROVINCES][A-Z][A-Z0-9]{4,6}")) }
                check("vin") { it.uppercase().matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
            }
            Kind.PASSPORT -> original.fields.filter { it.valid != null }.forEach { field ->
                checks[field.key] = if (values[field.key] == field.value) field.valid else null
                if (values[field.key] != field.value) notes += "${field.label}已修改，需重新核对机读区校验位"
            }
        }
        val fields = original.fields.map { it.copy(value = values[it.key].orEmpty(), valid = checks[it.key]) }
        fields.filter { it.valid == false }.forEach { notes += "${it.label}校验不通过，请核对" }
        return original.copy(fields = fields, notes = notes)
    }

    // ------------------------------------------------------------------
    // 身份证：18 位，MOD 11-2 校验位
    // ------------------------------------------------------------------

    private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private val ID_CHECK = "10X98765432"

    fun isIdCardValid(id: String): Boolean {
        val s = id.trim().uppercase()
        if (!s.matches(Regex("\\d{17}[0-9X]"))) return false
        var sum = 0
        for (i in 0 until 17) {
            val d = s[i].digitToIntOrNull() ?: return false
            sum += d * ID_WEIGHTS[i]
        }
        return ID_CHECK[sum % 11] == s[17]
    }

    private fun idCard(raw: String): Result {
        val text = raw.replace('　', ' ')
        val flat = text.replace(Regex("\\s+"), " ")
        val fields = mutableListOf<Field>()
        val id = Regex("\\d{17}[0-9xX]").find(flat)?.value?.uppercase()

        fields += Field("name", "姓名", after(flat, "姓名", setOf("性别", "性\\s*别", "民族", "出生", "住址", "公民身份号码")))
        val gender = Regex("性别\\s*([男女])").find(flat)?.groupValues?.get(1)
            ?: Regex("([男女])").find(text.substringBefore("出生", text))?.groupValues?.get(1)
            ?: ""
        fields += Field("gender", "性别", gender)
        fields += Field(
            "nation", "民族",
            Regex("民族\\s*([一-龥]{1,6}?)\\s*(?:出生|住址|\\d)").find(flat)?.groupValues?.get(1) ?: ""
        )
        fields += Field(
            "birth", "出生日期",
            Regex("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日").find(flat)
                ?.let { "${it.groupValues[1]}-${pad(it.groupValues[2])}-${pad(it.groupValues[3])}" } ?: ""
        )
        fields += Field(
            "address", "住址",
            Regex("住址\\s*(.+?)\\s*(?:公民身份号码|\\d{17})").find(flat)?.groupValues?.get(1)
                ?.trim() ?: ""
        )
        fields += Field(
            "idNumber", "公民身份号码", id ?: "",
            if (id == null) null else isIdCardValid(id)
        )

        val notes = mutableListOf<String>()
        if (id == null) notes += "没有找到 18 位号码，请检查图片是否完整"
        else if (!isIdCardValid(id)) notes += "校验位不通过，号码可能识别有误"

        return Result(Kind.ID_CARD, fields, notes)
    }

    // ------------------------------------------------------------------
    // 银行卡：Luhn 校验 + 本地 BIN 表
    // ------------------------------------------------------------------

    fun isLuhnValid(card: String): Boolean {
        val s = card.filter { it.isDigit() }
        if (s.length < 12 || s.length > 19) return false
        var sum = 0
        var alternate = false
        for (i in s.length - 1 downTo 0) {
            var n = s[i].digitToIntOrNull() ?: return false
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /** 本地 BIN 前缀表（离线、尽力而为；识别不出只显示"未知"） */
    private val BIN_TABLE = listOf(
        "622202" to "中国工商银行", "622848" to "中国农业银行", "622588" to "招商银行",
        "622700" to "中国建设银行", "622262" to "交通银行", "621700" to "中国建设银行",
        "622150" to "中国邮政储蓄银行", "622181" to "中国邮政储蓄银行", "621098" to "中国邮政储蓄银行",
        "622576" to "广发银行", "622568" to "广发银行", "622690" to "中信银行",
        "622908" to "兴业银行", "622909" to "兴业银行", "622521" to "华夏银行",
        "622178" to "华夏银行", "622155" to "上海浦东发展银行", "622516" to "上海浦东发展银行",
        "621756" to "中国银行", "621758" to "中国银行", "621661" to "中国银行",
        "621483" to "招商银行", "621485" to "招商银行", "621559" to "中国工商银行",
        "621226" to "中国工商银行", "621288" to "中国工商银行", "621790" to "中国银行",
        "621792" to "中国银行", "623058" to "平安银行", "623059" to "平安银行",
        "622126" to "中国光大银行", "622660" to "中国光大银行", "622836" to "中国民生银行",
        "622622" to "中国民生银行", "621691" to "中国民生银行", "622929" to "恒丰银行"
    )

    fun bankName(card: String): String {
        val s = card.filter { it.isDigit() }
        BIN_TABLE.forEach { (prefix, name) -> if (s.startsWith(prefix)) return name }
        val head2 = s.take(2).toIntOrNull()
        return when {
            s.startsWith("4") -> "Visa"
            head2 != null && head2 in 51..55 -> "Mastercard"
            s.startsWith("34") || s.startsWith("37") -> "American Express"
            s.startsWith("62") -> "银联"
            s.startsWith("6011") || s.startsWith("65") -> "Discover"
            s.startsWith("35") -> "JCB"
            else -> "未知"
        }
    }

    private fun bankCard(raw: String): Result {
        val digits = raw.replace(Regex("[^0-9]"), " ")
        val candidates = Regex("\\d{12,19}").findAll(raw.replace(Regex("[\\s-]"), "")).toList()
        // 优先取"四位一组"的连排卡号
        val grouped = Regex("(?:\\d{4}\\s*){3,4}\\d{1,4}").findAll(raw).map { it.value }.toList()
        val card = (grouped.firstOrNull() ?: candidates.firstOrNull()?.value ?: "")
            .replace(Regex("\\s"), "")
        val fields = listOf(
            Field("cardNumber", "卡号", spaced(card), if (card.isBlank()) null else isLuhnValid(card)),
            Field("issuer", "发卡行", if (card.isBlank()) "" else bankName(card)),
            Field("length", "位数", if (card.isBlank()) "" else "${card.length}")
        )
        val notes = mutableListOf<String>()
        if (card.isBlank()) notes += "没找到卡号"
        else if (!isLuhnValid(card)) notes += "Luhn 校验不通过，可能有数字识别错误"
        return Result(Kind.BANK_CARD, fields, notes)
    }

    private fun spaced(card: String): String =
        card.chunked(4).joinToString(" ")

    // ------------------------------------------------------------------
    // 增值税发票：号码 + 金额勾稽
    // ------------------------------------------------------------------

    private fun invoice(raw: String): Result {
        val flat = raw.replace('　', ' ').replace(Regex("[ \\t]+"), " ")
        fun pat(p: String, d: String = "") = Regex(p).find(flat)?.groupValues?.getOrNull(1) ?: d

        val code = pat("发票代码\\s*[:：]?\\s*(\\d{10,12})")
        val number = pat("发票号码\\s*[:：]?\\s*(\\d{8,20})")
        val date = Regex("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日").find(flat)
            ?.let { "${it.groupValues[1]}-${pad(it.groupValues[2])}-${pad(it.groupValues[3])}" } ?: ""
        val amount = Regex("(?:合计金额|金额|小写|价税合计)[^0-9\\-]{0,6}(-?\\d+(?:\\.\\d{1,2})?)")
            .find(flat)?.groupValues?.getOrNull(1)
        val tax = Regex("(?:税额)[^0-9\\-]{0,6}(-?\\d+(?:\\.\\d{1,2})?)")
            .find(flat)?.groupValues?.getOrNull(1)
        val rate = Regex("(?:税率)[^0-9]{0,4}(\\d{1,2}(?:\\.\\d{1,2})?)\\s*%")
            .find(flat)?.groupValues?.getOrNull(1)
        val checkCode = pat("校验码\\s*[:：]?\\s*([0-9A-Za-z]{6,})")

        val amtD = amount?.toDoubleOrNull()
        val taxD = tax?.toDoubleOrNull()
        val rateD = rate?.toDoubleOrNull()
        val consistent = if (amtD != null && taxD != null && rateD != null) {
            val expected = amtD * rateD / 100.0
            kotlin.math.abs(expected - taxD) <= 0.02 * kotlin.math.max(1.0, kotlin.math.abs(taxD)) + 0.01
        } else null

        val fields = listOf(
            Field("code", "发票代码", code, if (code.isBlank()) null else code.length in 10..12),
            Field("number", "发票号码", number, if (number.isBlank()) null else number.length in 8..20),
            Field("date", "开票日期", date),
            Field("amount", "金额(不含税)", amount ?: ""),
            Field("rate", "税率(%)", rate ?: ""),
            Field("tax", "税额", tax ?: ""),
            Field("checkCode", "校验码", checkCode),
            Field("crossCheck", "勾稽校验", when (consistent) {
                true -> "金额×税率 ≈ 税额，通过"
                false -> "金额×税率 与 税额 对不上"
                null -> "信息不足，未校验"
            }, consistent)
        )
        return Result(Kind.INVOICE, fields)
    }

    // ------------------------------------------------------------------
    // 营业执照：统一社会信用代码 GB 32100 校验码
    // ------------------------------------------------------------------

    private val USCC_BASE = "0123456789ABCDEFGHJKLMNPQRTUWXY"
    private val USCC_WEIGHTS = intArrayOf(1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28)

    fun isUsccValid(code: String): Boolean {
        val s = code.trim().uppercase()
        if (s.length != 18) return false
        var sum = 0
        for (i in 0 until 17) {
            val v = USCC_BASE.indexOf(s[i])
            if (v < 0) return false
            sum += v * USCC_WEIGHTS[i]
        }
        val expected = (31 - sum % 31).let { if (it == 31) 0 else it }
        return USCC_BASE[expected] == s[17]
    }

    private fun businessLicense(raw: String): Result {
        val flat = raw.replace('　', ' ').replace(Regex("[ \\t]+"), " ")
        val code = Regex("\\b([0-9A-Z]{18})\\b").find(flat.uppercase())?.groupValues?.get(1)
        val fields = listOf(
            Field("name", "名称", after(flat, "名\\s*称", setOf("类型", "法定代表", "经营范围", "注册", "统一社会信用"))),
            Field("uscc", "统一社会信用代码", code ?: "", if (code == null) null else isUsccValid(code)),
            Field("legal", "法定代表人", after(flat, "法定代表人", setOf("经营范围", "注册", "统一社会信用", "住所"))),
            Field("capital", "注册资本", Regex("注册资本\\s*[:：]?\\s*([^\\s]{1,20})").find(flat)?.groupValues?.getOrNull(1) ?: ""),
            Field("established", "成立日期", Regex("(?:成立日期|注册日期)\\s*[:：]?\\s*(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2})").find(flat)?.groupValues?.getOrNull(1) ?: "")
        )
        val notes = mutableListOf<String>()
        if (code == null) notes += "没找到 18 位统一社会信用代码"
        else if (!isUsccValid(code)) notes += "GB 32100 校验码不通过"
        return Result(Kind.BUSINESS_LICENSE, fields, notes)
    }

    // ------------------------------------------------------------------
    // 护照：MRZ 机读区（TD3，两行 44 字符），自带校验位
    // ------------------------------------------------------------------

    fun mrzCheckDigit(data: String): Char {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        data.forEachIndexed { i, c ->
            val v = when {
                c in '0'..'9' -> c - '0'
                c in 'A'..'Z' -> c - 'A' + 10
                c == '<' -> 0
                else -> return '?'
            }
            sum += v * weights[i % 3]
        }
        return (sum % 10).digitToChar()
    }

    private fun passport(raw: String): Result {
        val lines = raw.lines()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
        val head = lines.indexOfFirst { it.startsWith("P<") || it.startsWith("P ") }
        val line1 = if (head >= 0) lines[head] else null
        // OCR 常把第二行尾部截掉，补 '<' 到 44 位再解析，靠校验位兜住错误
        val line2 = if (head >= 0) lines.getOrNull(head + 1)?.padEnd(44, '<') else null

        if (line1 == null || line2 == null || line2.length < 28) {
            return Result(
                Kind.PASSPORT,
                emptyList(),
                listOf("MRZ 机读区不完整（应为护照底部两行 44 字符），请对准底部重拍")
            )
        }

        val namePart = line1.substringAfter("P<")
        val country = namePart.take(3)
        val nameRaw = namePart.drop(3)
        val (surname, given) = nameRaw.split("<<").let {
            (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "")
        }
        val passportNo = line2.take(9).replace("<", "")
        val noCheck = line2.getOrNull(9)
        val nationality = line2.substring(10, 13)
        val birth = line2.substring(13, 19)
        val birthCheck = line2.getOrNull(19)
        val sex = line2.getOrNull(20)
        val expiry = line2.substring(21, 27)
        val expiryCheck = line2.getOrNull(27)

        fun yyMMdd(v: String): String = if (v.length == 6 && v.all { it.isDigit() }) {
            val yy = v.take(2).toInt()
            val century = if (yy > 30) "19" else "20"
            "$century${v.take(2)}-${v.substring(2, 4)}-${v.substring(4, 6)}"
        } else v

        val fields = listOf(
            Field("surname", "姓", surname.replace("<", " ").trim()),
            Field("given", "名", given.replace("<", " ").trim()),
            Field("passportNo", "护照号码", passportNo,
                if (noCheck == null) null else mrzCheckDigit(passportNo) == noCheck),
            Field("country", "签发国", country),
            Field("nationality", "国籍", nationality),
            Field("birth", "出生日期", yyMMdd(birth),
                if (birthCheck == null) null else mrzCheckDigit(birth) == birthCheck),
            Field("sex", "性别", when (sex) { 'M' -> "男"; 'F' -> "女"; else -> sex?.toString() ?: "" }),
            Field("expiry", "有效期至", yyMMdd(expiry),
                if (expiryCheck == null) null else mrzCheckDigit(expiry) == expiryCheck)
        )
        val notes = mutableListOf<String>()
        if (fields.any { it.valid == false }) notes += "有校验位不通过，MRZ 可能识别有误"
        return Result(Kind.PASSPORT, fields, notes)
    }

    // ------------------------------------------------------------------
    // 驾驶证 / 行驶证：固定版式 + 关键字 + 号牌正则
    // ------------------------------------------------------------------

    private val PLATE_PROVINCES = "京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新"

    private fun driverLicense(raw: String): Result {
        val flat = raw.replace('　', ' ').replace(Regex("[ \\t]+"), " ")
        val id = Regex("\\d{17}[0-9xX]").find(flat)?.value?.uppercase()
        val fields = listOf(
            Field("name", "姓名", after(flat, "姓名", setOf("性别", "国籍", "住址", "出生", "初次", "准驾", "证号"))),
            Field("idNumber", "证号", id ?: "", if (id == null) null else isIdCardValid(id)),
            Field("sex", "性别", Regex("性别\\s*([男女])").find(flat)?.groupValues?.getOrNull(1) ?: ""),
            Field("nationality", "国籍", Regex("国籍\\s*[:：]?\\s*([一-龥]{1,10})").find(flat)?.groupValues?.getOrNull(1) ?: ""),
            Field("address", "住址", after(flat, "住址", setOf("出生日期", "初次", "准驾", "国籍", "中华人民共和国"))),
            Field("birth", "出生日期", Regex("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日").find(flat)
                ?.let { "${it.groupValues[1]}-${pad(it.groupValues[2])}-${pad(it.groupValues[3])}" } ?: ""),
            Field("firstIssue", "初次领证日期", Regex("初次领证日期\\s*[:：]?\\s*(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2})").find(flat)?.groupValues?.getOrNull(1) ?: ""),
            Field("vehicleClass", "准驾车型", Regex("准驾车型\\s*[:：]?\\s*([A-Z0-9]{1,6})").find(flat)?.groupValues?.getOrNull(1) ?: ""),
            Field("validPeriod", "有效期限", Regex("有效期限\\s*[:：]?\\s*([^\\s].{0,30})").find(flat)?.groupValues?.getOrNull(1)?.trim() ?: "")
        )
        return Result(Kind.DRIVER_LICENSE, fields)
    }

    private fun vehicleLicense(raw: String): Result {
        val flat = raw.replace('　', ' ').replace(Regex("[ \\t]+"), " ")
        val plate = Regex("[$PLATE_PROVINCES][A-Z][A-Z0-9]{4,6}").find(flat)?.value
        val vin = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b").find(flat.uppercase())?.value
        val fields = listOf(
            Field("plate", "号牌号码", plate ?: "", plate != null),
            Field("owner", "所有人", after(flat, "所有人", setOf("住址", "使用性质", "品牌", "车辆识别", "发动机", "注册"))),
            Field("vin", "车辆识别代号", vin ?: "", if (vin == null) null else vin.length == 17),
            Field("engineNo", "发动机号", Regex("发动机号(?:码)?\\s*[:：]?\\s*([A-Z0-9]{6,20})").find(flat.uppercase())?.groupValues?.getOrNull(1) ?: ""),
            Field("model", "品牌型号", after(flat, "品牌型号", setOf("车辆识别", "发动机", "注册日期", "发证日期", "使用性质"))),
            Field("useType", "使用性质", Regex("使用性质\\s*[:：]?\\s*([一-龥]{1,10})").find(flat)?.groupValues?.getOrNull(1) ?: ""),
            Field("registerDate", "注册日期", Regex("注册日期\\s*[:：]?\\s*(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2})").find(flat)?.groupValues?.getOrNull(1) ?: "")
        )
        return Result(Kind.VEHICLE_LICENSE, fields)
    }

    // ------------------------------------------------------------------
    // 通用小工具
    // ------------------------------------------------------------------

    /** 取 key 之后直到遇到任一终止词之前的内容 */
    private fun after(flat: String, key: String, stopWords: Set<String>): String {
        val idx = Regex(key).find(flat)?.range?.last ?: return ""
        val rest = flat.drop(idx + 1).trimStart().trimStart(':').trimStart('：')
        var end = rest.length
        for (w in stopWords) {
            Regex(w).find(rest)?.range?.first?.let { if (it < end) end = it }
        }
        return rest.substring(0, end).trim()
    }

    private fun pad(v: String): String = if (v.length == 1) "0$v" else v

    /** 导出为 JSON（不依赖任何三方库） */
    fun toJson(r: Result): String {
        val sb = StringBuilder()
        sb.append("{\n  \"kind\": \"${r.kind.label}\",\n  \"fields\": [\n")
        r.fields.forEachIndexed { i, f ->
            sb.append("    {\"key\": \"${f.key}\", \"label\": \"${escape(f.label)}\", \"value\": \"${escape(f.value)}\", \"valid\": ${f.valid}}")
            if (i != r.fields.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n  \"notes\": [")
        r.notes.forEachIndexed { i, n ->
            sb.append("\"${escape(n)}\"")
            if (i != r.notes.lastIndex) sb.append(", ")
        }
        sb.append("]\n}")
        return sb.toString()
    }

    /** 导出为 CSV */
    fun toCsv(r: Result): String {
        val sb = StringBuilder()
        sb.append("字段,值,校验\n")
        r.fields.forEach { f ->
            val valid = when (f.valid) {
                true -> "通过"; false -> "不通过"; null -> "-"
            }
            fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
            sb.append("${quote(f.label)},${quote(f.value)},$valid\n")
        }
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
}
