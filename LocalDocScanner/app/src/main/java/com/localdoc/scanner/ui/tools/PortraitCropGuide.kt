package com.localdoc.scanner.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localdoc.scanner.cv.PortraitSizes
import java.io.File

@Composable
internal fun PortraitCropGuide(file: File, format: Int, x: Float, y: Float) {
    var dimensions by remember(file) { mutableStateOf(0 to 0) }
    Box(Modifier.fillMaxWidth().height(280.dp)) {
        AsyncImage(file, "证件照构图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            onSuccess = { dimensions = it.result.drawable.intrinsicWidth to it.result.drawable.intrinsicHeight })
        if (dimensions.first > 0 && dimensions.second > 0) Canvas(Modifier.fillMaxSize()) {
            val (w,h) = dimensions
            val scale = minOf(size.width/w, size.height/h)
            val crop = PortraitSizes.crop(w,h,PortraitSizes.sizes[format.coerceIn(PortraitSizes.sizes.indices)],x,y)
            drawRect(Color(0xFF00B8A1), Offset((size.width-w*scale)/2+crop.left*scale,(size.height-h*scale)/2+crop.top*scale), Size(crop.width*scale,crop.height*scale), style = Stroke(3.dp.toPx()))
        }
    }
}
