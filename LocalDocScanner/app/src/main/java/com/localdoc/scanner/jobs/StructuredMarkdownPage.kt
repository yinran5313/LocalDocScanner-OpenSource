package com.localdoc.scanner.jobs

import android.content.Context
import android.graphics.Bitmap
import com.localdoc.scanner.models.*
import com.localdoc.scanner.ocr.*
import com.localdoc.scanner.structure.*
import com.localdoc.scanner.util.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

data class MarkdownRegionOutput(val region: LayoutRegion, val text: String, val table: ModelTable? = null)
data class MarkdownPageOutput(val markdown: String, val files: List<File>, val regions: List<MarkdownRegionOutput>, val warnings: List<String>)

/** Assign each OCR word once, retaining unassigned words and source coordinates. */
internal object StructuredMarkdownPage {
    suspend fun render(context: Context, image: Bitmap, words: List<OcrTextBox>, regions: List<LayoutRegion>,
        useTables: Boolean, columns: String, output: File, prefix: String): MarkdownPageOutput {
        val valid=regions.filter { it.valid }
        val assigned=valid.map { mutableListOf<OcrTextBox>() }
        val unassigned=mutableListOf<OcrTextBox>()
        for(word in words.filter(OcrLayout::valid)) {
            val area=(word.right-word.left)*(word.bottom-word.top)
            val ranked=valid.indices.map { i -> i to (maxOf(0f,minOf(word.right,valid[i].right)-maxOf(word.left,valid[i].left))*maxOf(0f,minOf(word.bottom,valid[i].bottom)-maxOf(word.top,valid[i].top))/area) }
                .filter { it.second>=.55f }.maxByOrNull { it.second+valid[it.first].score*.01f }
            if(ranked==null) unassigned+=word else assigned[ranked.first]+=word
        }
        val elements=mutableListOf<MarkdownRegionOutput>(); val files=mutableListOf<File>(); val warnings=mutableListOf<String>()
        for((index,region) in valid.withIndex()) {
            currentCoroutineContext().ensureActive()
            val selected=assigned[index]; var text=LayoutMarkdown.render(selected, "SINGLE"); var table: ModelTable?=null
            if(region.kind in listOf("figure","equation","table")) {
                val left=(region.left*image.width).roundToInt().coerceIn(0,image.width-1); val top=(region.top*image.height).roundToInt().coerceIn(0,image.height-1)
                val right=(region.right*image.width).roundToInt().coerceIn(left+1,image.width); val bottom=(region.bottom*image.height).roundToInt().coerceIn(top+1,image.height)
                val crop=Bitmap.createBitmap(image,left,top,right-left,bottom-top)
                try {
                    val reference=File(output,"figure-$prefix-${index+1}.jpg")
                    AtomicFiles.write(reference) { check(ImageIo.saveJpeg(crop,it,94)) }; files+=reference
                    val label=when(region.kind) { "table" -> "表格原图"; "equation" -> "公式原图"; else -> "插图" }
                    val imageText="![$label](${reference.name})"
                    if(region.kind=="table" && useTables) {
                        try {
                            val coordinates=RegionCoordinates(region,image.width,image.height)
                            val local=selected.map(coordinates::local).filter(OcrLayout::valid)
                            val recovered=TableModel.recover(context,crop,local)
                            table=recovered.copy(cells=recovered.cells.map(coordinates::page))
                            val remaining=selected.filter { word -> table.cells.none { c -> word.left<c.right && word.right>c.left && word.top<c.bottom && word.bottom>c.top } }
                            text=LayoutMarkdown.modelTable(recovered)+"\n\n"+imageText+if(remaining.isNotEmpty()) "\n\n"+LayoutMarkdown.render(remaining,"SINGLE") else ""
                        } catch(e: Exception) { if(e is CancellationException || e is ToolSliceEnded) throw e
                            warnings+="第${index+1}区表格结构未恢复，已保留原图和文字：${e.message}"; text=imageText+if(selected.isNotEmpty()) "\n\n$text" else ""
                        }
                    } else text=imageText+if(selected.isNotEmpty()) "\n\n$text" else ""
                } finally { if(crop!==image) crop.recycle() }
            } else if(region.kind=="title" && selected.isNotEmpty()) text="### "+selected.sortedWith(compareBy({it.top},{it.left})).joinToString(" ") { LayoutMarkdown.escape(it.text) }
            else if(region.kind in listOf("header","footer") && selected.isNotEmpty()) text="> "+selected.joinToString(" ") { LayoutMarkdown.escape(it.text) }
            if(selected.isNotEmpty() || region.kind in listOf("figure","equation","table")) elements+=MarkdownRegionOutput(region,text,table)
        }
        unassigned.forEach { word -> elements+=MarkdownRegionOutput(LayoutRegion("text",1f,word.left,word.top,word.right,word.bottom),LayoutMarkdown.render(listOf(word),"SINGLE")) }
        val ordered=if(columns=="SINGLE") elements.sortedWith(compareBy({it.region.top},{it.region.left})) else DocumentReadingOrder.order(elements) { it.region }
        return MarkdownPageOutput(ordered.joinToString("\n\n") { it.text },files,ordered,warnings)
    }
}
