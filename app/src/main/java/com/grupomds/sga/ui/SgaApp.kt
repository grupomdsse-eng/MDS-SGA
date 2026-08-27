package com.grupomds.sga.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.grupomds.sga.BuildConfig
import com.grupomds.sga.data.DeliveryNoteEntity
import com.grupomds.sga.data.ProductEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object Routes {
    const val HOME = "home"
    const val INVENTORY = "inventory"
    const val SCAN = "scan"
    const val REVIEW = "review"
    const val HISTORY = "history"
    const val PICKING = "picking/{noteId}"
    fun picking(id: Long): String = "picking/$id"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SgaApp(vm: SgaViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val operationMessage by vm.operationMessage.collectAsStateWithLifecycle()

    LaunchedEffect(operationMessage) {
        operationMessage?.let { message ->
            snackbar.showSnackbar(message)
            vm.clearOperationMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(route)) },
                navigationIcon = {
                    if (route != Routes.HOME) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) { HomeScreen(vm, nav) }
            composable(Routes.INVENTORY) { InventoryScreen(vm) }
            composable(Routes.SCAN) { ScanDeliveryNoteScreen(vm, nav) }
            composable(Routes.REVIEW) { ReviewDeliveryNoteScreen(vm, nav) }
            composable(Routes.HISTORY) { HistoryScreen(vm, nav) }
            composable(
                route = Routes.PICKING,
                arguments = listOf(navArgument("noteId") { type = NavType.LongType })
            ) { entry ->
                PickingScreen(
                    vm = vm,
                    nav = nav,
                    noteId = entry.arguments?.getLong("noteId") ?: 0L
                )
            }
        }
    }
}

private fun titleFor(route: String?): String = when (route) {
    Routes.INVENTORY -> "Inventario"
    Routes.SCAN -> "Escanear albarán"
    Routes.REVIEW -> "Revisar lectura"
    Routes.HISTORY -> "Historial"
    Routes.PICKING -> "Picking"
    else -> "SGA MDS · Almacén"
}

@Composable
private fun HomeScreen(vm: SgaViewModel, nav: NavHostController) {
    val products by vm.products.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val pending = history.count { it.status == DeliveryNoteEntity.STATUS_PENDING }
    val lowStock = products.count { it.stock <= 5 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Sistema de gestión de almacén",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text("Control de stock, OCR de albaranes y validación de salidas mediante código de barras.")
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryCard("Productos", products.size.toString(), Modifier.weight(1f))
                SummaryCard("Pendientes", pending.toString(), Modifier.weight(1f))
                SummaryCard("Stock ≤ 5", lowStock.toString(), Modifier.weight(1f))
            }
        }

        item {
            DashboardCard(
                title = "Escanear albarán",
                subtitle = "Fotografía el documento y detecta número, referencia, producto y cantidad.",
                icon = Icons.Default.CameraAlt,
                onClick = { nav.navigate(Routes.SCAN) }
            )
        }
        item {
            DashboardCard(
                title = "Inventario",
                subtitle = "Gestiona referencia, EAN, descripción, stock y ubicación.",
                icon = Icons.Default.Inventory2,
                onClick = { nav.navigate(Routes.INVENTORY) }
            )
        }
        item {
            DashboardCard(
                title = "Historial",
                subtitle = "Consulta albaranes pendientes y finalizados con trazabilidad.",
                icon = Icons.Default.History,
                onClick = { nav.navigate(Routes.HISTORY) }
            )
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DashboardCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InventoryScreen(vm: SgaViewModel) {
    val products by vm.products.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ProductEntity?>(null) }
    var showNew by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
            }.onSuccess(vm::importCsv)
        }
    }

    val visible = remember(products, search) {
        val query = search.trim()
        if (query.isBlank()) {
            products
        } else {
            products.filter { product ->
                product.reference.contains(query, ignoreCase = true) ||
                    product.description.contains(query, ignoreCase = true) ||
                    product.ean.orEmpty().contains(query, ignoreCase = true) ||
                    product.location.contains(query, ignoreCase = true)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Buscar referencia, EAN o producto") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = { showNew = true }) {
                Icon(Icons.Default.Add, contentDescription = "Añadir producto")
            }
            IconButton(
                onClick = {
                    csvLauncher.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "application/vnd.ms-excel",
                            "text/plain"
                        )
                    )
                }
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = "Importar CSV")
            }
        }

        Text(
            text = "${products.size} productos · ${visible.size} visibles",
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )

        if (products.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Inventario vacío", fontWeight = FontWeight.Bold)
                    Text("Importa un CSV exportado desde Excel. Se reconoce la columna CÓDIGO/REFERENCIA y la columna EAN.")
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(visible, key = { it.reference }) { product ->
                Card(onClick = { editing = product }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(product.reference, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Stock: ${product.stock}",
                                fontWeight = FontWeight.Bold,
                                color = if (product.stock <= 5) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(product.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "EAN: ${product.ean ?: "sin asignar"} · Ubicación: ${product.location.ifBlank { "—" }}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            OutlinedButton(
                                enabled = product.stock > 0,
                                onClick = { vm.adjustStock(product.reference, -1) }
                            ) { Text("−1") }
                            OutlinedButton(onClick = { vm.adjustStock(product.reference, 1) }) { Text("+1") }
                        }
                    }
                }
            }
        }
    }

    if (showNew || editing != null) {
        ProductDialog(
            initial = editing,
            onDismiss = {
                showNew = false
                editing = null
            },
            onSave = { product ->
                vm.saveProduct(product)
                showNew = false
                editing = null
            }
        )
    }
}

@Composable
private fun ProductDialog(
    initial: ProductEntity?,
    onDismiss: () -> Unit,
    onSave: (ProductEntity) -> Unit
) {
    var reference by remember(initial) { mutableStateOf(initial?.reference.orEmpty()) }
    var ean by remember(initial) { mutableStateOf(initial?.ean.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var stock by remember(initial) { mutableStateOf(initial?.stock?.toString() ?: "0") }
    var location by remember(initial) { mutableStateOf(initial?.location.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo producto" else "Editar producto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = reference,
                    onValueChange = { reference = it.uppercase() },
                    label = { Text("Referencia / código") },
                    enabled = initial == null,
                    singleLine = true
                )
                OutlinedTextField(
                    value = ean,
                    onValueChange = { ean = it.filter(Char::isLetterOrDigit).uppercase() },
                    label = { Text("EAN / código de barras") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción") }
                )
                OutlinedTextField(
                    value = stock,
                    onValueChange = { stock = it.filter(Char::isDigit) },
                    label = { Text("Stock") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Ubicación") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                enabled = reference.isNotBlank() && description.isNotBlank(),
                onClick = {
                    onSave(
                        ProductEntity(
                            reference = reference.trim(),
                            ean = ean.trim().takeIf { it.isNotBlank() },
                            description = description.trim(),
                            stock = stock.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            location = location.trim(),
                            active = initial?.active ?: true
                        )
                    )
                }
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ScanDeliveryNoteScreen(vm: SgaViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val busy by vm.ocrBusy.collectAsStateWithLifecycle()
    val error by vm.ocrError.collectAsStateWithLifecycle()
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        if (ok && uri != null) {
            vm.scanDeliveryNote(uri) { nav.navigate(Routes.REVIEW) }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.scanDeliveryNote(uri) { nav.navigate(Routes.REVIEW) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Fotografía el albarán completo",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "La app busca automáticamente el número de albarán y, en la tabla, la referencia de ARTÍCULO, la descripción y la CANTIDAD. Antes del picking puedes corregir cualquier dato."
        )

        Button(
            enabled = !busy,
            onClick = {
                vm.clearOcrError()
                val uri = createPhotoUri(context)
                photoUri = uri
                cameraLauncher.launch(uri)
            }
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Abrir cámara")
        }

        OutlinedButton(
            enabled = !busy,
            onClick = { imageLauncher.launch(arrayOf("image/*")) }
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Elegir una foto existente")
        }

        if (busy) {
            CircularProgressIndicator()
            Text("Leyendo albarán con OCR…")
        }
        if (!error.isNullOrBlank()) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        HorizontalDivider()
        Text(
            text = "Formato de comprobación incluido: albarán 300712 · referencia MTP11301N · cantidad 4.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun createPhotoUri(context: Context): Uri {
    val directory = File(context.cacheDir, "delivery_notes").apply { mkdirs() }
    val file = File(directory, "albaran_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        file
    )
}

@Composable
private fun ReviewDeliveryNoteScreen(vm: SgaViewModel, nav: NavHostController) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val current = draft

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No hay una lectura pendiente")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = current.number,
                onValueChange = vm::updateDraftNumber,
                label = { Text("Número de albarán") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            OutlinedTextField(
                value = current.customer,
                onValueChange = vm::updateDraftCustomer,
                label = { Text("Cliente (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Líneas detectadas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${current.lines.size}", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (current.lines.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        "No se ha podido detectar ninguna línea con seguridad. Puedes añadirlas manualmente y continuar con el picking.",
                        Modifier.padding(14.dp)
                    )
                }
            }
        }

        itemsIndexed(current.lines) { index, line ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (line.matchedProduct) "Coincide con inventario" else "Revisar referencia",
                        color = if (line.matchedProduct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = line.reference,
                        onValueChange = { vm.updateDraftLine(index, reference = it.uppercase()) },
                        label = { Text("Referencia / artículo") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = line.description,
                        onValueChange = { vm.updateDraftLine(index, description = it) },
                        label = { Text("Descripción") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = line.quantity.toString(),
                            onValueChange = { raw ->
                                raw.toIntOrNull()?.takeIf { it > 0 }?.let { qty ->
                                    vm.updateDraftLine(index, quantity = qty)
                                }
                            },
                            label = { Text("Cantidad") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        IconButton(onClick = { vm.removeDraftLine(index) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar línea")
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = vm::addDraftLine, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Añadir línea manualmente")
            }
        }

        item {
            Button(
                onClick = {
                    vm.createNote { id ->
                        nav.navigate(Routes.picking(id)) {
                            popUpTo(Routes.HOME)
                        }
                    }
                },
                enabled = current.number.isNotBlank() && current.lines.any { it.reference.isNotBlank() && it.quantity > 0 },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Confirmar y comenzar picking")
            }
        }
    }
}

@Composable
private fun PickingScreen(
    vm: SgaViewModel,
    nav: NavHostController,
    noteId: Long
) {
    val snapshot by vm.picking.collectAsStateWithLifecycle()
    val scanMessage by vm.scanMessage.collectAsStateWithLifecycle()
    var manualBarcode by remember(noteId) { mutableStateOf("") }

    LaunchedEffect(noteId) {
        vm.clearScanMessage()
        vm.loadPicking(noteId)
    }

    val data = snapshot
    if (data == null || data.note.id != noteId) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val totalExpected = data.lines.sumOf { it.expectedQty }
    val totalPicked = data.lines.sumOf { it.pickedQty }
    val pickingDone = totalExpected > 0 && totalPicked == totalExpected
    val completed = data.note.status == DeliveryNoteEntity.STATUS_COMPLETED
    val progress = if (totalExpected <= 0) 0f else (totalPicked.toFloat() / totalExpected.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Albarán ${data.note.number}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (data.note.customer.isNotBlank()) Text(data.note.customer)
            Text(
                text = if (completed) "FINALIZADO" else "PENDIENTE",
                color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("$totalPicked / $totalExpected unidades")
        }

        items(data.lines, key = { it.id }) { line ->
            val lineComplete = line.pickedQty == line.expectedQty
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (lineComplete) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(line.productReference, fontWeight = FontWeight.Bold)
                        Text(line.description)
                    }
                    Text(
                        text = "${line.pickedQty}/${line.expectedQty}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        if (!completed) {
            item {
                Text("Escáner de productos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Cada lectura suma una unidad. Si el código no pertenece al albarán o la cantidad ya está completa, se rechaza y queda registrado.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                BarcodeCamera(
                    enabled = !pickingDone,
                    onBarcode = { code -> vm.submitBarcode(noteId, code) },
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualBarcode,
                        onValueChange = { manualBarcode = it.filter(Char::isLetterOrDigit).uppercase() },
                        label = { Text("EAN / código manual") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    FilledTonalButton(
                        enabled = manualBarcode.isNotBlank(),
                        onClick = {
                            vm.submitBarcode(noteId, manualBarcode)
                            manualBarcode = ""
                        }
                    ) { Text("Validar") }
                }
            }

            scanMessage?.let { (accepted, message) ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (accepted) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        )
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(12.dp),
                            color = if (accepted) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (data.scanLogs.isNotEmpty()) {
                item {
                    Text("Últimas lecturas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                items(data.scanLogs.take(5), key = { it.id }) { log ->
                    Text(
                        text = "${if (log.accepted) "✓" else "✕"} ${log.barcode} · ${log.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (log.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        vm.finalize(noteId) {
                            nav.navigate(Routes.HISTORY) {
                                popUpTo(Routes.HOME)
                            }
                        }
                    },
                    enabled = pickingDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Finalizar albarán y descontar stock")
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Salida registrada", fontWeight = FontWeight.Bold)
                        Text("El albarán está cerrado y las existencias ya fueron descontadas del inventario.")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(vm: SgaViewModel, nav: NavHostController) {
    val history by vm.history.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (history.isEmpty()) {
            item { Text("Todavía no hay albaranes registrados.") }
        }

        items(history, key = { it.id }) { note ->
            val completed = note.status == DeliveryNoteEntity.STATUS_COMPLETED
            Card(
                onClick = { nav.navigate(Routes.picking(note.id)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (completed) Icons.Default.CheckCircle else Icons.Default.History,
                        contentDescription = null,
                        tint = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Albarán ${note.number}", fontWeight = FontWeight.Bold)
                        if (note.customer.isNotBlank()) {
                            Text(note.customer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            formatDate(note.completedAt ?: note.createdAt),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = if (completed) "Finalizado" else "Pendiente",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun formatDate(epoch: Long): String = SimpleDateFormat(
    "dd/MM/yyyy HH:mm",
    Locale.getDefault()
).format(Date(epoch))
