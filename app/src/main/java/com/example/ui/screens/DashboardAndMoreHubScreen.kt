package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.firebase.Formatters
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.*
import java.util.Calendar

@Composable
fun DashboardScreen(
    shop: Shop,
    platformConfig: PlatformConfig,
    tiles: List<TileProduct>,
    customers: List<Customer>,
    suppliers: List<Supplier>,
    sales: List<SaleOrder>,
    dataAccessRequests: List<DataAccessRequest>,
    onQuickAddTile: () -> Unit,
    onQuickNewSale: () -> Unit,
    onQuickPurchase: () -> Unit,
    onQuickExpense: () -> Unit,
    onOpenDelivery: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenSettings: () -> Unit,
    onRespondDataAccess: (DataAccessRequest, Boolean) -> Unit
) {
    val s = LocalAppStrings.current
    val now = System.currentTimeMillis()
    val startOfDay = remember(now) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val startOfMonth = remember(now) {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
    }

    val totalBoxes = remember(tiles) { tiles.sumOf { it.totalBoxes } }
    val lowStockTiles = remember(tiles, platformConfig.lowStockThreshold) {
        tiles.filter { it.totalBoxes <= platformConfig.lowStockThreshold }
    }
    val confirmedSales = remember(sales) { sales.filter { it.orderStatus == "CONFIRMED" } }
    val pendingSales = remember(sales) { sales.filter { it.orderStatus == "PENDING" } }
    val todaySalesSum = remember(confirmedSales, startOfDay) {
        confirmedSales.filter { it.saleDateMillis >= startOfDay }.sumOf { it.totalPayable }
    }
    val monthSalesSum = remember(confirmedSales, startOfMonth) {
        confirmedSales.filter { it.saleDateMillis >= startOfMonth }.sumOf { it.totalPayable }
    }
    val customerDue = remember(customers) { customers.sumOf { it.outstandingBalance.coerceAtLeast(0.0) } }
    val supplierDue = remember(suppliers) { suppliers.sumOf { it.outstandingBalance.coerceAtLeast(0.0) } }
    val pendingRequests = remember(dataAccessRequests) { dataAccessRequests.filter { it.permissionStatus == "PENDING" } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(LightBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (platformConfig.broadcastBanner.isNotBlank()) {
            item {
                Surface(
                    color = AmberGoldSoft,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, AmberGold),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Campaign, contentDescription = null, tint = AmberGoldDark)
                        Spacer(Modifier.width(10.dp))
                        Text(platformConfig.broadcastBanner, style = MaterialTheme.typography.bodyMedium, color = RoyalNavy)
                    }
                }
            }
        }

        items(pendingRequests, key = { it.id }) { req ->
            Surface(
                color = InfoBlueSoft,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, InfoBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Super Admin Data Access Request", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                    Text("Admin: ${req.adminIdentity} | Reason: ${req.reason}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onRespondDataAccess(req, true) },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                        ) { Text("Authorize Access") }
                        OutlinedButton(onClick = { onRespondDataAccess(req, false) }) { Text("Deny / Revoke") }
                    }
                }
            }
        }

        // Quick Actions Strip
        item {
            SectionHeaderRow(s.quickActions)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionChip(s.newSale, Icons.Default.PointOfSale, RoyalNavy, Modifier.weight(1f).testTag("quick_new_sale"), onQuickNewSale)
                QuickActionChip(s.addTile, Icons.Default.AddBox, AmberGoldDark, Modifier.weight(1f).testTag("quick_add_tile"), onQuickAddTile)
                QuickActionChip(s.navPurchase, Icons.Default.ShoppingCart, InfoBlue, Modifier.weight(1f).testTag("quick_purchase"), onQuickPurchase)
                QuickActionChip(s.navExpenses, Icons.Default.Payments, WarningOrange, Modifier.weight(1f).testTag("quick_expense"), onQuickExpense)
            }
        }

        // Secondary Module Strip
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenDelivery,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Deliveries & Returns", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onOpenReports,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.navReports, maxLines = 1)
                }
            }
        }

        // KPI Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTileCard(s.totalTileProducts, "${tiles.size}", Icons.Default.GridView, RoyalNavy, Modifier.weight(1f))
                    KpiTileCard(s.totalStock, "$totalBoxes Boxes", Icons.Default.Inventory2, AmberGoldDark, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTileCard(s.todaysSales, Formatters.currency(todaySalesSum), Icons.Default.Today, SuccessGreen, Modifier.weight(1f))
                    KpiTileCard(s.monthlySales, Formatters.currency(monthSalesSum), Icons.Default.CalendarMonth, InfoBlue, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTileCard(s.confirmedOrders, "${confirmedSales.size}", Icons.Default.CheckCircle, SuccessGreen, Modifier.weight(1f))
                    KpiTileCard(s.pendingOrders, "${pendingSales.size}", Icons.Default.PendingActions, WarningOrange, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTileCard(s.customerOutstanding, Formatters.currency(customerDue), Icons.Default.AccountBalanceWallet, DangerRed, Modifier.weight(1f))
                    KpiTileCard(s.supplierOutstanding, Formatters.currency(supplierDue), Icons.Default.LocalShipping, DangerRed, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTileCard(s.totalCustomers, "${customers.size}", Icons.Default.People, RoyalNavy, Modifier.weight(1f))
                    KpiTileCard(s.lowStockAlerts, "${lowStockTiles.size}", Icons.Default.Warning, if (lowStockTiles.isEmpty()) SuccessGreen else DangerRed, Modifier.weight(1f))
                }
            }
        }

        // Low Stock Alerts
        if (lowStockTiles.isNotEmpty()) {
            item {
                SectionHeaderRow("${s.lowStockAlerts} (≤ ${platformConfig.lowStockThreshold} Boxes)")
                PremiumCard {
                    lowStockTiles.take(5).forEachIndexed { idx, tile ->
                        if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = BorderLight)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TileThumbnail(tile.photoBase64, tile.name, Modifier.size(40.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tile.name, style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                Text("${tile.code} • ${tile.sizeName}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                            }
                            StatusBadge("${tile.totalBoxes} Boxes", DangerRedSoft, DangerRed)
                        }
                    }
                }
            }
        }

        // Recent Transactions
        item {
            SectionHeaderRow(s.recentTransactions)
            if (sales.isEmpty()) {
                PremiumCard {
                    Text("No sales recorded yet. Tap 'New Bill' to create your first invoice.", style = MaterialTheme.typography.bodyMedium, color = SlateMuted)
                }
            } else {
                PremiumCard {
                    sales.sortedByDescending { it.saleDateMillis }.take(5).forEachIndexed { idx, sale ->
                        if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = BorderLight)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("#${sale.invoiceNumber} • ${sale.customerName}", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                Text("${Formatters.date(sale.saleDateMillis)} • ${sale.orderStatus}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Formatters.currency(sale.totalPayable), style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                StatusBadge(
                                    text = sale.paymentStatus,
                                    bgColor = if (sale.paymentStatus == "PAID") SuccessGreenSoft else WarningOrangeSoft,
                                    textColor = if (sale.paymentStatus == "PAID") SuccessGreen else WarningOrange
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    label: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = CardWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BorderLight),
        shadowElevation = 2.dp,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = SlateDark, maxLines = 1)
        }
    }
}

@Composable
fun MoreModulesSheetContent(
    isSuperAdmin: Boolean,
    onNavigate: (String) -> Unit,
    onOpenSuperAdmin: () -> Unit,
    onSwitchShop: () -> Unit,
    onSignOut: () -> Unit
) {
    val s = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Shop Modules & Tools", style = MaterialTheme.typography.headlineSmall, color = RoyalNavy)
        HorizontalDivider(color = BorderLight)
        MoreMenuRow(Icons.Default.ShoppingCart, "Purchase & Suppliers", "Purchase invoices, loading/transport & supplier ledger") { onNavigate("purchase") }
        MoreMenuRow(Icons.Default.LocalShipping, "Delivery & Exchanges", "Partial delivery challans & goods returns/exchanges") { onNavigate("delivery") }
        MoreMenuRow(Icons.Default.Payments, s.navExpenses, "Loading, transport, labour & shop expenses") { onNavigate("expenses") }
        MoreMenuRow(Icons.Default.Assessment, "${s.navReports} (14 Reports)", "Sales, stock valuation, P&L, outstanding & PDF exports") { onNavigate("reports") }
        MoreMenuRow(Icons.Default.Settings, s.navSettings, "Shop profile, tile sizes, locations, backup & support") { onNavigate("settings") }
        HorizontalDivider(color = BorderLight)
        if (isSuperAdmin) {
            MoreMenuRow(Icons.Default.AdminPanelSettings, s.superAdminConsole, "Manage all shops, subscriptions & global controls", tint = AmberGoldDark) { onOpenSuperAdmin() }
        }
        MoreMenuRow(Icons.Default.SwapHoriz, "Switch / Register Shop", "Switch between multi-tenant shops") { onSwitchShop() }
        MoreMenuRow(Icons.Default.Logout, s.logout, "Sign out of this device", tint = DangerRed) { onSignOut() }
    }
}

@Composable
private fun MoreMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = RoyalNavy,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SlateMuted)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SlateMuted)
    }
}
