package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.core.content.ContextCompat
import com.example.data.firebase.Formatters
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.utils.ImageUtils
import com.example.utils.PdfGenerator
import java.io.File

@Composable
fun SalesAndBillingScreen(
    shop: Shop,
    platformConfig: PlatformConfig,
    sales: List<SaleOrder>,
    tiles: List<TileProduct>,
    customers: List<Customer>,
    openNewTrigger: Int,
    onSaveSale: (SaleOrder, Customer?, String?) -> Unit,
    onDeleteSale: (SaleOrder, Boolean) -> Unit,
    onRecordSalePayment: (SaleOrder, Double, String, String) -> Unit,
    onOpenDeliveryForSale: (SaleOrder) -> Unit
) {
    val context = LocalContext.current
    var filterTab by remember { mutableStateOf("ALL") } // ALL, CONFIRMED, PENDING
    var search by remember { mutableStateOf("") }
    var editingSale by remember { mutableStateOf<SaleOrder?>(null) }
    var deletingSale by remember { mutableStateOf<SaleOrder?>(null) }
    var paymentSale by remember { mutableStateOf<SaleOrder?>(null) }
    var showBillDialog by remember { mutableStateOf(false) }
    var generatedPdf by remember { mutableStateOf<File?>(null) }
    var pdfTargetPhone by remember { mutableStateOf<String?>(null) }
    var pdfTargetName by remember { mutableStateOf<String?>(null) }
    var pdfCaption by remember { mutableStateOf<String?>(null) }

    val tilePhotosById = remember(tiles) {
        tiles.associate { it.id to it.photoBase64 }
    }

    LaunchedEffect(openNewTrigger) {
        if (openNewTrigger > 0) {
            editingSale = null
            showBillDialog = true
        }
    }

    val filteredSales = remember(sales, filterTab, search) {
        sales.filter { s ->
            val matchTab = when (filterTab) {
                "CONFIRMED" -> s.orderStatus == "CONFIRMED"
                "PENDING" -> s.orderStatus == "PENDING"
                else -> true
            }
            val q = search.trim().lowercase()
            val matchQ = q.isBlank() || s.invoiceNumber.lowercase().contains(q) || s.customerName.lowercase().contains(q)
            matchTab && matchQ
        }.sortedByDescending { it.saleDateMillis }
    }

    Box(modifier = Modifier.fillMaxSize().background(LightBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = SecondaryNavy, contentColor = Color.White) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Orders & Sales Bills", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        StatusBadge("${sales.size} Bills", AmberGold, RoyalNavy)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ALL" to "All Orders", "CONFIRMED" to "Confirmed Sales", "PENDING" to "Pending Orders").forEach { (key, label) ->
                            FilterChip(
                                selected = filterTab == key,
                                onClick = { filterTab = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search invoice # or customer...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )

            if (filteredSales.isEmpty()) {
                EmptyStateView(
                    title = "No Sales Bills Yet",
                    subtitle = "Create a Pending Order or Confirmed Sales Invoice with Quick Size Pricing.",
                    actionLabel = "Create New Bill",
                    onAction = { editingSale = null; showBillDialog = true }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 84.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredSales, key = { it.id }) { sale ->
                        val resolvedMobile = sale.customerMobile.ifBlank {
                            customers.firstOrNull { it.id == sale.customerId }?.mobile.orEmpty()
                        }
                        PremiumCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("#${sale.invoiceNumber} • ${sale.customerName}", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                    Text("${Formatters.date(sale.saleDateMillis)} • ${sale.items.size} items${if (resolvedMobile.isNotBlank()) " • $resolvedMobile" else ""}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(Formatters.currency(sale.totalPayable), style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                    IconButton(
                                        onClick = { editingSale = sale; showBillDialog = true },
                                        modifier = Modifier.testTag("edit_sale_${sale.id}")
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Bill", tint = RoyalNavy)
                                    }
                                    if (platformConfig.billDeletionAllowed) {
                                        IconButton(
                                            onClick = { deletingSale = sale },
                                            modifier = Modifier.testTag("delete_sale_${sale.id}")
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Bill", tint = DangerRed)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StatusBadge(
                                    sale.orderStatus,
                                    if (sale.orderStatus == "CONFIRMED") SuccessGreenSoft else WarningOrangeSoft,
                                    if (sale.orderStatus == "CONFIRMED") SuccessGreen else WarningOrange
                                )
                                StatusBadge(
                                    sale.paymentStatus,
                                    if (sale.paymentStatus == "PAID") SuccessGreenSoft else DangerRedSoft,
                                    if (sale.paymentStatus == "PAID") SuccessGreen else DangerRed
                                )
                                if (sale.receivedAmount > 0.0) {
                                    StatusBadge("Paid: ${Formatters.currency(sale.receivedAmount)}", TealSoftWash, RoyalNavy)
                                }
                                if (sale.remainingBalance > 0.01) {
                                    StatusBadge("Due: ${Formatters.currency(sale.remainingBalance)}", DangerRedSoft, DangerRed)
                                }
                                if (sale.paymentHistory.size > 1) {
                                    StatusBadge("${sale.paymentHistory.size} Installments", InfoBlueSoft, InfoBlue)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        generatedPdf = PdfGenerator.generateSalePdf(
                                            context = context,
                                            shop = shop,
                                            sale = sale,
                                            isQuotation = false,
                                            tilePhotosById = tilePhotosById
                                        )
                                        pdfTargetPhone = resolvedMobile
                                        pdfTargetName = sale.customerName
                                        pdfCaption = "${shop.shopName} - Invoice #${sale.invoiceNumber} (${Formatters.currency(sale.totalPayable)})"
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Invoice PDF")
                                }
                                OutlinedButton(
                                    onClick = {
                                        generatedPdf = PdfGenerator.generateSalePdf(
                                            context = context,
                                            shop = shop,
                                            sale = sale,
                                            isQuotation = true,
                                            tilePhotosById = tilePhotosById
                                        )
                                        pdfTargetPhone = resolvedMobile
                                        pdfTargetName = sale.customerName
                                        pdfCaption = "${shop.shopName} - Quotation #${sale.invoiceNumber}"
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Text("Quotation PDF")
                                }
                                if (sale.remainingBalance > 0.01) {
                                    Button(
                                        onClick = { paymentSale = sale },
                                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("+ Pay")
                                    }
                                }
                                if (sale.orderStatus == "CONFIRMED") {
                                    OutlinedButton(
                                        onClick = { onOpenDeliveryForSale(sale) },
                                        contentPadding = PaddingValues(horizontal = 10.dp)
                                    ) {
                                        Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Delivery")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editingSale = null; showBillDialog = true },
            containerColor = RoyalNavy,
            contentColor = AmberGold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("new_sale_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "New Sales Bill")
        }
    }

    if (showBillDialog) {
        SaleBillEditorDialog(
            shop = shop,
            platformConfig = platformConfig,
            existing = editingSale,
            tiles = tiles,
            customers = customers,
            nextInvoiceNumber = "INV-${1001 + sales.size}",
            onDismiss = { showBillDialog = false },
            onSave = { sale, cust, prevStatus ->
                onSaveSale(sale, cust, prevStatus)
                showBillDialog = false
            }
        )
    }

    if (deletingSale != null) {
        val targetSale = deletingSale!!
        val hasDeductedStock = targetSale.orderStatus == "CONFIRMED" &&
            targetSale.items.any { !it.isManualItem && it.tileId.isNotBlank() }
        val deductedSummary = targetSale.items
            .filter { !it.isManualItem && it.tileId.isNotBlank() }
            .joinToString("\n") { item ->
                val locStr = if (item.deductedLocations.isNotEmpty()) {
                    item.deductedLocations.entries.joinToString(", ") { "${it.key}: ${it.value} Box" }
                } else {
                    "${item.fullBoxes} Box"
                }
                "• ${item.tileName} ($locStr)"
            }

        AlertDialog(
            onDismissRequest = { deletingSale = null },
            containerColor = CardWhite,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = DangerRed)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Bill #${targetSale.invoiceNumber}", color = DangerRed)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Customer: ${targetSale.customerName} • ${Formatters.currency(targetSale.totalPayable)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = RoyalNavy
                    )
                    if (hasDeductedStock) {
                        Text(
                            "इस बिल की टाइलें स्टॉक से कम हो चुकी हैं। क्या आप इन टाइलों को वापस उसी वेयरहाउस में जोड़ना चाहते हैं जहाँ से कम हुई थीं?\n\nThis confirmed bill deducted stock. Do you want to restore these tiles back to their exact original warehouse(s)?",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateDark
                        )
                        if (deductedSummary.isNotBlank()) {
                            Surface(color = LightBackground, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    deductedSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = RoyalNavy,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                onDeleteSale(targetSale, true)
                                deletingSale = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            modifier = Modifier.fillMaxWidth().testTag("delete_sale_restore_yes_btn")
                        ) {
                            Text("हाँ, स्टॉक वापस जोड़ें (Yes, Restore & Delete)")
                        }
                        OutlinedButton(
                            onClick = {
                                onDeleteSale(targetSale, false)
                                deletingSale = null
                            },
                            border = BorderStroke(1.dp, DangerRed),
                            modifier = Modifier.fillMaxWidth().testTag("delete_sale_restore_no_btn")
                        ) {
                            Text("नहीं, बिना स्टॉक जोड़े डिलीट करें (No, Delete Only)", color = DangerRed)
                        }
                    } else {
                        Text(
                            "क्या आप इस बिल को हटाना चाहते हैं? (Delete this bill permanently from active sales?)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateDark
                        )
                    }
                }
            },
            confirmButton = {
                if (!hasDeductedStock) {
                    Button(
                        onClick = {
                            onDeleteSale(targetSale, false)
                            deletingSale = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                    ) { Text("Delete Bill") }
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSale = null }) { Text("Cancel") }
            }
        )
    }

    if (paymentSale != null) {
        val target = paymentSale!!
        var payAmount by remember(target.id) { mutableStateOf(target.remainingBalance.toString()) }
        var payMode by remember(target.id) { mutableStateOf("UPI") }
        var payNote by remember(target.id) { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { paymentSale = null },
            containerColor = CardWhite,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Add Payment • #${target.invoiceNumber}", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Total: ${Formatters.currency(target.totalPayable)} | Paid: ${Formatters.currency(target.receivedAmount)} | Due: ${Formatters.currency(target.remainingBalance)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateDark
                    )
                    if (target.paymentHistory.isNotEmpty()) {
                        Surface(color = LightBackground, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Previous Payments / पिछला भुगतान:", style = MaterialTheme.typography.labelSmall, color = RoyalNavy)
                                target.paymentHistory.forEach { entry ->
                                    Text(
                                        "• ${Formatters.date(entry.dateMillis)} • ${entry.mode} • ${Formatters.currency(entry.amount)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateDark
                                    )
                                }
                            }
                        }
                    }
                    FormInputField(payAmount, { payAmount = it }, "Amount Received (₹) *", keyboardType = KeyboardType.Decimal)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Cash", "UPI", "Bank").forEach { m ->
                            FilterChip(selected = payMode == m, onClick = { payMode = m }, label = { Text(m) })
                        }
                    }
                    FormInputField(payNote, { payNote = it }, "Note / Reference (e.g. GPay / Cash)")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = payAmount.toDoubleOrNull() ?: return@Button
                        if (amt <= 0.0) return@Button
                        onRecordSalePayment(target, amt, payMode, payNote)
                        paymentSale = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) { Text("Save Payment") }
            },
            dismissButton = { TextButton(onClick = { paymentSale = null }) { Text("Cancel") } }
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
private fun SaleBillEditorDialog(
    shop: Shop,
    platformConfig: PlatformConfig,
    existing: SaleOrder?,
    tiles: List<TileProduct>,
    customers: List<Customer>,
    nextInvoiceNumber: String,
    onDismiss: () -> Unit,
    onSave: (SaleOrder, Customer?, String?) -> Unit
) {
    var invoiceNo by remember { mutableStateOf(existing?.invoiceNumber ?: nextInvoiceNumber) }
    var selectedCustomer by remember { mutableStateOf(customers.firstOrNull { it.id == existing?.customerId }) }
    var custName by remember { mutableStateOf(existing?.customerName ?: "") }
    var custMobile by remember { mutableStateOf(existing?.customerMobile ?: "") }
    var deliveryAddr by remember { mutableStateOf(existing?.deliveryAddress ?: "") }
    var showPricesOnPdf by remember { mutableStateOf(existing?.showPricesOnPdf ?: shop.showPricesOnPdfDefault) }
    var orderStatus by remember { mutableStateOf(existing?.orderStatus ?: "CONFIRMED") }
    var paymentMode by remember { mutableStateOf(existing?.paymentMode ?: "Cash") }
    var receivedStr by remember { mutableStateOf(existing?.receivedAmount?.let { if (it == 0.0) "" else it.toString() } ?: "") }
    var discountStr by remember { mutableStateOf(existing?.discount?.let { if (it == 0.0) "" else it.toString() } ?: "") }
    var loadingStr by remember { mutableStateOf(existing?.loadingCharges?.let { if (it == 0.0) "" else it.toString() } ?: "") }
    var deliveryStr by remember { mutableStateOf(existing?.deliveryCharges?.let { if (it == 0.0) "" else it.toString() } ?: "") }
    var extraStr by remember { mutableStateOf(existing?.additionalCharges?.let { if (it == 0.0) "" else it.toString() } ?: "") }
    var remarks by remember { mutableStateOf(existing?.remarks ?: "") }

    val lineItems = remember { mutableStateListOf<SaleLineItem>().apply { if (existing != null) addAll(existing.items) } }

    var tileSearch by remember { mutableStateOf("") }
    var showTilePicker by remember { mutableStateOf(false) }
    var showManualItemDialog by remember { mutableStateOf(false) }

    // Quick Size Pricing State
    var quickSize by remember { mutableStateOf("2x4") }
    var quickRateType by remember { mutableStateOf("Standard") } // Standard, Premium / HL, Custom
    var quickRateValue by remember { mutableStateOf("1400") }

    val subtotal = lineItems.sumOf { it.lineTotal }
    val discount = if (platformConfig.billingDiscountAllowed) (discountStr.toDoubleOrNull() ?: 0.0) else 0.0
    val loading = loadingStr.toDoubleOrNull() ?: 0.0
    val delivery = deliveryStr.toDoubleOrNull() ?: 0.0
    val extra = extraStr.toDoubleOrNull() ?: 0.0
    val totalPayable = (subtotal - discount + loading + delivery + extra).coerceAtLeast(0.0)
    val received = receivedStr.toDoubleOrNull() ?: 0.0
    val remaining = (totalPayable - received).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text(if (existing == null) "New Sales Bill / Order" else "Edit #${existing.invoiceNumber}", color = RoyalNavy) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormInputField(invoiceNo, { invoiceNo = it }, "Invoice Number *")
                if (customers.isNotEmpty()) {
                    Text("Quick Pick Customer:", style = MaterialTheme.typography.labelSmall, color = SlateMuted)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        customers.forEach { c ->
                            FilterChip(
                                selected = selectedCustomer?.id == c.id,
                                onClick = {
                                    selectedCustomer = c
                                    custName = c.name
                                    custMobile = c.mobile
                                    deliveryAddr = c.address
                                },
                                label = { Text(c.name) }
                            )
                        }
                    }
                }
                FormInputField(custName, { custName = it }, "Customer Name *")
                FormInputField(custMobile, { custMobile = it }, "Mobile Number", keyboardType = KeyboardType.Phone)
                FormInputField(deliveryAddr, { deliveryAddr = it }, "Delivery Address")

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Show Prices on Customer PDF", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = showPricesOnPdf, onCheckedChange = { showPricesOnPdf = it })
                }

                // Order Status: PENDING vs CONFIRMED
                Text("Order Status (Confirmed deducts stock):", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = orderStatus == "CONFIRMED", onClick = { orderStatus = "CONFIRMED" }, label = { Text("CONFIRMED") })
                    FilterChip(selected = orderStatus == "PENDING", onClick = { orderStatus = "PENDING" }, label = { Text("PENDING (No Stock Deduct)") })
                }

                // Item Actions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showTilePicker = true },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Inventory Tile") }
                    OutlinedButton(
                        onClick = { showManualItemDialog = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Manual Item") }
                }

                // Quick Size Pricing
                if (lineItems.isNotEmpty()) {
                    Surface(color = AmberGoldSoft, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, AmberGold)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Quick Size Pricing", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                lineItems.map { it.tileSize }.distinct().forEach { sz ->
                                    FilterChip(selected = quickSize == sz, onClick = { quickSize = sz }, label = { Text(sz) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Standard" to "1200", "Premium / HL" to "1650", "Custom" to quickRateValue).forEach { (lbl, def) ->
                                    FilterChip(
                                        selected = quickRateType == lbl,
                                        onClick = {
                                            quickRateType = lbl
                                            if (lbl != "Custom") quickRateValue = def
                                        },
                                        label = { Text(lbl) }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FormInputField(quickRateValue, { quickRateValue = it }, "Rate/Box (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                                Button(
                                    onClick = {
                                        val r = quickRateValue.toDoubleOrNull() ?: return@Button
                                        for (i in lineItems.indices) {
                                            val it = lineItems[i]
                                            if (it.tileSize == quickSize) {
                                                val rPiece = if (it.piecesPerBox > 0) r / it.piecesPerBox else 0.0
                                                val tot = (it.fullBoxes * r) + (it.loosePieces * rPiece)
                                                lineItems[i] = it.copy(ratePerBox = r, ratePerPiece = rPiece, lineTotal = tot)
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                                ) { Text("Apply") }
                            }
                        }
                    }
                }

                // Added Order Items
                lineItems.forEachIndexed { idx, item ->
                    Surface(color = LightBackground, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TileThumbnail(item.photoBase64, item.tileName, Modifier.size(38.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${item.tileName} (${item.tileSize})", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                    Text("${item.tileCode} • Pcs/Box: ${item.piecesPerBox} • Total Pcs: ${item.totalPieces()}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                }
                                IconButton(onClick = { lineItems.removeAt(idx) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove Item", tint = DangerRed)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FormInputField(
                                    value = item.fullBoxes.toString(),
                                    onValueChange = { v ->
                                        val b = (v.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                                        val tot = (b * item.ratePerBox) + (item.loosePieces * item.ratePerPiece)
                                        lineItems[idx] = item.copy(fullBoxes = b, lineTotal = tot)
                                    },
                                    label = "Boxes",
                                    modifier = Modifier.weight(1f),
                                    keyboardType = KeyboardType.Number
                                )
                                FormInputField(
                                    value = item.loosePieces.toString(),
                                    onValueChange = { v ->
                                        val lp = (v.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                                        val tot = (item.fullBoxes * item.ratePerBox) + (lp * item.ratePerPiece)
                                        lineItems[idx] = item.copy(loosePieces = lp, lineTotal = tot)
                                    },
                                    label = "Loose",
                                    modifier = Modifier.weight(1f),
                                    keyboardType = KeyboardType.Number
                                )
                                FormInputField(
                                    value = item.ratePerBox.toString(),
                                    onValueChange = { v ->
                                        val r = v.toDoubleOrNull() ?: 0.0
                                        val rp = if (item.piecesPerBox > 0) r / item.piecesPerBox else 0.0
                                        val tot = (item.fullBoxes * r) + (item.loosePieces * rp)
                                        lineItems[idx] = item.copy(ratePerBox = r, ratePerPiece = rp, lineTotal = tot)
                                    },
                                    label = "Rate/Box",
                                    modifier = Modifier.weight(1f),
                                    keyboardType = KeyboardType.Decimal
                                )
                            }
                            Text("Line Total: ${Formatters.currency(item.lineTotal)}", style = MaterialTheme.typography.labelMedium, color = SuccessGreen)
                        }
                    }
                }

                // Charges & Payment
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (platformConfig.billingDiscountAllowed) {
                        FormInputField(discountStr, { discountStr = it }, "Discount (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                    }
                    FormInputField(loadingStr, { loadingStr = it }, "Loading (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(deliveryStr, { deliveryStr = it }, "Delivery (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                    FormInputField(extraStr, { extraStr = it }, "Extra (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                }

                Surface(color = RoyalNavy, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Payable: ${Formatters.currency(totalPayable)}", style = MaterialTheme.typography.titleMedium, color = AmberGold)
                        Text("Balance Due: ${Formatters.currency(remaining)}", style = MaterialTheme.typography.bodySmall, color = Color.White)
                    }
                }

                FormInputField(receivedStr, { receivedStr = it }, "Received Amount (₹)", keyboardType = KeyboardType.Decimal)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Cash", "UPI", "Bank").forEach { m ->
                        FilterChip(selected = paymentMode == m, onClick = { paymentMode = m }, label = { Text(m) })
                    }
                }
                FormInputField(remarks, { remarks = it }, "Remarks")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (custName.isBlank() || lineItems.isEmpty()) return@Button
                    val pStatus = when {
                        remaining <= 0.01 -> "PAID"
                        received > 0.0 -> "PARTIAL"
                        else -> "UNPAID"
                    }
                    val order = (existing ?: SaleOrder()).copy(
                        invoiceNumber = invoiceNo.trim(),
                        customerId = selectedCustomer?.id ?: existing?.customerId.orEmpty(),
                        customerName = custName.trim(),
                        customerMobile = custMobile.trim(),
                        deliveryAddress = deliveryAddr.trim(),
                        showPricesOnPdf = showPricesOnPdf,
                        items = lineItems.toList(),
                        subtotal = subtotal,
                        discount = discount,
                        additionalCharges = extra,
                        loadingCharges = loading,
                        deliveryCharges = delivery,
                        totalPayable = totalPayable,
                        receivedAmount = received,
                        remainingBalance = remaining,
                        paymentMode = paymentMode,
                        orderStatus = orderStatus,
                        paymentStatus = pStatus,
                        remarks = remarks.trim()
                    )
                    onSave(order, selectedCustomer, existing?.orderStatus)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
            ) { Text("Save Bill") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showTilePicker) {
        AlertDialog(
            onDismissRequest = { showTilePicker = false },
            containerColor = CardWhite,
            title = { Text("Select Tile from Inventory", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.heightIn(max = 380.dp)) {
                    FormInputField(tileSearch, { tileSearch = it }, "Search name, code, size")
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val list = tiles.filter {
                            val q = tileSearch.trim().lowercase()
                            q.isBlank() || it.name.lowercase().contains(q) || it.code.lowercase().contains(q) || it.sizeName.lowercase().contains(q)
                        }
                        items(list, key = { it.id }) { t ->
                            Surface(
                                color = LightBackground,
                                shape = RoundedCornerShape(8.dp),
                                onClick = {
                                    val rateBox = t.sellingPricePerBox
                                    val ratePcs = if (t.piecesPerBox > 0) rateBox / t.piecesPerBox else t.sellingPricePerPiece
                                    lineItems.add(
                                        SaleLineItem(
                                            tileId = t.id,
                                            tileCode = t.code,
                                            tileName = t.name,
                                            tileSize = t.sizeName,
                                            piecesPerBox = t.piecesPerBox,
                                            fullBoxes = 1L,
                                            loosePieces = 0L,
                                            ratePerBox = rateBox,
                                            ratePerPiece = ratePcs,
                                            lineTotal = rateBox,
                                            photoBase64 = t.photoBase64
                                        )
                                    )
                                    quickSize = t.sizeName
                                    showTilePicker = false
                                }
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TileThumbnail(t.photoBase64, t.name, Modifier.size(40.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(t.name, style = MaterialTheme.typography.titleSmall)
                                        Text("${t.code} • ${t.sizeName} • Stock: ${t.totalBoxes} Box", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    }
                                    Text(Formatters.currency(t.sellingPricePerBox), style = MaterialTheme.typography.titleSmall, color = SuccessGreen)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTilePicker = false }) { Text("Close") } }
        )
    }

    if (showManualItemDialog) {
        ManualItemDialog(
            onDismiss = { showManualItemDialog = false },
            onAdd = { item ->
                lineItems.add(item)
                showManualItemDialog = false
            }
        )
    }
}

@Composable
private fun ManualItemDialog(
    onDismiss: () -> Unit,
    onAdd: (SaleLineItem) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("Box") }
    var rate by remember { mutableStateOf("") }
    var photoBase64 by remember { mutableStateOf("") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photoBase64 = ImageUtils.uriToCompressedBase64(context, uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && pendingCameraUri != null) {
            photoBase64 = ImageUtils.uriToCompressedBase64(context, pendingCameraUri!!)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = ImageUtils.createTempCameraUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("Add Manual Item (Bill Only)", color = RoyalNavy) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TileThumbnail(photoBase64, "Manual Item Photo", Modifier.size(56.dp))
                    OutlinedButton(onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            val uri = ImageUtils.createTempCameraUri(context)
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }) { Text("Camera") }
                    OutlinedButton(onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Gallery") }
                }
                FormInputField(name, { name = it }, "Item Name *")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(size, { size = it }, "Size", Modifier.weight(1f))
                    FormInputField(code, { code = it }, "Code", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(qty, { qty = it }, "Qty", Modifier.weight(1f), KeyboardType.Number)
                    FormInputField(unit, { unit = it }, "Unit", Modifier.weight(1f))
                    FormInputField(rate, { rate = it }, "Rate (₹)", Modifier.weight(1f), KeyboardType.Decimal)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    val q = (qty.toLongOrNull() ?: 1L).coerceAtLeast(1L)
                    val r = rate.toDoubleOrNull() ?: 0.0
                    onAdd(
                        SaleLineItem(
                            tileCode = code.ifBlank { "MANUAL" },
                            tileName = name.trim(),
                            tileSize = size.ifBlank { "Custom" },
                            piecesPerBox = 1L,
                            fullBoxes = q,
                            loosePieces = 0L,
                            ratePerBox = r,
                            ratePerPiece = r,
                            lineTotal = q * r,
                            isManualItem = true,
                            manualUnit = unit.ifBlank { "Box" },
                            photoBase64 = photoBase64
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
            ) { Text("Add to Bill") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
