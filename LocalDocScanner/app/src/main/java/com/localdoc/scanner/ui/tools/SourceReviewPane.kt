package com.localdoc.scanner.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.ReviewPage
import com.localdoc.scanner.ocr.OcrTextBox
import com.localdoc.scanner.pdf.PdfTools
import com.localdoc.scanner.util.BitmapOwnership
import com.localdoc.scanner.util.ImageIo
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.min

/** Keep the original visible while editing; changing fields only redraws the highlight. */
@Composable
internal fun SourceReviewPane(page:ReviewPage,focus:SourceFocus?,modifier:Modifier=Modifier) {
    var error by remember(page.source,page.page) { mutableStateOf("") }
    val bitmap by com.localdoc.scanner.ui.components.rememberDisplayBitmap(page.source to page.page,onError={error="原图读取失败：${it.message}"}) {
        val source=File(page.source)
        (if(source.extension.equals("pdf",true)) PdfTools.renderPage(source,page.page,1600) else ImageIo.loadFromFile(source,1600))
    }
    val cell=page.modelCells?.firstOrNull { focus?.row==it.row && focus?.column==it.column }
    val boxes=if(cell!=null) listOf(OcrTextBox(focus?.value.orEmpty(),cell.left,cell.top,cell.right,cell.bottom)) else page.sourceBoxes.orEmpty().filter { b ->
        focus?.value?.trim()?.takeIf(String::isNotBlank)?.let { it in b.text || b.text.isNotBlank() && b.text in it }==true }
    Column(modifier.testTag("source-review"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(if(focus==null) "原图 · 点字段或单元格可联动定位" else "原图 · ${focus.label}${if(boxes.isEmpty()) "（未匹配位置）" else " · 橙框定位"}",style=MaterialTheme.typography.labelSmall,modifier=Modifier.testTag("source-review-focus"))
        val image=bitmap
        if(image!=null && !image.isRecycled) ZoomableImage(image,page.source to page.page,Modifier.fillMaxWidth().weight(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                val scale=min(size.width/image.width,size.height/image.height)
                val width=image.width*scale;val height=image.height*scale
                val left=(size.width-width)/2;val top=(size.height-height)/2
                boxes.filter { com.localdoc.scanner.ocr.OcrLayout.valid(it) }.forEach { b ->
                    drawRect(Color(0xFFFF702B),Offset(left+b.left*width,top+b.top*height),Size((b.right-b.left)*width,(b.bottom-b.top)*height),style=Stroke(3.dp.toPx()))
                }
            }
        } else Box(Modifier.fillMaxSize()) { Text(error.ifBlank {"正在读取原图…"}) }
    }
}
