package com.palazik.vpn.data.codec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter

object QrCodec {
    fun encode(text: String, size: Int = 768): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    fun decodeFromUri(context: Context, uri: Uri): String? {
        val bitmap = loadBitmap(context, uri) ?: return null
        return decodeBitmap(bitmap)
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? = decodeBitmap(bitmap)

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri)
                ) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        }.getOrNull()

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val source = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val luminance = RGBLuminanceSource(source.width, source.height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(luminance))
        return try {
            QRCodeReader().decode(binaryBitmap).text
        } catch (_: ReaderException) {
            null
        }
    }
}
