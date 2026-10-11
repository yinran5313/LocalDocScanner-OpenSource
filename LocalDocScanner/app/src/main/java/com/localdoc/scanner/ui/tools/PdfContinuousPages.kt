package com.localdoc.scanner.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.snapping.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.localdoc.scanner.pdf.*
import kotlinx.coroutines.*
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PdfContinuousPages(session:PdfReadSession, listState:LazyListState, snapPages:Boolean, onPageChanged:(Int)->Unit, modifier:Modifier=Modifier) {
    var zoom by remember(session) { mutableFloatStateOf(1f) }
    var pan by remember(session) { mutableFloatStateOf(0f) }
    val density=LocalDensity.current
    var ratios by remember(session) { mutableStateOf(List(session.pageCount) { 0.707f }) }
    LaunchedEffect(session) {
        try { ratios = withContext(Dispatchers.IO) { session.pageAspectRatios() } }
        catch (e: Exception) { if (e is CancellationException) throw e }
    }
    val pageCallback by rememberUpdatedState(onPageChanged)
    LaunchedEffect(listState,session) { snapshotFlow { listState.firstVisibleItemIndex }.collect { pageCallback(it) } }
    LaunchedEffect(listState, snapPages, zoom == 1f) {
        if (!snapPages || zoom != 1f) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving && listState.firstVisibleItemScrollOffset > 0) {
                val first = listState.firstVisibleItemIndex
                val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == first }?.size ?: 0
                val next = if (size > 0 && listState.firstVisibleItemScrollOffset >= size / 2) first + 1 else first
                listState.animateScrollToItem(next.coerceIn(0, session.pageCount - 1))
            }
        }
    }
    val snap=rememberSnapFlingBehavior(listState,SnapPosition.Start)
    val normal=ScrollableDefaults.flingBehavior()
    BoxWithConstraints(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceContainer).testTag("pdf-pages")) {
        val viewWidth=with(density) { maxWidth.toPx() }
        val viewHeight=maxHeight
        val latestZoom by rememberUpdatedState(zoom)
        val latestPan by rememberUpdatedState(pan)
        val transform by rememberUpdatedState<(Float,Offset,Offset)->Unit>({ factor,center,movement ->
            val next=PdfViewportMath.zoom(zoom*factor)
            val ratio=next/zoom
            val origin=center.x-viewWidth/2f
            pan=PdfViewportMath.pan((pan-origin)*ratio+origin+movement.x,viewWidth,next)
            val index=listState.firstVisibleItemIndex
            val offset=PdfViewportMath.offset(listState.firstVisibleItemScrollOffset,ratio,center.y,movement.y)
            zoom=next
            listState.requestScrollToItem(index,offset)
        })
        val gestures=Modifier.pointerInput(session) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                var distance=Offset.Zero
                var horizontal=false
                do {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    if(event.changes.count { it.pressed }>=2) {
                        transform(event.calculateZoom(),event.calculateCentroid(useCurrent=false),event.calculatePan())
                        event.changes.forEach { it.consume() }
                    } else if(latestZoom>1f) {
                        val change=event.changes.firstOrNull { it.pressed }
                        if(change!=null) {
                            val delta=change.positionChange()
                            distance+=delta
                            if(!horizontal && abs(distance.x)>viewConfiguration.touchSlop && abs(distance.x)>abs(distance.y)*1.25f) horizontal=true
                            if(horizontal) { pan=PdfViewportMath.pan(latestPan+delta.x,viewWidth,latestZoom); change.consume() }
                        }
                    }
                } while(event.changes.any { it.pressed })
            }
        }.pointerInput(session) { detectTapGestures(onDoubleTap={ position -> transform(if(latestZoom>1.05f) 1f/latestZoom else 2f,position,Offset.Zero) }) }
        LazyColumn(state=listState,modifier=Modifier.fillMaxSize().then(gestures),
            contentPadding=PaddingValues(vertical=if(snapPages && zoom==1f) 0.dp else 8.dp),
            verticalArrangement=Arrangement.spacedBy(if(snapPages && zoom==1f) 0.dp else 8.dp),
            flingBehavior=if(snapPages && zoom==1f) snap else normal) {
            items(session.pageCount,key={ it }) { index ->
                val width=maxWidth-16.dp
                val itemHeight=if(snapPages && zoom==1f) viewHeight else width*zoom/ratios.getOrElse(index) { 0.707f }
                Box(Modifier.fillMaxWidth().height(itemHeight).clipToBounds().testTag("pdf-page-$index"),contentAlignment=Alignment.Center) {
                    PdfRenderedPage(session,index,(1200*zoom).toInt().coerceIn(1200,2800),
                        Modifier.requiredWidth(width*zoom).fillMaxHeight().graphicsLayer { translationX=pan })
                }
            }
        }
    }
}

@Composable
private fun PdfRenderedPage(session:PdfReadSession,index:Int,resolution:Int,modifier:Modifier) {
    val ownership=remember(session,index) { com.localdoc.scanner.util.BitmapOwnership() }
    var bitmap by remember(session,index) { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf("") }
    val tier=(resolution/200)*200
    LaunchedEffect(session,index,tier) {
        delay(120)
        var pending:Bitmap?=null
        try {
            val image=withContext(Dispatchers.IO) { session.render(index,tier)?.also { pending=ownership.adopt(it) } }
            val old=bitmap; bitmap=image; pending=null
            ownership.retire(old)
            if(image==null) error="无法显示第${index+1}页"
        } catch(e:Exception) { if(e is CancellationException) throw e; error="页面读取失败：${e.message}" }
        finally { ownership.retire(pending) }
    }
    DisposableEffect(ownership) { onDispose { ownership.dispose() } }
    val value=bitmap
    if(value!=null && !value.isRecycled) Image(value.asImageBitmap(),"第${index+1}页",modifier,contentScale=ContentScale.Fit)
    else Box(modifier,contentAlignment=Alignment.Center) { if(error.isBlank()) CircularProgressIndicator() else Text(error) }
}
