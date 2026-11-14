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
import com.example.utils.PdfGenerator
import java.io.File

@Composable
fun PurchaseAndSuppliersScreen(
    shop: Shop,
    suppliers: List<Supplier>,
    purchases: List<PurchaseBill>,
    tiles: List<TileProduct>,
    openPurchaseTrigger: Int,
    onSaveSupplier: (Supplier) -> Unit,
    onRecordPurchase: (PurchaseBill, Supplier?) -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 = Purchase Bills, 1 = Suppliers
    var showSupplierDialog by remember { mutableStateOf(false) }
    var editingSupplier by remember { mutableStateOf<Supplier?>(null) }
    var showPurchaseDialog by remember { mutableStateOf(false) }
    var ledgerSupplier by remember { mutableStateOf<Supplier?>(null) }
    var generatedPdf by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(openPurchaseTrigger) {
        if (openPurchaseTrigger > 0) {
            tab = 0
            showPurchaseDialog = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LightBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = SecondaryNavy, contentColor = AmberGold) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Purchases (${purchases.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Suppliers (${suppliers.size})") })
            }

            if (tab == 0) {
                if (purchases.isEmpty()) {
                    EmptyStateView(
                        title = "No Purchases Recorded",
                        subtitle = "Log supplier purchase bills with loading, transport & payment mode.",
                        actionLabel = "New Purchase Bill",
                        onAction = { showPurchaseDialog = true }
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 84.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(purchases.sortedByDescending { it.purchaseDateMillis }, key = { it.id }) { p ->
                            PremiumCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("#${p.invoiceNumber} • ${p.supplierName}", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                        Text("${Formatters.date(p.purchaseDateMillis)} • ${p.paymentMode}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(Formatters.currency(p.totalAmount), style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                        if (p.isReturn) StatusBadge("RETURN", WarningOrangeSoft, WarningOrange)
                                        else if (p.remainingDue > 0) StatusBadge("Due: ${Formatters.currency(p.remainingDue)}", DangerRedSoft, DangerRed)
                                        else StatusBadge("PAID", SuccessGreenSoft, SuccessGreen)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("Loading: ${Formatters.currency(p.loadingCharges)}  •  Transport: ${Formatters.currency(p.transportCharges)}  •  Discount: ${Formatters.currency(p.discount)}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                            }
                        }
                    }
                }
            } else {
                if (suppliers.isEmpty()) {
                    EmptyStateView(
                        title = "No Suppliers Yet",
                        subtitle = "Add marble & tile manufacturers or distributors.",
                        actionLabel = "Add Supplier",
                        onAction = { editingSupplier = null; showSupplierDialog = true }
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 84.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(suppliers, key = { it.id }) { sup ->
                            PremiumCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sup.name, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                        Text("${sup.mobile} • GST: ${sup.gstNumber.ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                        Text("Total: ${Formatters.currency(sup.totalPurchases)} | Paid: ${Formatters.currency(sup.totalPaid)}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        StatusBadge(
                                            "Due ${Formatters.currency(sup.outstandingBalance)}",
                                            if (sup.outstandingBalance > 0) DangerRedSoft else SuccessGreenSoft,
                                            if (sup.outstandingBalance > 0) DangerRed else SuccessGreen
                                        )
                                        Row {
                                            IconButton(onClick = { editingSupplier = sup; showSupplierDialog = true }) {
                                                Icon(Icons.Default.Edit, contentDescription = "Edit Supplier", tint = RoyalNavy)
                                            }
                                            IconButton(onClick = { ledgerSupplier = sup }) {
                                                Icon(Icons.Default.ReceiptLong, contentDescription = "Supplier Ledger", tint = AmberGoldDark)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                if (tab == 0) showPurchaseDialog = true
                else { editingSupplier = null; showSupplierDialog = true }
            },
            containerColor = RoyalNavy,
            contentColor = AmberGold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = if (tab == 0) "New Purchase" else "Add Supplier")
        }
    }

    if (showSupplierDialog) {
        var name by remember { mutableStateOf(editingSupplier?.name ?: "") }
        var mobile by remember { mutableStateOf(editingSupplier?.mobile ?: "") }
        var address by remember { mutableStateOf(editingSupplier?.address ?: "") }
        var gst by remember { mutableStateOf(editingSupplier?.gstNumber ?: "") }
        var prevBal by remember { mutableStateOf((editingSupplier?.previousBalance ?: 0.0).let { if (it == 0.0) "" else it.toString() }) }

        AlertDialog(
            onDismissRequest = { showSupplierDialog = false },
            containerColor = CardWhite,
            title = { Text(if (editingSupplier == null) "Add Supplier" else "Edit Supplier", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(name, { name = it }, "Supplier Name *")
                    FormInputField(mobile, { mobile = it }, "Mobile Number *", keyboardType = KeyboardType.Phone)
                    FormInputField(address, { address = it }, "Address")
                    FormInputField(gst, { gst = it }, "GST Number")
                    FormInputField(prevBal, { prevBal = it }, "Previous Balance (₹)", keyboardType = KeyboardType.Decimal)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isBlank() || mobile.isBlank()) return@Button
                        val pb = prevBal.toDoubleOrNull() ?: 0.0
                        val model = (editingSupplier ?: Supplier()).copy(
                            name = name.trim(),
                            mobile = mobile.trim(),
                            address = address.trim(),
                            gstNumber = gst.trim(),
                            previousBalance = pb
                        )
                        onSaveSupplier(model)
                        showSupplierDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save Supplier") }
            },
            dismissButton = { TextButton(onClick = { showSupplierDialog = false }) { Text("Cancel") } }
        )
    }

    if (showPurchaseDialog) {
        NewPurchaseBillDialog(
            suppliers = suppliers,
            tiles = tiles,
            nextInvoice = "PUR-${101 + purchases.size}",
            onDismiss = { showPurchaseDialog = false },
            onSave = { bill, sup ->
                onRecordPurchase(bill, sup)
                showPurchaseDialog = false
            }
        )
    }

    if (ledgerSupplier != null) {
        val sup = ledgerSupplier!!
        val supBills = purchases.filter { it.supplierId == sup.id }
        AlertDialog(
            onDismissRequest = { ledgerSupplier = null },
            containerColor = CardWhite,
            title = { Text("Supplier Ledger: ${sup.name}", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Opening: ${Formatters.currency(sup.previousBalance)} | Purchases: ${Formatters.currency(sup.totalPurchases)} | Paid: ${Formatters.currency(sup.totalPaid)}", style = MaterialTheme.typography.bodySmall)
                    Text("Outstanding: ${Formatters.currency(sup.outstandingBalance)}", style = MaterialTheme.typography.titleMedium, color = DangerRed)
                    HorizontalDivider()
                    supBills.forEach { b ->
                        Text("${Formatters.date(b.purchaseDateMillis)} • #${b.invoiceNumber}: Total ${Formatters.currency(b.totalAmount)} | Paid ${Formatters.currency(b.amountPaid)} (${b.paymentMode})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        generatedPdf = PdfGenerator.generateTableReportPdf(
                            context = context,
                            shop = shop,
                            reportTitle = "Supplier Ledger - ${sup.name}",
                            dateFilterSummary = "All Time",
                            summaryLines = listOf("Mobile: ${sup.mobile} | GST: ${sup.gstNumber}", "Outstanding Balance: ${Formatters.currency(sup.outstandingBalance)}"),
                            headers = listOf("Date", "Invoice", "Total", "Paid", "Due"),
                            rows = supBills.map { listOf(Formatters.date(it.purchaseDateMillis), it.invoiceNumber, Formatters.currency(it.totalAmount), Formatters.currency(it.amountPaid), Formatters.currency(it.remainingDue)) }
                        )
                        ledgerSupplier = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Export Ledger PDF") }
            },
            dismissButton = { TextButton(onClick = { ledgerSupplier = null }) { Text("Close") } }
        )
    }

    if (generatedPdf != null) {
        PdfActionDialog(file = generatedPdf!!, onDismiss = { generatedPdf = null })
    }
}

@Composable
private fun NewPurchaseBillDialog(
    suppliers: List<Supplier>,
    tiles: List<TileProduct>,
    nextInvoice: String,
    onDismiss: () -> Unit,
    onSave: (PurchaseBill, Supplier?) -> Unit
) {
    var invoiceNo by remember { mutableStateOf(nextInvoice) }
    var selectedSupplier by remember { mutableStateOf(suppliers.firstOrNull()) }
    var isReturn by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<PurchaseLineItem>() }
    var loadingStr by remember { mutableStateOf("0") }
    var transportStr by remember { mutableStateOf("0") }
    var otherStr by remember { mutableStateOf("0") }
    var discountStr by remember { mutableStateOf("0") }
    var paidStr by remember { mutableStateOf("0") }
    var paymentMode by remember { mutableStateOf("Cash") }
    var remarks by remember { mutableStateOf("") }

    var selectedTile by remember { mutableStateOf(tiles.firstOrNull()) }
    var addBoxes by remember { mutableStateOf("10") }
    var addLoose by remember { mutableStateOf("0") }
    var addRate by remember { mutableStateOf(selectedTile?.purchaseCost?.toString() ?: "900") }

    val subtotal = items.sumOf { it.lineTotal }
    val loading = loadingStr.toDoubleOrNull() ?: 0.0
    val transport = transportStr.toDoubleOrNull() ?: 0.0
    val other = otherStr.toDoubleOrNull() ?: 0.0
    val discount = discountStr.toDoubleOrNull() ?: 0.0
    val total = (subtotal + loading + transport + other - discount).coerceAtLeast(0.0)
    val paid = paidStr.toDoubleOrNull() ?: 0.0
    val due = (total - paid).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("New Purchase / Return Bill", color = RoyalNavy) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isReturn, onClick = { isReturn = false }, label = { Text("Purchase") })
                    FilterChip(selected = isReturn, onClick = { isReturn = true }, label = { Text("Purchase Return") })
                }
                FormInputField(invoiceNo, { invoiceNo = it }, "Purchase Invoice # *")
                Text("Select Supplier:", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suppliers.forEach { s ->
                        FilterChip(selected = selectedSupplier?.id == s.id, onClick = { selectedSupplier = s }, label = { Text(s.name) })
                    }
                }
                if (tiles.isNotEmpty()) {
                    Surface(color = LightBackground, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Add Tile Product:", style = MaterialTheme.typography.titleSmall)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                tiles.forEach { t ->
                                    FilterChip(
                                        selected = selectedTile?.id == t.id,
                                        onClick = {
                                            selectedTile = t
                                            addRate = if (t.purchaseCost > 0) t.purchaseCost.toString() else "900"
                                        },
                                        label = { Text("${t.code} (${t.sizeName})") }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FormInputField(addBoxes, { addBoxes = it }, "Boxes", Modifier.weight(1f), KeyboardType.Number)
                                FormInputField(addLoose, { addLoose = it }, "Loose", Modifier.weight(1f), KeyboardType.Number)
                                FormInputField(addRate, { addRate = it }, "Rate/Box", Modifier.weight(1f), KeyboardType.Decimal)
                            }
                            Button(
                                onClick = {
                                    val t = selectedTile ?: return@Button
                                    val b = addBoxes.toLongOrNull() ?: 0L
                                    val l = addLoose.toLongOrNull() ?: 0L
                                    val r = addRate.toDoubleOrNull() ?: 0.0
                                    val lineTot = (b * r) + (l * (if (t.piecesPerBox > 0) r / t.piecesPerBox else 0.0))
                                    items.add(
                                        PurchaseLineItem(
                                            tileId = t.id,
                                            tileCode = t.code,
                                            tileName = t.name,
                                            sizeName = t.sizeName,
                                            piecesPerBox = t.piecesPerBox,
                                            boxes = b,
                                            loosePieces = l,
                                            purchaseRatePerBox = r,
                                            lineTotal = lineTot
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                            ) { Text("+ Add Tile to Purchase") }
                        }
                    }
                }
                items.forEach { item ->
                    Text("• ${item.tileName}: ${item.boxes} Box + ${item.loosePieces} Pcs = ${Formatters.currency(item.lineTotal)}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormInputField(loadingStr, { loadingStr = it }, "Loading (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                    FormInputField(transportStr, { transportStr = it }, "Transport (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormInputField(otherStr, { otherStr = it }, "Other Exp (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                    FormInputField(discountStr, { discountStr = it }, "Discount (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                }
                Text("Total Payable: ${Formatters.currency(total)} | Remaining Due: ${Formatters.currency(due)}", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                FormInputField(paidStr, { paidStr = it }, "Amount Paid (₹)", keyboardType = KeyboardType.Decimal)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Cash", "UPI", "Bank", "Credit").forEach { m ->
                        FilterChip(selected = paymentMode == m, onClick = { paymentMode = m }, label = { Text(m) })
                    }
                }
                FormInputField(remarks, { remarks = it }, "Remarks")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sup = selectedSupplier ?: return@Button
                    if (items.isEmpty()) return@Button
                    val bill = PurchaseBill(
                        invoiceNumber = invoiceNo.trim(),
                        supplierId = sup.id,
                        supplierName = sup.name,
                        items = items.toList(),
                        subtotal = subtotal,
                        loadingCharges = loading,
                        transportCharges = transport,
                        otherExpenses = other,
                        discount = discount,
                        totalAmount = total,
                        amountPaid = paid,
                        remainingDue = due,
                        paymentMode = paymentMode,
                        isReturn = isReturn,
                        remarks = remarks
                    )
                    onSave(bill, sup)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
            ) { Text("Save Purchase") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
