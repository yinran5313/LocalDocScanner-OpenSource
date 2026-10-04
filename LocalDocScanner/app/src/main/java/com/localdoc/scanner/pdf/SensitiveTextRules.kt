package com.localdoc.scanner.pdf

internal object SensitiveTextRules {
    fun kinds(text: String, keywords: List<String> = emptyList()): List<String> {
        val compact = text.replace(Regex("\\s"), "")
        return buildList {
            if (Regex("(?<![0-9])1[3-9][0-9]{9}(?![0-9])").containsMatchIn(compact)) add("手机号")
            if (Regex("(?<![0-9])[1-9][0-9]{5}(?:19|20)[0-9]{2}[01][0-9][0-3][0-9][0-9]{3}[0-9Xx](?![0-9])").containsMatchIn(compact)) add("身份证号候选")
            if (Regex("(?<![0-9])[0-9]{13,19}(?![0-9])").findAll(compact).any { luhn(it.value) }) add("银行卡号候选")
            if (keywords.any { it.isNotBlank() && text.contains(it.trim(), ignoreCase = true) }) add("自定义关键词")
        }.distinct()
    }
    private fun luhn(value: String): Boolean {
        if (value.toSet().size <= 1) return false
        val sum = value.reversed().mapIndexed { index, c ->
            val digit = c.digitToInt(); val number = if (index % 2 == 1) digit * 2 else digit
            if (number > 9) number - 9 else number
        }.sum()
        return sum % 10 == 0
    }
}
