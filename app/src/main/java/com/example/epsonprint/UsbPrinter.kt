package com.example.epsonprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import androidx.core.content.ContextCompat

/** Capa USB: detecta, pide permiso, abre y escribe bytes a la impresora. */
class UsbPrinter(private val ctx: Context) {
    private val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var conn: UsbDeviceConnection? = null
    private var outEp: UsbEndpoint? = null
    private var iface: UsbInterface? = null

    companion object { const val ACTION_PERM = "com.example.epsonprint.USB_PERMISSION" }

    fun listDevices(): List<UsbDevice> = mgr.deviceList.values.toList()

    fun findPrinter(): UsbDevice? = listDevices().firstOrNull { d ->
        (d.vendorId == 0x04B8) ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER }
    }

    fun describe(d: UsbDevice): String = buildString {
        append("Dispositivo: ${d.productName ?: "?"} (VID %04X, PID %04X)\n".format(d.vendorId, d.productId))
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            append("  Interfaz $i: clase ${f.interfaceClass}, subclase ${f.interfaceSubclass}, protocolo ${f.interfaceProtocol}, endpoints ${f.endpointCount}\n")
        }
    }

    fun requestPermission(d: UsbDevice, onResult: (Boolean) -> Unit) {
        if (mgr.hasPermission(d)) { onResult(true); return }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {}
                onResult(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_PERM), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ACTION_PERM).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        mgr.requestPermission(d, pi)
    }

    /** Abre la interfaz de impresora con endpoint bulk OUT, priorizando clase 7 (USB_CLASS_PRINTER). */
    fun open(d: UsbDevice): String {
        close()
        var targetIface: UsbInterface? = null
        var targetEp: UsbEndpoint? = null

        // 1. Priorizar interfaz con clase 7 (USB_CLASS_PRINTER)
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    if ((ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) && (ep.direction == UsbConstants.USB_DIR_OUT)) {
                        targetIface = f
                        targetEp = ep
                        break
                    }
                }
            }
            if (targetIface != null) break
        }

        // 2. Si no hay clase 7 y es un dispositivo Epson, buscar cualquier interfaz con Bulk OUT
        if ((targetIface == null) && (d.vendorId == 0x04B8)) {
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    if ((ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) && (ep.direction == UsbConstants.USB_DIR_OUT)) {
                        targetIface = f
                        targetEp = ep
                        break
                    }
                }
                if (targetIface != null) break
            }
        }

        if ((targetIface == null) || (targetEp == null)) {
            return "No se encontró interfaz de impresora con endpoint bulk OUT."
        }

        val c = mgr.openDevice(d) ?: return "No se pudo abrir el dispositivo."
        if (!c.claimInterface(targetIface, true)) {
            c.close()
            return "No se pudo reclamar la interfaz ${targetIface.id}."
        }
        conn = c
        iface = targetIface
        outEp = targetEp
        return "OK: interfaz ${targetIface.id} abierta, endpoint OUT ${targetEp.endpointNumber}."
    }

    val isConnected: Boolean get() = (conn != null) && (outEp != null)

    fun write(data: ByteArray): Int = writeWithProgress(data) { _, _ -> }

    fun writeWithProgress(data: ByteArray, onChunkSent: (sentBytes: Int, totalBytes: Int) -> Unit): Int {
        val c = conn ?: return -1
        val ep = outEp ?: return -1
        var sent = 0
        val chunkSize = 4096
        val chunk = ByteArray(chunkSize)

        while (sent < data.size) {
            val len = minOf(chunkSize, data.size - sent)
            System.arraycopy(data, sent, chunk, 0, len)
            val r = c.bulkTransfer(ep, chunk, len, 5000)
            if (r < 0) return -1
            sent += r
            onChunkSent(sent, data.size)
        }
        return sent
    }

    fun close() {
        iface?.let { conn?.releaseInterface(it) }
        conn?.close(); conn = null; outEp = null; iface = null
    }
}

