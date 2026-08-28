# SGA MDS — Android 1.3.1


## Corrección 1.3.1: referencias nuevas de Google Sheets

Antes de crear un albarán, la app fuerza una sincronización del maestro y espera a que termine. Además, las referencias se normalizan eliminando caracteres invisibles que puede introducir Google Sheets/Excel. El caso `PRMT1VMP` está cubierto por una prueba de regresión.

Aplicación Android nativa para gestión de almacén, OCR de albaranes, picking mediante EAN y cierre de expediciones con etiquetas de transporte.

## Google Sheets configurado

La aplicación usa como maestro de productos esta hoja:

`https://docs.google.com/spreadsheets/d/1HmU9IPRGRWte1iXxUvYaoBc4jEmvNncHvMviI2Ggt3c/edit?gid=0#gid=0`

Se intenta descargar automáticamente la pestaña `gid=0` como CSV al abrir la app. También hay un botón **Sincronizar** en Inicio e Inventario.

Para que un APK pueda leer la hoja sin autenticación Google, la hoja debe permitir lectura mediante enlace. Si no puede descargarse, la app muestra el error y conserva el inventario local ya existente.

### Columnas reconocidas

La importación es tolerante a nombres habituales:

- Código: `Código`, `Referencia`, `Artículo`, `SKU`, `Code`.
- EAN: `EAN`, `EAN13`, `GTIN`, `Barcode`, `Código de barras`.
- Descripción: `Descripción`, `Producto`, `Nombre`.
- Stock: `Stock`, `Existencias`, `Unidades`, `Cantidad`, `Stock actual`, `Disponible`.
- Ubicación: `Ubicación`, `Pasillo`, `Hueco`, `Almacén`.

El **CÓDIGO** es la clave del producto. El **EAN** es el valor que se valida al escanear el código de barras.

## Flujo completo

1. La app sincroniza el maestro de productos de Google Sheets.
2. El operario fotografía el albarán.
3. El OCR detecta:
   - el número situado debajo de `ALBARÁN`;
   - las referencias situadas debajo de la columna `ARTÍCULO`;
   - la cantidad de la columna `CANTIDAD`.
4. En revisión se muestra el EAN y stock asociado a cada CÓDIGO.
5. Al confirmar, la app exige que cada CÓDIGO exista en el maestro y tenga EAN.
6. En picking se escanea el **EAN** del producto.
7. Tras reconocerlo aparece una ventana para introducir manualmente cuántas unidades se añaden al picking.
8. No permite superar la cantidad esperada del albarán.
9. Cuando todos los productos están completos, aparece el escáner de **etiquetas de transporte**.
10. Pueden registrarse varias etiquetas; los duplicados se rechazan.
11. El botón de finalizar permanece bloqueado hasta tener el picking completo y al menos una etiqueta de transporte.
12. Al finalizar se descuenta el stock local del SGA, se registra el movimiento, las etiquetas y el cierre del albarán.

## Relación CÓDIGO → EAN

El albarán no necesita contener el EAN. Por ejemplo:

```text
ARTÍCULO
MTP11301N
```

La aplicación busca `MTP11301N` en Google Sheets. Si el maestro indica:

```text
Código: MTP11301N
EAN: 8430000000001
```

el picking solo acepta `8430000000001` para esa referencia.

La versión 1.3.1 **no autoasigna EAN desconocidos** al escanear, porque eso podría relacionar un código de barras incorrecto con un artículo.

## Unidades manuales

Al escanear un EAN aparece un diálogo con:

- Código/referencia.
- Descripción.
- EAN.
- Unidades ya picadas.
- Unidades pendientes.
- Campo `Unidades a añadir`.

Puedes escanear una sola vez y añadir, por ejemplo, 4 unidades, siempre que el albarán tenga al menos 4 pendientes.

## Etiquetas de transporte

Después de completar los productos, el mismo sistema CameraX/ML Kit cambia al paso de expedición. Admite todos los formatos de código de barras soportados por ML Kit, útil para EAN, Code 128, QR, Data Matrix, PDF417, etc.

Las etiquetas quedan almacenadas en `transport_labels` asociadas al albarán. No se puede cerrar sin registrar al menos una.

## Stock y sincronización

`ProductEntity` conserva dos valores conceptuales:

- `sheetStock`: último stock base leído desde Google Sheets.
- `stock`: stock disponible del SGA después de las salidas/ajustes locales.

Al volver a sincronizar, la app conserva la diferencia local del SGA para evitar que una nueva descarga de la hoja restaure automáticamente las unidades ya dadas de salida desde este dispositivo.

Esta versión **lee** Google Sheets; no escribe cambios en la hoja. La escritura bidireccional requeriría autenticación/API de Google Sheets.

## OCR del formato aportado

Las pruebas incluidas cubren el albarán de ejemplo:

- Albarán: `300712`.
- Referencia: `MTP11301N`.
- Descripción: `Matricula Acrilica (52x11)`.
- Cantidad: `4`.

Reglas principales:

1. `ALBARÁN` se localiza mediante las coordenadas OCR.
2. El número se toma del valor alineado más próximo situado debajo.
3. Se localiza la cabecera `ARTÍCULO`.
4. Las referencias se aceptan únicamente dentro de esa columna.
5. La cantidad se cruza horizontalmente con la columna `CANTIDAD`.

## Estabilidad

La aplicación incluye medidas para uso prolongado:

- `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`.
- Un código quieto no genera lecturas repetidas continuamente.
- Validaciones y escrituras de picking serializadas con `Mutex`.
- El analizador, CameraX, ML Kit y el executor se liberan al abandonar la pantalla.
- OCR fuera del hilo principal.
- Timeout de 25 segundos para OCR problemático.
- Limpieza de fotografías temporales antiguas.
- Operaciones de Room transaccionales.
- Migración de base de datos `1 → 2` sin borrar historial existente.
- Prevención de albaranes y etiquetas duplicadas.

## Proyecto listo para GitHub

En la raíz deben verse directamente:

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

No subas el ZIP como un único archivo a GitHub. Extrae todo primero y usa `SUBIR_A_GITHUB.bat`, GitHub Desktop o Git.

## Generar el APK en GitHub

El workflow está en:

`.github/workflows/build-apk.yml`

En GitHub entra en **Actions → Build Android APK → Run workflow**. Ejecuta tests y compila `app-debug.apk`, que se publica como artefacto `SGA-MDS-1.3.1-debug-apk`.

## Versiones

- `versionCode = 5`
- `versionName = 1.3.1`
- `minSdk = 26`
- `targetSdk = 35`
- Java 17
