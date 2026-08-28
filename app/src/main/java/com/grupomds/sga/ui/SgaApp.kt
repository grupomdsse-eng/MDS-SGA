package com.grupomds.sga.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.grupomds.sga.data.DeliveryNoteEntity
import com.grupomds.sga.data.ProductEntity
import com.grupomds.sga.data.StockMovementEntity
import com.grupomds.sga.data.ProductScanCandidate
import com.grupomds.sga.data.SgaRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object Routes {
    const val HOME = "home"
    const val OPERATIONS = "operations"
    const val INVENTORY = "inventory"
    const val MOVEMENTS = "movements"
    const val COUNT = "count"
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

    val rootRoutes = setOf(Routes.HOME, Routes.OPERATIONS, Routes.INVENTORY, Routes.HISTORY)
    val showBottomBar = route in rootRoutes

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(titleFor(route), fontWeight = FontWeight.SemiBold)
                        if (route in rootRoutes) {
                            Text(
                                "MDS Warehouse · Operación en tiempo real",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (route !in rootRoutes) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    listOf(
                        Triple(Routes.HOME, "Inicio", Icons.Default.Home),
                        Triple(Routes.OPERATIONS, "Operaciones", Icons.Default.AssignmentTurnedIn),
                        Triple(Routes.INVENTORY, "Stock", Icons.Default.Inventory2),
                        Triple(Routes.HISTORY, "Historial", Icons.Default.History)
                    ).forEach { (target, label, icon) ->
                        NavigationBarItem(
                            selected = route == target,
                            onClick = {
                                if (route != target) {
                                    nav.navigate(target) {
                                        launchSingleTop = true
                                        popUpTo(Routes.HOME) { saveState = true }
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) { HomeScreen(vm, nav) }
            composable(Routes.OPERATIONS) { OperationsScreen(vm, nav) }
            composable(Routes.INVENTORY) { InventoryScreen(vm) }
            composable(Routes.MOVEMENTS) { MovementsScreen(vm) }
            composable(Routes.COUNT) { CountScreen(vm) }
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
    Routes.OPERATIONS -> "Operaciones"
    Routes.INVENTORY -> "Inventario"
    Routes.MOVEMENTS -> "Movimientos de stock"
    Routes.COUNT -> "Recuento de inventario"
    Routes.SCAN -> "Nueva salida"
    Routes.REVIEW -> "Validación del albarán"
    Routes.HISTORY -> "Historial"
    Routes.PICKING -> "Preparación y expedición"
    else -> "Centro operativo"
}

@Composable
private fun HomeScreen(vm: SgaViewModel, nav: NavHostController) {
    val products by vm.products.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    val syncBusy by vm.syncBusy.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()

    val pending = history.count { it.status == DeliveryNoteEntity.STATUS_PENDING }
    val completedToday = history.count {
        it.status == DeliveryNoteEntity.STATUS_COMPLETED && it.completedAt?.let(::isToday) == true
    }
    val lowStock = products.count { it.stock in 0..5 }
    val withoutEan = products.count { it.ean.isNullOrBlank() }
    val totalUnits = products.sumOf { it.stock }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ProcessHeader(
                eyebrow = "CONTROL DE ALMACÉN",
                title = "Centro operativo",
                subtitle = "Preparación, expedición, trazabilidad y stock en una única vista para el operario."
            )
        }

        item {
            SyncBanner(syncBusy = syncBusy, syncStatus = syncStatus, onSync = { vm.syncStockFromGoogleSheet() })
        }

        item {
            Card(
                onClick = { nav.navigate(Routes.SCAN) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(12.dp).size(30.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Nueva salida", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Fotografiar albarán → validar → picking → transporte → cierre")
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
        }

        item {
            Text("Indicadores operativos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("Pendientes", pending.toString(), "Preparaciones abiertas", Modifier.weight(1f))
                SummaryCard("Expedidos hoy", completedToday.toString(), "Albaranes cerrados", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("Stock total", totalUnits.toString(), "${products.size} referencias", Modifier.weight(1f))
                SummaryCard("Stock crítico", lowStock.toString(), "≤ 5 unidades", Modifier.weight(1f))
            }
        }

        if (withoutEan > 0 || lowStock > 0) {
            item {
                StatusPanel(
                    title = "Atención operativa",
                    message = buildString {
                        if (withoutEan > 0) append("$withoutEan referencias sin EAN. ")
                        if (lowStock > 0) append("$lowStock referencias con stock crítico.")
                    }.trim(),
                    tone = StatusTone.WARNING
                )
            }
        }

        item {
            Text("Accesos de almacén", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            DashboardCard(
                title = "Cola de operaciones",
                subtitle = "Reanuda preparaciones pendientes y revisa expediciones completadas.",
                icon = Icons.Default.AssignmentTurnedIn,
                onClick = { nav.navigate(Routes.OPERATIONS) }
            )
        }
        item {
            DashboardCard(
                title = "Inventario y maestro de EAN",
                subtitle = "Stock sincronizado con Google Sheets, ubicaciones y ajustes controlados.",
                icon = Icons.Default.Inventory2,
                onClick = { nav.navigate(Routes.INVENTORY) }
            )
        }
        item {
            DashboardCard(
                title = "Trazabilidad de stock",
                subtitle = "Consulta entradas, salidas y ajustes con stock resultante.",
                icon = Icons.Default.SwapVert,
                onClick = { nav.navigate(Routes.MOVEMENTS) }
            )
        }
        item {
            DashboardCard(
                title = "Recuento de inventario",
                subtitle = "Escanea un EAN, introduce el stock físico y registra la diferencia con trazabilidad.",
                icon = Icons.Default.FactCheck,
                onClick = { nav.navigate(Routes.COUNT) }
            )
        }

        if (history.isNotEmpty()) {
            item {
                Text("Actividad reciente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(history.take(3), key = { "home-note-${it.id}" }) { note ->
                CompactOperationCard(note = note, onClick = { nav.navigate(Routes.picking(note.id)) })
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProcessHeader(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SyncBanner(syncBusy: Boolean, syncStatus: String?, onSync: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (syncBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Maestro de productos", fontWeight = FontWeight.SemiBold)
                Text(
                    syncStatus ?: "Sincronización automática con Google Sheets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(enabled = !syncBusy, onClick = onSync) { Text("Actualizar") }
        }
    }
}

private enum class StatusTone { INFO, WARNING, ERROR, SUCCESS }

@Composable
private fun StatusPanel(
    title: String,
    message: String,
    tone: StatusTone,
    loading: Boolean = false
) {
    val container = when (tone) {
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.secondaryContainer
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.INFO -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (tone) {
        StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.INFO -> MaterialTheme.colorScheme.onSurface
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else if (tone == StatusTone.WARNING) Icon(Icons.Default.WarningAmber, contentDescription = null, tint = content)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = content)
                Text(message, style = MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}

@Composable
private fun WorkflowStepper(current: Int, labels: List<String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val step = index + 1
            val active = step == current
            val done = step < current
            Surface(
                modifier = Modifier.weight(1f),
                color = when {
                    active -> MaterialTheme.colorScheme.primaryContainer
                    done -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
                shape = MaterialTheme.shapes.small
            ) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (done) "✓" else step.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun OperationsScreen(vm: SgaViewModel, nav: NavHostController) {
    val history by vm.history.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val query = search.trim()
    val visible = if (query.isBlank()) history else history.filter {
        it.number.contains(query, ignoreCase = true) || it.customer.contains(query, ignoreCase = true)
    }
    val pending = visible.filter { it.status == DeliveryNoteEntity.STATUS_PENDING }
    val completed = visible.filter { it.status == DeliveryNoteEntity.STATUS_COMPLETED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProcessHeader(
                eyebrow = "COLA DE TRABAJO",
                title = "Operaciones de salida",
                subtitle = "Prioriza tareas pendientes, reanuda el picking y confirma expediciones desde el mismo flujo."
            )
        }
        item {
            Button(onClick = { nav.navigate(Routes.SCAN) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Crear nueva salida")
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Buscar albarán o cliente") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            Text("Pendientes · ${pending.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (pending.isEmpty()) {
            item { StatusPanel("Cola despejada", "No hay preparaciones pendientes con este filtro.", StatusTone.SUCCESS) }
        } else {
            items(pending, key = { "pending-${it.id}" }) { note ->
                CompactOperationCard(note, onClick = { nav.navigate(Routes.picking(note.id)) })
            }
        }
        item {
            Text("Completadas · ${completed.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(completed.take(30), key = { "completed-${it.id}" }) { note ->
            CompactOperationCard(note, onClick = { nav.navigate(Routes.picking(note.id)) })
        }
    }
}

@Composable
private fun CompactOperationCard(note: DeliveryNoteEntity, onClick: () -> Unit) {
    val completed = note.status == DeliveryNoteEntity.STATUS_COMPLETED
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = if (completed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    if (completed) Icons.Default.CheckCircle else Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp),
                    tint = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Albarán ${note.number}", fontWeight = FontWeight.Bold)
                Text(note.customer.ifBlank { "Cliente no indicado" }, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (completed) "Finalizado · ${formatDate(note.completedAt ?: note.createdAt)}" else "Pendiente · ${formatDate(note.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun MovementsScreen(vm: SgaViewModel) {
    val movements by vm.movements.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val visible = remember(movements, search) {
        val q = search.trim()
        if (q.isBlank()) movements else movements.filter {
            it.productReference.contains(q, ignoreCase = true) ||
                it.reason.contains(q, ignoreCase = true) ||
                it.deliveryNoteNumber.orEmpty().contains(q, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ProcessHeader(
                eyebrow = "TRAZABILIDAD",
                title = "Movimientos de stock",
                subtitle = "Auditoría cronológica de ajustes y salidas, con existencias resultantes después de cada movimiento."
            )
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Buscar referencia, motivo o albarán") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        if (visible.isEmpty()) {
            item { StatusPanel("Sin movimientos", "Todavía no hay movimientos que mostrar.", StatusTone.INFO) }
        } else {
            items(visible, key = { it.id }) { movement -> MovementCard(movement) }
        }
    }
}

@Composable
private fun MovementCard(movement: StockMovementEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = if (movement.delta >= 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (movement.delta >= 0) "+${movement.delta}" else movement.delta.toString(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    fontWeight = FontWeight.Bold
                )
            }
            Column(Modifier.weight(1f)) {
                Text(movement.productReference, fontWeight = FontWeight.Bold)
                Text(movement.reason, style = MaterialTheme.typography.bodySmall)
                movement.deliveryNoteNumber?.let { Text("Albarán $it", style = MaterialTheme.typography.labelSmall) }
                Text(formatDate(movement.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Stock", style = MaterialTheme.typography.labelSmall)
                Text(movement.stockAfter.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CountScreen(vm: SgaViewModel) {
    val product by vm.countProduct.collectAsStateWithLifecycle()
    val message by vm.countMessage.collectAsStateWithLifecycle()
    val busy by vm.scanBusy.collectAsStateWithLifecycle()
    var manualBarcode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.clearCountProduct()
        vm.clearCountMessage()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProcessHeader(
                eyebrow = "CONTROL DE INVENTARIO",
                title = "Recuento físico",
                subtitle = "Verifica existencias reales por EAN. Cada diferencia queda registrada como movimiento de recuento físico."
            )
        }
        item {
            StatusPanel(
                title = "Disciplina de recuento",
                message = "Escanea la etiqueta del producto, cuenta físicamente las unidades y confirma el valor real. El sistema registra la diferencia, no una edición silenciosa.",
                tone = StatusTone.INFO
            )
        }
        item {
            BarcodeCamera(
                enabled = !busy && product == null,
                onBarcode = vm::submitCountBarcode,
                modifier = Modifier.fillMaxWidth().height(300.dp)
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualBarcode,
                    onValueChange = { manualBarcode = it.filter(Char::isLetterOrDigit).uppercase() },
                    label = { Text("EAN manual / escáner físico") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (manualBarcode.isNotBlank() && !busy && product == null) {
                            vm.submitCountBarcode(manualBarcode)
                            manualBarcode = ""
                        }
                    })
                )
                FilledTonalButton(
                    enabled = manualBarcode.isNotBlank() && !busy && product == null,
                    onClick = {
                        vm.submitCountBarcode(manualBarcode)
                        manualBarcode = ""
                    }
                ) { Text("Buscar") }
            }
        }
        if (!message.isNullOrBlank()) {
            item {
                StatusPanel(
                    title = if (message.orEmpty().contains("registrado", ignoreCase = true)) "Recuento guardado" else "Resultado",
                    message = message.orEmpty(),
                    tone = if (message.orEmpty().contains("registrado", ignoreCase = true)) StatusTone.SUCCESS else StatusTone.WARNING
                )
            }
        }
    }

    product?.let { selected ->
        PhysicalCountDialog(
            product = selected,
            busy = busy,
            onDismiss = vm::clearCountProduct,
            onConfirm = vm::confirmPhysicalCount
        )
    }
}

@Composable
private fun PhysicalCountDialog(
    product: ProductEntity,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var countedText by remember(product.reference, product.stock) { mutableStateOf(product.stock.toString()) }
    val counted = countedText.toIntOrNull()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Confirmar recuento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(product.reference, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(product.description)
                Text("EAN: ${product.ean ?: "—"}", style = MaterialTheme.typography.bodySmall)
                Text("Stock registrado: ${product.stock}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = countedText,
                    onValueChange = { countedText = it.filter(Char::isDigit).take(7) },
                    label = { Text("Stock físico contado") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (counted != null) {
                    val delta = counted - product.stock
                    Text(
                        "Diferencia: ${if (delta >= 0) "+$delta" else delta.toString()}",
                        color = if (delta == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = counted != null && counted >= 0 && !busy, onClick = { counted?.let(onConfirm) }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Registrar recuento")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun isToday(epoch: Long): Boolean {
    val formatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return formatter.format(Date(epoch)) == formatter.format(Date())
}

@Composable
private fun InventoryScreen(vm: SgaViewModel) {
    val products by vm.products.collectAsStateWithLifecycle()
    val syncBusy by vm.syncBusy.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ProductEntity?>(null) }
    var showNew by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ProcessHeader(
            eyebrow = "MAESTRO DE PRODUCTOS",
            title = "Inventario",
            subtitle = "Consulta CÓDIGO, EAN, ubicación y existencias. Los ajustes manuales quedan registrados en trazabilidad."
        )
        Spacer(Modifier.height(12.dp))
        SyncBanner(syncBusy = syncBusy, syncStatus = syncStatus, onSync = { vm.syncStockFromGoogleSheet() })

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Buscar código, EAN o producto") },
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
                Icon(Icons.Default.UploadFile, contentDescription = "Importar CSV manual")
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
                    Text("Pulsa Sincronizar. La hoja debe contener una columna CÓDIGO/REFERENCIA y una columna EAN.")
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
                            text = "EAN: ${product.ean ?: "sin EAN"} · Ubicación: ${product.location.ifBlank { "—" }}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        product.sheetStock?.let { sourceStock ->
                            Text(
                                text = "Stock base hoja: $sourceStock · Ajuste SGA: ${product.stock - sourceStock}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
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
    val busy by vm.ocrBusy.collectAsStateWithLifecycle()
    val error by vm.ocrError.collectAsStateWithLifecycle()
    val syncBusy by vm.syncBusy.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.syncStockFromGoogleSheet(showMessage = false)
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.scanDeliveryNote(uri) { nav.navigate(Routes.REVIEW) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProcessHeader(
                eyebrow = "SALIDAS · OCR DOCUMENTAL",
                title = "Capturar albarán",
                subtitle = "El sistema identifica el número situado bajo ALBARÁN y toma las referencias exclusivamente de la columna ARTÍCULO."
            )
        }

        item {
            WorkflowStepper(current = 1, labels = listOf("Documento", "Picking", "Transporte", "Cierre"))
        }

        item {
            SyncBanner(syncBusy = syncBusy, syncStatus = syncStatus, onSync = { vm.syncStockFromGoogleSheet() })
        }

        if (!busy) {
            item {
                DocumentCamera(
                    enabled = !syncBusy,
                    onCaptured = { file ->
                        vm.clearOcrError()
                        vm.scanDeliveryNoteFile(file) { nav.navigate(Routes.REVIEW) }
                    },
                    onError = { message, cause -> vm.reportUiError(message, cause) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp)
                )
            }
        }

        if (busy) {
            item {
                StatusPanel(
                    title = "Analizando documento",
                    message = "OCR en curso. Estamos localizando ALBARÁN, ARTÍCULO y CANTIDAD.",
                    tone = StatusTone.INFO,
                    loading = true
                )
            }
        }

        if (!error.isNullOrBlank()) {
            item {
                StatusPanel(
                    title = "No se pudo leer la captura",
                    message = error.orEmpty(),
                    tone = StatusTone.ERROR
                )
            }
        }

        item {
            OutlinedButton(
                enabled = !busy && !syncBusy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    runCatching { imageLauncher.launch(arrayOf("image/*")) }
                        .onFailure { cause -> vm.reportUiError("No se pudo abrir el selector de imágenes", cause) }
                }
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Usar una fotografía existente")
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Criterios de lectura", fontWeight = FontWeight.Bold)
                    Text("• Nº de albarán: primer valor válido inmediatamente debajo de ALBARÁN.", style = MaterialTheme.typography.bodySmall)
                    Text("• Referencias: únicamente valores situados debajo de ARTÍCULO.", style = MaterialTheme.typography.bodySmall)
                    Text("• Cantidad: se cruza por la misma línea horizontal con CANTIDAD.", style = MaterialTheme.typography.bodySmall)
                    Text("• Antes de crear la salida siempre existe una pantalla de revisión.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ReviewDeliveryNoteScreen(vm: SgaViewModel, nav: NavHostController) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val syncBusy by vm.syncBusy.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
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
            ProcessHeader(
                eyebrow = "CONTROL DE DOCUMENTO",
                title = "Validar lectura OCR",
                subtitle = "Confirma el albarán y las líneas antes de generar la orden de picking. Ningún dato OCR afecta al stock sin esta validación."
            )
        }
        item {
            WorkflowStepper(current = 1, labels = listOf("Documento", "Picking", "Transporte", "Cierre"))
        }
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

        item {
            if (syncBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Actualizando referencias de Google Sheets…", style = MaterialTheme.typography.bodySmall)
                }
            } else if (!syncStatus.isNullOrBlank()) {
                Text(syncStatus.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        }

        itemsIndexed(current.lines) { index, line ->
            val lineKey = SgaRepository.normalizeReference(line.reference)
            val matched = products.firstOrNull { product ->
                SgaRepository.normalizeReference(product.reference) == lineKey
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when {
                            matched != null -> "Código relacionado con Google Sheets"
                            syncBusy -> "Comprobando código en Google Sheets…"
                            else -> "Revisar código: no aparece en inventario"
                        },
                        color = if (matched != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (matched != null) {
                        Text(
                            "EAN: ${matched.ean ?: "NO CONFIGURADO"} · Stock: ${matched.stock}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
    val scanBusy by vm.scanBusy.collectAsStateWithLifecycle()
    val pendingProductScan by vm.pendingProductScan.collectAsStateWithLifecycle()
    var manualBarcode by remember(noteId) { mutableStateOf("") }
    var manualTransportBarcode by remember(noteId) { mutableStateOf("") }

    LaunchedEffect(noteId) {
        vm.clearScanMessage()
        vm.clearPendingProductScan()
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
    val hasTransportLabels = data.transportLabels.isNotEmpty()

    LaunchedEffect(pickingDone) {
        if (pickingDone) vm.clearScanMessage()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProcessHeader(
                eyebrow = if (completed) "EXPEDICIÓN FINALIZADA" else "ORDEN DE SALIDA",
                title = "Albarán ${data.note.number}",
                subtitle = data.note.customer.ifBlank { "Preparación guiada por EAN con control de cantidades y trazabilidad." }
            )
        }
        item {
            val currentStep = when {
                completed -> 4
                !pickingDone -> 2
                else -> 3
            }
            WorkflowStepper(current = currentStep, labels = listOf("Documento", "Picking", "Transporte", "Cierre"))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (completed) "Completado" else "Progreso de preparación", fontWeight = FontWeight.Bold)
                        Text("$totalPicked / $totalExpected uds.", fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        when {
                            completed -> "Salida cerrada con ${data.transportLabels.size} etiqueta(s) de transporte."
                            pickingDone -> "Picking completo. Escanea las etiquetas de transporte para habilitar el cierre."
                            else -> "Escanea el EAN del siguiente producto y confirma las unidades."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(data.lines, key = { it.id }) { line ->
            val lineComplete = line.pickedQty == line.expectedQty
            val product = data.productsByReference[line.productReference]
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
                        Text(
                            "EAN: ${product?.ean ?: "NO CONFIGURADO"} · Stock: ${product?.stock ?: 0}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "${line.pickedQty}/${line.expectedQty}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        if (!completed && !pickingDone) {
            item {
                Text("Picking de productos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "El albarán contiene el CÓDIGO del artículo. La aplicación busca ese CÓDIGO en Google Sheets, obtiene su EAN y solo acepta el código de barras que pertenezca a ese artículo. Al escanear podrás indicar manualmente cuántas unidades quieres añadir.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                BarcodeCamera(
                    enabled = !scanBusy && pendingProductScan == null,
                    onBarcode = { code -> vm.submitBarcode(noteId, code) },
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
            }

            if (scanBusy) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Validando código…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualBarcode,
                        onValueChange = { manualBarcode = it.filter(Char::isLetterOrDigit).uppercase() },
                        label = { Text("EAN manual / escáner físico") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (manualBarcode.isNotBlank() && !scanBusy && pendingProductScan == null) {
                                vm.submitBarcode(noteId, manualBarcode)
                                manualBarcode = ""
                            }
                        })
                    )
                    FilledTonalButton(
                        enabled = manualBarcode.isNotBlank() && !scanBusy && pendingProductScan == null,
                        onClick = {
                            vm.submitBarcode(noteId, manualBarcode)
                            manualBarcode = ""
                        }
                    ) { Text("Validar") }
                }
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

        if (!completed && pickingDone) {
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Etiquetas de transporte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    "El picking está completo. Escanea ahora todas las etiquetas de transporte del envío. Puedes registrar varias; no se admiten duplicadas.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                BarcodeCamera(
                    enabled = !scanBusy,
                    onBarcode = { code -> vm.submitTransportLabel(noteId, code) },
                    modifier = Modifier.fillMaxWidth().height(260.dp)
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualTransportBarcode,
                        onValueChange = { manualTransportBarcode = it.uppercase() },
                        label = { Text("Etiqueta manual / escáner físico") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (manualTransportBarcode.isNotBlank() && !scanBusy) {
                                vm.submitTransportLabel(noteId, manualTransportBarcode)
                                manualTransportBarcode = ""
                            }
                        })
                    )
                    FilledTonalButton(
                        enabled = manualTransportBarcode.isNotBlank() && !scanBusy,
                        onClick = {
                            vm.submitTransportLabel(noteId, manualTransportBarcode)
                            manualTransportBarcode = ""
                        }
                    ) { Text("Añadir") }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Etiquetas registradas: ${data.transportLabels.size}",
                            fontWeight = FontWeight.Bold
                        )
                        if (data.transportLabels.isEmpty()) {
                            Text("Debes registrar al menos una etiqueta antes de finalizar.")
                        } else {
                            data.transportLabels.take(8).forEach { label ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "✓ ${label.barcode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { vm.removeTransportLabel(noteId, label.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Eliminar etiqueta")
                                    }
                                }
                            }
                            if (data.transportLabels.size > 8) {
                                Text("… y ${data.transportLabels.size - 8} más", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
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
                    enabled = hasTransportLabels && !scanBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Finalizar y cerrar albarán")
                }
            }
        }

        if (data.scanLogs.isNotEmpty()) {
            item {
                Text("Últimas lecturas de producto", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            items(data.scanLogs.take(5), key = { it.id }) { log ->
                Text(
                    text = "${if (log.accepted) "✓" else "✕"} ${log.barcode} · ${log.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (log.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }

        if (completed) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Salida registrada", fontWeight = FontWeight.Bold)
                        Text("El albarán está cerrado y las existencias locales del SGA ya fueron descontadas.")
                        Text("Etiquetas de transporte: ${data.transportLabels.size}")
                        data.transportLabels.take(5).forEach { Text(it.barcode, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    pendingProductScan?.let { candidate ->
        ProductQuantityDialog(
            candidate = candidate,
            busy = scanBusy,
            onDismiss = vm::clearPendingProductScan,
            onConfirm = { quantity -> vm.confirmProductScan(noteId, quantity) }
        )
    }
}

@Composable
private fun ProductQuantityDialog(
    candidate: ProductScanCandidate,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var quantityText by remember(candidate.barcode, candidate.pickedQty) { mutableStateOf("1") }
    val quantity = quantityText.toIntOrNull()
    val valid = quantity != null && quantity in 1..candidate.remainingQty

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Añadir unidades") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(candidate.reference, fontWeight = FontWeight.Bold)
                Text(candidate.description)
                Text("EAN: ${candidate.ean}")
                Text("Picado: ${candidate.pickedQty}/${candidate.expectedQty} · Pendientes: ${candidate.remainingQty}")
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { value -> quantityText = value.filter(Char::isDigit).take(5) },
                    label = { Text("Unidades a añadir") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !busy
                )
                if (quantity != null && quantity > candidate.remainingQty) {
                    Text(
                        "Máximo ${candidate.remainingQty} unidades",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid && !busy,
                onClick = { onConfirm(quantity ?: 1) }
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Añadir ${quantity ?: 0}")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun HistoryScreen(vm: SgaViewModel, nav: NavHostController) {
    val history by vm.history.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ProcessHeader(
                eyebrow = "AUDITORÍA DE SALIDAS",
                title = "Historial",
                subtitle = "Consulta albaranes pendientes y finalizados junto con sus lecturas y etiquetas de transporte."
            )
        }
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
