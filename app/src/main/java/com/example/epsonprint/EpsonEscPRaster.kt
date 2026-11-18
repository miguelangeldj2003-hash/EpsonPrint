package com.example.epsonprint

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream

enum class PaperSize(val displayName: String, val widthMm: Int, val heightMm: Int, val paperCode: Byte) {
    A4("A4 (210 x 297 mm)", 210, 297, 0.toByte()),
    LETTER("Carta / Letter (8.5 x 11 in)", 216, 279, 1.toByte()),
    PHOTO_4X6("Foto 4x6 pulg (10x15 cm)", 102, 152, 10.toByte())
}

enum class PrintQuality(val displayName: String, val dpi: Int) {
    DRAFT("Borrador rápido (180 DPI)", 180),
    NORMAL("Normal (360 DPI)", 360)
}

/**
 * Generador de secuencias ESC/P-R oficial basado en el driver open-source de Epson:
 * epson-inkjet-printer-escpr (lib/epson-escpr-api.c).
 */
object EpsonEscPRaster {
    private const val TAG = "EpsonEscPRaster"

    /**
     * ETAPA 1: Genera un trabajo de impresión ESC/P-R oficial para UNA PÁGINA EN BLANCO.
     * La impresora debe alimentar 1 hoja limpia, hacer el ciclo de paso de página y expulsarla.
     */
    fun buildBlankPageJob(
        paperSize: PaperSize = PaperSize.A4,
        quality: PrintQuality = PrintQuality.NORMAL
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dpi = quality.dpi
        val targetWidthPixels = ((paperSize.widthMm / 25.4f) * dpi).toInt()
        val targetHeightPixels = ((paperSize.heightMm / 25.4f) * dpi).toInt()

        // 1. ExitPacketMode / @EJL Header
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x1B, 0x01))
        out.write("@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII))

        // 2. InitPrinter (ESC @)
        out.write(byteArrayOf(0x1B, 0x40))

        // 3. EnterRemoteMode (REMOTE1)
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0x00, 0x00))
        out.write("REMOTE1".toByteArray(Charsets.US_ASCII))

        // 4. Comandos Remotos (2 letras + uint16 LE length + payload)
        out.writeRemoteCmd("JS", byteArrayOf(0x00, 0x00))
        out.writeRemoteCmd("JH", "ESCPRlib".toByteArray(Charsets.US_ASCII))
        out.writeRemoteCmd("HD", byteArrayOf(0x00, 0x00))
        out.writeRemoteCmd("PP", byteArrayOf(0x01, 0xFF.toByte())) // Auto feed
        out.writeRemoteCmd("US", byteArrayOf(0x00, 0x00))
        out.writeRemoteCmd("US", byteArrayOf(0x00, 0x00))

        // Exit Remote Mode
        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))

        // 5. Enter ESCPR Mode
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x06, 0x00, 0x00))
        out.write("ESCPR".toByteArray(Charsets.US_ASCII))

        // 6. setq (Quality setup - Números en BIG-endian)
        val setqPayload = ByteArrayOutputStream().apply {
            write(0x00) // Quality (0 = Normal)
            writeShortBE(dpi) // Resolution X (BE)
            writeShortBE(dpi) // Resolution Y (BE)
        }.toByteArray()
        out.writeEscPrCmd('q', "setq", setqPayload)

        // 7. seti (Ink / Media setup - Números en BIG-endian)
        val setiPayload = ByteArrayOutputStream().apply {
            write(0x00) // Media Type (0 = Plain Paper)
            write(paperSize.paperCode.toInt()) // Paper Code (A4 = 0, Letter = 1, 4x6 = 10)
            write(0x00) // Color Mode (0 = Mono)
        }.toByteArray()
        out.writeEscPrCmd('i', "seti", setiPayload)

        // 8. chku (Check Unit / Status)
        out.writeEscPrCmd('u', "chku", byteArrayOf(0x00, 0x00))

        // 9. setj (Job / Area setup - Números en BIG-endian)
        val setjPayload = ByteArrayOutputStream().apply {
            write(paperSize.paperCode.toInt()) // Paper Code
            writeShortBE(targetWidthPixels) // Printable Width (BE)
            writeShortBE(targetHeightPixels) // Printable Height (BE)
            writeShortBE(0) // Top Margin (BE)
            writeShortBE(0) // Left Margin (BE)
        }.toByteArray()
        out.writeEscPrCmd('j', "setj", setjPayload)

        // 10. sttp (Start Page)
        val sttpPayload = ByteArrayOutputStream().apply {
            writeShortBE(1) // Page Number 1 (BE)
            writeShortBE(0) // Reserved/Options
        }.toByteArray()
        out.writeEscPrCmd('p', "sttp", sttpPayload)

        // 11. endp (End Page - EPS_END_PAGE = 0x00)
        out.writeEscPrCmd('p', "endp", byteArrayOf(0x00))

        // 12. endj (End Job)
        out.writeEscPrCmd('j', "endj", byteArrayOf())

        // 13. Teardown (ESC @ + REMOTE1 JE)
        out.write(byteArrayOf(0x1B, 0x40))
        out.write(byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0x00, 0x00))
        out.write("REMOTE1".toByteArray(Charsets.US_ASCII))
        out.writeRemoteCmd("JE", byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))

        val result = out.toByteArray()
        logHexDump("Etapa 1 - Página en blanco", result)
        return result
    }

    /**
     * Mantiene la firma para compatibilidad con el resto de la UI.
     * Redirige al trabajo de página en blanco en la Etapa 1.
     */
    fun buildRasterJob(
        sourceBitmap: Bitmap,
        paperSize: PaperSize = PaperSize.A4,
        quality: PrintQuality = PrintQuality.NORMAL,
        dither: Boolean = true,
        onProgress: ((Float) -> Unit)? = null
    ): ByteArray {
        onProgress?.invoke(1.0f)
        return buildBlankPageJob(paperSize, quality)
    }

    private fun ByteArrayOutputStream.writeEscPrCmd(cmdClass: Char, cmdName: String, params: ByteArray) {
        write(0x1B)
        write(cmdClass.code)
        val payloadLen = 4 + params.size
        writeIntLE(payloadLen)
        write(cmdName.toByteArray(Charsets.US_ASCII))
        write(params)
    }

    private fun ByteArrayOutputStream.writeRemoteCmd(cmd: String, params: ByteArray) {
        write(cmd.toByteArray(Charsets.US_ASCII))
        writeShortLE(params.size)
        write(params)
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortBE(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
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
