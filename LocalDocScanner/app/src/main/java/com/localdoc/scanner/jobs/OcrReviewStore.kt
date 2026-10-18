package com.localdoc.scanner.jobs

import android.content.Context
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.localdoc.scanner.ocr.OcrTextBox

/** Read one existing medium receipt at a time instead of duplicating all boxes in result.json. */
internal object OcrReviewStore {
    fun load(context:Context,job:String,key:String):ReviewPage {
        require(key.matches(Regex("ocr:[0-9]+:[0-9]+")))
        val receipt=ToolCheckpoints(ToolTasks.dir(context,job),ToolTasks.gson).load(key) ?: error("这页的识别回执或图片已缺失，请先保存现有结果")
        val value=JsonParser.parseString(receipt.payload).asJsonObject
        val boxes:List<OcrTextBox> = value["boxes"]?.let {ToolTasks.gson.fromJson(it,object:TypeToken<List<OcrTextBox>>(){}.type)} ?: emptyList()
        return ReviewPage(value["source"].asString,value["page"].asInt,value["text"].asString,emptyMap(),emptyList(),sourceBoxes=boxes)
    }
}
