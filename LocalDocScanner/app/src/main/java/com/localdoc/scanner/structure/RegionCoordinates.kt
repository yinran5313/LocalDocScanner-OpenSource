package com.localdoc.scanner.structure

import com.localdoc.scanner.models.TableCell
import com.localdoc.scanner.ocr.OcrTextBox
import kotlin.math.roundToInt

/** One coordinate transform for cropped inference and original-image review. */
internal class RegionCoordinates(region: LayoutRegion, val pageWidth: Int, val pageHeight: Int) {
    init { require(region.valid && pageWidth > 0 && pageHeight > 0) }
    val left=(region.left*pageWidth).roundToInt().coerceIn(0,pageWidth-1)
    val top=(region.top*pageHeight).roundToInt().coerceIn(0,pageHeight-1)
    val width=(region.right*pageWidth).roundToInt().coerceIn(left+1,pageWidth)-left
    val height=(region.bottom*pageHeight).roundToInt().coerceIn(top+1,pageHeight)-top
    fun local(word: OcrTextBox)=word.copy(
        left=((word.left*pageWidth-left)/width).coerceIn(0f,1f), right=((word.right*pageWidth-left)/width).coerceIn(0f,1f),
        top=((word.top*pageHeight-top)/height).coerceIn(0f,1f), bottom=((word.bottom*pageHeight-top)/height).coerceIn(0f,1f))
    fun page(cell: TableCell)=cell.copy(left=(left+cell.left*width)/pageWidth, right=(left+cell.right*width)/pageWidth,
        top=(top+cell.top*height)/pageHeight, bottom=(top+cell.bottom*height)/pageHeight)
}
