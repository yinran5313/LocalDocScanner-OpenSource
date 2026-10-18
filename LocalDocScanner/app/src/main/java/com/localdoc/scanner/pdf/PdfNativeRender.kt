package com.localdoc.scanner.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.RenderParams
import android.os.Build

/** The old display overload omits markup. Request annotations explicitly on API35+. */
internal object PdfNativeRender {
    fun page(page:PdfRenderer.Page,bitmap:Bitmap) {
        if(Build.VERSION.SDK_INT>=35) page.render(bitmap,null,null,
            RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
                .setRenderFlags(RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS).build())
        else page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }
}
