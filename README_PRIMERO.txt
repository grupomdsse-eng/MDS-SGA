SGA MDS - VERSION 1.3.1 - GOOGLE SHEETS + PICKING + TRANSPORTE
===============================================================

NOVEDADES PRINCIPALES
---------------------
1) STOCK Y EAN DESDE GOOGLE SHEETS
   Hoja configurada:
   https://docs.google.com/spreadsheets/d/1HmU9IPRGRWte1iXxUvYaoBc4jEmvNncHvMviI2Ggt3c/edit?gid=0#gid=0

   La app sincroniza automaticamente al abrir y tambien dispone de boton SINCRONIZAR.
   Reconoce columnas CÓDIGO/REFERENCIA/ARTÍCULO, EAN, DESCRIPCIÓN, STOCK y UBICACIÓN.

   IMPORTANTE: para descarga directa sin login, la hoja debe permitir lectura mediante enlace.

2) RELACION CODIGO -> EAN
   - En el albaran se lee el CÓDIGO situado debajo de ARTÍCULO.
   - Ese CÓDIGO se busca en el maestro de Google Sheets.
   - La app obtiene el EAN del producto.
   - En picking SOLO acepta el EAN relacionado con ese CÓDIGO.
   - Ya no se autoasignan EAN desconocidos durante el picking.

3) UNIDADES MANUALES DESPUES DE ESCANEAR
   - Escanea el EAN.
   - Se abre una ventana con producto, EAN, picado y pendientes.
   - Introduce 1, 2, 3... unidades.
   - Nunca permite superar la cantidad del albaran.

4) ETIQUETAS DE TRANSPORTE OBLIGATORIAS
   - Al completar los productos aparece automaticamente el escaner de transporte.
   - Permite registrar varias etiquetas.
   - Evita duplicados.
   - No deja finalizar el albaran hasta registrar al menos una etiqueta.
   - Las etiquetas quedan guardadas con el albaran.

5) OCR DEL ALBARAN
   - Numero: valor situado debajo de ALBARÁN.
   - Referencia: solo valores de la columna situada debajo de ARTÍCULO.
   - Cantidad: columna CANTIDAD de la misma fila.

6) ESTABILIDAD
   - CameraX usa KEEP_ONLY_LATEST.
   - Un codigo quieto no crea lecturas infinitas.
   - Operaciones de lectura serializadas con Mutex.
   - Camera, scanner y executor se liberan al salir.
   - OCR con timeout y fuera del hilo de interfaz.
   - Migracion Room 1 -> 2 sin borrar historial.

PARA SUBIR A GITHUB
-------------------
1) Extrae el ZIP completo.
2) NO subas el ZIP como un unico archivo.
3) Abre la carpeta extraida y comprueba que ves .github, app, gradle, docs y sample.
4) Ejecuta SUBIR_A_GITHUB.bat o usa GitHub Desktop.
5) En GitHub: Actions -> Build Android APK -> Run workflow.

ALBARAN DE PRUEBA
-----------------
Numero: 300712
Referencia/Codigo: MTP11301N
Cantidad: 4
