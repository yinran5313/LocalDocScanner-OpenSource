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
    private val pending = mutableMapOf<String, Pair<Context, () -> String>>()
    private val references = mutableMapOf<String, Int>()
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val flushPending = Runnable { synchronized(this) { pending.keys.toList().forEach(::flush) } }
    @Synchronized private fun flush(key: String) {
        pending.remove(key)?.let { (context, encode) ->
            context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE).edit().putString(key, encode()).apply()
        }
    }
    @Synchronized fun acquire(key: String) { references[key] = (references[key] ?: 0) + 1 }
    @Synchronized fun release(key: String) {
        val count = (references[key] ?: 1) - 1
        if (count <= 0) { flush(key); references.remove(key); states.remove(key) } else references[key] = count
    }
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
    @Synchronized fun snapshot(context: Context, request: ToolRequest): Map<String, String> {
        val prefix = key(request) + ":"
        pending.keys.filter { it.startsWith(prefix) }.toList().forEach(::flush)
        return context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            .mapValues { it.value as String }
            .filterKeys { it !in setOf("outcome", "stage", "pendingSaveFiles", "saveStatus", "showInputAfterProcessing", "taskId") }
    }
    @Suppress("UNCHECKED_CAST")
    @Synchronized fun <T> state(context: Context, key: String, type: Type, initial: () -> T): MutableState<T> {
        states[key]?.let { return it as MutableState<T> }
        val prefs = context.getSharedPreferences("tool_drafts_v44", Context.MODE_PRIVATE)
        val value = if (prefs.contains(key)) runCatching { gson.fromJson<T>(prefs.getString(key, null), type) }.getOrElse { initial() } else initial()
        val backing = mutableStateOf(value)
        val holder = object : MutableState<T> {
            override var value: T
                get() = backing.value
                set(value) {
                    backing.value = value
                    synchronized(ToolDrafts) { pending[key] = context.applicationContext to { gson.toJson(backing.value, type) } }
                    handler.removeCallbacks(flushPending); handler.postDelayed(flushPending, 250)
                }
            override fun component1() = value
            override fun component2(): (T) -> Unit = { this.value = it }
        }
        states[key] = holder
        // A submitted task must receive displayed defaults even if the user never touches a control.
        if (!prefs.contains(key)) {
            pending[key] = context.applicationContext to { gson.toJson(backing.value, type) }
            handler.removeCallbacks(flushPending)
            handler.postDelayed(flushPending, 250)
        }
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
        pending.keys.removeAll { it.startsWith(prefix) }; references.keys.removeAll { it.startsWith(prefix) }
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
    val holder = remember(key) { ToolDrafts.state(context, key, object : TypeToken<T>() {}.type, initial) }
    DisposableEffect(key) { ToolDrafts.acquire(key); onDispose { ToolDrafts.release(key) } }
    return holder
}
