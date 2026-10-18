package com.localdoc.scanner.models

import android.content.Context
import android.graphics.*
import com.localdoc.scanner.output.OutputHistoryStore
import com.localdoc.scanner.util.AtomicFiles
import com.localdoc.scanner.util.ImageIo
import com.google.gson.Gson
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CountMark(val x:Float,val y:Float,val manual:Boolean=false)

/** Model results remain suggestions; the review has its own explicitly confirmed output. */
object CountReview {
    fun detected(boxes:List<CountedObject>)=boxes.map {CountMark((it.left+it.right)/2,(it.top+it.bottom)/2)}
    fun changed(marks:List<CountMark>,x:Float,y:Float,remove:Boolean,radius:Float,aspect:Float=1f):List<CountMark> {
        require(x.isFinite() && y.isFinite() && radius.isFinite() && radius>0f && aspect.isFinite() && aspect>0f)
        fun distance(p:CountMark)=(p.x-x)*(p.x-x)+(p.y-y)*(p.y-y)/(aspect*aspect)
        val nearest=marks.indices.minByOrNull {i ->distance(marks[i])}
        val nearby=nearest?.takeIf {distance(marks[it])<=radius*radius}
        if(remove) return if(nearby==null) marks else marks.filterIndexed {i,_ ->i!=nearby}
        if(nearby!=null || marks.size>=5000) return marks
        return marks+CountMark(x.coerceIn(0f,1f),y.coerceIn(0f,1f),true)
    }
    fun export(context:Context,source:Bitmap,marks:List<CountMark>,label:String,directory:File):List<File> {
        require(marks.all {it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f})
        directory.mkdirs()
        val image=File(directory,"人工复核计数.jpg")
        val bitmap=source.copy(Bitmap.Config.ARGB_8888,true)
        try {
            val canvas=Canvas(bitmap);val radius=(source.width/70f).coerceAtLeast(8f)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=radius;typeface=Typeface.DEFAULT_BOLD;textAlign=Paint.Align.CENTER}
            marks.forEachIndexed {index,p ->
                paint.color=if(p.manual) Color.rgb(230,113,26) else Color.rgb(0,137,106)
                canvas.drawCircle(p.x*source.width,p.y*source.height,radius,paint)
                paint.color=Color.WHITE;paint.textSize=radius*minOf(1f,2.5f/(index+1).toString().length)
                canvas.drawText("${index+1}",p.x*source.width,p.y*source.height+paint.textSize*.35f,paint)
            }
            AtomicFiles.write(image) {check(ImageIo.saveJpeg(bitmap,it,95))}
        } finally {bitmap.recycle()}
        val json=File(directory,"人工复核计数.json");AtomicFiles.text(json,Gson().toJson(mapOf("category" to label,"count" to marks.size,"confirmed" to true,"marks" to marks)))
        val csv=File(directory,"人工复核计数.csv");AtomicFiles.text(csv,"\uFEFF类别,复核数量,人工补点数量\n$label,${marks.size},${marks.count {it.manual}}\n")
        val bundle=File(directory,"人工复核计数.zip")
        AtomicFiles.write(bundle) {part ->ZipOutputStream(part.outputStream()).use {zip ->listOf(image,json,csv).forEach {file ->zip.putNextEntry(ZipEntry(file.name));file.inputStream().use {it.copyTo(zip)};zip.closeEntry()}}}
        return listOf(image,bundle).also {it.forEach {file ->OutputHistoryStore.recordGenerated(context,file,OutputHistoryStore.mimeFor(file))}}
    }
}
