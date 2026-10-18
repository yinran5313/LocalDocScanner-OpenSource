package com.localdoc.scanner.barcode

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.ui.components.WrappingOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BarcodeResultDialog(record:BarcodeRecord,onClose:()->Unit,onRename:(String)->Unit) {
    val context=LocalContext.current;val clipboard=LocalClipboardManager.current
    var name by remember(record.text) {mutableStateOf(record.name)}
    var error by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onClose,title={Text(record.name.ifBlank {"扫码结果"})},text={
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("类型：${if(record.format=="QR_CODE") "二维码" else record.format} · ${SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(Date(record.time))}",style=MaterialTheme.typography.bodySmall)
            SelectionContainer {Text(record.text)}
            OutlinedTextField(name,{name=it.take(80)},label={Text("名称，方便以后查找")},singleLine=true,modifier=Modifier.fillMaxWidth())
            TextButton({onRename(name)}) {Text("保存名称")}
            WrappingOptions {
                TextButton({clipboard.setText(AnnotatedString(record.text))}) {Text("复制内容")}
                val uri=runCatching {Uri.parse(record.text.trim())}.getOrNull()
                if(uri?.scheme?.lowercase() in listOf("http","https") && !uri?.host.isNullOrBlank()) TextButton({
                    runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,uri))}.onFailure {error="没有可打开此链接的浏览器"}
                }) {Text("在浏览器打开")}
                TextButton({context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,record.text)},"分享扫码结果"))}) {Text("分享文字")}
            }
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClose){Text("完成")}})
}
