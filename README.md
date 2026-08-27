# SGA MDS — Android

Proyecto Android nativo para gestión de almacén, lectura OCR de albaranes y picking mediante códigos de barras.

## Funciones incluidas

- Inventario local con **Código/Referencia, EAN, Descripción, Stock y Ubicación**.
- Alta y edición manual de productos.
- Importación de productos desde **CSV UTF-8** exportado desde Excel.
- Reconoce encabezados como `Código`, `Referencia`, `Artículo`, `EAN`, `Descripción`, `Stock` y `Ubicación`.
- Fotografía de un albarán con la cámara del dispositivo.
- OCR local mediante **Google ML Kit Text Recognition**.
- Detección automática de:
  - número de albarán;
  - cliente (si se reconoce);
  - referencia de la columna **ARTÍCULO**;
  - descripción;
  - cantidad de la columna **CANTIDAD**.
- Parser reforzado con coordenadas OCR: intenta usar físicamente las columnas ARTÍCULO y CANTIDAD, no solo texto plano.
- Pantalla de revisión manual antes de guardar el albarán.
- Picking con cámara usando **CameraX + ML Kit Barcode Scanning**.
- Cada lectura correcta añade una unidad picada.
- Rechaza productos que no pertenezcan al albarán.
- Impide picar más unidades de las indicadas.
- Registro de lecturas correctas e incorrectas.
- Finalización bloqueada mientras falten unidades.
- Validación de stock antes de cerrar.
- Al finalizar, descuenta el stock y registra el movimiento.
- Historial de albaranes pendientes y finalizados.
- Evita registrar dos veces el mismo número de albarán.
- Funcionamiento local/offline para los datos del almacén.

## Albarán de ejemplo usado para comprobar el OCR

El parser incluye pruebas para el formato aportado:

- Albarán: `300712`
- Cliente: `ALCALA AUTOCASION SL`
- Referencia: `MTP11301N`
- Descripción: `Matricula Acrilica (52x11)`
- Cantidad: `4`

La aplicación **siempre muestra una pantalla de revisión** antes de crear el picking. Un OCR nunca debe descontar stock directamente sin confirmación.

## Importar el Excel de productos

La app importa CSV. Desde Excel usa **Guardar como → CSV UTF-8**.

Ejemplo incluido en `sample/productos.csv`:

```csv
Código;EAN;Descripción;Stock;Ubicación
MTP11301N;8430000000001;Matricula Acrilica (52x11);100;A-01-01
```

La columna de referencia puede llamarse `Código`, `Referencia`, `Artículo`, `SKU`, etc. La aplicación normaliza mayúsculas/minúsculas y acentos.

**Recomendación para EAN/GTIN:** en Excel configura esa columna como **Texto** antes de guardar el CSV. Así se conservan posibles ceros iniciales y Excel no transforma códigos largos a notación científica. La app también intenta normalizar valores exportados como `8,43E+12`.

## Abrir el proyecto

Requisitos recomendados:

- Android Studio reciente.
- JDK 17.
- Android SDK 35.

Abre **esta carpeta raíz**, la que contiene `settings.gradle.kts` y la carpeta `app`.

## Compilar localmente

### Windows

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

### Linux / macOS

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Los launchers `gradlew` y `gradlew.bat` descargan Gradle 8.9 la primera vez y verifican su SHA-256. No es necesario guardar un `gradle-wrapper.jar` binario en el repositorio.

El APK queda en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Generar el APK automáticamente en GitHub

El proyecto ya incluye:

```text
.github/workflows/build-apk.yml
```

Al hacer push a `main`/`master`, o ejecutarlo manualmente desde **Actions**, GitHub:

1. instala Java 17;
2. instala Android SDK 35;
3. instala Gradle 8.9;
4. ejecuta los tests;
5. compila el APK;
6. publica `SGA-MDS-debug-apk` como artefacto descargable.

## Cómo subirlo sin perder carpetas

GitHub no guarda carpetas vacías y subir archivos sueltos desde el navegador puede llevar a errores de estructura.

La forma más segura es:

1. Extraer el ZIP completo en Windows.
2. Crear un repositorio vacío en GitHub.
3. Abrir esta carpeta con GitHub Desktop o Git.
4. Hacer commit de **todo el contenido**.
5. Publicar/push al repositorio.

También se incluye `SUBIR_A_GITHUB.bat`, que conserva automáticamente toda la estructura si Git está instalado.

Al abrir tu repositorio deben verse directamente:

```text
.github/
app/
gradle/
docs/
sample/
.editorconfig
.gitignore
build.gradle.kts
gradle.properties
gradlew
gradlew.bat
README.md
settings.gradle.kts
```

No subas el ZIP como un único archivo esperando que GitHub lo descomprima: GitHub no lo hace.

## Flujo de trabajo del almacén

1. **Inventario** → importa el maestro de productos.
2. **Escanear albarán** → fotografía el documento.
3. **Revisar lectura** → confirma número, referencias y cantidades.
4. **Comenzar picking**.
5. Escanea cada EAN con la cámara.
6. La app muestra el progreso por referencia.
7. Cuando todo esté completo, pulsa **Finalizar albarán y descontar stock**.
8. El movimiento queda guardado en el historial.

## Seguridad operativa

- No se descuenta stock al hacer OCR.
- No se descuenta stock durante cada lectura: el descuento se hace de forma transaccional al cerrar el albarán.
- No se permite cerrar con cantidades incompletas.
- No se permite cerrar si el stock real es insuficiente.
- Un producto ajeno al albarán queda rechazado y registrado.
- Un albarán ya finalizado no puede volver a procesarse.
- El mismo número de albarán no puede crearse dos veces.

## Nota de arquitectura

Esta versión guarda los datos en Room dentro del dispositivo. Es adecuada para un único terminal o para trabajar offline. Si se quiere utilizar varios móviles simultáneamente contra el mismo stock, la evolución correcta es añadir una API/backend central y sincronización multiusuario.
