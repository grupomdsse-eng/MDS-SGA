# SGA MDS — Android 2.0.0

Aplicación Android nativa para gestión operativa de almacén: maestro de productos desde Google Sheets, captura OCR de albaranes, picking por EAN, cantidades manuales, expedición con etiquetas de transporte, trazabilidad de stock y recuento físico.

## 2.0.0 — rediseño profesional y cámara documental integrada

Esta versión cambia de forma importante la captura de albaranes y la experiencia de uso:

- **Cámara documental dentro de la propia app con CameraX**: ya no abre una aplicación de cámara externa ni depende de permisos temporales `FileProvider` para leer la foto capturada.
- La fotografía se guarda directamente en la caché privada de SGA MDS y el OCR abre el **archivo local real**, evitando el error `No se puede abrir la fotografía` visto en algunos dispositivos.
- La cámara se desmonta antes de procesar el OCR para reducir consumo de memoria.
- Guía visual A4, flash, botón de captura grande y mensajes operativos en la propia cámara.
- Nuevo **Centro operativo** con KPI, avisos, cola de trabajo y accesos rápidos.
- Nueva pantalla **Operaciones de salida** para reanudar albaranes pendientes y revisar expediciones cerradas.
- Nueva pantalla **Movimientos de stock** para auditoría cronológica.
- Nuevo **Recuento físico**: escanea EAN, introduce existencias reales y registra la diferencia como movimiento trazable.
- Navegación inferior para Inicio / Operaciones / Stock / Historial.
- Flujo visual de 4 etapas: **Documento → Picking → Transporte → Cierre**.
- Entrada manual compatible con escáneres físicos tipo *keyboard wedge*: EAN y etiquetas pueden confirmarse también con Enter.

## Google Sheets configurado

Maestro de productos:

`https://docs.google.com/spreadsheets/d/1HmU9IPRGRWte1iXxUvYaoBc4jEmvNncHvMviI2Ggt3c/edit?gid=0#gid=0`

La aplicación intenta descargar `gid=0` como CSV al iniciar y permite forzar una actualización desde la interfaz. Para lectura directa sin autenticación, la hoja debe permitir acceso de lectura mediante enlace.

### Columnas reconocidas

- Código / referencia: `Código`, `Codigo`, `Referencia`, `Artículo`, `Articulo`, `SKU`, `Code`.
- EAN: `EAN`, `EAN13`, `GTIN`, `Barcode`, `Código de barras`, `Codigo de barras`.
- Descripción: `Descripción`, `Descripcion`, `Producto`, `Nombre`.
- Stock: `Stock`, `Existencias`, `Unidades`, `Cantidad`, `Stock actual`, `Disponible`.
- Ubicación: `Ubicación`, `Ubicacion`, `Pasillo`, `Hueco`, `Almacén`.

El **CÓDIGO** es la clave de relación con el albarán y el **EAN** es el código que se valida durante el picking.

## Flujo de una salida

1. El SGA sincroniza CÓDIGO, EAN y stock del Google Sheet.
2. El operario abre **Nueva salida**.
3. La cámara documental integrada fotografía el albarán.
4. OCR detecta:
   - número inmediatamente debajo de `ALBARÁN`;
   - referencias únicamente en la columna inferior a `ARTÍCULO`;
   - cantidad correspondiente en `CANTIDAD`.
5. El operario revisa y corrige la lectura antes de generar la operación.
6. Cada referencia del albarán se relaciona con su EAN del maestro.
7. En picking se escanea el EAN.
8. Tras una lectura correcta se indica manualmente cuántas unidades se añaden.
9. No se permite superar la cantidad solicitada.
10. Al terminar productos, se habilita el escáner de etiquetas de transporte.
11. Se registra una o varias etiquetas, sin duplicados.
12. Solo se habilita **Finalizar y cerrar** con picking completo y al menos una etiqueta.
13. El cierre registra albarán, líneas, lecturas, etiquetas y movimientos de stock.

## OCR del formato Metatrafic aportado

Prueba de regresión incluida:

- Albarán: `300712`
- Referencia: `MTP11301N`
- Cantidad: `4`

Reglas de posicionamiento:

1. `ALBARÁN` se localiza por coordenadas OCR.
2. Se busca el valor válido más cercano **debajo y alineado** con esa cabecera.
3. Se localiza `ARTÍCULO`.
4. Se crea una banda virtual de columna bajo esa cabecera.
5. Solo se consideran referencias dentro de esa banda.
6. La cantidad se cruza por fila con la columna `CANTIDAD`.

## Relación CÓDIGO → EAN

Ejemplo:

```text
ALBARÁN
300712

ARTÍCULO
MTP11301N
```

Si Google Sheets contiene:

```text
Código: MTP11301N
EAN: 8430000000001
```

el picking acepta `8430000000001` y lo relaciona con `MTP11301N`. No se autoasignan EAN desconocidos.

La normalización de referencias elimina BOM, NBSP, espacios invisibles, comillas y caracteres de ancho cero. El caso `PRMT1VMP` está cubierto por prueba de regresión.

## Recuento físico

El módulo **Recuento de inventario** permite:

1. Escanear un EAN con cámara o lector físico.
2. Mostrar referencia, descripción y stock registrado.
3. Introducir el stock físico contado.
4. Calcular la diferencia.
5. Guardar el nuevo stock.
6. Registrar un movimiento con motivo `Recuento físico`.

Así el cambio queda auditado y no se convierte en una edición silenciosa.

## Trazabilidad

`stock_movements` registra:

- altas iniciales;
- sincronizaciones que cambian existencias;
- ajustes manuales;
- recuentos físicos;
- salidas de albaranes.

La pantalla **Movimientos de stock** permite buscar por referencia, motivo o número de albarán y muestra el stock resultante de cada movimiento.

## Estabilidad

- Captura documental interna con `ImageCapture`, sin cámara externa.
- Archivo de cámara privado y validación de existencia/tamaño antes del OCR.
- Decoder OCR capaz de abrir directamente archivos locales o URIs del selector de documentos.
- Fotografías grandes reducidas antes de ML Kit a una dimensión segura.
- La cámara documental se libera al comenzar OCR.
- `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` para códigos de barras.
- Escaneo de EAN limitado a resolución operativa 1280×720.
- No se acumulan fotogramas ni se repite indefinidamente un código inmóvil.
- Scanner ML Kit y reconocedor OCR no se cierran mientras tienen tareas activas.
- Executors y casos de uso CameraX se liberan al abandonar una pantalla.
- Timeouts en OCR y sincronización.
- Límites de tamaño de CSV/Google Sheets.
- Operaciones Room transaccionales.
- Corrutinas con barrera de excepciones y diagnóstico local de fallos fatales.
- Prevención de albaranes y etiquetas de transporte duplicados.

## Persistencia local / modo de trabajo

Room mantiene el trabajo del dispositivo aunque no haya red temporalmente. Google Sheets actúa como maestro de productos; el SGA conserva el stock local después de sus movimientos mediante:

- `sheetStock`: último stock base recibido de Sheets.
- `stock`: existencias del SGA con movimientos locales aplicados.

Una nueva sincronización conserva la diferencia local para no restaurar unidades ya expedidas.

> La versión actual **lee** Google Sheets, pero no escribe cambios de vuelta a la hoja. Una sincronización bidireccional multiusuario requiere un backend/API autenticado para evitar conflictos entre dispositivos.

## Proyecto listo para GitHub

En la raíz del repositorio deben aparecer directamente:

```text
.github/
app/
docs/
gradle/
sample/
.editorconfig
.gitignore
build.gradle.kts
gradle.properties
gradlew
gradlew.bat
README.md
README_PRIMERO.txt
SUBIR_A_GITHUB.bat
COMPROBAR_PROYECTO.bat
settings.gradle.kts
```

No subas el ZIP como un único archivo. Extrae su contenido y usa `SUBIR_A_GITHUB.bat`, GitHub Desktop o Git.

## Compilar APK

Workflow:

`.github/workflows/build-apk.yml`

En GitHub: **Actions → Build Android APK → Run workflow**.

El workflow ejecuta tests y genera el artefacto:

`SGA-MDS-2.0.0-debug-apk`

## Versiones técnicas

- `versionCode = 7`
- `versionName = 2.0.0`
- `minSdk = 26`
- `targetSdk = 35`
- `compileSdk = 35`
- Java 17
- Kotlin 1.9.25
- Jetpack Compose + Material 3
- Room
- CameraX
- ML Kit Text Recognition + Barcode Scanning
