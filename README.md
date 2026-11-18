# Epson USB Print Studio (Android - ESC/P-R Driver)

Aplicación nativa de Android desarrollada en **Kotlin** y **Jetpack Compose (Material 3)** para imprimir documentos (PDFs) e imágenes directamente en impresoras **Epson EcoTank / Inkjet (L3210, L3310, L3150, L3250, L3110, etc.)** conectadas por cable USB / adaptador OTG.

> ⚖️ **Aviso de Licencia y Origen del Código**: La arquitectura de secuencias binarias y comandos del protocolo ESC/P-R en `EpsonEscPRaster.kt` deriva y sigue las especificaciones del driver oficial open-source de Epson `epson-inkjet-printer-escpr` (licencia GPL v2, `lib/epson-escpr-api.c`).

---

## 🛠️ Arquitectura del Proyecto

- **UI / Frontend**: Jetpack Compose con Material 3 (`MainActivity.kt`).
  - Detección visual del estado USB con indicador LED.
  - Selección de archivos (PDFs e imágenes) con vista previa de miniatura.
  - Ajustes de impresión: Tamaño de papel (A4, Carta, Foto 4x6), Tramado de fotos (Floyd-Steinberg Dithering), Número de copias.
  - Diálogo de progreso en tiempo real con barra animada e historial de consola.
- **Capa USB (`UsbPrinter.kt`)**:
  - Detección e identificación de dispositivos de clase impresora (`USB_CLASS_PRINTER` - Clase 7) o VID Epson (`0x04B8`).
  - Solicitud de permisos con `PendingIntent.FLAG_MUTABLE` y `RECEIVER_NOT_EXPORTED` para Android 14 (API 34).
  - Transmisión en bloques de 4 KB con offset `0` para resolver el error de JNI `bulkTransfer` en Android.
- **Motor de Conversión Ráster ESC/P-R (`EpsonEscPRaster.kt`)**:
  - `ExitPacketMode` (`@EJL 1284.4\n@EJL     \n`) -> `ESC @` -> `REMOTE1` (`JS`, `JH`, `HD`, `PP`, `US`) -> Exit Remote.
  - Secuencia ESCPR: `setq` (Little/Big-endian según spec), `seti` (Media/PaperCode: A4=0, Carta=1, 4x6=10), `chku`, `setj`.
  - Páginas: `sttp` -> datos `dsnd` (o página en blanco) -> `endp` (`0x00`) -> `endj` -> Teardown (`ESC @` + `REMOTE1 JE`).

---

## 🚀 Requisitos y Compilación

- **JDK 17** / **Android Studio Ladybug or newer**.
- **Target SDK**: 34 (Android 14) / **Min SDK**: 26 (Android 8.0).
- **Dispositivo**: Celular/Tablet Android con soporte USB Host / OTG y cable OTG conectado a impresora Epson.
