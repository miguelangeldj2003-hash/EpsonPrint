package com.example.epsonprint

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

enum class PaperSize(val displayName: String, val widthMm: Int, val heightMm: Int, val msCode: Byte) {
    A4("A4 (210 x 297 mm)", 210, 297, 0x02.toByte()),
    LETTER("Carta / Letter (8.5 x 11 in)", 216, 279, 0x01.toByte()),
    PHOTO_4X6("Foto 4x6 pulg (10x15 cm)", 102, 152, 0x05.toByte())
}

enum class PrintQuality(val displayName: String, val dpi: Int) {
    DRAFT("Borrador rápido (180 DPI)", 180),
    NORMAL("Normal (360 DPI)", 360)
}

object EpsonEscPRaster {

    fun buildRasterJob(
        sourceBitmap: Bitmap,
        paperSize: PaperSize = PaperSize.A4,
        quality: PrintQuality = PrintQuality.NORMAL,
        dither: Boolean = true,
        onProgress: ((Float) -> Unit)? = null
    ): ByteArray {
        val dpi = quality.dpi

        // Puntos totales según el tamaño de papel y DPI
        val targetWidthPixels = ((paperSize.widthMm / 25.4f) * dpi).toInt()
        val targetHeightPixels = ((paperSize.heightMm / 25.4f) * dpi).toInt()

        // Escalar manteniendo proporción
        val scaledBitmap = scaleBitmapToFit(sourceBitmap, targetWidthPixels, targetHeightPixels)

        val width = scaledBitmap.width
        val height = scaledBitmap.height
        val bytesPerLine = (width + 7) / 8

        // Convertir a grises (luminancia ponderada)
        val gray = FloatArray(width * height)
        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            gray[i] = (0.299f * r) + (0.587f * g) + (0.114f * b)
        }

        // Matriz de bits (true = negro/tinta, false = blanco/papel)
        val bwBits = BooleanArray(width * height)

        if (dither) {
            // Difusión de error Floyd-Steinberg
            val errMap = gray.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val oldVal = errMap[idx].coerceIn(0f, 255f)
                    val newVal = if (oldVal < 128f) 0f else 255f
                    bwBits[idx] = (newVal == 0f)
                    val err = oldVal - newVal

                    if (x + 1 < width) errMap[idx + 1] += err * (7f / 16f)
                    if (y + 1 < height) {
                        if (x > 0) errMap[idx + width - 1] += err * (3f / 16f)
                        errMap[idx + width] += err * (5f / 16f)
                        if (x + 1 < width) errMap[idx + width + 1] += err * (1f / 16f)
                    }
                }
            }
        } else {
            for (i in gray.indices) {
                bwBits[i] = (gray[i] < 128f)
            }
        }

        val out = ByteArrayOutputStream()

        // === PASO 1: RESET & MODO REMOTO (REMOTE1) ESC/P-R ===
        // Reset inicial
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @

        // Iniciar modo remoto "REMOTE1"
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0x00, 0x00))
        out.write("REMOTE1".toByteArray(Charsets.US_ASCII))

        // Comando VI (Vendor Info)
        out.write("VI".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x02, 0x00, 0x00, 0x00))

        // Comando LD (Tipo de Papel: 0 = Normal)
        out.write("LD".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x01, 0x00, 0x00))

        // Comando MS (Tamaño de Papel)
        out.write("MS".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x01, 0x00, paperSize.msCode))

        // Salir de Modo Remoto y entrar a modo Ráster
        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))

        // === PASO 2: CONFIGURACIÓN DE GRÁFICOS ESC/P2 ===
        // Activar modo gráficos ESC ( G
        out.write(byteArrayOf(0x1B, 0x28, 0x47, 0x01, 0x00, 0x01))

        // Unidad base DPI ESC ( U (3600 / dpi)
        val densityByte = (3600 / dpi).toByte()
        out.write(byteArrayOf(0x1B, 0x28, 0x55, 0x01, 0x00, densityByte))

        // Selección de color negro ESC ( r
        out.write(byteArrayOf(0x1B, 0x28, 0x72, 0x01, 0x00, 0x00))

        // Longitud de página ESC ( C
        val pageUnits = targetHeightPixels
        val cLow = (pageUnits and 0xFF).toByte()
        val cHigh = ((pageUnits shr 8) and 0xFF).toByte()
        out.write(byteArrayOf(0x1B, 0x28, 0x43, 0x02, 0x00, cLow, cHigh))

        // === PASO 3: ENVÍO DE LÍNEAS RÁSTER SIN CR/LF ===
        val nL = (bytesPerLine and 0xFF).toByte()
        val nH = ((bytesPerLine shr 8) and 0xFF).toByte()

        for (y in 0 until height) {
            val lineBytes = ByteArray(bytesPerLine)
            var hasDot = false
            for (x in 0 until width) {
                if (bwBits[y * width + x]) {
                    val byteIdx = x / 8
                    val bitIdx = 7 - (x % 8)
                    lineBytes[byteIdx] = (lineBytes[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                    hasDot = true
                }
            }

            if (hasDot) {
                // Posición vertical absoluta para la línea y (ESC ( V)
                val vLow = (y and 0xFF).toByte()
                val vHigh = ((y shr 8) and 0xFF).toByte()
                out.write(byteArrayOf(0x1B, 0x28, 0x56, 0x02, 0x00, vLow, vHigh))

                // Enviar línea ráster ESC .
                out.write(
                    byteArrayOf(
                        0x1B, 0x2E, // ESC .
                        0x00,       // Compresión (0 = sin comprimir)
                        densityByte,// Densidad V
                        densityByte,// Densidad H
                        0x01, 0x00, // 1 línea
                        nL, nH      // Bytes por línea
                    )
                )
                out.write(lineBytes)
            }

            if (y % 20 == 0) {
                onProgress?.invoke((y.toFloat() / height.toFloat()))
            }
        }

        // === PASO 4: EXPULSIÓN DE PÁGINA Y RESET ===
        out.write(0x0C) // Form Feed (FF - Expulsar 1 sola hoja)
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @ (Reset final)

        return out.toByteArray()
    }

    private fun scaleBitmapToFit(src: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val w = src.width
        val h = src.height
        val scale = minOf(maxWidth.toFloat() / w, maxHeight.toFloat() / h, 1.0f)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        return if (newW == w && newH == h) {
            src
        } else {
            Bitmap.createScaledBitmap(src, newW, newH, true)
        }
    }
}
