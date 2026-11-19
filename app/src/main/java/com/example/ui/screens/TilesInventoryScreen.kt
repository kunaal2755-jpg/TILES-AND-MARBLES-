package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.firebase.Formatters
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.utils.CataloguePdfConfig
import com.example.utils.ImageUtils
import com.example.utils.IntentUtils
import com.example.utils.PdfGenerator
import java.io.File

@Composable
fun TilesInventoryScreen(
    shop: Shop,
    tiles: List<TileProduct>,
    tileSizes: List<TileSizeConfig>,
    locations: List<StockLocation>,
    movements: List<StockMovement>,
    lowStockThreshold: Long,
    openAddTrigger: Int,
    onSaveTile: (TileProduct) -> Unit,
    onDeleteTile: (TileProduct) -> Unit,
    onAdjustStock: (TileProduct, String, String, String, Long, Long, String) -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf("All") }
    var selectedSizeFilter by remember { mutableStateOf("All Sizes") }
    var selectedLocationFilter by remember { mutableStateOf("All Locations") }

    var editingTile by remember { mutableStateOf<TileProduct?>(null) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var deletingTile by remember { mutableStateOf<TileProduct?>(null) }
    var adjustingTile by remember { mutableStateOf<TileProduct?>(null) }
    var showMovementsDialog by remember { mutableStateOf(false) }
    var showCataloguePdfDialog by remember { mutableStateOf(false) }
    var generatedPdf by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(openAddTrigger) {
        if (openAddTrigger > 0) {
            editingTile = null
            showAddEditDialog = true
        }
    }

    val filteredTiles = remember(tiles, search, activeFilter, selectedSizeFilter, selectedLocationFilter, lowStockThreshold) {
        tiles.filter { t ->
            val q = search.trim().lowercase()
            val matchesQuery = q.isBlank() || listOf(t.name, t.code, t.sizeName, t.category, t.brand, t.shadeNumber, t.batchNumber)
                .any { it.lowercase().contains(q) }
            val matchesFilter = when (activeFilter) {
                "Low Stock" -> t.totalBoxes <= lowStockThreshold
                "Floor", "Wall", "Marble", "Sanitary" -> t.category.equals(activeFilter, ignoreCase = true)
                else -> true
            }
            val matchesSize = selectedSizeFilter == "All Sizes" || t.sizeName == selectedSizeFilter
            val matchesLoc = selectedLocationFilter == "All Locations" || (t.locationBoxes[selectedLocationFilter] ?: 0L) > 0L
            matchesQuery && matchesFilter && matchesSize && matchesLoc
        }
    }

    val totalBoxes = remember(tiles) { tiles.sumOf { it.totalBoxes } }

    Box(modifier = Modifier.fillMaxSize().background(LightBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Summary Strip
            Surface(color = SecondaryNavy, contentColor = Color.White) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Tile Inventory & Stock", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text("${tiles.size} Products  •  $totalBoxes Total Boxes", style = MaterialTheme.typography.bodySmall, color = AmberGold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { showMovementsDialog = true }) {
                            Icon(Icons.Default.History, contentDescription = "Stock Movement History", tint = Color.White)
                        }
                        Button(
                            onClick = { showCataloguePdfDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = RoyalNavy),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("pdf_catalogue_button")
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PDF Catalogue", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Search & Filters
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search name, code, size, brand, shade, batch...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("tile_search_input")
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Low Stock", "Floor", "Wall", "Marble").forEach { f ->
                        FilterChip(
                            selected = activeFilter == f,
                            onClick = { activeFilter = f },
                            label = { Text(f) }
                        )
                    }
                    tileSizes.filter { it.enabled }.forEach { sz ->
                        FilterChip(
                            selected = selectedSizeFilter == sz.name,
                            onClick = { selectedSizeFilter = if (selectedSizeFilter == sz.name) "All Sizes" else sz.name },
                            label = { Text(sz.name) }
                        )
                    }
                    locations.forEach { loc ->
                        FilterChip(
                            selected = selectedLocationFilter == loc.name,
                            onClick = { selectedLocationFilter = if (selectedLocationFilter == loc.name) "All Locations" else loc.name },
                            label = { Text(loc.name) }
                        )
                    }
                }
            }

            if (filteredTiles.isEmpty()) {
                EmptyStateView(
                    title = "No Tiles Found",
                    subtitle = "Add your tiles & marble slabs with multi-location stock.",
                    actionLabel = "Add Tile Product",
                    onAction = { editingTile = null; showAddEditDialog = true }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 84.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredTiles, key = { it.id }) { tile ->
                        PremiumCard {
                            Row(verticalAlignment = Alignment.Top) {
                                TileThumbnail(tile.photoBase64, tile.name, Modifier.size(74.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(tile.name, style = MaterialTheme.typography.titleMedium, color = RoyalNavy, modifier = Modifier.weight(1f))
                                        Row {
                                            IconButton(
                                                onClick = { editingTile = tile; showAddEditDialog = true },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Edit Tile", tint = RoyalNavy, modifier = Modifier.size(18.dp))
                                            }
                                            IconButton(
                                                onClick = { deletingTile = tile },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Tile", tint = DangerRed, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                    Text(
                                        "Code: ${tile.code} • ${tile.sizeName} • ${tile.brand.ifBlank { tile.category }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateMuted
                                    )
                                    Text(
                                        "Shade: ${tile.shadeNumber.ifBlank { "-" }} • Batch: ${tile.batchNumber.ifBlank { "-" }} • ${tile.quality}/${tile.finish}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateMuted
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                        StatusBadge(
                                            "${tile.totalBoxes} Box + ${tile.loosePieces} Pcs",
                                            if (tile.totalBoxes <= lowStockThreshold) DangerRedSoft else SuccessGreenSoft,
                                            if (tile.totalBoxes <= lowStockThreshold) DangerRed else SuccessGreen
                                        )
                                        StatusBadge("${Formatters.currency(tile.sellingPricePerBox)}/Box", AmberGoldSoft, AmberGoldDark)
                                        if (tile.damagedPieces > 0) StatusBadge("Dmg: ${tile.damagedPieces}", DangerRedSoft, DangerRed)
                                        if (tile.samplePieces > 0) StatusBadge("Sample: ${tile.samplePieces}", InfoBlueSoft, InfoBlue)
                                    }
                                    if (tile.locationBoxes.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            tile.locationBoxes.entries.joinToString(" | ") { "${it.key}: ${it.value}" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SlateDark
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { adjustingTile = tile },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Adjust / Transfer Stock", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editingTile = null; showAddEditDialog = true },
            containerColor = RoyalNavy,
            contentColor = AmberGold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_tile_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Tile Product")
        }
    }

    if (showAddEditDialog) {
        AddEditTileDialog(
            existing = editingTile,
            tileSizes = tileSizes.filter { it.enabled },
            locations = locations,
            onDismiss = { showAddEditDialog = false },
            onSave = { tile ->
                onSaveTile(tile)
                showAddEditDialog = false
            }
        )
    }

    if (deletingTile != null) {
        ConfirmDialog(
            title = "Soft Delete Tile?",
            message = "Move '${deletingTile!!.name}' (${deletingTile!!.code}) to Recycle Bin?",
            confirmText = "Move to Bin",
            onConfirm = {
                onDeleteTile(deletingTile!!)
                deletingTile = null
            },
            onDismiss = { deletingTile = null }
        )
    }

    if (adjustingTile != null) {
        StockAdjustmentDialog(
            tile = adjustingTile!!,
            locations = locations.map { it.name }.ifEmpty { listOf("Main Shop", "Warehouse 1", "Warehouse 2") },
            onDismiss = { adjustingTile = null },
            onConfirm = { type, from, to, boxes, pcs, reason ->
                onAdjustStock(adjustingTile!!, type, from, to, boxes, pcs, reason)
                adjustingTile = null
            }
        )
    }

    if (showMovementsDialog) {
        AlertDialog(
            onDismissRequest = { showMovementsDialog = false },
            containerColor = CardWhite,
            title = { Text("Stock Movement History", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (movements.isEmpty()) {
                        Text("No stock movements recorded yet.", color = SlateMuted)
                    } else {
                        movements.sortedByDescending { it.createdAt?.seconds ?: 0L }.take(40).forEach { m ->
                            Surface(color = LightBackground, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("${m.movementType}: ${m.tileName}", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                    Text("${m.boxesDelta} Box, ${m.piecesDelta} Pcs • ${m.fromLocation} -> ${m.toLocation}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                    Text("${m.reason} • By ${m.performedBy}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMovementsDialog = false }) { Text("Close") } }
        )
    }

    if (showCataloguePdfDialog) {
        CataloguePdfConfigDialog(
            shop = shop,
            tiles = tiles,
            tileSizes = tileSizes,
            onDismiss = { showCataloguePdfDialog = false },
            onGenerated = { file ->
                showCataloguePdfDialog = false
                generatedPdf = file
            }
        )
    }

    if (generatedPdf != null) {
        PdfActionDialog(file = generatedPdf!!, onDismiss = { generatedPdf = null })
    }
}

@Composable
private fun AddEditTileDialog(
    existing: TileProduct?,
    tileSizes: List<TileSizeConfig>,
    locations: List<StockLocation>,
    onDismiss: () -> Unit,
    onSave: (TileProduct) -> Unit
) {
    val context = LocalContext.current
    var code by remember { mutableStateOf(existing?.code ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var selectedSize by remember { mutableStateOf(existing?.sizeName ?: (tileSizes.firstOrNull()?.name ?: "2x4")) }
    var piecesPerBox by remember { mutableStateOf((existing?.piecesPerBox ?: tileSizes.firstOrNull { it.name == selectedSize }?.piecesPerBox ?: 4L).toString()) }
    var brand by remember { mutableStateOf(existing?.brand ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "Floor") }
    var shade by remember { mutableStateOf(existing?.shadeNumber ?: "") }
    var batch by remember { mutableStateOf(existing?.batchNumber ?: "") }
    var quality by remember { mutableStateOf(existing?.quality ?: "Premium") }
    var finish by remember { mutableStateOf(existing?.finish ?: "Glossy") }
    var loosePieces by remember { mutableStateOf((existing?.loosePieces ?: 0L).toString()) }
    var reservedPieces by remember { mutableStateOf((existing?.reservedPieces ?: 0L).toString()) }
    var priceBox by remember { mutableStateOf((existing?.sellingPricePerBox ?: 0.0).let { if (it == 0.0) "" else it.toString() }) }
    var pricePiece by remember { mutableStateOf((existing?.sellingPricePerPiece ?: 0.0).let { if (it == 0.0) "" else it.toString() }) }
    var purchaseCost by remember { mutableStateOf((existing?.purchaseCost ?: 0.0).let { if (it == 0.0) "" else it.toString() }) }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var photoBase64 by remember { mutableStateOf(existing?.photoBase64 ?: "") }

    val locNames = remember(locations) { locations.map { it.name }.ifEmpty { listOf("Main Shop", "Warehouse 1", "Warehouse 2") } }
    val locBoxes = remember {
        mutableStateMapOf<String, String>().apply {
            locNames.forEach { loc -> put(loc, (existing?.locationBoxes?.get(loc) ?: 0L).toString()) }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            photoBase64 = ImageUtils.uriToCompressedBase64(context, uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text(if (existing == null) "Add Tile Product" else "Edit Tile Product", color = RoyalNavy) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TileThumbnail(photoBase64, "Tile Preview", Modifier.size(64.dp))
                    OutlinedButton(onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Photo")
                    }
                }
                FormInputField(code, { code = it }, "Tile Code *")
                FormInputField(name, { name = it }, "Tile Name *")
                Text("Select Size:", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tileSizes.forEach { sz ->
                        FilterChip(
                            selected = selectedSize == sz.name,
                            onClick = {
                                selectedSize = sz.name
                                piecesPerBox = sz.piecesPerBox.toString()
                            },
                            label = { Text(sz.name) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(piecesPerBox, { piecesPerBox = it }, "Pcs/Box", Modifier.weight(1f), KeyboardType.Number)
                    FormInputField(loosePieces, { loosePieces = it }, "Loose Pcs", Modifier.weight(1f), KeyboardType.Number)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(brand, { brand = it }, "Brand", Modifier.weight(1f))
                    FormInputField(category, { category = it }, "Category", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(shade, { shade = it }, "Shade No.", Modifier.weight(1f))
                    FormInputField(batch, { batch = it }, "Batch No.", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(quality, { quality = it }, "Quality", Modifier.weight(1f))
                    FormInputField(finish, { finish = it }, "Finish", Modifier.weight(1f))
                }
                Text("Multi-Location Box Stock:", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                locNames.forEach { loc ->
                    FormInputField(
                        value = locBoxes[loc] ?: "0",
                        onValueChange = { locBoxes[loc] = it },
                        label = "$loc (Boxes)",
                        keyboardType = KeyboardType.Number
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(priceBox, { priceBox = it }, "Sell/Box (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                    FormInputField(pricePiece, { pricePiece = it }, "Sell/Pcs (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(purchaseCost, { purchaseCost = it }, "Cost/Box (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                    FormInputField(reservedPieces, { reservedPieces = it }, "Reserved Pcs", Modifier.weight(1f), KeyboardType.Number)
                }
                FormInputField(description, { description = it }, "Description", singleLine = false)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (code.isBlank() || name.isBlank()) return@Button
                    val ppb = (piecesPerBox.toLongOrNull() ?: 4L).coerceAtLeast(1L)
                    val pBox = priceBox.toDoubleOrNull() ?: 0.0
                    val pPcs = pricePiece.toDoubleOrNull() ?: if (ppb > 0) pBox / ppb else 0.0
                    val locMap = locBoxes.mapValues { (_, v) -> (v.toLongOrNull() ?: 0L).coerceAtLeast(0L) }
                    val sizeObj = tileSizes.firstOrNull { it.name == selectedSize }
                    val model = (existing ?: TileProduct()).copy(
                        code = code.trim(),
                        name = name.trim(),
                        sizeId = sizeObj?.id ?: "",
                        sizeName = selectedSize,
                        brand = brand.trim(),
                        category = category.trim().ifBlank { "Floor" },
                        shadeNumber = shade.trim(),
                        batchNumber = batch.trim(),
                        quality = quality.trim().ifBlank { "Premium" },
                        finish = finish.trim().ifBlank { "Glossy" },
                        piecesPerBox = ppb,
                        locationBoxes = locMap,
                        totalBoxes = locMap.values.sum(),
                        loosePieces = (loosePieces.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                        reservedPieces = (reservedPieces.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                        sellingPricePerBox = pBox,
                        sellingPricePerPiece = pPcs,
                        purchaseCost = purchaseCost.toDoubleOrNull() ?: 0.0,
                        description = description.trim(),
                        photoBase64 = photoBase64
                    )
                    onSave(model)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
            ) { Text("Save Product") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StockAdjustmentDialog(
    tile: TileProduct,
    locations: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, Long, Long, String) -> Unit
) {
    val types = listOf("STOCK_IN", "STOCK_OUT", "TRANSFER", "DAMAGE", "SAMPLE", "CORRECTION")
    var selectedType by remember { mutableStateOf("STOCK_IN") }
    var fromLoc by remember { mutableStateOf(locations.first()) }
    var toLoc by remember { mutableStateOf(locations.getOrElse(1) { locations.first() }) }
    var boxes by remember { mutableStateOf("0") }
    var pieces by remember { mutableStateOf("0") }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("Adjust Stock: ${tile.name}", color = RoyalNavy) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t ->
                        FilterChip(selected = selectedType == t, onClick = { selectedType = t }, label = { Text(t) })
                    }
                }
                if (selectedType in listOf("STOCK_OUT", "TRANSFER", "DAMAGE", "SAMPLE")) {
                    Text("From Location:", style = MaterialTheme.typography.labelSmall)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        locations.forEach { l -> FilterChip(selected = fromLoc == l, onClick = { fromLoc = l }, label = { Text(l) }) }
                    }
                }
                if (selectedType in listOf("STOCK_IN", "TRANSFER", "CORRECTION")) {
                    Text("To Location:", style = MaterialTheme.typography.labelSmall)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        locations.forEach { l -> FilterChip(selected = toLoc == l, onClick = { toLoc = l }, label = { Text(l) }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(boxes, { boxes = it }, "Boxes", Modifier.weight(1f), KeyboardType.Number)
                    FormInputField(pieces, { pieces = it }, "Pieces", Modifier.weight(1f), KeyboardType.Number)
                }
                FormInputField(reason, { reason = it }, "Reason / Note *")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        selectedType,
                        fromLoc,
                        toLoc,
                        boxes.toLongOrNull() ?: 0L,
                        pieces.toLongOrNull() ?: 0L,
                        reason.ifBlank { selectedType }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
            ) { Text("Apply Adjustment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CataloguePdfConfigDialog(
    shop: Shop,
    tiles: List<TileProduct>,
    tileSizes: List<TileSizeConfig>,
    onDismiss: () -> Unit,
    onGenerated: (File) -> Unit
) {
    val context = LocalContext.current
    var categoryFilter by remember { mutableStateOf("All") }
    var sizeFilter by remember { mutableStateOf("All") }
    var cfg by remember { mutableStateOf(CataloguePdfConfig(paperSize = shop.defaultPaperSize, showPrices = shop.showPricesOnPdfDefault)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("Generate Tile Catalogue PDF", color = RoyalNavy) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Filter Category:", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("All") + tiles.map { it.category }.distinct()).forEach { c ->
                        FilterChip(selected = categoryFilter == c, onClick = { categoryFilter = c }, label = { Text(c) })
                    }
                }
                Text("Filter Size:", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("All") + tileSizes.map { it.name }.distinct()).forEach { sz ->
                        FilterChip(selected = sizeFilter == sz, onClick = { sizeFilter = sz }, label = { Text(sz) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Show Prices ON/OFF", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = cfg.showPrices, onCheckedChange = { cfg = cfg.copy(showPrices = it) })
                }
                HorizontalDivider()
                Text("Select Fields to Include:", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                PdfCheckRow("Tile Image (Per-Size Dimensions)", cfg.includeImage) { cfg = cfg.copy(includeImage = it) }
                PdfCheckRow("Tile Name", cfg.includeName) { cfg = cfg.copy(includeName = it) }
                PdfCheckRow("Tile Code & Shade", cfg.includeCode) { cfg = cfg.copy(includeCode = it) }
                PdfCheckRow("Size", cfg.includeSize) { cfg = cfg.copy(includeSize = it) }
                PdfCheckRow("Quality", cfg.includeQuality) { cfg = cfg.copy(includeQuality = it) }
                PdfCheckRow("Finish", cfg.includeFinish) { cfg = cfg.copy(includeFinish = it) }
                PdfCheckRow("Pieces Per Box", cfg.includePiecesPerBox) { cfg = cfg.copy(includePiecesPerBox = it) }
                PdfCheckRow("Available Boxes", cfg.includeBoxes) { cfg = cfg.copy(includeBoxes = it) }
                PdfCheckRow("Loose Pieces", cfg.includeLoosePieces) { cfg = cfg.copy(includeLoosePieces = it) }
                PdfCheckRow("Total Square Feet", cfg.includeTotalSqFt) { cfg = cfg.copy(includeTotalSqFt = it) }
                PdfCheckRow("Selling Price", cfg.includeSellingPrice) { cfg = cfg.copy(includeSellingPrice = it) }
                PdfCheckRow("Purchase Cost (Confidential)", cfg.includePurchaseCost) { cfg = cfg.copy(includePurchaseCost = it) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val filtered = tiles.filter {
                        (categoryFilter == "All" || it.category == categoryFilter) &&
                            (sizeFilter == "All" || it.sizeName == sizeFilter)
                    }
                    val file = PdfGenerator.generateTileCataloguePdf(context, shop, filtered, tileSizes, cfg)
                    onGenerated(file)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
            ) { Text("Generate PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PdfCheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PdfActionDialog(
    file: File,
    recipientPhone: String? = null,
    recipientName: String? = null,
    captionText: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hasPhone = !recipientPhone.isNullOrBlank() && recipientPhone.filter { it.isDigit() }.length >= 10
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = RoyalNavy)
                Spacer(Modifier.width(8.dp))
                Text("PDF Ready", color = RoyalNavy)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Generated ${file.name}. Open, print, or share via WhatsApp.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SlateDark
                )
                if (hasPhone) {
                    Surface(
                        color = SuccessGreenSoft,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Direct WhatsApp Ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SuccessGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${recipientName?.ifBlank { "Customer" } ?: "Customer"} • $recipientPhone",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateDark
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { IntentUtils.viewPdf(context, file) }) { Text("Open") }
                OutlinedButton(onClick = { IntentUtils.printPdf(context, file, file.name) }) { Text("Print") }
                Button(
                    onClick = {
                        IntentUtils.sharePdf(
                            context = context,
                            file = file,
                            title = file.name,
                            preferWhatsApp = true,
                            phoneNumber = if (hasPhone) recipientPhone else null,
                            captionText = captionText
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    modifier = Modifier.testTag("pdf_whatsapp_button")
                ) { Text("WhatsApp") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { IntentUtils.sharePdf(context, file, file.name, preferWhatsApp = false) }) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
