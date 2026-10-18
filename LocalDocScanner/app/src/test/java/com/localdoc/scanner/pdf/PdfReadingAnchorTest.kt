package com.localdoc.scanner.pdf

import org.junit.Assert.*
import org.junit.Test

class PdfReadingAnchorTest {
    @Test fun closingSearchAtPageTwentyNeverSelectsPageNineteen() {
        assertEquals(19 to 0, PdfViewportMath.resizedAnchor(19,0,680,820,true,46))
        assertEquals(19 to 0, PdfViewportMath.resizedAnchor(18,679,680,820,true,46))
        assertEquals(45 to 0, PdfViewportMath.resizedAnchor(45,679,680,820,true,46))
    }
    @Test fun continuousReadingRetainsItsFractionInsteadOfSnapping() {
        assertEquals(7 to 300, PdfViewportMath.resizedAnchor(7,300,1000,1000,false,46))
        assertEquals(7 to 150, PdfViewportMath.resizedAnchor(7,300,1000,500,false,46))
    }
}
