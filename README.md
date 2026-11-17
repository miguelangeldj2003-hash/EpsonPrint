# Epson USB Print Studio (Android - ESC/P-R Driver)

Aplicación nativa de Android desarrollada en **Kotlin** y **Jetpack Compose (Material 3)** para imprimir documentos (PDFs) e imágenes directamente en impresoras **Epson EcoTank / Inkjet (serie L3310, L3150, L3250, L3110, etc.)** conectadas por cable USB / adaptador OTG.

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
- **Motor de Conversión Ráster (`EpsonEscPRaster.kt`)**:
  - Encabezado Modo Remoto `REMOTE1` (`VI`, `LD`, `MS`).
  - Configuración de gráficos ESC/P2 (`ESC ( G`, `ESC ( U` 360 DPI, `ESC ( r` Canal negro `0x00`, `ESC ( C`).
  - Posicionamiento vertical absoluto `ESC ( V` por fila de píxeles sin caracteres de salto de línea (`CR/LF`).
  - Expulsión de hoja con `Form Feed` (`0x0C`).

---

## 🔍 Problemas Resueltos y Diagnóstico Técnico

### 1. Error de transmisión USB (`bulkTransfer`)
- **Problema**: `c.bulkTransfer(ep, data, offset, length, timeout)` en Android falla cuando el `offset` es mayor a 0 al enviar buffers grandes.
- **Solución**: Se implementó copia de datos a un buffer intermedio de 4 KB (`ByteArray(4096)`), manteniendo `offset = 0` constante en cada transmisión USB.

### 2. Impresión de líneas negras / Hojas infinitas
- **Problema**: La versión inicial enviaba `0x0D 0x0A` (`CR LF`) tras cada fila ráster. La impresora procesaba esto como miles de saltos de texto, alimentando todas las hojas de la bandeja y marcando error de papel.
- **Solución**: Se eliminaron los saltos `CR LF` entre líneas ráster y se agregó posicionamiento vertical absoluto con `ESC ( V`, finalizando con un único `Form Feed` (`0x0C`).

---

## 🚀 Requisitos y Compilación

- **JDK 17** / **Android Studio Ladybug or newer**.
- **Target SDK**: 34 (Android 14) / **Min SDK**: 26 (Android 8.0).
- **Dispositivo**: Celular/Tablet Android con soporte USB Host / OTG y cable OTG conectado a impresora Epson.
