package com.localdoc.scanner.jobs

import android.content.Context
import android.graphics.Bitmap
import com.localdoc.scanner.models.*
import com.localdoc.scanner.ocr.*
import com.localdoc.scanner.structure.*
import kotlinx.coroutines.CancellationException

internal data class PageTableResult(val table: ModelTable, val note: String)

/** Share the layout -> cropped SLANet path used by structured Markdown. */
internal object PageTableRecovery {
    suspend fun recover(context: Context, image: Bitmap, words: List<OcrTextBox>): PageTableResult {
        var layoutNote="未安装版面模型，使用整页结构恢复"
        val regions=if(ModelCatalog.installed(context,ModelCatalog.get(context,"layout"))) {
            try { DocumentLayoutModel.detect(context,image).filter { it.valid && it.kind=="table" }.also {
                layoutNote=if(it.isEmpty()) "未定位到表格，使用整页结构恢复" else "先定位表格区域，再恢复行列"
            } } catch(e:Exception) { if(e is CancellationException) throw e
                layoutNote="版面定位未完成，使用整页结构恢复：${e.message}"; emptyList() }
        } else emptyList()
        if(regions.isEmpty()) return PageTableResult(TableModel.recover(context,image,words),"$layoutNote；请逐格复核")
        val ordered=DocumentReadingOrder.order(regions) { it }
        val assigned=ordered.map { mutableListOf<OcrTextBox>() }
        words.filter(OcrLayout::valid).forEach { word ->
            val area=(word.right-word.left)*(word.bottom-word.top)
            ordered.indices.map { i -> i to (maxOf(0f,minOf(word.right,ordered[i].right)-maxOf(word.left,ordered[i].left))*maxOf(0f,minOf(word.bottom,ordered[i].bottom)-maxOf(word.top,ordered[i].top))/area) }
                .filter { it.second>=.55f }.maxByOrNull { it.second }?.let { assigned[it.first]+=word }
        }
        val tables=ordered.mapIndexed { index,region ->
            val coordinates=RegionCoordinates(region,image.width,image.height)
            val crop=Bitmap.createBitmap(image,coordinates.left,coordinates.top,coordinates.width,coordinates.height)
            try { val result=TableModel.recover(context,crop,assigned[index].map(coordinates::local).filter(OcrLayout::valid))
                result.copy(cells=result.cells.map(coordinates::page))
            } finally { if(crop!==image) crop.recycle() }
        }
        val rows=mutableListOf<List<String>>(); val merges=mutableListOf<CellMerge>(); val cells=mutableListOf<TableCell>()
        for(table in tables) {
            if(rows.isNotEmpty()) rows+=emptyList<String>()
            val offset=rows.size
            rows+=table.rows
            merges+=table.merges.map { it.copy(top=it.top+offset,bottom=it.bottom+offset) }
            cells+=table.cells.map { it.copy(row=it.row+offset) }
        }
        return PageTableResult(ModelTable(rows,merges,cells,tables.minOf { it.score }),
            "$layoutNote；${tables.size}个表格按阅读顺序排列，原始识别文字完整保留；请逐格复核")
    }
}
