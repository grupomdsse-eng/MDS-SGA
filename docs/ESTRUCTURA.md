# Estructura del proyecto SGA MDS 2.0.0

```text
SGA_MDS_GitHub_Ready_v4/
├── .github/
│   └── workflows/
│       └── build-apk.yml
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/grupomds/sga/
│       │   │   ├── AppCrashReporter.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── SgaApplication.kt
│       │   │   ├── data/
│       │   │   │   ├── Daos.kt
│       │   │   │   ├── Entities.kt
│       │   │   │   ├── GoogleSheetStockSource.kt
│       │   │   │   ├── SgaDatabase.kt
│       │   │   │   └── SgaRepository.kt
│       │   │   ├── ocr/
│       │   │   │   ├── DeliveryNoteParser.kt
│       │   │   │   └── SafeOcrImageDecoder.kt
│       │   │   └── ui/
│       │   │       ├── BarcodeCamera.kt
│       │   │       ├── DocumentCamera.kt
│       │   │       ├── SgaApp.kt
│       │   │       ├── SgaViewModel.kt
│       │   │       └── theme/Theme.kt
│       │   └── res/
│       └── test/
├── gradle/
├── docs/
│   ├── ESTRUCTURA.md
│   └── ANALISIS_MERCADO_WMS.md
├── sample/
│   └── productos.csv
├── .editorconfig
├── .gitignore
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── README.md
├── README_PRIMERO.txt
├── SUBIR_A_GITHUB.bat
├── COMPROBAR_PROYECTO.bat
└── settings.gradle.kts
```

## Persistencia Room

- `products`: código, EAN, descripción, stock SGA, stock base Sheets y ubicación.
- `delivery_notes`: cabecera y estado de salida.
- `delivery_lines`: cantidades previstas y picadas.
- `scan_logs`: lecturas aceptadas/rechazadas.
- `transport_labels`: etiquetas de expedición.
- `stock_movements`: auditoría de variaciones de stock.

La base de datos continúa en versión 2, por lo que 2.0.0 puede instalarse sobre 1.3.x sin una migración destructiva.
