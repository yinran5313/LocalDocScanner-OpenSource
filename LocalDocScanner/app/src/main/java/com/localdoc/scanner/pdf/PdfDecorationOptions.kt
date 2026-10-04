package com.localdoc.scanner.pdf

data class PdfDecorationOptions(
    val numberStart: Int = 1,
    val numberPrefix: String = "",
    val numberSuffix: String = " / {total}",
    val numberPosition: Int = 5,
    val watermarkAngle: Float = 32f
) {
    fun numberLabel(page: Int, total: Int): String {
        require(numberStart in 1..999999 && numberPosition in 0..5 && numberPrefix.length <= 40 && numberSuffix.length <= 40)
        return numberPrefix + (numberStart + page) + numberSuffix.replace("{total}", total.toString())
    }
    fun validate() {
        numberLabel(0, 1)
        require(watermarkAngle.isFinite() && watermarkAngle in -90f..90f)
    }
}
