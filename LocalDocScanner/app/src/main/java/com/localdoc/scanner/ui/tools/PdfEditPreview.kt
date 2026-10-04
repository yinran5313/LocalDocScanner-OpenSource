package com.localdoc.scanner.ui.tools

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.pdf.PdfInkPoint
import com.localdoc.scanner.pdf.PdfMarkup
import com.localdoc.scanner.pdf.NormalizedRect
import com.localdoc.scanner.pdf.PdfPlacementMath
import com.localdoc.scanner.pdf.PdfTools
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun PdfEditPreview(
    source: File,
    bitmap: Bitmap?,
    pageIndex: Int,
    pageCount: Int,
    operation: Int,
    text: String,
    header: String,
    footer: String,
    watermark: String,
    addPageNumbers: Boolean,
    opacity: Float,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    markup: PdfMarkup,
    strokes: List<List<PdfInkPoint>>,
    signatureBitmap: Bitmap?,
    watermarkBitmap: Bitmap?,
    decoration: com.localdoc.scanner.pdf.PdfDecorationOptions = com.localdoc.scanner.pdf.PdfDecorationOptions(),
    onRectChange: (Float, Float, Float, Float) -> Unit,
    onInteractionStart: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val canvasHeight = 430.dp
    val canvasHeightPx = with(density) { canvasHeight.toPx() }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val signature = remember(signatureBitmap) { signatureBitmap?.asImageBitmap() }
    val watermarkImage = remember(watermarkBitmap) { watermarkBitmap?.asImageBitmap() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPreviousPage, enabled = pageIndex > 0) { Text("上一页") }
            Text("${pageIndex + 1} / $pageCount · 实时预览")
            Button(onClick = onNextPage, enabled = pageIndex + 1 < pageCount) { Text("下一页") }
        }
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items((0 until pageCount.coerceAtMost(200)).toList(), key = { it }) { index ->
                PdfPageThumbnail(source, index, selected = index == pageIndex, onClick = { onSelectPage(index) })
            }
        }
        Box(Modifier.fillMaxWidth().height(canvasHeight).background(Color(0xFF111318))) {
            if (bitmap == null || image == null) {
                Text("正在渲染页面…", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(canvasHeight)) {
                val areaWidthPx = with(density) { maxWidth.toPx() }
                val scale = min(areaWidthPx / bitmap.width, canvasHeightPx / bitmap.height)
                val pageWidth = bitmap.width * scale
                val pageHeight = bitmap.height * scale
                val left = (areaWidthPx - pageWidth) / 2f
                val top = (canvasHeightPx - pageHeight) / 2f
                val handleRadius = with(density) { 18.dp.toPx() }

                Canvas(
                    Modifier.fillMaxWidth().height(canvasHeight)
                        .pointerInput(bitmap, operation, x, y, width, height) {
                            detectTapGestures { point ->
                                if (operation in 1..5 || operation == 7) {
                                    val nx = ((point.x - left) / pageWidth).coerceIn(0f, 1f)
                                    val ny = ((point.y - top) / pageHeight).coerceIn(0f, 1f)
                                    if (point.x in left..(left + pageWidth) && point.y in top..(top + pageHeight)) {
                                        onInteractionStart()
                                        val rect = PdfPlacementMath.centeredAt(nx, ny, width, height)
                                        onRectChange(rect.x, rect.y, rect.width, rect.height)
                                    }
                                }
                            }
                        }
                        .pointerInput(bitmap, operation, x, y, width, height) {
                            var resizing = false
                            detectDragGestures(
                                onDragStart = { point ->
                                    if (operation in 1..5 || operation == 7) onInteractionStart()
                                    val right = left + (x + width) * pageWidth
                                    val bottom = top + (y + height) * pageHeight
                                    resizing = operation in setOf(2, 4, 7) && abs(point.x - right) <= handleRadius * 1.8f && abs(point.y - bottom) <= handleRadius * 1.8f
                                },
                                onDrag = { change, drag ->
                                    if (operation in 1..5 || operation == 7) {
                                        if (resizing) {
                                            val rect = PdfPlacementMath.resize(
                                                NormalizedRect(x, y, width, height), drag.x / pageWidth, drag.y / pageHeight
                                            )
                                            onRectChange(rect.x, rect.y, rect.width, rect.height)
                                        } else {
                                            val rect = PdfPlacementMath.move(
                                                NormalizedRect(x, y, width, height), drag.x / pageWidth, drag.y / pageHeight
                                            )
                                            onRectChange(rect.x, rect.y, rect.width, rect.height)
                                        }
                                        change.consume()
                                    }
                                }
                            )
                        }
                ) {
                    drawImage(
                        image,
                        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(pageWidth.toInt(), pageHeight.toInt())
                    )

                    val rectLeft = left + x * pageWidth
                    val rectTop = top + y * pageHeight
                    val rectWidth = width * pageWidth
                    val rectHeight = height * pageHeight
                    val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = (pageWidth * 0.035f).coerceIn(15f, 32f)
                        color = android.graphics.Color.rgb(20, 115, 62)
                    }
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        when (operation) {
                            0 -> {
                                val subtle = Paint(previewPaint).apply { alpha = (opacity.coerceIn(0.08f, 0.7f) * 255).toInt() }
                                if (header.isNotBlank()) native.drawText(header.take(50), left + 12f, top + subtle.textSize + 8f, subtle)
                                if (footer.isNotBlank()) native.drawText(footer.take(50), left + 12f, top + pageHeight - 12f, subtle)
                                if (addPageNumbers) {
                                    val label = runCatching { decoration.numberLabel(pageIndex, pageCount) }.getOrDefault("页码无效")
                                    val position = decoration.numberPosition
                                    subtle.textAlign = when (position % 3) { 0 -> Paint.Align.LEFT; 1 -> Paint.Align.CENTER; else -> Paint.Align.RIGHT }
                                    val xx = when (position % 3) { 0 -> left + 12f; 1 -> left + pageWidth / 2f; else -> left + pageWidth - 12f }
                                    native.drawText(label, xx, if (position < 3) top + subtle.textSize + 8f else top + pageHeight - 12f, subtle)
                                    subtle.textAlign = Paint.Align.LEFT
                                }
                                if (watermark.isNotBlank()) {
                                    native.save()
                                    native.rotate(-decoration.watermarkAngle, left + pageWidth / 2f, top + pageHeight / 2f)
                                    subtle.textSize = (pageWidth * 0.08f).coerceIn(22f, 58f)
                                    subtle.textAlign = Paint.Align.CENTER
                                    native.drawText(watermark.take(32), left + pageWidth / 2f, top + pageHeight / 2f, subtle)
                                    native.restore()
                                }
                            }
                            1 -> native.drawText(text.ifBlank { "填写文字" }.take(50), rectLeft, rectTop + previewPaint.textSize, previewPaint)
                            3 -> {
                                previewPaint.color = android.graphics.Color.rgb(255, 142, 0)
                                native.drawCircle(rectLeft + 12f, rectTop + 12f, 12f, previewPaint)
                                previewPaint.color = android.graphics.Color.DKGRAY
                                native.drawText(text.ifBlank { "便签" }.take(24), rectLeft + 30f, rectTop + previewPaint.textSize, previewPaint)
                            }
                        }
                    }
                    when (operation) {
                        0 -> if (watermarkImage != null && watermarkBitmap != null) {
                            val drawWidth = pageWidth * 0.34f
                            val drawHeight = drawWidth * watermarkBitmap.height / watermarkBitmap.width.coerceAtLeast(1).toFloat()
                            drawImage(
                                watermarkImage,
                                dstOffset = androidx.compose.ui.unit.IntOffset((left + (pageWidth - drawWidth) / 2f).toInt(), (top + (pageHeight - drawHeight) / 2f).toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize(drawWidth.toInt(), drawHeight.toInt()),
                                alpha = opacity
                            )
                        }
                        2 -> when (markup) {
                            PdfMarkup.HIGHLIGHT -> drawRect(Color.Yellow.copy(alpha = 0.38f), Offset(rectLeft, rectTop), androidx.compose.ui.geometry.Size(rectWidth, rectHeight))
                            PdfMarkup.UNDERLINE -> drawLine(Color(0xFF007A43), Offset(rectLeft, rectTop + rectHeight), Offset(rectLeft + rectWidth, rectTop + rectHeight), 4f)
                            PdfMarkup.STRIKEOUT -> drawLine(Color.Red, Offset(rectLeft, rectTop + rectHeight / 2f), Offset(rectLeft + rectWidth, rectTop + rectHeight / 2f), 4f)
                        }
                        4 -> strokes.forEach { stroke ->
                            stroke.zipWithNext().forEach { (a, b) ->
                                drawLine(
                                    Color(0xFF007A43),
                                    Offset(rectLeft + a.x * rectWidth, rectTop + a.y * rectHeight),
                                    Offset(rectLeft + b.x * rectWidth, rectTop + b.y * rectHeight),
                                    4f
                                )
                            }
                        }
                        5 -> if (signature != null) drawImage(
                            signature,
                            dstOffset = androidx.compose.ui.unit.IntOffset(rectLeft.toInt(), rectTop.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(rectWidth.toInt(), rectHeight.toInt())
                        ) else drawRect(Color(0x22007A43), Offset(rectLeft, rectTop), androidx.compose.ui.geometry.Size(rectWidth, rectHeight))
                        7 -> drawRect(Color.Black, Offset(rectLeft, rectTop), androidx.compose.ui.geometry.Size(rectWidth, rectHeight))
                    }
                    if (operation in 1..5 || operation == 7) {
                        drawRect(Color(0xFF00A15D), Offset(rectLeft, rectTop), androidx.compose.ui.geometry.Size(rectWidth, rectHeight), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                        if (operation in setOf(2, 4, 7)) drawCircle(Color(0xFF00A15D), handleRadius * 0.55f, Offset(rectLeft + rectWidth, rectTop + rectHeight))
                    }
                }
            }
        }
        Text(
            when (operation) {
                0 -> "当前显示页眉、页脚、页码和水印示意；生成后会重新读取真实PDF。"
                6 -> "表单字段直接填写；生成后在结果预览中核对真实显示。"
                else -> "点按页面定位；按住绿色框拖动位置。标记、手写和打码可拖右下圆点改变范围。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun PdfPageThumbnail(file: File, index: Int, selected: Boolean, onClick: () -> Unit) {
    var bitmap by remember(file, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, index) {
        bitmap = withContext(Dispatchers.IO) { PdfTools.renderPage(file, index, 260) }
    }
    DisposableEffect(file, index) { onDispose { bitmap?.recycle() } }
    Column(
        Modifier.width(74.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.width(68.dp).height(88.dp)
                .background(Color.White)
                .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFF00A15D) else Color.Gray),
            contentAlignment = Alignment.Center
        ) {
            val value = bitmap
            if (value == null) Text("…", color = Color.DarkGray)
            else AsyncImage(value, contentDescription = "第${index + 1}页", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(88.dp))
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
    }
}
