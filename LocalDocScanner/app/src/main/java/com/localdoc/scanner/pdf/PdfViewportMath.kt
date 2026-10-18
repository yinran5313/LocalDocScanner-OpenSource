package com.localdoc.scanner.pdf
object PdfViewportMath {
    fun zoom(value:Float)=if(value.isFinite()) value.coerceIn(1f,4f) else 1f
    fun pan(value:Float,width:Float,zoom:Float):Float {
        val bound=(width*(zoom-1f)/2f).coerceAtLeast(0f)
        return if(value.isFinite()) value.coerceIn(-bound,bound) else 0f
    }
    fun offset(old:Int,ratio:Float,centroid:Float,movement:Float)=(old*ratio+centroid*(ratio-1f)-movement).toInt().coerceAtLeast(0)
    fun resizedAnchor(index: Int, offset: Int, oldSize: Int, newSize: Int, snap: Boolean, count: Int): Pair<Int, Int> {
        if (snap) return (index + if (oldSize > 0 && offset >= oldSize / 2) 1 else 0).coerceIn(0, (count - 1).coerceAtLeast(0)) to 0
        val scaled = if (oldSize > 0) (offset.toDouble() * newSize / oldSize).toInt() else offset
        return index to scaled.coerceAtLeast(0)
    }
}
