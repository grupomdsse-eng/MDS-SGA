# Análisis funcional de referencia — WMS móvil

El rediseño 2.0.0 se ha orientado a patrones presentes en productos WMS/RF actuales como SAP EWM, Oracle WMS Redwood Mobile, Odoo Barcode, Mecalux Easy WMS e Infor Warehouse Mobility.

## Patrones adoptados

### 1. Operaciones en tiempo real por escaneo
Los sistemas WMS modernos desplazan la validación al suelo de almacén y utilizan el escaneo para confirmar producto, operación, embalaje o expedición en el momento en que ocurre.

Aplicado en SGA MDS:
- picking por EAN;
- validación positiva/negativa inmediata;
- etiquetado de transporte antes del cierre;
- actualización transaccional de stock.

### 2. Flujo guiado por etapas
Picking, packing y shipping se presentan como procesos diferenciados pero consecutivos.

Aplicado en SGA MDS:
- Documento;
- Picking;
- Transporte;
- Cierre.

### 3. UI RF orientada al operario
Las interfaces RF recientes priorizan cabecera de proceso, instrucciones y alertas muy visibles y controles grandes para uso táctil.

Aplicado en SGA MDS:
- cabeceras de proceso;
- avisos operativos destacados;
- botones y cámaras grandes;
- indicadores de progreso;
- navegación de pocas opciones y alta legibilidad.

### 4. Cola de trabajo
Los WMS empresariales presentan tareas pendientes y permiten reanudar trabajo en curso.

Aplicado en SGA MDS:
- pantalla Operaciones;
- pendientes separados de completados;
- reanudación del picking desde la cola.

### 5. Trazabilidad de inventario
Un WMS profesional no debe modificar existencias sin contexto.

Aplicado en SGA MDS:
- `stock_movements`;
- motivo, delta y stock resultante;
- relación con albarán cuando corresponde;
- pantalla de auditoría.

### 6. Recuento físico
El conteo cíclico/físico es una función estándar del mercado WMS.

Aplicado en SGA MDS 2.0.0:
- lectura de EAN;
- consulta del stock registrado;
- introducción del stock físico;
- cálculo de diferencia;
- movimiento `Recuento físico`.

### 7. Cámara y lector físico
Las aplicaciones móviles actuales permiten cámara, mientras que los terminales robustos suelen utilizar escáner integrado/keyboard wedge.

Aplicado en SGA MDS:
- CameraX + ML Kit;
- entrada manual compatible con lector que escribe como teclado;
- confirmación con Enter en campos de EAN/etiqueta.

## Evolución recomendada para una fase multiusuario

Para pasar de SGA de un dispositivo/almacén a plataforma empresarial multioperario, los siguientes módulos deberían añadirse sobre un backend central autenticado:

- usuarios, roles y operario responsable;
- ubicaciones obligatorias y validación ubicación-producto;
- entradas/recepciones y put-away;
- transferencias internas;
- inventarios/cycle count por campañas y discrepancias aprobables;
- lotes, series y caducidades cuando el producto lo requiera;
- packing units / bultos;
- impresión de etiquetas;
- olas de picking y priorización;
- multi-almacén;
- sincronización bidireccional transaccional;
- dashboard web y analítica de productividad/errores.
