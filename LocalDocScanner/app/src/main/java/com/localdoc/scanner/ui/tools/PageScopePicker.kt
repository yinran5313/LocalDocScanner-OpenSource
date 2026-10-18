package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.pdf.PageSelection
import com.localdoc.scanner.pdf.PdfTools
import com.localdoc.scanner.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import coil.compose.AsyncImage

/** A single range contract shared by PDF image export, enhancement and long images. */
@Composable
internal fun PageScopePicker(file:File,raw:String,onChange:(String)->Unit,onCount:(Int)->Unit) {
    var count by remember(file) {mutableIntStateOf(0)}
    var mode by rememberSaveable(file.absolutePath) {mutableIntStateOf(if(raw.isBlank()) 0 else 2)}
    var current by rememberSaveable(file.absolutePath) {mutableIntStateOf(0)}
    var thumbnails by rememberSaveable(file.absolutePath) {mutableStateOf(false)}
    LaunchedEffect(file) {count=withContext(Dispatchers.IO) {PdfTools.pageCount(file)}}
    val selection=remember(raw,count) {runCatching {PageSelection.parse(raw,count)}}
    val selected=selection.getOrDefault(emptyList())
    val callback by rememberUpdatedState(onCount)
    LaunchedEffect(selected,count) {callback(selected.size)}
    Text("处理范围",style=MaterialTheme.typography.titleSmall)
    WrappingOptions {
        FilterChip(mode==0,{mode=0;onChange("")},label={Text("全部 · $count 页")})
        FilterChip(mode==1,{mode=1;thumbnails=true;onChange("${current+1}")},label={Text("当前 · 第${current+1}页")})
        FilterChip(mode==2,{mode=2;thumbnails=true},label={Text("选择页面")})
    }
    if(mode==2) OutlinedTextField(raw,onChange,label={Text("页码范围")},supportingText={Text("如1-3,5；留空表示全部")},modifier=Modifier.fillMaxWidth(),singleLine=true,isError=count>0 && selection.isFailure)
    if(count>0 && selection.isFailure) Text(selection.exceptionOrNull()?.message ?: "请检查页码",color=MaterialTheme.colorScheme.error)
    else Text("本次处理 ${selected.size} / $count 页",style=MaterialTheme.typography.bodySmall)
    TextButton({thumbnails=!thumbnails}) {Text(if(thumbnails) "收起选页缩略图" else "用缩略图选页")}
    if(thumbnails && count>0) {
        LazyRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items((0 until minOf(count,200)).toList(),key={it}) {page ->
                val image by rememberDisplayBitmap(file to page) {PdfTools.renderPage(file,page,220)}
                OutlinedCard(Modifier.width(80.dp).clickable {
                    current=page
                    if(mode==1) onChange("${page+1}") else {
                        mode=2
                        val values=if(page in selected) selected-page else selected+page
                        // Blank means all, so an empty selection must remain explicitly invalid.
                        onChange(if(values.isEmpty()) "0" else values.sorted().joinToString(",") {"${it+1}"})
                    }
                }) {Column(Modifier.padding(5.dp)) {
                    AsyncImage(image,"第${page+1}页",Modifier.fillMaxWidth().height(88.dp))
                    Text("${if(page in selected) "✓ " else ""}${page+1}",style=MaterialTheme.typography.labelMedium)
                }}
            }
        }
        if(count>200) Text("缩略图显示前200页，其它页可直接输入页码范围。",style=MaterialTheme.typography.bodySmall)
    }
}
