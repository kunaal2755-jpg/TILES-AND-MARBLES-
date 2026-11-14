package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.firebase.Formatters
import com.example.data.firebase.SecurityUtils
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun SuperAdminConsoleScreen(
    shops: List<Shop>,
    platformConfig: PlatformConfig,
    adminSecrets: AdminSecrets,
    dataAccessRequests: List<DataAccessRequest>,
    shopArchives: List<ShopArchive>,
    backups: List<BackupRecord>,
    adminLogs: List<AdminLog>,
    onCreateShopAccount: (String, String, String, String, String, String, String, String, Long, Double) -> Unit,
    onUpdateShopStatus: (Shop, String) -> Unit,
    onUpdateShopCredentials: (Shop, String, String) -> Unit,
    onUpdateSubscription: (Shop, String, Long, Double, Long) -> Unit,
    onArchiveShop: (Shop) -> Unit,
    onRestoreShop: (Shop) -> Unit,
    onSavePlatformConfig: (PlatformConfig) -> Unit,
    onSaveAdminSecrets: (AdminSecrets) -> Unit,
    onTestTelegram: (String, String) -> Unit,
    onRequestDataAccess: (Shop, String) -> Unit,
    onRevokeDataAccess: (DataAccessRequest) -> Unit,
    onSwitchToUserApp: () -> Unit,
    onSignOut: () -> Unit
) {
    var mainTab by remember { mutableStateOf(0) } // 0=Dashboard, 1=Shops, 2=Control, 3=Vault
    var shopSearch by remember { mutableStateOf("") }
    var showCreateShopDialog by remember { mutableStateOf(false) }
    var credShop by remember { mutableStateOf<Shop?>(null) }
    var subShop by remember { mutableStateOf<Shop?>(null) }
    var accessReqShop by remember { mutableStateOf<Shop?>(null) }

    Scaffold(
        topBar = {
            NavyTopHeader(
                title = "Super Admin Console",
                subtitle = "Master Control • ${platformConfig.brandingName}",
                actions = {
                    Button(
                        onClick = onSwitchToUserApp,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = RoyalNavy),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("switch_to_user_app_button")
                    ) {
                        Text("User App", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Default.Logout, contentDescription = "Sign Out", tint = Color.White)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = RoyalNavy, contentColor = Color.White) {
                val navItems = listOf(
                    0 to ("Dashboard" to Icons.Default.Dashboard),
                    1 to ("Shops" to Icons.Default.Store),
                    2 to ("Control" to Icons.Default.Tune),
                    3 to ("Vault" to Icons.Default.Security)
                )
                navItems.forEach { (idx, pair) ->
                    NavigationBarItem(
                        selected = mainTab == idx,
                        onClick = { mainTab = idx },
                        icon = { Icon(pair.second, contentDescription = pair.first) },
                        label = { Text(pair.first) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = RoyalNavy,
                            selectedTextColor = AmberGold,
                            indicatorColor = AmberGold,
                            unselectedIconColor = Color.White.copy(alpha = 0.7f),
                            unselectedTextColor = Color.White.copy(alpha = 0.7f)
                        )
                    )
                }
            }
        },
        containerColor = LightBackground
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (mainTab) {
                0 -> {
                    val activeCount = shops.count { it.status == "active" }
                    val pendingCount = shops.count { it.status == "pending" }
                    val blockedCount = shops.count { it.status == "blocked" || it.status == "suspended" }
                    val expiredCount = shops.count { it.subscriptionExpiryMillis > 0 && it.subscriptionExpiryMillis < System.currentTimeMillis() }
                    val totalRevenue = shops.sumOf { it.subscriptionPaid }

                    LazyColumn(
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                KpiTileCard("Registered Shops", "${shops.size}", Icons.Default.Storefront, RoyalNavy, Modifier.weight(1f))
                                KpiTileCard("Active Shops", "$activeCount", Icons.Default.CheckCircle, SuccessGreen, Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                KpiTileCard("Pending Approval", "$pendingCount", Icons.Default.Pending, WarningOrange, Modifier.weight(1f))
                                KpiTileCard("Blocked / Suspended", "$blockedCount", Icons.Default.Block, DangerRed, Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                KpiTileCard("Expired Plans", "$expiredCount", Icons.Default.EventBusy, DangerRed, Modifier.weight(1f))
                                KpiTileCard("Plan Collection", Formatters.currency(totalRevenue), Icons.Default.Payments, SuccessGreen, Modifier.weight(1f))
                            }
                        }
                        item {
                            SectionHeaderRow("Recent Super Admin Activity Logs")
                        }
                        items(adminLogs.take(20), key = { it.id }) { log ->
                            PremiumCard {
                                Text(log.action, style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                Text("${log.details} • Shop: ${log.targetShopId.ifBlank { "Platform" }}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                Text("Actor: ${log.actorEmail}", style = MaterialTheme.typography.labelSmall, color = SlateMuted)
                            }
                        }
                    }
                }

                1 -> {
                    val filtered = remember(shops, shopSearch) {
                        shops.filter {
                            val q = shopSearch.trim().lowercase()
                            q.isBlank() || it.shopName.lowercase().contains(q) || it.shopCode.lowercase().contains(q) || it.mobile.contains(q) || it.ownerName.lowercase().contains(q)
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = shopSearch,
                                onValueChange = { shopSearch = it },
                                placeholder = { Text("Search shops by name, ID code, mobile...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(12.dp)
                            )
                            LazyColumn(
                                contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 84.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filtered, key = { it.id }) { shop ->
                                    PremiumCard {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("${shop.shopName} (${shop.shopCode})", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                                Text("Owner: ${shop.ownerName} • ${shop.mobile}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                                Text("Plan: ${shop.subscriptionPlan.uppercase()} • Paid: ${Formatters.currency(shop.subscriptionPaid)} • Exp: ${Formatters.date(shop.subscriptionExpiryMillis)}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                                            }
                                            StatusBadge(
                                                shop.status.uppercase(),
                                                if (shop.status == "active") SuccessGreenSoft else DangerRedSoft,
                                                if (shop.status == "active") SuccessGreen else DangerRed
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(onClick = { credShop = shop }) { Text("Credentials") }
                                            OutlinedButton(onClick = { subShop = shop }) { Text("Subscription") }
                                            OutlinedButton(onClick = { accessReqShop = shop }) { Text("Request Access") }
                                            if (shop.status != "active") {
                                                Button(onClick = { onUpdateShopStatus(shop, "active") }, colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)) { Text("Activate") }
                                            }
                                            if (shop.status == "active") {
                                                OutlinedButton(onClick = { onUpdateShopStatus(shop, "blocked") }) { Text("Block", color = DangerRed) }
                                            }
                                            if (shop.status != "archived") {
                                                OutlinedButton(onClick = { onArchiveShop(shop) }) { Text("Archive") }
                                            } else {
                                                Button(onClick = { onRestoreShop(shop) }, colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = RoyalNavy)) { Text("Restore") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        FloatingActionButton(
                            onClick = { showCreateShopDialog = true },
                            containerColor = RoyalNavy,
                            contentColor = AmberGold,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("admin_create_shop_fab")
                        ) {
                            Icon(Icons.Default.AddBusiness, contentDescription = "Create Shop")
                        }
                    }
                }

                2 -> {
                    SuperAdminControlSection(
                        platformConfig = platformConfig,
                        adminSecrets = adminSecrets,
                        adminLogs = adminLogs,
                        onSavePlatformConfig = onSavePlatformConfig,
                        onSaveAdminSecrets = onSaveAdminSecrets,
                        onTestTelegram = onTestTelegram
                    )
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item { SectionHeaderRow("Authorized Shop Data Access (${dataAccessRequests.size})") }
                        items(dataAccessRequests, key = { it.id }) { req ->
                            PremiumCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(req.shopName, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                        Text("Reason: ${req.reason}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    }
                                    StatusBadge(
                                        req.permissionStatus,
                                        if (req.permissionStatus == "APPROVED") SuccessGreenSoft else WarningOrangeSoft,
                                        if (req.permissionStatus == "APPROVED") SuccessGreen else WarningOrange
                                    )
                                }
                                if (req.permissionStatus == "APPROVED" && req.snapshotSummaryJson.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("Owner-Authorized Snapshot: ${req.snapshotSummaryJson}", style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
                                }
                                if (req.permissionStatus != "REVOKED") {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(onClick = { onRevokeDataAccess(req) }) {
                                        Text("Revoke Access Immediately", color = DangerRed)
                                    }
                                }
                            }
                        }

                        item { SectionHeaderRow("Shop Archives (${shopArchives.size})") }
                        items(shopArchives, key = { it.id }) { arch ->
                            PremiumCard {
                                Text("${arch.shopName} (${arch.shopId})", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                Text("Backup Verified: ${arch.backupVerified} • Restored: ${arch.restored}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                            }
                        }

                        item { SectionHeaderRow("Cloud Backup Vault (${backups.size})") }
                        items(backups, key = { it.id }) { b ->
                            PremiumCard {
                                Text("${b.shopName} (${b.backupType})", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                                Text("Records: ${b.recordCount} • Images: ${b.imageRefCount} • Status: ${b.status}", style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateShopDialog) {
        var code by remember { mutableStateOf("TV-${(1000..9999).random()}") }
        var name by remember { mutableStateOf("") }
        var owner by remember { mutableStateOf("") }
        var uid by remember { mutableStateOf("") }
        var mobile by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var plan by remember { mutableStateOf("monthly") }
        var days by remember { mutableStateOf("30") }
        var paid by remember { mutableStateOf(platformConfig.planMonthlyPrice.toString()) }

        AlertDialog(
            onDismissRequest = { showCreateShopDialog = false },
            containerColor = CardWhite,
            title = { Text("Create Shop Account", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(code, { code = it }, "Shop User ID / Code *")
                    FormInputField(name, { name = it }, "Shop Name *")
                    FormInputField(owner, { owner = it }, "Owner Name *")
                    FormInputField(uid, { uid = it }, "Owner Firebase UID (Optional)")
                    FormInputField(mobile, { mobile = it }, "Mobile Number *", keyboardType = KeyboardType.Phone)
                    FormInputField(email, { email = it }, "Owner Email", keyboardType = KeyboardType.Email)
                    FormInputField(pass, { pass = it }, "Assigned Login Password", isPassword = true)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("trial", "monthly", "quarterly", "half_yearly", "yearly", "custom").forEach { p ->
                            FilterChip(selected = plan == p, onClick = { plan = p }, label = { Text(p.uppercase()) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormInputField(days, { days = it }, "Days", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                        FormInputField(paid, { paid = it }, "Paid (₹)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (code.isBlank() || name.isBlank() || mobile.isBlank()) return@Button
                        onCreateShopAccount(
                            code.trim(), name.trim(), owner.trim(), uid.trim(), mobile.trim(), email.trim(),
                            pass, plan, days.toLongOrNull() ?: 30L, paid.toDoubleOrNull() ?: 0.0
                        )
                        showCreateShopDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Create Shop") }
            },
            dismissButton = { TextButton(onClick = { showCreateShopDialog = false }) { Text("Cancel") } }
        )
    }

    if (credShop != null) {
        val target = credShop!!
        var code by remember { mutableStateOf(target.shopCode) }
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { credShop = null },
            containerColor = CardWhite,
            title = { Text("Edit Login Credentials", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(code, { code = it }, "Shop User ID / Code")
                    FormInputField(pass, { pass = it }, "New Password (blank keeps existing)", isPassword = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateShopCredentials(target, code, pass)
                        credShop = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { credShop = null }) { Text("Cancel") } }
        )
    }

    if (subShop != null) {
        val target = subShop!!
        var plan by remember { mutableStateOf(target.subscriptionPlan) }
        var addDays by remember { mutableStateOf("30") }
        var amt by remember { mutableStateOf(platformConfig.planMonthlyPrice.toString()) }
        var grace by remember { mutableStateOf(target.graceDays.toString()) }

        AlertDialog(
            onDismissRequest = { subShop = null },
            containerColor = CardWhite,
            title = { Text("Subscription: ${target.shopName}", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("trial" to 14L, "monthly" to 30L, "quarterly" to 90L, "half_yearly" to 180L, "yearly" to 365L, "custom" to 365L).forEach { (p, d) ->
                            FilterChip(
                                selected = plan == p,
                                onClick = { plan = p; addDays = d.toString() },
                                label = { Text(p.uppercase()) }
                            )
                        }
                    }
                    FormInputField(addDays, { addDays = it }, "Extend Days", keyboardType = KeyboardType.Number)
                    FormInputField(amt, { amt = it }, "Payment Received (₹)", keyboardType = KeyboardType.Decimal)
                    FormInputField(grace, { grace = it }, "Grace Period Days", keyboardType = KeyboardType.Number)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateSubscription(target, plan, addDays.toLongOrNull() ?: 30L, amt.toDoubleOrNull() ?: 0.0, grace.toLongOrNull() ?: 7L)
                        subShop = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) { Text("Update Plan") }
            },
            dismissButton = { TextButton(onClick = { subShop = null }) { Text("Cancel") } }
        )
    }

    if (accessReqShop != null) {
        val target = accessReqShop!!
        var reason by remember { mutableStateOf("Subscription & inventory troubleshooting support") }
        AlertDialog(
            onDismissRequest = { accessReqShop = null },
            containerColor = CardWhite,
            title = { Text("Request Owner Authorization", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Zero-trust privacy: ${target.shopName}'s owner must approve before you can view shop summary data.", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                    FormInputField(reason, { reason = it }, "Support Reason *", singleLine = false)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (reason.isBlank()) return@Button
                        onRequestDataAccess(target, reason.trim())
                        accessReqShop = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Send Request") }
            },
            dismissButton = { TextButton(onClick = { accessReqShop = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SuperAdminControlSection(
    platformConfig: PlatformConfig,
    adminSecrets: AdminSecrets,
    adminLogs: List<AdminLog>,
    onSavePlatformConfig: (PlatformConfig) -> Unit,
    onSaveAdminSecrets: (AdminSecrets) -> Unit,
    onTestTelegram: (String, String) -> Unit
) {
    var subTab by remember { mutableStateOf(0) } // 0=App, 1=Telegram, 2=Update, 3=Security, 4=Support
    var cfg by remember(platformConfig) { mutableStateOf(platformConfig) }
    var sec by remember(adminSecrets) { mutableStateOf(adminSecrets) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = subTab, containerColor = SecondaryNavy, contentColor = AmberGold) {
            listOf("App Control", "Telegram", "App Update", "Security", "Support").forEachIndexed { idx, lbl ->
                Tab(selected = subTab == idx, onClick = { subTab = idx }, text = { Text(lbl) })
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (subTab) {
                0 -> {
                    PremiumCard {
                        Text("Global App Control & Plan Pricing", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(cfg.brandingName, { cfg = cfg.copy(brandingName = it) }, "Platform Branding Name")
                        Spacer(Modifier.height(6.dp))
                        FormInputField(cfg.broadcastBanner, { cfg = cfg.copy(broadcastBanner = it) }, "Global Broadcast Banner Message")
                        Spacer(Modifier.height(8.dp))
                        SwitchRow("Emergency Lock (All User Apps)", cfg.emergencyLock) { cfg = cfg.copy(emergencyLock = it) }
                        SwitchRow("New Shop Registration Enabled", cfg.registrationEnabled) { cfg = cfg.copy(registrationEnabled = it) }
                        SwitchRow("Require Admin Approval for New Shops", cfg.adminApprovalRequired) { cfg = cfg.copy(adminApprovalRequired = it) }
                        SwitchRow("Billing Discount Allowed", cfg.billingDiscountAllowed) { cfg = cfg.copy(billingDiscountAllowed = it) }
                        SwitchRow("Bill Deletion Allowed", cfg.billDeletionAllowed) { cfg = cfg.copy(billDeletionAllowed = it) }
                        SwitchRow("Attendance Control Enabled", cfg.attendanceControlEnabled) { cfg = cfg.copy(attendanceControlEnabled = it) }
                        SwitchRow("Staff Advance Allowed", cfg.staffAdvanceAllowed) { cfg = cfg.copy(staffAdvanceAllowed = it) }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormInputField(cfg.lowStockThreshold.toString(), { cfg = cfg.copy(lowStockThreshold = it.toLongOrNull() ?: 10L) }, "Low Stock Limit", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                            FormInputField(cfg.freeTrialDays.toString(), { cfg = cfg.copy(freeTrialDays = it.toLongOrNull() ?: 14L) }, "Free Trial Days", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormInputField(cfg.planMonthlyPrice.toString(), { cfg = cfg.copy(planMonthlyPrice = it.toDoubleOrNull() ?: 999.0) }, "Monthly ₹", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                            FormInputField(cfg.planYearlyPrice.toString(), { cfg = cfg.copy(planYearlyPrice = it.toDoubleOrNull() ?: 7999.0) }, "Yearly ₹", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onSavePlatformConfig(cfg) },
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Sync App Controls Globally") }
                    }
                }

                1 -> {
                    PremiumCard {
                        Text("Telegram Bot & Real-Time Alerts", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(sec.telegramBotToken, { sec = sec.copy(telegramBotToken = it) }, "Telegram Bot Token")
                        Spacer(Modifier.height(6.dp))
                        FormInputField(sec.telegramChatId, { sec = sec.copy(telegramChatId = it) }, "Telegram Chat ID")
                        Spacer(Modifier.height(8.dp))
                        SwitchRow("New Sale Alert", sec.alertNewSale) { sec = sec.copy(alertNewSale = it) }
                        SwitchRow("Payment Received Alert", sec.alertPayment) { sec = sec.copy(alertPayment = it) }
                        SwitchRow("Low Stock Alert", sec.alertLowStock) { sec = sec.copy(alertLowStock = it) }
                        SwitchRow("Attendance Alert", sec.alertAttendance) { sec = sec.copy(alertAttendance = it) }
                        SwitchRow("Salary Finalized Alert", sec.alertSalary) { sec = sec.copy(alertSalary = it) }
                        SwitchRow("New Stock Purchase Alert", sec.alertNewStock) { sec = sec.copy(alertNewStock = it) }
                        SwitchRow("Bill Deletion Alert", sec.alertBillDelete) { sec = sec.copy(alertBillDelete = it) }
                        SwitchRow("Security Alert", sec.alertSecurity) { sec = sec.copy(alertSecurity = it) }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onTestTelegram(sec.telegramBotToken, sec.telegramChatId) }, modifier = Modifier.weight(1f)) {
                                Text("Test Alert")
                            }
                            Button(
                                onClick = { onSaveAdminSecrets(sec) },
                                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                                modifier = Modifier.weight(1f)
                            ) { Text("Save Telegram") }
                        }
                    }
                }

                2 -> {
                    PremiumCard {
                        Text("App Update & Rollout Management", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormInputField(cfg.appVersionCode.toString(), { cfg = cfg.copy(appVersionCode = it.toLongOrNull() ?: 1L) }, "Version Code", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                            FormInputField(cfg.appVersionName, { cfg = cfg.copy(appVersionName = it) }, "Version Name", modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        FormInputField(cfg.apkDownloadUrl, { cfg = cfg.copy(apkDownloadUrl = it) }, "APK Download URL")
                        Spacer(Modifier.height(6.dp))
                        FormInputField(cfg.changelog, { cfg = cfg.copy(changelog = it) }, "Release Changelog", singleLine = false)
                        Spacer(Modifier.height(8.dp))
                        SwitchRow("Mandatory Update", cfg.mandatoryUpdate) { cfg = cfg.copy(mandatoryUpdate = it) }
                        SwitchRow("Beta Channel Release", cfg.betaRelease) { cfg = cfg.copy(betaRelease = it) }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val rolled = cfg.copy(appVersionCode = (cfg.appVersionCode - 1).coerceAtLeast(1L), mandatoryUpdate = false)
                                    cfg = rolled
                                    onSavePlatformConfig(rolled)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Rollback") }
                            Button(
                                onClick = { onSavePlatformConfig(cfg) },
                                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                                modifier = Modifier.weight(1f)
                            ) { Text("Publish Update") }
                        }
                    }
                }

                3 -> {
                    var newPin by remember { mutableStateOf("") }
                    PremiumCard {
                        Text("Master Admin PIN & Security Logs", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(newPin, { newPin = it }, "Set / Change Master Admin PIN", keyboardType = KeyboardType.NumberPassword, isPassword = true)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (newPin.isNotBlank()) {
                                    onSaveAdminSecrets(sec.copy(masterAdminPinHash = SecurityUtils.sha256(newPin)))
                                    newPin = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Update Master PIN") }
                        HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 10.dp))
                        Text("Security Audit Trail (${adminLogs.size})", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                        adminLogs.take(12).forEach { log ->
                            Text("• ${log.action} (${log.details})", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                        }
                    }
                }

                else -> {
                    PremiumCard {
                        Text("Common Admin Support Number (Synced Live to All User Apps)", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(cfg.supportPhone, { cfg = cfg.copy(supportPhone = it) }, "Support Mobile Number *", keyboardType = KeyboardType.Phone)
                        Spacer(Modifier.height(6.dp))
                        FormInputField(cfg.supportWhatsapp, { cfg = cfg.copy(supportWhatsapp = it) }, "Support WhatsApp Number *", keyboardType = KeyboardType.Phone)
                        Spacer(Modifier.height(6.dp))
                        FormInputField(cfg.supportEmail, { cfg = cfg.copy(supportEmail = it) }, "Support Email", keyboardType = KeyboardType.Email)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onSavePlatformConfig(cfg) },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            modifier = Modifier.fillMaxWidth().testTag("save_support_number_button")
                        ) { Text("Broadcast Support Contact to All User Apps") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = SlateDark, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
