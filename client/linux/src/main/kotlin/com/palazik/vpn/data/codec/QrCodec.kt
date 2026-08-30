package com.palazik.vpn.data.codec

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ReaderException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object QrCodec {

    fun encode(text: String, size: Int = 768): ImageBitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        return image.toComposeImageBitmap()
    }

    fun decodeFromFile(file: File): String? {
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        return decodeImage(image)
    }

    private fun decodeImage(image: BufferedImage): String? {
        val luminance = BufferedImageLuminanceSource(image)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(luminance))
        return try {
            QRCodeReader().decode(binaryBitmap).text
        } catch (_: ReaderException) {
            null
        }
    }
}
