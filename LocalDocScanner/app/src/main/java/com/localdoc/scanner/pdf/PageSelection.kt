package com.localdoc.scanner.pdf

object PageSelection {
    fun parse(raw: String, count: Int): List<Int> {
        require(count > 0)
        if (raw.isBlank()) return (0 until count).toList()
        val pages = raw.replace('，', ',').split(',').flatMap { token ->
            require(token.trim().matches(Regex("[0-9]+(\\s*-\\s*[0-9]+)?"))) { "页码格式无效：$token" }
            val pair = token.trim().split('-').map { it.trim().toIntOrNull() ?: error("页码过大") }
            val a = pair.first(); val b = pair.last()
            require(a in 1..count && b in a..count) { "页码超出范围：$token" }
            (a..b).map { it - 1 }
        }
        return pages.distinct()
    }
}
