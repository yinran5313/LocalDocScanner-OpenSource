package com.localdoc.scanner.pdf
object PdfViewportMath {
    fun zoom(value:Float)=if(value.isFinite()) value.coerceIn(1f,4f) else 1f
    fun pan(value:Float,width:Float,zoom:Float):Float {
        val bound=(width*(zoom-1f)/2f).coerceAtLeast(0f)
        return if(value.isFinite()) value.coerceIn(-bound,bound) else 0f
    }
    fun offset(old:Int,ratio:Float,centroid:Float,movement:Float)=(old*ratio+centroid*(ratio-1f)-movement).toInt().coerceAtLeast(0)
}
