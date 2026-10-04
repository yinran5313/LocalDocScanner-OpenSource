package com.localdoc.scanner.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.localdoc.scanner.ui.ToolRequest
import java.io.File
import java.lang.reflect.Type

/** Small configuration/receipt journals; bitmaps and passwords are never stored here. */
object ToolDrafts {
    private val states = mutableMapOf<String, MutableState<*>>()
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(File::class.java, object : JsonSerializer<File>, JsonDeserializer<File> {
            override fun serialize(src: File, type: Type, context: JsonSerializationContext) = JsonPrimitive(src.absolutePath)
            override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext) = File(json.asString)
        })
        .registerTypeHierarchyAdapter(Uri::class.java, object : JsonSerializer<Uri>, JsonDeserializer<Uri> {
            override fun serialize(src: Uri, type: Type, context: JsonSerializationContext) = JsonPrimitive(src.toString())
            override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext) = Uri.parse(json.asString)
        }).create()
    fun key(request: ToolRequest): String = request.tool.id + ":" + request.files.joinToString("|") { it.absolutePath }
    @Suppress("UNCHECKED_CAST")
    @Synchronized fun <T> state(context: Context, key: String, type: Type, initial: () -> T): MutableState<T> {
        states[key]?.let { return it as MutableState<T> }
        val prefs = context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE)
        val value = if (prefs.contains(key)) runCatching { gson.fromJson<T>(prefs.getString(key, null), type) }.getOrElse { initial() } else initial()
        val backing = mutableStateOf(value)
        val holder = object : MutableState<T> {
            override var value: T
                get() = backing.value
                set(value) { backing.value = value; prefs.edit().putString(key, gson.toJson(value, type)).apply() }
            override fun component1() = value
            override fun component2(): (T) -> Unit = { this.value = it }
        }
        states[key] = holder
        return holder
    }
    fun saveRequest(context: Context, request: ToolRequest?) {
        context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE).edit()
            .putString("active_request", request?.let { gson.toJson(it) }).apply()
    }
    @Synchronized fun forget(context: Context, request: ToolRequest) {
        val prefix = key(request) + ":"
        val prefs = context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
        states.keys.removeAll { it.startsWith(prefix) }
    }
    fun restoreRequest(context: Context): ToolRequest? = runCatching {
        gson.fromJson(context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE).getString("active_request", null), ToolRequest::class.java)
            ?.takeIf { it.files.isNotEmpty() && it.files.all(File::isFile) }
    }.getOrNull()
}

@Composable
internal inline fun <reified T> rememberToolState(request: ToolRequest, field: String, noinline initial: () -> T): MutableState<T> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val key = ToolDrafts.key(request) + ":" + field
    return remember(key) { ToolDrafts.state(context, key, object : TypeToken<T>() {}.type, initial) }
}
