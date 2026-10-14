package com.localdoc.scanner.structure

/** Physical sizes are layout presets; crop each side before selecting it here. */
object CardTemplates {
    data class Template(val label: String, val widthMm: Float, val heightMm: Float, val twoSides: Boolean)
    val templates = listOf(Template("标准卡片 85.6×54mm", 85.6f, 54f, true), Template("学生证卡片 85.6×54mm", 85.6f, 54f, true),
        Template("护照页面 125×88mm", 125f, 88f, false), Template("A6证件 105×148mm", 105f, 148f, false))
    fun extract(kind: StructureExtractor.Kind, raw: String): StructureExtractor.Result {
        val labels = when (kind) {
            StructureExtractor.Kind.STUDENT_CARD -> listOf("name" to "姓名", "school" to "学校", "studentId" to "学号", "department" to "院系", "major" to "专业", "class" to "班级", "validUntil" to "有效期")
            StructureExtractor.Kind.SOCIAL_CARD -> listOf("name" to "姓名", "idNumber" to "社会保障号码", "cardNumber" to "卡号", "issuer" to "发卡机构", "validUntil" to "有效期")
            StructureExtractor.Kind.MEMBERSHIP -> listOf("name" to "姓名", "cardNumber" to "会员号", "issuer" to "机构", "validUntil" to "有效期")
            else -> emptyList()
        }
        val flat = raw.replace(Regex("[\\t ]+"), " ")
        return StructureExtractor.Result(kind, labels.map { (key, label) ->
            val aliases = when (key) { "school" -> "学校|校名"; "studentId" -> "学号|学生编号"; "department" -> "院系|学院"; else -> Regex.escape(label) }
            val value = Regex("(?:$aliases)[：: ]*([^\\n]+)").find(flat)?.groupValues?.get(1)?.trim().orEmpty()
            val stop = labels.map { it.second }.filter { it != label }.mapNotNull { s -> value.indexOf(s).takeIf { it >= 0 } }.minOrNull()
            StructureExtractor.Field(key, label, if (stop == null) value else value.take(stop).trim())
        }, listOf("字段为识别建议，请逐项核对；本地不进行证件真伪核验"))
    }
}
