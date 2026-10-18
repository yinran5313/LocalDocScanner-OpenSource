package com.localdoc.scanner.cv

internal object ImageApplyScope {
    fun resolve(paths:List<String>,mode:Int,current:Int,selected:List<String>):Set<String> = when(mode) {
        0 -> paths.toSet()
        1 -> paths.getOrNull(current)?.let(::setOf).orEmpty()
        2 -> selected.toSet().intersect(paths.toSet())
        else -> error("图片处理范围无效")
    }
}
