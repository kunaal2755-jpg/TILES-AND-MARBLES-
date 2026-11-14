package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
fun CustomersScreen(
    shop: Shop,
    customers: List<Customer>,
    sales: List<SaleOrder>,
    deliveries: List<DeliveryRecord>,
    returns: List<ReturnExchange>,
    onSaveCustomer: (Customer) -> Unit,
    onRecordPayment: (Customer, Double, SaleOrder?, String) -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var showAddEdit by remember { mutableStateOf(false) }
    var editingCustomer by remember { mutableStateOf<Customer?>(null) }
    var ledgerCustomer by remember { mutableStateOf<Customer?>(null) }
    var paymentCustomer by remember { mutableStateOf<Customer?>(null) }
    var generatedPdf by remember { mutableStateOf<File?>(null) }

    val filtered = remember(customers, search) {
        customers.filter {
            val q = search.trim().lowercase()
            q.isBlank() || it.name.lowercase().contains(q) || it.mobile.contains(q)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LightBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = SecondaryNavy, contentColor = androidx.compose.ui.graphics.Color.White) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Customer Directory & Ledgers", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
                        Text("Total Due: ${Formatters.currency(customers.sumOf { it.outstandingBalance.coerceAtLeast(0.0) })}", style = MaterialTheme.typography.bodySmall, color = AmberGold)
                    }
                    OutlinedButton(
                        onClick = {
                            val dueList = customers.filter { it.outstandingBalance > 0 }
                            generatedPdf = PdfGenerator.generateTableReportPdf(
                                context = context,
                                shop = shop,
                                reportTitle = "Customer Outstanding Statement",
                                dateFilterSummary = Formatters.date(System.currentTimeMillis()),
                                summaryLines = listOf("Total Customers With Due: ${dueList.size}", "Grand Outstanding: ${Formatters.currency(dueList.sumOf { it.outstandingBalance })}"),
                                headers = listOf("Customer", "Mobile", "Purchases", "Paid", "Due"),
                                rows = dueList.map { listOf(it.name, it.mobile, Formatters.currency(it.totalPurchases), Formatters.currency(it.totalPayments), Formatters.currency(it.outstandingBalance)) }
                            )
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberGold)
                    ) { Text("Outstanding PDF", color = AmberGold) }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search customer name or mobile...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )

            if (filtered.isEmpty()) {
                EmptyStateView(
                    title = "No Customers Found",
                    subtitle = "Add contractors, builders, and homeowners to track purchases, payments & ledgers.",
                    actionLabel = "Add Customer",
                    onAction = { editingCustomer = null; showAddEdit = true }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 84.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { cust ->
                        PremiumCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cust.name, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                    Text("${cust.mobile} • ${cust.address.ifBlank { "No address" }}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    Text("Purchases: ${Formatters.currency(cust.totalPurchases)} • Paid: ${Formatters.currency(cust.totalPayments)}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    StatusBadge(
                                        "Due ${Formatters.currency(cust.outstandingBalance)}",
                                        if (cust.outstandingBalance > 0) DangerRedSoft else SuccessGreenSoft,
                                        if (cust.outstandingBalance > 0) DangerRed else SuccessGreen
                                    )
                                    Row {
                                        IconButton(onClick = { editingCustomer = cust; showAddEdit = true }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Customer", tint = RoyalNavy)
                                        }
                                        IconButton(onClick = { IntentUtils.openWhatsApp(context, cust.mobile, "Hello ${cust.name}, your current balance with ${shop.shopName} is ${Formatters.currency(cust.outstandingBalance)}.") }) {
                                            Icon(Icons.Default.Chat, contentDescription = "WhatsApp Customer", tint = SuccessGreen)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { ledgerCustomer = cust },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Ledger & PDFs") }
                                Button(
                                    onClick = { paymentCustomer = cust },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Receive Payment") }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editingCustomer = null; showAddEdit = true },
            containerColor = RoyalNavy,
            contentColor = AmberGold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("add_customer_fab")
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = "Add Customer")
        }
    }

    if (showAddEdit) {
        var name by remember { mutableStateOf(editingCustomer?.name ?: "") }
        var mobile by remember { mutableStateOf(editingCustomer?.mobile ?: "") }
        var address by remember { mutableStateOf(editingCustomer?.address ?: "") }
        var prevBal by remember { mutableStateOf((editingCustomer?.previousBalance ?: 0.0).let { if (it == 0.0) "" else it.toString() }) }

        AlertDialog(
            onDismissRequest = { showAddEdit = false },
            containerColor = CardWhite,
            title = { Text(if (editingCustomer == null) "Add Customer" else "Edit Customer", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(name, { name = it }, "Customer Name *")
                    FormInputField(mobile, { mobile = it }, "Mobile Number *", keyboardType = KeyboardType.Phone)
                    FormInputField(address, { address = it }, "Address")
                    FormInputField(prevBal, { prevBal = it }, "Previous Balance (₹)", keyboardType = KeyboardType.Decimal)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isBlank() || mobile.isBlank()) return@Button
                        val model = (editingCustomer ?: Customer()).copy(
                            name = name.trim(),
                            mobile = mobile.trim(),
                            address = address.trim(),
                            previousBalance = prevBal.toDoubleOrNull() ?: 0.0
                        )
                        onSaveCustomer(model)
                        showAddEdit = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddEdit = false }) { Text("Cancel") } }
        )
    }

    if (paymentCustomer != null) {
        val cust = paymentCustomer!!
        var amount by remember { mutableStateOf(cust.outstandingBalance.coerceAtLeast(0.0).let { if (it == 0.0) "" else it.toString() }) }
        var mode by remember { mutableStateOf("Cash") }
        AlertDialog(
            onDismissRequest = { paymentCustomer = null },
            containerColor = CardWhite,
            title = { Text("Receive Payment: ${cust.name}", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current Due: ${Formatters.currency(cust.outstandingBalance)}", color = DangerRed)
                    FormInputField(amount, { amount = it }, "Amount Received (₹) *", keyboardType = KeyboardType.Decimal)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Cash", "UPI", "Bank").forEach { m ->
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amount.toDoubleOrNull() ?: return@Button
                        if (amt <= 0) return@Button
                        val unpaidSale = sales.firstOrNull { it.customerId == cust.id && it.remainingBalance > 0 }
                        onRecordPayment(cust, amt, unpaidSale, mode)
                        generatedPdf = PdfGenerator.generateTableReportPdf(
                            context = context,
                            shop = shop,
                            reportTitle = "Payment Receipt",
                            dateFilterSummary = Formatters.date(System.currentTimeMillis()),
                            summaryLines = listOf("Received From: ${cust.name} (${cust.mobile})", "Amount: ${Formatters.currency(amt)} via $mode"),
                            headers = listOf("Particulars", "Amount"),
                            rows = listOf(
                                listOf("Previous Balance", Formatters.currency(cust.outstandingBalance)),
                                listOf("Payment Received ($mode)", Formatters.currency(amt)),
                                listOf("Updated Balance", Formatters.currency((cust.outstandingBalance - amt).coerceAtLeast(0.0)))
                            )
                        )
                        paymentCustomer = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) { Text("Save & Receipt PDF") }
            },
            dismissButton = { TextButton(onClick = { paymentCustomer = null }) { Text("Cancel") } }
        )
    }

    if (ledgerCustomer != null) {
        val cust = ledgerCustomer!!
        val custSales = sales.filter { it.customerId == cust.id || it.customerName.equals(cust.name, true) }
        val custDels = deliveries.filter { it.customerId == cust.id || it.customerName.equals(cust.name, true) }
        val custRets = returns.filter { it.customerId == cust.id || it.customerName.equals(cust.name, true) }

        val ledgerRows = buildList<List<String>> {
            custSales.forEach { s ->
                add(listOf(Formatters.date(s.saleDateMillis), "Sale #${s.invoiceNumber}", Formatters.currency(s.totalPayable), Formatters.currency(s.receivedAmount)))
            }
            custDels.forEach { d ->
                add(listOf(Formatters.date(d.deliveryDateMillis), "Delivery #${d.invoiceNumber}", "-", "Delivered"))
            }
            custRets.forEach { r ->
                add(listOf(Formatters.date(r.returnDateMillis), "Return (${r.condition})", Formatters.currency(r.differenceAmount), r.settlementType))
            }
        }

        AlertDialog(
            onDismissRequest = { ledgerCustomer = null },
            containerColor = CardWhite,
            title = { Text("Customer Ledger: ${cust.name}", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Opening: ${Formatters.currency(cust.previousBalance)} | Purchases: ${Formatters.currency(cust.totalPurchases)} | Paid: ${Formatters.currency(cust.totalPayments)}", style = MaterialTheme.typography.bodySmall)
                    Text("Closing Balance: ${Formatters.currency(cust.outstandingBalance)}", style = MaterialTheme.typography.titleMedium, color = DangerRed)
                    HorizontalDivider()
                    if (ledgerRows.isEmpty()) {
                        Text("No transactions recorded yet.", color = SlateMuted)
                    } else {
                        ledgerRows.forEach { row ->
                            Text(row.joinToString("  •  "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        generatedPdf = PdfGenerator.generateTableReportPdf(
                            context = context,
                            shop = shop,
                            reportTitle = "Customer Ledger - ${cust.name}",
                            dateFilterSummary = "All Transactions",
                            summaryLines = listOf("Customer: ${cust.name} (${cust.mobile})", "Closing Outstanding: ${Formatters.currency(cust.outstandingBalance)}"),
                            headers = listOf("Date", "Particulars", "Amount", "Paid/Note"),
                            rows = ledgerRows.ifEmpty { listOf(listOf("-", "Opening Balance", Formatters.currency(cust.previousBalance), "-")) }
                        )
                        ledgerCustomer = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Generate Ledger PDF") }
            },
            dismissButton = { TextButton(onClick = { ledgerCustomer = null }) { Text("Close") } }
        )
    }

    if (generatedPdf != null) {
        PdfActionDialog(file = generatedPdf!!, onDismiss = { generatedPdf = null })
    }
}
