package com.example.epsonprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val isPdf: Boolean,
    val pageCount: Int = 1,
    val previewBitmap: Bitmap? = null,
    val sizeText: String = ""
)

class MainActivity : ComponentActivity() {
    private lateinit var usb: UsbPrinter

    private val selectedFileState = mutableStateOf<SelectedFile?>(null)
    private val logState = mutableStateListOf<String>()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { processSelectedUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = UsbPrinter(this)
        log("Bienvenido a Epson USB Print Studio")
        log("Conecta tu impresora Epson (encendida) por cable USB/OTG.")

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0066B2),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD6E4FF),
                    onPrimaryContainer = Color(0xFF001C3B),
                    secondary = Color(0xFF00897B),
                    surfaceVariant = Color(0xFFF0F4F8)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF8F9FA)
                ) {
                    EpsonPrintMainScreen(
                        usb = usb,
                        selectedFile = selectedFileState.value,
                        logList = logState,
                        onPickFile = { picker.launch(arrayOf("image/*", "application/pdf")) },
                        onLog = { message -> log(message) },
                        onClearLogs = { logState.clear() }
                    )
                }
            }
        }
    }

    private fun log(message: String) {
        logState.add(message)
    }

    private fun processSelectedUri(uri: Uri) {
        try {
            val name = getFileName(this, uri) ?: "Archivo seleccionado"
            val type = contentResolver.getType(uri) ?: ""
            val isPdf = type.contains("pdf", ignoreCase = true) || (uri.path?.endsWith(".pdf", ignoreCase = true) == true)

            if (isPdf) {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    pfd.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            val pages = renderer.pageCount
                            var firstPageBitmap: Bitmap? = null
                            if (pages > 0) {
                                val page = renderer.openPage(0)
                                val bmp = Bitmap.createBitmap(page.width / 2, page.height / 2, Bitmap.Config.ARGB_8888)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.close()
                                firstPageBitmap = bmp
                            }
                            selectedFileState.value = SelectedFile(
                                uri = uri,
                                name = name,
                                isPdf = true,
                                pageCount = pages,
                                previewBitmap = firstPageBitmap,
                                sizeText = "$pages página(s) PDF"
                            )
                            log("Cargado PDF: $name ($pages página(s))")
                        }
                    }
                }
            } else {
                val inputStream = contentResolver.openInputStream(uri)
                val fullBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (fullBitmap != null) {
                    val preview = Bitmap.createScaledBitmap(
                        fullBitmap,
                        (fullBitmap.width / 4).coerceAtLeast(100),
                        (fullBitmap.height / 4).coerceAtLeast(100),
                        true
                    )
                    selectedFileState.value = SelectedFile(
                        uri = uri,
                        name = name,
                        isPdf = false,
                        previewBitmap = preview,
                        sizeText = "${fullBitmap.width} x ${fullBitmap.height} px"
                    )
                    log("Cargada Imagen: $name (${fullBitmap.width}x${fullBitmap.height}px)")
                }
            }
        } catch (e: Exception) {
            log("Error procesando archivo: ${e.localizedMessage}")
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    override fun onDestroy() {
        usb.close()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpsonPrintMainScreen(
    usb: UsbPrinter,
    selectedFile: SelectedFile?,
    logList: List<String>,
    onPickFile: () -> Unit,
    onLog: (String) -> Unit,
    onClearLogs: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isConnected by remember { mutableStateOf(false) }
    var connectedDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var connectionDetails by remember { mutableStateOf("No conectado") }

    var paperSize by remember { mutableStateOf(PaperSize.A4) }
    var printQuality by remember { mutableStateOf(PrintQuality.NORMAL) }
    var enableDithering by remember { mutableStateOf(true) }
    var numCopies by remember { mutableIntStateOf(1) }

    var isPrinting by remember { mutableStateOf(false) }
    var printProgress by remember { mutableFloatStateOf(0f) }
    var printStatusText by remember { mutableStateOf("") }

    val logListState = rememberLazyListState()

    // Auto-scroll logs
    LaunchedEffect(logList.size) {
        if (logList.isNotEmpty()) {
            logListState.animateScrollToItem(logList.size - 1)
        }
    }

    fun detectPrinter() {
        val all = usb.listDevices()
        onLog("Dispositivos USB detectados: ${all.size}")
        all.forEach { onLog(usb.describe(it)) }

        val printerDev = usb.findPrinter()
        if (printerDev == null) {
            isConnected = false
            connectedDevice = null
            connectionDetails = "No se encontró ninguna impresora USB."
            onLog("⚠️ No se encontró impresora Epson. Revisa el cable OTG o si la impresora está encendida.")
            return
        }

        connectedDevice = printerDev
        usb.requestPermission(printerDev) { granted ->
            if (!granted) {
                isConnected = false
                connectionDetails = "Permiso USB denegado"
                onLog("❌ Permiso USB denegado por el usuario.")
            } else {
                val openMsg = usb.open(printerDev)
                onLog("🔌 Status USB: $openMsg")
                if (openMsg.startsWith("OK")) {
                    isConnected = true
                    connectionDetails = "${printerDev.productName ?: "Epson Printer"} (VID ${"%04X".format(printerDev.vendorId)})"
                } else {
                    isConnected = false
                    connectionDetails = openMsg
                }
            }
        }
    }

    fun testWriteEscAt() {
        if (!isConnected || connectedDevice == null) {
            onLog("⚠️ Primero debes detectar y conectar la impresora.")
            return
        }
        val sent = usb.write(byteArrayOf(0x1B, 0x40))
        if (sent >= 0) {
            onLog("✅ Test ESC @ enviado con éxito ($sent bytes escritos).")
        } else {
            onLog("❌ Error al enviar datos ESC @ a la impresora.")
        }
    }

    fun testStage1BlankPage() {
        if (!isConnected || connectedDevice == null) {
            onLog("⚠️ Primero debes detectar y conectar la impresora.")
            return
        }
        val blankJob = EpsonEscPRaster.buildBlankPageJob(paperSize, printQuality)
        val hexDump = EpsonEscPRaster.getHexDumpPreview(blankJob, 200)
        onLog("📄 [Etapa 1] Enviando página en blanco ESC/P-R (${blankJob.size} bytes)...")
        onLog("🔍 Hex Dump (primeros 200 bytes):\n$hexDump")

        isPrinting = true
        printProgress = 0.5f
        printStatusText = "Enviando página en blanco ESC/P-R (Etapa 1)..."

        scope.launch(Dispatchers.IO) {
            val sent = usb.writeWithProgress(blankJob) { current, total ->
                scope.launch(Dispatchers.Main) {
                    printProgress = current.toFloat() / total.toFloat()
                }
            }
            withContext(Dispatchers.Main) {
                isPrinting = false
                if (sent >= 0) {
                    onLog("✅ [Etapa 1] Enviada ($sent bytes). La impresora debe tomar 1 sola hoja limpia y expulsarla.")
                } else {
                    onLog("❌ [Etapa 1] Error al enviar a la impresora.")
                }
            }
        }
    }

    fun startPrintingProcess(context: Context) {
        val file = selectedFile ?: return
        if (!isConnected) {
            onLog("⚠️ Conecta la impresora antes de imprimir.")
            return
        }

        isPrinting = true
        printProgress = 0f
        printStatusText = "Iniciando trabajo de impresión..."

        scope.launch(Dispatchers.IO) {
            try {
                val bitmapsToPrint = mutableListOf<Bitmap>()

                withContext(Dispatchers.Main) {
                    printStatusText = "Cargando documento/imagen..."
                    printProgress = 0.1f
                }

                if (file.isPdf) {
                    val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
                    if (pfd != null) {
                        pfd.use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                for (p in 0 until renderer.pageCount) {
                                    val page = renderer.openPage(p)
                                    val renderDpi = printQuality.dpi
                                    val targetW = ((paperSize.widthMm / 25.4f) * renderDpi).toInt()
                                    val targetH = ((paperSize.heightMm / 25.4f) * renderDpi).toInt()
                                    val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    page.close()
                                    bitmapsToPrint.add(bmp)
                                }
                            }
                        }
                    }
                } else {
                    val inputStream = context.contentResolver.openInputStream(file.uri)
                    val loadedBmp = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (loadedBmp != null) {
                        bitmapsToPrint.add(loadedBmp)
                    }
                }

                if (bitmapsToPrint.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onLog("❌ Error: No se pudo obtener bitmap para imprimir.")
                        isPrinting = false
                    }
                    return@launch
                }

                val totalPages = bitmapsToPrint.size
                val totalTasks = totalPages * numCopies
                var globalTaskIndex = 0

                for (copy in 1..numCopies) {
                    for ((index, bmp) in bitmapsToPrint.withIndex()) {
                        val taskIdx = globalTaskIndex
                        withContext(Dispatchers.Main) {
                            printStatusText = if (numCopies > 1) {
                                "Copia $copy de $numCopies — Procesando Pág ${index + 1}/$totalPages..."
                            } else {
                                "Procesando Pág ${index + 1}/$totalPages..."
                            }
                            printProgress = (taskIdx.toFloat() / totalTasks.toFloat()).coerceIn(0f, 1f)
                        }

                        val rasterBytes = EpsonEscPRaster.buildRasterJob(
                            sourceBitmap = bmp,
                            paperSize = paperSize,
                            quality = printQuality,
                            dither = enableDithering,
                            onProgress = { rasterProg ->
                                scope.launch(Dispatchers.Main) {
                                    val prog = (taskIdx + (0.5f * rasterProg)) / totalTasks
                                    printProgress = prog.coerceIn(0f, 1f)
                                }
                            }
                        )

                        withContext(Dispatchers.Main) {
                            printStatusText = if (numCopies > 1) {
                                "Copia $copy de $numCopies — Enviando datos Pág ${index + 1}/$totalPages..."
                            } else {
                                "Enviando datos USB a la impresora (${rasterBytes.size} bytes)..."
                            }
                        }

                        val bytesSent = usb.writeWithProgress(rasterBytes) { sent, total ->
                            scope.launch(Dispatchers.Main) {
                                val chunkProg = sent.toFloat() / total.toFloat()
                                val prog = (taskIdx + 0.5f + (0.5f * chunkProg)) / totalTasks
                                printProgress = prog.coerceIn(0f, 1f)
                            }
                        }

                        if (bytesSent < 0) {
                            withContext(Dispatchers.Main) {
                                onLog("❌ Error en la transmisión USB durante la copia $copy, página ${index + 1}.")
                            }
                            isPrinting = false
                            return@launch
                        }

                        globalTaskIndex++
                    }
                }

                withContext(Dispatchers.Main) {
                    printProgress = 1.0f
                    printStatusText = "¡Impresión finalizada con éxito!"
                    onLog("🎉 $numCopies copia(s) enviada(s) exitosamente a la impresora.")
                    isPrinting = false
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onLog("❌ Excepción durante la impresión: ${e.localizedMessage}")
                    isPrinting = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Epson USB Print Studio",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card de Estado de Conexión USB
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isConnected) "Impresora Conectada" else "Impresora Desconectada",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = if (isConnected) Color(0xFF1B5E20) else Color(0xFFC62828)
                            )
                            Text(
                                text = connectionDetails,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                    Button(
                        onClick = { detectPrinter() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Detectar", fontSize = 13.sp)
                    }
                }
            }

            // Acciones Rápidas (Botones)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { testWriteEscAt() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test (ESC @)", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { onPickFile() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Elegir Archivo", fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = { testStage1BlankPage() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("PROBAR ETAPA 1 (Página en blanco ESC/P-R)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Tarjeta de Previsualización y Configuración de Impresión
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Documento Seleccionado",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedFile == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .border(1.dp, Color.LightGray, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Ningún archivo seleccionado. Pulsa 'Elegir Archivo'",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedFile.previewBitmap != null) {
                                Image(
                                    bitmap = selectedFile.previewBitmap.asImageBitmap(),
                                    contentDescription = "Vista previa",
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = if (selectedFile.isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedFile.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = selectedFile.sizeText,
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Configuración de Impresión
                        Text(
                            text = "Ajustes de Impresión",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Selector de Papel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tamaño de Papel:", fontSize = 13.sp)
                            var paperExpanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(
                                    onClick = { paperExpanded = true },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(paperSize.displayName, fontSize = 12.sp)
                                }
                                DropdownMenu(
                                    expanded = paperExpanded,
                                    onDismissRequest = { paperExpanded = false }
                                ) {
                                    PaperSize.entries.forEach { size ->
                                        DropdownMenuItem(
                                            text = { Text(size.displayName) },
                                            onClick = {
                                                paperSize = size
                                                paperExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Toggle de Tramado (Dithering)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tramado de fotos (Dithering):", fontSize = 13.sp)
                            Switch(
                                checked = enableDithering,
                                onCheckedChange = { enableDithering = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Selector de Número de Copias
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Número de Copias:", fontSize = 13.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedIconButton(
                                    onClick = { if (numCopies > 1) numCopies-- },
                                    enabled = numCopies > 1,
                                    modifier = Modifier.size(36.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Menos", modifier = Modifier.size(16.dp))
                                }

                                Text(
                                    text = numCopies.toString(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )

                                OutlinedIconButton(
                                    onClick = { if (numCopies < 99) numCopies++ },
                                    enabled = numCopies < 99,
                                    modifier = Modifier.size(36.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Más", modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Botón de Impresión Principal
                        val localContext = androidx.compose.ui.platform.LocalContext.current
                        Button(
                            onClick = { startPrintingProcess(localContext) },
                            enabled = isConnected && !isPrinting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "IMPRIMIR AHORA",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            // Consola de Logs
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Registro de Consola",
                            color = Color(0xFF81C784),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { onClearLogs() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Limpiar", tint = Color.Gray)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(logList) { logLine ->
                            Text(
                                text = logLine,
                                color = Color(0xFFE0E0E0),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Diálogo de Progreso de Impresión
    if (isPrinting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = {
                Text(
                    text = "Imprimiendo...",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { printProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = printStatusText,
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )
                }
            }
        )
    }
}
