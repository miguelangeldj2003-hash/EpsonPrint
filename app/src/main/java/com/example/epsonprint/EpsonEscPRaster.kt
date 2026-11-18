package com.example.epsonprint

import android.util.Log
import java.io.ByteArrayOutputStream

// Códigos de tamaño según epson-escpr-media.h (A4=0, Letter=1, 4x6=10)
enum class PaperSize(val displayName: String, val widthMm: Int, val heightMm: Int, val msCode: Byte) {
    A4("A4 (210 x 297 mm)", 210, 297, 0x00.toByte()),
    LETTER("Carta / Letter (8.5 x 11 in)", 216, 279, 0x01.toByte()),
    PHOTO_4X6("Foto 4x6 pulg (10x15 cm)", 102, 152, 0x0A.toByte())
}

enum class PrintQuality(val displayName: String, val dpi: Int) {
    DRAFT("Borrador rápido", 180),
    NORMAL("Normal", 360)
}

/**
 * Generador de trabajos ESC/P-R. Formato tomado de lib/epson-escpr-api.c
 * (driver epson-inkjet-printer-escpr, GPL). Ver README: este archivo deriva de ese driver.
 *
 * FORMATOS (importante):
 *  - Comando ESC/P-R:  ESC + clase(1) + longitud de parámetros (4 bytes LITTLE-endian)
 *                      + nombre (4 ASCII) + parámetros
 *  - Comando remoto:   2 letras + longitud (2 bytes LE) + parámetros (el primero suele ser 0x00)
 *  - Los números DENTRO de los parámetros de setj/setq van en BIG-endian.
 */
object EpsonEscPRaster {
    private const val TAG = "EpsonEscPRaster"
    private const val BASE_DPI = 360   // inputResolution 0 = 360x360

    /** ETAPA 1: un trabajo de UNA página sin datos de imagen. */
    fun buildBlankPageJob(
        paperSize: PaperSize = PaperSize.A4,
        quality: PrintQuality = PrintQuality.NORMAL
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // --- Inicio de trabajo (SendStartJob) ---
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x1B, 0x01))                  // ExitPacketMode
        out.write("@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x40))                                    // InitPrinter (ESC @)
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0x00, 0x00))           // EnterRemoteMode
        out.write("REMOTE1".toByteArray(Charsets.US_ASCII))

        out.remote("JS", byteArrayOf(0x00, 0x00, 0x00, 0x00))                 // JS
        out.remote("JH", byteArrayOf(0, 0, 0, 0, 0, 0) + "ESCPRlib".toByteArray(Charsets.US_ASCII)) // JH (14 bytes)
        out.remote("HD", byteArrayOf(0x00, 0x03, 0xFF.toByte()))              // HD
        out.remote("PP", byteArrayOf(0x00, 0x01, 0xFF.toByte()))              // PP (papel auto)
        out.remote("US", byteArrayOf(0x00, 0x05, 0xFF.toByte()))              // QM1 (modo silencioso: ajuste de impresora)
        out.remote("US", byteArrayOf(0x00, 0x08, 0xFF.toByte()))              // QM2

        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))                        // ExitRemoteMode
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x06, 0x00, 0x00))           // ESCPRMode
        out.write("ESCPR".toByteArray(Charsets.US_ASCII))

        // setq: calidad (9 bytes). tipoPapel, calidad, color, brillo, contraste, saturación, plano, paleta(2 BE)
        val q = if (quality == PrintQuality.DRAFT) 0 else 1
        out.escCmd('q', "setq", byteArrayOf(0, q.toByte(), 0, 0, 0, 0, 0, 0, 0))

        // seti: modo de proceso de imagen (10 bytes en cero; último = posición de encuadernación)
        out.escCmd('q', "seti", ByteArray(10))

        // chku (impresoras con versión de datos >= 3). Si la impresora no responde, probar sin este comando.
        out.escCmd('u', "chku", byteArrayOf(0x01, 0x01))

        // setj: tamaño de papel y área imprimible (22 bytes) - valores en píxeles a 360 dpi
        val w = mmToPx(paperSize.widthMm.toFloat(), paperSize)
        val h = mmToPx(paperSize.heightMm.toFloat(), paperSize, height = true)
        val margin = 42 // ~3 mm. PROVISIONAL: el driver lo calcula con datos de la impresora
        val job = ByteArrayOutputStream().apply {
            beInt(w); beInt(h)
            beShort(margin); beShort(margin)            // top, left
            beInt(w - 2 * margin); beInt(h - 2 * margin) // área imprimible
            write(0x00)                                  // inputResolution 360x360
            write(0x00)                                  // printDirection (bidireccional)
        }.toByteArray()
        out.escCmd('j', "setj", job)

        // --- Página ---
        out.write(byteArrayOf(0x1B, 'p'.code.toByte(), 0, 0, 0, 0))           // StartPage "sttp" (sin parámetros)
        out.write("sttp".toByteArray(Charsets.US_ASCII))
        // Datos de imagen: una banda negra de 20 líneas, sin compresión, RGB (3 bytes por píxel).
        // Formato de SendLine: ESC d + (7 + tamaño) LE4 + "dsnd" + xOff(BE2) + yOff(BE2) + comp(0=sin) + tamaño(BE2) + datos
        val lineBytes = (w - 2 * margin) * 3
        val black = ByteArray(lineBytes) // 0x00 = negro
        for (row in 20 until 40) {
            out.write(0x1B); out.write('d'.code)
            val paramSize = 7 + lineBytes
            out.write(paramSize and 0xFF); out.write((paramSize shr 8) and 0xFF)
            out.write((paramSize shr 16) and 0xFF); out.write((paramSize shr 24) and 0xFF)
            out.write("dsnd".toByteArray(Charsets.US_ASCII))
            out.beShort(0)          // xOffset
            out.beShort(row)        // yOffset
            out.write(0x00)         // sin compresión
            out.beShort(lineBytes)  // tamaño de datos
            out.write(black)
        }

        // --- Fin de página y de trabajo (SendEndJob) ---
        out.escCmd('p', "endp", byteArrayOf(0x00))                            // EndPage
        out.escCmd('j', "endj", ByteArray(0))                                 // EndJob
        out.write(byteArrayOf(0x1B, 0x40))                                    // InitPrinter
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0x00, 0x00))           // EnterRemoteMode
        out.write("REMOTE1".toByteArray(Charsets.US_ASCII))
        out.remote("JE", byteArrayOf(0x00))                                   // JE
        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))                        // ExitRemoteMode

        val result = out.toByteArray()
        Log.d(TAG, "[Etapa 1] ${result.size} bytes:\n${getHexDumpPreview(result, 200)}")
        return result
    }

    /** Aún sin imagen: devuelve la página en blanco hasta completar las etapas siguientes. */
    fun buildRasterJob(
        sourceBitmap: android.graphics.Bitmap,
        paperSize: PaperSize = PaperSize.A4,
        quality: PrintQuality = PrintQuality.NORMAL,
        dither: Boolean = true,
        onProgress: ((Float) -> Unit)? = null
    ): ByteArray {
        onProgress?.invoke(1.0f)
        return buildBlankPageJob(paperSize, quality)
    }

    private fun mmToPx(mm: Float, size: PaperSize, height: Boolean = false): Int = when (size) {
        PaperSize.LETTER -> if (height) 3960 else 3060   // 8.5x11 pulg a 360 dpi
        PaperSize.PHOTO_4X6 -> if (height) 2160 else 1440
        else -> Math.round(mm / 25.4f * BASE_DPI)
    }

    // ---- Helpers de formato ----
    private fun ByteArrayOutputStream.escCmd(cls: Char, name: String, params: ByteArray) {
        write(0x1B); write(cls.code)
        write(params.size and 0xFF); write((params.size shr 8) and 0xFF)
        write((params.size shr 16) and 0xFF); write((params.size shr 24) and 0xFF) // longitud 4 bytes LE
        write(name.toByteArray(Charsets.US_ASCII))
        write(params)
    }

    private fun ByteArrayOutputStream.remote(name: String, params: ByteArray) {
        write(name.toByteArray(Charsets.US_ASCII))
        write(params.size and 0xFF); write((params.size shr 8) and 0xFF)           // longitud 2 bytes LE
        write(params)
    }

    private fun ByteArrayOutputStream.beInt(v: Int) {
        write((v shr 24) and 0xFF); write((v shr 16) and 0xFF); write((v shr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.beShort(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }

    fun getHexDumpPreview(data: ByteArray, maxBytes: Int = 200): String {
        val count = minOf(maxBytes, data.size)
        val sb = StringBuilder()
        for (i in 0 until count) {
            sb.append("%02X ".format(data[i]))
            if ((i + 1) % 16 == 0) sb.append("\n")
        }
        return sb.toString().trim()
    }
}
