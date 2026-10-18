package com.localdoc.scanner.util

import android.graphics.Bitmap
import java.util.IdentityHashMap

/** A departing screen retires bitmaps; running background operations release them later. */
internal class BitmapOwnership {
    private data class Entry(var owner: Boolean = true, var readers: Int = 0)
    private val entries = IdentityHashMap<Bitmap, Entry>()
    private val displayed = java.util.WeakHashMap<Bitmap, Boolean>()
    private var disposed = false
    @Synchronized fun display(bitmap: Bitmap) { displayed[bitmap] = true }
    private fun free(bitmap:Bitmap) { if(displayed[bitmap]!=true) bitmap.recycle();entries.remove(bitmap) }
    @Synchronized fun adopt(bitmap: Bitmap): Bitmap {
        val entry = entries.getOrPut(bitmap) { Entry(owner = !disposed) }
        if (!entry.owner && entry.readers == 0) free(bitmap)
        return bitmap
    }
    @Synchronized fun acquire(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        entries.getOrPut(bitmap) { Entry(owner = !disposed) }.readers++
        return true
    }
    @Synchronized fun release(bitmap: Bitmap) {
        val entry = entries[bitmap] ?: return
        entry.readers--
        check(entry.readers >= 0)
        if (!entry.owner && entry.readers == 0) free(bitmap)
    }
    @Synchronized fun retire(bitmap: Bitmap?) {
        if (bitmap == null) return
        val entry = entries[bitmap] ?: return
        entry.owner = false
        if (entry.readers == 0) free(bitmap)
    }
    @Synchronized fun dispose() {
        disposed = true
        entries.keys.toList().forEach(::retire)
    }
}
