package cc.moon.internet.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Share links as a QR matrix, so another phone can import them by camera. */
object QrCode {

    /** [row][col] = true means a dark module. Square, sized by the encoder. */
    fun encode(text: String, size: Int = 512): Array<BooleanArray> {
        require(text.isNotBlank()) { "нечего кодировать" }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        // ZXing pads the requested size; crop back to the real module grid so the drawn cells
        // line up exactly instead of blurring on a fractional cell width.
        val rect = bits.enclosingRectangle ?: error("пустой QR")
        val (left, top, w, h) = listOf(rect[0], rect[1], rect[2], rect[3])
        val cell = w / modulesAcross(bits, left, top, w)
        val n = w / cell
        return Array(n) { y -> BooleanArray(n) { x -> bits[left + x * cell, top + y * cell] } }
            .also { if (h != w) { /* square by construction; nothing to do */ } }
    }

    /** Width of one module in pixels, found from the first run of dark pixels. */
    private fun modulesAcross(bits: com.google.zxing.common.BitMatrix, left: Int, top: Int, w: Int): Int {
        var run = 0
        while (run < w && bits[left + run, top]) run++
        return run.coerceAtLeast(1)
    }
}
