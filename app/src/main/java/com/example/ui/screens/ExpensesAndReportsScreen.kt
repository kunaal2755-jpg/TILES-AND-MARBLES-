package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.utils.PdfGenerator
import java.io.File
import java.util.Calendar

private val EXPENSE_CATEGORIES = listOf(
    "Loading", "Transport", "Labour", "Shop Expenses", "Repair", "Electricity", "Other"
)

@Composable
fun ExpensesScreen(
    shop: Shop,
    expenses: List<ExpenseEntry>,
    openNewTrigger: Int,
    onSaveExpense: (ExpenseEntry) -> Unit
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf("All") }
    var showDialog by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntry?>(null) }
    var generatedPdf by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(openNewTrigger) {
        if (openNewTrigger > 0) {
            editingExpense = null
            showDialog = true
        }
    }

    val startOfDay = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
    }
    val startOfMonth = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }.timeInMillis
    }

    val todaySum = remember(expenses) { expenses.filter { it.dateMillis >= startOfDay }.sumOf { it.amount } }
    val monthSum = remember(expenses) { expenses.filter { it.dateMillis >= startOfMonth }.sumOf { it.amount } }
    val filtered = remember(expenses, selectedCategory) {
        expenses.filter { selectedCategory == "All" || it.category.equals(selectedCategory, true) }
            .sortedByDescending { it.dateMillis }
    }

    Box(modifier = Modifier.fillMaxSize().background(LightBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = SecondaryNavy, contentColor = Color.White) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Loading & Shop Expenses", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text("Today: ${Formatters.currency(todaySum)}  •  Month: ${Formatters.currency(monthSum)}", style = MaterialTheme.typography.bodySmall, color = AmberGold)
                    }
                    OutlinedButton(
                        onClick = {
                            generatedPdf = PdfGenerator.generateTableReportPdf(
                                context = context,
                                shop = shop,
                                reportTitle = "Expenses Register",
                                dateFilterSummary = Formatters.date(System.currentTimeMillis()),
                                summaryLines = listOf("Today Expenses: ${Formatters.currency(todaySum)}", "Month Expenses: ${Formatters.currency(monthSum)}"),
                                headers = listOf("Date", "Category", "Mode", "Invoice", "Amount"),
                                rows = filtered.map { listOf(Formatters.date(it.dateMillis), it.category, it.paymentMode, it.relatedInvoiceNumber.ifBlank { "-" }, Formatters.currency(it.amount)) }
                            )
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberGold)
                    ) { Text("Export PDF", color = AmberGold) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (listOf("All") + EXPENSE_CATEGORIES).forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                EmptyStateView(
                    title = "No Expenses Recorded",
                    subtitle = "Track loading, transport, labour, electricity, repair & shop overheads.",
                    actionLabel = "Add Expense",
                    onAction = { editingExpense = null; showDialog = true }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 84.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { exp ->
                        PremiumCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(exp.category, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                    Text("${Formatters.date(exp.dateMillis)} • ${exp.paymentMode} ${if (exp.relatedInvoiceNumber.isNotBlank()) "• #${exp.relatedInvoiceNumber}" else ""}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    if (exp.description.isNotBlank()) {
                                        Text(exp.description, style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Formatters.currency(exp.amount), style = MaterialTheme.typography.titleMedium, color = DangerRed)
                                    IconButton(onClick = { editingExpense = exp; showDialog = true }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Expense", tint = RoyalNavy)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editingExpense = null; showDialog = true },
            containerColor = RoyalNavy,
            contentColor = AmberGold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("add_expense_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Expense")
        }
    }

    if (showDialog) {
        var category by remember { mutableStateOf(editingExpense?.category ?: "Loading") }
        var amount by remember { mutableStateOf(editingExpense?.amount?.let { if (it == 0.0) "" else it.toString() } ?: "") }
        var mode by remember { mutableStateOf(editingExpense?.paymentMode ?: "Cash") }
        var invoiceNo by remember { mutableStateOf(editingExpense?.relatedInvoiceNumber ?: "") }
        var desc by remember { mutableStateOf(editingExpense?.description ?: "") }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = CardWhite,
            title = { Text(if (editingExpense == null) "Add Expense" else "Edit Expense", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Category", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EXPENSE_CATEGORIES.forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                        }
                    }
                    FormInputField(amount, { amount = it }, "Amount (₹) *", keyboardType = KeyboardType.Decimal)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Cash", "UPI", "Bank").forEach { m ->
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
                        }
                    }
                    FormInputField(invoiceNo, { invoiceNo = it }, "Related Bill / Invoice # (Optional)")
                    FormInputField(desc, { desc = it }, "Description / Remarks", singleLine = false)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amount.toDoubleOrNull() ?: return@Button
                        if (amt <= 0) return@Button
                        onSaveExpense(
                            (editingExpense ?: ExpenseEntry()).copy(
                                category = category,
                                amount = amt,
                                paymentMode = mode,
                                relatedInvoiceNumber = invoiceNo.trim(),
                                description = desc.trim()
                            )
                        )
                        showDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }

    if (generatedPdf != null) {
        PdfActionDialog(file = generatedPdf!!, onDismiss = { generatedPdf = null })
    }
}

@Composable
fun ReportsScreen(
    shop: Shop,
    platformConfig: PlatformConfig,
    tiles: List<TileProduct>,
    sales: List<SaleOrder>,
    purchases: List<PurchaseBill>,
    customers: List<Customer>,
    suppliers: List<Supplier>,
    expenses: List<ExpenseEntry>,
    returns: List<ReturnExchange>,
    staff: List<StaffMember>,
    attendance: List<AttendanceRecord>,
    salaryMonths: List<SalaryMonthRecord>
) {
    val context = LocalContext.current
    var rangeFilter by remember { mutableStateOf("ALL") } // ALL, TODAY, MONTH
    var activeReportIndex by remember { mutableStateOf(0) }
    var generatedPdf by remember { mutableStateOf<File?>(null) }

    val startOfDay = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
    }
    val startOfMonth = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }.timeInMillis
    }

    fun inRange(millis: Long): Boolean = when (rangeFilter) {
        "TODAY" -> millis >= startOfDay
        "MONTH" -> millis >= startOfMonth
        else -> true
    }

    val reportNames = listOf(
        "1. Daily Sales Report",
        "2. Monthly Sales Report",
        "3. Purchase Report",
        "4. Stock Valuation Report",
        "5. Low Stock Report",
        "6. Damaged Stock Report",
        "7. Customer Outstanding Report",
        "8. Supplier Outstanding Report",
        "9. Expenses Report",
        "10. Loading & Transport Charges",
        "11. Return & Exchange Report",
        "12. Staff Attendance Report",
        "13. Staff Salary Report",
        "14. Profit & Loss Report"
    )

    data class ReportData(
        val title: String,
        val summary: List<String>,
        val headers: List<String>,
        val rows: List<List<String>>
    )

    val currentReport: ReportData = remember(
        activeReportIndex, rangeFilter, tiles, sales, purchases, customers, suppliers, expenses, returns, staff, attendance, salaryMonths
    ) {
        when (activeReportIndex) {
            0 -> {
                val list = sales.filter { it.saleDateMillis >= startOfDay && it.orderStatus == "CONFIRMED" }
                ReportData(
                    "Daily Sales Report",
                    listOf("Bills Today: ${list.size}", "Revenue: ${Formatters.currency(list.sumOf { it.totalPayable })}"),
                    listOf("Invoice", "Customer", "Mode", "Total", "Received"),
                    list.map { listOf(it.invoiceNumber, it.customerName, it.paymentMode, Formatters.currency(it.totalPayable), Formatters.currency(it.receivedAmount)) }
                )
            }
            1 -> {
                val list = sales.filter { it.saleDateMillis >= startOfMonth && it.orderStatus == "CONFIRMED" }
                ReportData(
                    "Monthly Sales Report",
                    listOf("Monthly Bills: ${list.size}", "Monthly Total: ${Formatters.currency(list.sumOf { it.totalPayable })}"),
                    listOf("Date", "Invoice", "Customer", "Total", "Due"),
                    list.map { listOf(Formatters.date(it.saleDateMillis), it.invoiceNumber, it.customerName, Formatters.currency(it.totalPayable), Formatters.currency(it.remainingBalance)) }
                )
            }
            2 -> {
                val list = purchases.filter { inRange(it.purchaseDateMillis) }
                ReportData(
                    "Purchase Report",
                    listOf("Bills: ${list.size}", "Total Purchase: ${Formatters.currency(list.sumOf { it.totalAmount })}"),
                    listOf("Date", "Invoice", "Supplier", "Total", "Due"),
                    list.map { listOf(Formatters.date(it.purchaseDateMillis), it.invoiceNumber, it.supplierName, Formatters.currency(it.totalAmount), Formatters.currency(it.remainingDue)) }
                )
            }
            3 -> {
                val totalCostVal = tiles.sumOf { it.totalBoxes * it.purchaseCost }
                val totalSellVal = tiles.sumOf { it.totalBoxes * it.sellingPricePerBox }
                ReportData(
                    "Stock Valuation Report",
                    listOf("Products: ${tiles.size}", "Cost Valuation: ${Formatters.currency(totalCostVal)}", "Retail Valuation: ${Formatters.currency(totalSellVal)}"),
                    listOf("Tile", "Size", "Boxes", "Cost Value", "Sale Value"),
                    tiles.map { listOf(it.name, it.sizeName, "${it.totalBoxes}", Formatters.currency(it.totalBoxes * it.purchaseCost), Formatters.currency(it.totalBoxes * it.sellingPricePerBox)) }
                )
            }
            4 -> {
                val low = tiles.filter { it.totalBoxes <= platformConfig.lowStockThreshold }
                ReportData(
                    "Low Stock Report",
                    listOf("Threshold: <= ${platformConfig.lowStockThreshold} boxes", "Items Alert: ${low.size}"),
                    listOf("Code", "Tile Name", "Size", "Boxes", "Loose"),
                    low.map { listOf(it.code, it.name, it.sizeName, "${it.totalBoxes}", "${it.loosePieces}") }
                )
            }
            5 -> {
                val dmg = tiles.filter { it.damagedPieces > 0 }
                ReportData(
                    "Damaged Stock Report",
                    listOf("Affected SKUs: ${dmg.size}", "Total Damaged Pieces: ${dmg.sumOf { it.damagedPieces }}"),
                    listOf("Code", "Tile Name", "Damaged Pcs", "Sample Pcs"),
                    dmg.map { listOf(it.code, it.name, "${it.damagedPieces}", "${it.samplePieces}") }
                )
            }
            6 -> {
                val due = customers.filter { it.outstandingBalance > 0 }
                ReportData(
                    "Customer Outstanding Report",
                    listOf("Customers Due: ${due.size}", "Total Due: ${Formatters.currency(due.sumOf { it.outstandingBalance })}"),
                    listOf("Customer", "Mobile", "Purchases", "Paid", "Balance"),
                    due.map { listOf(it.name, it.mobile, Formatters.currency(it.totalPurchases), Formatters.currency(it.totalPayments), Formatters.currency(it.outstandingBalance)) }
                )
            }
            7 -> {
                val due = suppliers.filter { it.outstandingBalance > 0 }
                ReportData(
                    "Supplier Outstanding Report",
                    listOf("Suppliers Due: ${due.size}", "Total Payable: ${Formatters.currency(due.sumOf { it.outstandingBalance })}"),
                    listOf("Supplier", "Mobile", "Purchases", "Paid", "Balance"),
                    due.map { listOf(it.name, it.mobile, Formatters.currency(it.totalPurchases), Formatters.currency(it.totalPaid), Formatters.currency(it.outstandingBalance)) }
                )
            }
            8 -> {
                val list = expenses.filter { inRange(it.dateMillis) }
                ReportData(
                    "Expenses Report",
                    listOf("Entries: ${list.size}", "Total Expenses: ${Formatters.currency(list.sumOf { it.amount })}"),
                    listOf("Date", "Category", "Mode", "Description", "Amount"),
                    list.map { listOf(Formatters.date(it.dateMillis), it.category, it.paymentMode, it.description.ifBlank { "-" }, Formatters.currency(it.amount)) }
                )
            }
            9 -> {
                val loadingSales = sales.filter { inRange(it.saleDateMillis) && (it.loadingCharges > 0 || it.deliveryCharges > 0) }
                val loadingPur = purchases.filter { inRange(it.purchaseDateMillis) && (it.loadingCharges > 0 || it.transportCharges > 0) }
                val loadingExp = expenses.filter { inRange(it.dateMillis) && (it.category == "Loading" || it.category == "Transport") }
                val totalLT = loadingSales.sumOf { it.loadingCharges + it.deliveryCharges } +
                    loadingPur.sumOf { it.loadingCharges + it.transportCharges } +
                    loadingExp.sumOf { it.amount }
                val combinedRows = buildList {
                    loadingSales.forEach { add(listOf(Formatters.date(it.saleDateMillis), "Sale #${it.invoiceNumber}", "Loading/Del", Formatters.currency(it.loadingCharges + it.deliveryCharges))) }
                    loadingPur.forEach { add(listOf(Formatters.date(it.purchaseDateMillis), "Purchase #${it.invoiceNumber}", "Load/Trans", Formatters.currency(it.loadingCharges + it.transportCharges))) }
                    loadingExp.forEach { add(listOf(Formatters.date(it.dateMillis), "Expense", it.category, Formatters.currency(it.amount))) }
                }
                ReportData(
                    "Loading & Transport Charges",
                    listOf("Total Loading & Transport: ${Formatters.currency(totalLT)}"),
                    listOf("Date", "Source", "Type", "Amount"),
                    combinedRows
                )
            }
            10 -> {
                val list = returns.filter { inRange(it.returnDateMillis) }
                ReportData(
                    "Return & Exchange Report",
                    listOf("Total Cases: ${list.size}"),
                    listOf("Date", "Customer", "Returned Tile", "Condition", "Settlement"),
                    list.map { listOf(Formatters.date(it.returnDateMillis), it.customerName, it.returnedTileName, it.condition, "${it.settlementType} (${Formatters.currency(it.differenceAmount)})") }
                )
            }
            11 -> {
                val mk = Formatters.monthKey()
                val rows = staff.map { m ->
                    val recs = attendance.filter { it.staffId == m.id && it.monthKey == mk }
                    listOf(m.fullName, "${recs.count { it.status == "P" }}", "${recs.count { it.status == "HALF" }}", "${recs.count { it.status == "A" }}")
                }
                ReportData(
                    "Staff Attendance Report ($mk)",
                    listOf("Active Staff: ${staff.size}"),
                    listOf("Staff Name", "Present", "Half", "Absent"),
                    rows
                )
            }
            12 -> {
                val mk = Formatters.monthKey()
                val list = salaryMonths.filter { it.monthKey == mk }
                ReportData(
                    "Staff Salary Report ($mk)",
                    listOf("Records: ${list.size}", "Total Net Payable: ${Formatters.currency(list.sumOf { it.netPayable })}"),
                    listOf("Staff", "Earned", "Advances", "Net Payable", "Status"),
                    list.map { listOf(it.staffName, Formatters.currency(it.earnedSalary), Formatters.currency(it.advancesDeducted), Formatters.currency(it.netPayable), if (it.isFinalized) "FINAL" else "OPEN") }
                )
            }
            else -> {
                val confirmed = sales.filter { inRange(it.saleDateMillis) && it.orderStatus == "CONFIRMED" }
                val totalRevenue = confirmed.sumOf { it.totalPayable }
                val tileMap = tiles.associateBy { it.id }
                val cogs = confirmed.sumOf { sale ->
                    sale.items.sumOf { item ->
                        val cost = tileMap[item.tileId]?.purchaseCost ?: 0.0
                        item.fullBoxes * cost
                    }
                }
                val totalExpenses = expenses.filter { inRange(it.dateMillis) }.sumOf { it.amount }
                val netProfit = totalRevenue - cogs - totalExpenses
                ReportData(
                    "Profit & Loss Report",
                    listOf(
                        "Confirmed Sales Revenue: ${Formatters.currency(totalRevenue)}",
                        "Estimated COGS: ${Formatters.currency(cogs)}",
                        "Shop & Loading Expenses: ${Formatters.currency(totalExpenses)}",
                        "Estimated Net Profit: ${Formatters.currency(netProfit)}"
                    ),
                    listOf("P&L Line Item", "Amount"),
                    listOf(
                        listOf("Confirmed Sales Revenue", Formatters.currency(totalRevenue)),
                        listOf("Cost of Goods Sold (Est.)", Formatters.currency(cogs)),
                        listOf("Gross Margin", Formatters.currency(totalRevenue - cogs)),
                        listOf("Operating & Loading Expenses", Formatters.currency(totalExpenses)),
                        listOf("Net Profit / Loss", Formatters.currency(netProfit))
                    )
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(LightBackground)) {
        Surface(color = SecondaryNavy, contentColor = Color.White) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Business Reports (14)", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ALL" to "All", "TODAY" to "Today", "MONTH" to "Month").forEach { (k, lbl) ->
                            FilterChip(selected = rangeFilter == k, onClick = { rangeFilter = k }, label = { Text(lbl) })
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    reportNames.forEachIndexed { idx, name ->
                        FilterChip(
                            selected = activeReportIndex == idx,
                            onClick = { activeReportIndex = idx },
                            label = { Text(name) }
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                PremiumCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(currentReport.title, style = MaterialTheme.typography.titleLarge, color = RoyalNavy)
                            Text("Filter: $rangeFilter", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                        }
                        Button(
                            onClick = {
                                generatedPdf = PdfGenerator.generateTableReportPdf(
                                    context = context,
                                    shop = shop,
                                    reportTitle = currentReport.title,
                                    dateFilterSummary = rangeFilter,
                                    summaryLines = currentReport.summary,
                                    headers = currentReport.headers,
                                    rows = currentReport.rows.ifEmpty { listOf(List(currentReport.headers.size) { "-" }) }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                            modifier = Modifier.testTag("export_report_pdf_button")
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = AmberGold, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export PDF")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    currentReport.summary.forEach { line ->
                        Text("• $line", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = RoyalNavy)
                    }
                }
            }

            if (currentReport.rows.isEmpty()) {
                item {
                    PremiumCard {
                        Text("No records match the selected date filter for ${currentReport.title}.", color = SlateMuted)
                    }
                }
            } else {
                items(currentReport.rows) { row ->
                    PremiumCard {
                        currentReport.headers.zip(row).forEach { (h, v) ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(h, style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = RoyalNavy)
                            }
                        }
                    }
                }
            }
        }
    }

    if (generatedPdf != null) {
        PdfActionDialog(file = generatedPdf!!, onDismiss = { generatedPdf = null })
    }
}
