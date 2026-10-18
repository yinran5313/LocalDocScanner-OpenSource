package com.localdoc.scanner.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** A bitmap handed to a Compose painter can still be referenced by a recorded
 * frame after recomposition. Let Android release displayed images when their
 * last UI reference is gone; only recycle results that never reached the UI. */
@Composable
internal fun rememberDisplayBitmap(owner:Any?,request:Any?=owner,onError:(Exception)->Unit={},load:suspend ()->Bitmap?):State<Bitmap?> {
    val state=remember(owner) {mutableStateOf<Bitmap?>(null)}
    val error by rememberUpdatedState(onError)
    LaunchedEffect(owner,request) {
        var pending:Bitmap?=null
        try {
            val image=withContext(Dispatchers.IO) {load().also {pending=it}}
            state.value=image;pending=null
        } catch(e:Exception) {if(e is CancellationException) throw e;error(e)}
        finally {pending?.recycle()}
    }
    return state
}
