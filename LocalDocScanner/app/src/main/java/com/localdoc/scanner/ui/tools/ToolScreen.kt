package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.jobs.*
import com.localdoc.scanner.ui.AppViewModel
import com.localdoc.scanner.ui.ToolRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import com.localdoc.scanner.ui.components.*

@Composable
fun ToolScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val request = vm.toolRequest
    if (request == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有待处理的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val inputKey = request.files.map { it.absolutePath }
    var checked by remember(inputKey) { mutableStateOf(false) }
    var locked by remember(inputKey) { mutableStateOf<File?>(null) }
    var inputError by remember(inputKey) { mutableStateOf("") }
    LaunchedEffect(inputKey) {
        withContext(Dispatchers.IO) {
            for (file in request.files.filter { it.extension.equals("pdf", true) }) {
                try {
                    val encrypted = com.localdoc.scanner.pdf.PdfReadSession.open(file).use { it.encrypted }
                    if (encrypted) { locked = file; break }
                } catch (_: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                    locked = file; break
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    inputError = "${request.names.getOrNull(request.files.indexOf(file)) ?: file.name}：${e.message}"
                    break
                }
            }
        }
        checked = true
    }
    if (!checked || locked != null || inputError.isNotBlank()) {
        Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            when {
                !checked -> Text("正在检查文件…")
                locked != null -> {
                    Text("先解锁待处理PDF", style = MaterialTheme.typography.titleLarge)
                    Text(request.names.getOrNull(request.files.indexOf(locked)) ?: locked!!.name)
                    PdfReader(locked!!, Modifier.fillMaxWidth().weight(1f), onWorkingCopyReady = {
                        vm.replaceToolInput(locked!!, it)
                    })
                }
                else -> Text("文件读取失败：$inputError", color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }
    key(request.tool.id, inputKey) {
        when (request.tool.id) {
            "images_to_pdf" -> ImagesToPdfFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_merge" -> PdfMergeFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_split" -> PdfSplitFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_compress" -> PdfCompressFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_to_images" -> PdfToImagesFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_text" -> PdfTextFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_encrypt" -> PdfEncryptFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_office" -> PdfOfficeFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_sign" -> PdfSignFlow(request, vm, onBack, onOpenDoc, modifier)
            "pdf_compare" -> PdfCompareFlow(request, vm, onBack, onOpenDoc, modifier)
            "ocr" -> OcrFlow(request, vm, onBack, onOpenDoc, modifier)
            "batch_extract", "table_xlsx" -> StructuredWorkbench(request, vm, onBack, modifier)
            "card" -> CardFlow(request, vm, onBack, onOpenDoc, modifier)
            "barcode" -> BarcodeFlow(request, vm, onBack, onOpenDoc, modifier)
            "long_image" -> LongImageFlow(request, vm, onBack, onOpenDoc, modifier)
            "image_edit" -> ImageEditFlow(request, vm, onBack, onOpenDoc, modifier)
            else -> UnknownFlow(request, vm, onBack, onOpenDoc, modifier)
        }
    }
}



@Composable
internal fun UnknownFlow(
    request: ToolRequest, vm: AppViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit,
    modifier: Modifier
) {
    ToolFlow(
        request = request, vm = vm, onBack = onBack, onOpenDoc = onOpenDoc,
        runLabel = "存为文档", modifier = modifier,
        config = { Text("该入口暂不支持处理，请返回首页选择工具。") }
    )
}
