package com.example.ui.screens

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.firebase.Formatters
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.utils.IntentUtils
import com.example.utils.PdfGenerator
import java.io.File

@Composable
fun DeliveryAndReturnScreen(
    shop: Shop,
    sales: List<SaleOrder>,
    tiles: List<TileProduct>,
    customers: List<Customer>,
    deliveries: List<DeliveryRecord>,
    returns: List<ReturnExchange>,
    preselectedSale: SaleOrder?,
    onRecordPartialDelivery: (SaleOrder, Map<Int, Long>, Double, String, (DeliveryRecord) -> Unit) -> Unit,
    onRecordReturnExchange: (ReturnExchange, TileProduct?, TileProduct?, Customer?) -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 = Partial Deliveries, 1 = Returns & Exchanges
    var activeSaleForDelivery by remember(preselectedSale) { mutableStateOf(preselectedSale) }
    var showReturnDialog by remember { mutableStateOf(false) }
    var generatedPdf by remember { mutableStateOf<File?>(null) }
    var pdfTargetPhone by remember { mutableStateOf<String?>(null) }
    var pdfTargetName by remember { mutableStateOf<String?>(null) }
    var pdfCaption by remember { mutableStateOf<String?>(null) }

    val confirmedSales = remember(sales) { sales.filter { it.orderStatus == "CONFIRMED" } }

    Column(modifier = Modifier.fillMaxSize().background(LightBackground)) {
        TabRow(selectedTabIndex = tab, containerColor = SecondaryNavy, contentColor = AmberGold) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Partial Deliveries") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Returns & Exchanges") })
        }

        if (tab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SectionHeaderRow("Pending / Partial Delivery Invoices")
                }
                items(confirmedSales, key = { it.id }) { sale ->
                    val totalPcs = sale.items.sumOf { it.totalPieces() }
                    val delPcs = sale.items.sumOf { it.deliveredPieces }
                    val remPcs = (totalPcs - delPcs).coerceAtLeast(0L)
                    PremiumCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("#${sale.invoiceNumber} • ${sale.customerName}", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                Text("Delivered: $delPcs / $totalPcs Pcs  •  Remaining: $remPcs Pcs", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                            }
                            if (remPcs > 0) {
                                Button(
                                    onClick = { activeSaleForDelivery = sale },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                                ) { Text("Deliver") }
                            } else {
                                StatusBadge("FULLY DELIVERED", SuccessGreenSoft, SuccessGreen)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    SectionHeaderRow("Delivery Challan History (${deliveries.size})")
                }
                items(deliveries.sortedByDescending { it.deliveryDateMillis }, key = { it.id }) { del ->
                    PremiumCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Challan for #${del.invoiceNumber} • ${del.customerName}", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                Text("${Formatters.date(del.deliveryDateMillis)} • Loading: ${Formatters.currency(del.loadingCharges)}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                            }
                            OutlinedButton(onClick = {
                                val file = PdfGenerator.generateDeliveryChallanPdf(context, shop, del)
                                val custPhone = customers.firstOrNull { it.id == del.customerId }?.mobile
                                    ?: sales.firstOrNull { it.id == del.saleId }?.customerMobile
                                IntentUtils.sharePdf(
                                    context = context,
                                    file = file,
                                    title = "Delivery Challan",
                                    preferWhatsApp = true,
                                    phoneNumber = custPhone,
                                    captionText = "${shop.shopName} - Delivery Challan #${del.invoiceNumber}"
                                )
                            }) { Text("WhatsApp PDF") }
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 84.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        SectionHeaderRow("Return & Exchange History (${returns.size})")
                    }
                    if (returns.isEmpty()) {
                        item {
                            EmptyStateView(
                                title = "No Returns or Exchanges",
                                subtitle = "Record returns with Good/Damaged condition & replacement products.",
                                actionLabel = "Record Return / Exchange",
                                onAction = { showReturnDialog = true }
                            )
                        }
                    } else {
                        items(returns.sortedByDescending { it.returnDateMillis }, key = { it.id }) { r ->
                            PremiumCard {
                                Text("#${r.invoiceNumber} • ${r.customerName}", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                Text("Returned: ${r.returnedTileName} (${r.returnBoxes} Box, ${r.returnPieces} Pcs)", style = MaterialTheme.typography.bodySmall)
                                if (r.replacementTileName.isNotBlank()) {
                                    Text("Replaced with: ${r.replacementTileName} (${r.replacementBoxes} Box, ${r.replacementPieces} Pcs)", style = MaterialTheme.typography.bodySmall, color = InfoBlue)
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusBadge(r.condition, if (r.condition == "GOOD") SuccessGreenSoft else DangerRedSoft, if (r.condition == "GOOD") SuccessGreen else DangerRed)
                                    StatusBadge("${r.settlementType}: ${Formatters.currency(r.differenceAmount)}", AmberGoldSoft, AmberGoldDark)
                                }
                            }
                        }
                    }
                }
                FloatingActionButton(
                    onClick = { showReturnDialog = true },
                    containerColor = RoyalNavy,
                    contentColor = AmberGold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "New Return or Exchange")
                }
            }
        }
    }

    if (activeSaleForDelivery != null) {
        val sale = activeSaleForDelivery!!
        val qtyInputs = remember(sale.id) {
            mutableStateMapOf<Int, String>().apply {
                sale.items.forEachIndexed { idx, item -> put(idx, item.remainingDeliveryPieces().toString()) }
            }
        }
        var loadingStr by remember { mutableStateOf("0") }
        var remarks by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { activeSaleForDelivery = null },
            containerColor = CardWhite,
            title = { Text("Record Partial Delivery #${sale.invoiceNumber}", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sale.items.forEachIndexed { idx, item ->
                        val rem = item.remainingDeliveryPieces()
                        if (rem > 0) {
                            Surface(color = LightBackground, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(item.tileName, style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                    Text("Purchased: ${item.totalPieces()} Pcs | Prev Delivered: ${item.deliveredPieces} | Remaining: $rem", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    Spacer(Modifier.height(4.dp))
                                    FormInputField(qtyInputs[idx] ?: "0", { qtyInputs[idx] = it }, "Deliver Now (Pcs)", keyboardType = KeyboardType.Number)
                                }
                            }
                        }
                    }
                    FormInputField(loadingStr, { loadingStr = it }, "Loading Charges (₹)", keyboardType = KeyboardType.Decimal)
                    FormInputField(remarks, { remarks = it }, "Delivery Remarks")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val map = qtyInputs.mapValues { (_, v) -> v.toLongOrNull() ?: 0L }
                        onRecordPartialDelivery(sale, map, loadingStr.toDoubleOrNull() ?: 0.0, remarks) { rec ->
                            activeSaleForDelivery = null
                            generatedPdf = PdfGenerator.generateDeliveryChallanPdf(context, shop, rec)
                            pdfTargetPhone = sale.customerMobile.ifBlank {
                                customers.firstOrNull { it.id == sale.customerId }?.mobile.orEmpty()
                            }
                            pdfTargetName = sale.customerName
                            pdfCaption = "${shop.shopName} - Delivery Challan #${sale.invoiceNumber}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save & Generate Challan") }
            },
            dismissButton = { TextButton(onClick = { activeSaleForDelivery = null }) { Text("Cancel") } }
        )
    }

    if (showReturnDialog) {
        ReturnExchangeDialog(
            sales = confirmedSales,
            tiles = tiles,
            customers = customers,
            onDismiss = { showReturnDialog = false },
            onSave = { rec, retTile, repTile, cust ->
                onRecordReturnExchange(rec, retTile, repTile, cust)
                showReturnDialog = false
            }
        )
    }

    if (generatedPdf != null) {
        PdfActionDialog(
            file = generatedPdf!!,
            recipientPhone = pdfTargetPhone,
            recipientName = pdfTargetName,
            captionText = pdfCaption,
            onDismiss = { generatedPdf = null }
        )
    }
}

@Composable
private fun ReturnExchangeDialog(
    sales: List<SaleOrder>,
    tiles: List<TileProduct>,
    customers: List<Customer>,
    onDismiss: () -> Unit,
    onSave: (ReturnExchange, TileProduct?, TileProduct?, Customer?) -> Unit
) {
    var selectedSale by remember { mutableStateOf(sales.firstOrNull()) }
    var selectedReturnedTileId by remember { mutableStateOf(selectedSale?.items?.firstOrNull()?.tileId ?: "") }
    var retBoxes by remember { mutableStateOf("1") }
    var retPieces by remember { mutableStateOf("0") }
    var reason by remember { mutableStateOf("Shade mismatch / excess") }
    var condition by remember { mutableStateOf("GOOD") } // GOOD or DAMAGED
    var replacementTile by remember { mutableStateOf<TileProduct?>(null) }
    var repBoxes by remember { mutableStateOf("0") }
    var repPieces by remember { mutableStateOf("0") }

    val retTileObj = tiles.firstOrNull { it.id == selectedReturnedTileId }
    val retValue = ((retBoxes.toLongOrNull() ?: 0L) * (retTileObj?.sellingPricePerBox ?: 0.0)) +
        ((retPieces.toLongOrNull() ?: 0L) * (retTileObj?.sellingPricePerPiece ?: 0.0))
    val repValue = ((repBoxes.toLongOrNull() ?: 0L) * (replacementTile?.sellingPricePerBox ?: 0.0)) +
        ((repPieces.toLongOrNull() ?: 0L) * (replacementTile?.sellingPricePerPiece ?: 0.0))
    val diff = repValue - retValue
    val settlement = when {
        diff > 0.01 -> "ADDITIONAL_PAYMENT"
        diff < -0.01 -> "REFUND"
        else -> "EVEN"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("Goods Return / Exchange", color = RoyalNavy) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select Original Invoice:", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sales.forEach { s ->
                        FilterChip(
                            selected = selectedSale?.id == s.id,
                            onClick = {
                                selectedSale = s
                                selectedReturnedTileId = s.items.firstOrNull()?.tileId ?: ""
                            },
                            label = { Text("#${s.invoiceNumber}") }
                        )
                    }
                }
                Text("Returned Tile:", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    selectedSale?.items?.forEach { item ->
                        FilterChip(
                            selected = selectedReturnedTileId == item.tileId,
                            onClick = { selectedReturnedTileId = item.tileId },
                            label = { Text(item.tileName) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(retBoxes, { retBoxes = it }, "Return Boxes", Modifier.weight(1f), KeyboardType.Number)
                    FormInputField(retPieces, { retPieces = it }, "Return Pcs", Modifier.weight(1f), KeyboardType.Number)
                }
                FormInputField(reason, { reason = it }, "Return Reason")
                Text("Condition (Damaged is NOT added to saleable stock):", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = condition == "GOOD", onClick = { condition = "GOOD" }, label = { Text("GOOD (Restock)") })
                    FilterChip(selected = condition == "DAMAGED", onClick = { condition = "DAMAGED" }, label = { Text("DAMAGED") })
                }
                Text("Replacement Product (Optional):", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = replacementTile == null, onClick = { replacementTile = null }, label = { Text("Return Only") })
                    tiles.forEach { t ->
                        FilterChip(selected = replacementTile?.id == t.id, onClick = { replacementTile = t }, label = { Text(t.name) })
                    }
                }
                if (replacementTile != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormInputField(repBoxes, { repBoxes = it }, "Replace Boxes", Modifier.weight(1f), KeyboardType.Number)
                        FormInputField(repPieces, { repPieces = it }, "Replace Pcs", Modifier.weight(1f), KeyboardType.Number)
                    }
                }
                Surface(color = AmberGoldSoft, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "$settlement: ${Formatters.currency(kotlin.math.abs(diff))}",
                        style = MaterialTheme.typography.titleSmall,
                        color = RoyalNavy,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val s = selectedSale ?: return@Button
                    val retName = retTileObj?.name ?: s.items.firstOrNull { it.tileId == selectedReturnedTileId }?.tileName ?: "Tile"
                    val rec = ReturnExchange(
                        saleId = s.id,
                        invoiceNumber = s.invoiceNumber,
                        customerId = s.customerId,
                        customerName = s.customerName,
                        returnedTileId = selectedReturnedTileId,
                        returnedTileName = retName,
                        returnBoxes = retBoxes.toLongOrNull() ?: 0L,
                        returnPieces = retPieces.toLongOrNull() ?: 0L,
                        reason = reason,
                        condition = condition,
                        replacementTileId = replacementTile?.id ?: "",
                        replacementTileName = replacementTile?.name ?: "",
                        replacementBoxes = repBoxes.toLongOrNull() ?: 0L,
                        replacementPieces = repPieces.toLongOrNull() ?: 0L,
                        differenceAmount = kotlin.math.abs(diff),
                        settlementType = settlement
                    )
                    val cust = customers.firstOrNull { it.id == s.customerId }
                    onSave(rec, retTileObj, replacementTile, cust)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
            ) { Text("Confirm Return") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
