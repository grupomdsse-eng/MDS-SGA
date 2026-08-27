SGA MDS - VERSION 1.2.0 ESTABLE - LISTA PARA GITHUB
====================================================

CAMBIOS IMPORTANTES DE ESTA VERSION
-----------------------------------
1) El numero de albaran se obtiene por POSICION:
   - busca la palabra ALBARAN en la parte superior;
   - toma el numero alineado que esta justo debajo;
   - evita confundirlo con N cliente, CIF, pedido u otros numeros.

2) La referencia se obtiene por COLUMNA:
   - localiza la cabecera ARTICULO;
   - solo acepta referencias situadas debajo de esa columna;
   - no busca referencias por el resto del documento.

3) La cantidad se cruza con la columna CANTIDAD de la misma fila.

4) Estabilidad reforzada:
   - el mismo codigo de barras quieto no se registra una y otra vez;
   - las lecturas se procesan de una en una, sin crear colas;
   - la camara libera analizador, recursos y executor al salir;
   - el OCR no bloquea el hilo de interfaz y tiene timeout de seguridad;
   - se limita la acumulacion de fotos temporales.

PARA SUBIR A GITHUB
-------------------
1) NO subas este ZIP como un unico archivo a GitHub.
2) Extrae el ZIP completo.
3) Abre la carpeta SGA_MDS_GitHub_Ready_v2.
4) Comprueba que ves: .github, app, gradle, docs y sample.
5) Ejecuta SUBIR_A_GITHUB.bat o usa GitHub Desktop.

PARA GENERAR EL APK
-------------------
GitHub -> Actions -> Build Android APK -> Run workflow.
Al finalizar descarga el artefacto SGA-MDS-debug-apk.

ALBARAN DE PRUEBA
-----------------
Numero: 300712 (debajo de ALBARAN)
Referencia: MTP11301N (debajo de ARTICULO)
Producto: Matricula Acrilica (52x11)
Cantidad: 4
