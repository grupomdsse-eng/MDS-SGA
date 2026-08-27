# Estructura del proyecto SGA MDS 1.3.0

```text
SGA_MDS_GitHub_Ready_v3/
├── .github/
│   └── workflows/
│       └── build-apk.yml
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/grupomds/sga/
│       │   │   ├── MainActivity.kt
│       │   │   ├── SgaApplication.kt
│       │   │   ├── data/
│       │   │   │   ├── Daos.kt
│       │   │   │   ├── Entities.kt
│       │   │   │   ├── GoogleSheetStockSource.kt
│       │   │   │   ├── SgaDatabase.kt
│       │   │   │   └── SgaRepository.kt
│       │   │   ├── ocr/
│       │   │   │   └── DeliveryNoteParser.kt
│       │   │   └── ui/
│       │   │       ├── BarcodeCamera.kt
│       │   │       ├── SgaApp.kt
│       │   │       ├── SgaViewModel.kt
│       │   │       └── theme/
│       │   └── res/
│       └── test/
├── gradle/
├── docs/
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

## Datos persistentes Room

- `products`: CÓDIGO/Referencia, EAN, descripción, stock SGA, stock base Google Sheets y ubicación.
- `delivery_notes`: cabecera e estado del albarán.
- `delivery_lines`: cantidades esperadas y picadas.
- `scan_logs`: trazabilidad de lecturas de producto.
- `transport_labels`: etiquetas de transporte asociadas al albarán.
- `stock_movements`: ajustes y salidas de stock.

La base de datos está en versión 2 e incluye migración desde la versión 1 sin borrar datos.
