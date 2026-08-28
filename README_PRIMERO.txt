SGA MDS - VERSION 2.0.0 - EDICION PROFESIONAL
==============================================

CORRECCION PRINCIPAL DE ESTA VERSION
------------------------------------
La foto del albaran YA NO se realiza abriendo la aplicacion de camara externa.
SGA MDS incorpora una camara documental CameraX dentro de la propia aplicacion.

La captura se guarda directamente en la carpeta privada de la app y el OCR abre
el archivo local real. Esto elimina la dependencia de permisos temporales de una
app de camara externa que podia producir: "No se puede abrir la fotografia".

NUEVA INTERFAZ PROFESIONAL
--------------------------
- Centro operativo con KPI y avisos.
- Navegacion inferior: Inicio / Operaciones / Stock / Historial.
- Cola de operaciones pendientes.
- Flujo visual: Documento -> Picking -> Transporte -> Cierre.
- Camara documental con guia A4, flash e instrucciones.
- Movimientos de stock y trazabilidad.
- Recuento fisico por EAN.
- Entrada compatible con lector fisico tipo teclado + Enter.

FLUJO DE SALIDA
---------------
1) Sincroniza Google Sheets.
2) Fotografia el albaran desde la camara integrada.
3) Revisa numero, referencias y cantidades detectadas.
4) Confirma el albaran.
5) Escanea EAN de producto.
6) Indica manualmente las unidades.
7) Completa todas las lineas.
8) Escanea etiqueta(s) de transporte.
9) Finaliza y cierra.

GOOGLE SHEETS
-------------
https://docs.google.com/spreadsheets/d/1HmU9IPRGRWte1iXxUvYaoBc4jEmvNncHvMviI2Ggt3c/edit?gid=0#gid=0

El albaran contiene CÓDIGO. Google Sheets relaciona CÓDIGO -> EAN.
El picking se valida escaneando EAN.

REGLAS OCR
----------
- Numero: debajo de ALBARAN.
- Referencia: solo debajo de ARTICULO.
- Cantidad: misma fila en CANTIDAD.

PARA SUBIR A GITHUB
-------------------
1) Extrae el ZIP.
2) No subas el ZIP como archivo unico.
3) Comprueba que en la raiz ves .github, app, gradle, docs y sample.
4) Ejecuta SUBIR_A_GITHUB.bat o usa GitHub Desktop.
5) GitHub -> Actions -> Build Android APK -> Run workflow.

VERSION
-------
versionCode: 7
versionName: 2.0.0
