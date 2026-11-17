package com.example.epsonprint

import android.util.Log
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
    private const val TAG = "EpsonEscPRaster"

    /**
     * ETAPA 1: Genera un trabajo ESC/P-R oficial con UNA SOLA PÁGINA EN BLANCO.
     * La impresora debe tomar 1 sola hoja, pasarla limpia sin imprimir y expulsarla.
     */
    fun buildBlankPageJob(
        paperSize: PaperSize = PaperSize.A4,
        quality: PrintQuality = PrintQuality.NORMAL
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. ExitPacketMode / EJL Header
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x1B, 0x01))
        out.write("@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII))

        // 2. InitPrinter (ESC @)
        out.write(byteArrayOf(0x1B, 0x40))

        // 3. EnterRemoteMode (REMOTE1)
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0x00, 0x00))
        out.write("REMOTE1".toByteArray(Charsets.US_ASCII))

        // 4. Comandos Remotos (2 letras + uint16 LE length + payload)
        // JS (Job Setup)
        out.writeRemoteCmd("JS", byteArrayOf(0x00, 0x00))

        // JH (Job Header - "ESCPRlib")
        out.writeRemoteCmd("JH", "ESCPRlib".toByteArray(Charsets.US_ASCII))

        // PP (Paper Path - Auto 0x01 0xFF)
        out.writeRemoteCmd("PP", byteArrayOf(0x01, 0xFF.toByte()))

        // 5. Exit Remote Mode & Enter ESCPR Mode
        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00)) // Exit remote
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x06, 0x00, 0x00)) // Enter ESCPR mode
        out.write("ESCPR".toByteArray(Charsets.US_ASCII))

        // 6. Start Page ("sttp")
        val dpi = quality.dpi
        val targetWidthPixels = ((paperSize.widthMm / 25.4f) * dpi).toInt()
        val targetHeightPixels = ((paperSize.heightMm / 25.4f) * dpi).toInt()

        val sttpPayload = ByteArrayOutputStream()
        sttpPayload.write(0x00) // Quality: Normal
        sttpPayload.write(paperSize.msCode.toInt()) // Paper Size (A4 = 0x02)
        sttpPayload.write(0x00) // Paper Type: Plain
        sttpPayload.writeShortLE(targetWidthPixels)
        sttpPayload.writeShortLE(targetHeightPixels)
        sttpPayload.writeShortLE(dpi)
        sttpPayload.writeShortLE(dpi)

        val sttpBytes = sttpPayload.toByteArray()
        out.write(byteArrayOf(0x1B, 'p'.code.toByte()))
        out.writeShortLE(sttpBytes.size + 4) // uint16 LE payload len
        out.write("sttp".toByteArray(Charsets.US_ASCII))
        out.write(sttpBytes)

        // 7. Sin líneas de imagen (Página en blanco)

        // 8. End Page ("endp")
        out.write(byteArrayOf(0x1B, 'p'.code.toByte()))
        out.writeShortLE(5) // length of "endp" + 0x00 = 5
        out.write("endp".toByteArray(Charsets.US_ASCII))
        out.write(0x00)

        // 9. End Job ("endj")
        out.write(byteArrayOf(0x1B, 'j'.code.toByte()))
        out.writeShortLE(4) // length of "endj" = 4
        out.write("endj".toByteArray(Charsets.US_ASCII))

        val result = out.toByteArray()
        logHexDump("Etapa 1 - Página en blanco", result)
        return result
    }

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

    private fun ByteArrayOutputStream.writeRemoteCmd(cmd: String, data: ByteArray) {
        write(cmd.toByteArray(Charsets.US_ASCII))
        writeShortLE(data.size)
        write(data)
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun logHexDump(tag: String, data: ByteArray) {
        val maxBytes = minOf(200, data.size)
        val sb = StringBuilder()
        for (i in 0 until maxBytes) {
            sb.append("%02X ".format(data[i]))
        }
        val hexString = sb.toString().trim()
        Log.d(TAG, "[$tag] Primeros $maxBytes bytes (${data.size} bytes totales):\n$hexString")
    }

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
