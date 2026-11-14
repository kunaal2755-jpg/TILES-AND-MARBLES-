package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.firebase.AppLanguage
import com.example.data.firebase.Formatters
import com.example.data.firebase.SecurityUtils
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.utils.IntentUtils

@Composable
fun SettingsScreen(
    shop: Shop,
    platformConfig: PlatformConfig,
    tileSizes: List<TileSizeConfig>,
    locations: List<StockLocation>,
    activityLogs: List<ActivityLog>,
    backups: List<BackupRecord>,
    dataAccessRequests: List<DataAccessRequest>,
    language: AppLanguage,
    onToggleLanguage: () -> Unit,
    onUpdateShop: (Shop, String) -> Unit,
    onSaveTileSize: (TileSizeConfig) -> Unit,
    onSaveLocation: (StockLocation) -> Unit,
    onRunManualBackup: () -> Unit,
    onRespondDataAccess: (DataAccessRequest, Boolean) -> Unit,
    onExportJsonSummary: () -> String,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current

    // Shop Profile editable fields
    var shopName by remember(shop.id, shop.shopName) { mutableStateOf(shop.shopName) }
    var ownerName by remember(shop.id, shop.ownerName) { mutableStateOf(shop.ownerName) }
    var mobile by remember(shop.id, shop.mobile) { mutableStateOf(shop.mobile) }
    var address by remember(shop.id, shop.address) { mutableStateOf(shop.address) }
    var gst by remember(shop.id, shop.gstNumber) { mutableStateOf(shop.gstNumber) }
    var primaryHex by remember(shop.id, shop.primaryColorHex) { mutableStateOf(shop.primaryColorHex) }

    // Dialog states
    var showSizeDialog by remember { mutableStateOf(false) }
    var editingSize by remember { mutableStateOf<TileSizeConfig?>(null) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showSecretCodeDialog by remember { mutableStateOf(false) }
    var notifyLowStock by remember { mutableStateOf(true) }
    var notifyDues by remember { mutableStateOf(true) }
    var notifyOrders by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(LightBackground),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Shop Profile & Branding
        item {
            PremiumCard {
                Text("1. Shop Profile & Custom Branding", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                Spacer(Modifier.height(8.dp))
                FormInputField(shopName, { shopName = it }, "Shop Name")
                Spacer(Modifier.height(6.dp))
                FormInputField(ownerName, { ownerName = it }, "Owner Name")
                Spacer(Modifier.height(6.dp))
                FormInputField(mobile, { mobile = it }, "Shop Mobile", keyboardType = KeyboardType.Phone)
                Spacer(Modifier.height(6.dp))
                FormInputField(address, { address = it }, "Address", singleLine = false)
                Spacer(Modifier.height(6.dp))
                FormInputField(gst, { gst = it }, "GST Number")
                Spacer(Modifier.height(6.dp))
                FormInputField(primaryHex, { primaryHex = it }, "Primary Theme Color Hex (e.g. #0B192C)")
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        onUpdateShop(
                            shop.copy(
                                shopName = shopName.trim(),
                                ownerName = ownerName.trim(),
                                mobile = mobile.trim(),
                                address = address.trim(),
                                gstNumber = gst.trim(),
                                primaryColorHex = primaryHex.trim().ifBlank { "#0B192C" }
                            ),
                            "Shop profile & branding saved!"
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                    modifier = Modifier.fillMaxWidth().testTag("save_shop_profile_button")
                ) { Text("Save Profile & Theme") }
            }
        }

        // 2 & 14. Security, Devices & Secret Code
        item {
            PremiumCard {
                Text("2. Security, Devices & Attendance Secret Code", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                Spacer(Modifier.height(6.dp))
                Text("Shop Code: ${shop.shopCode} • Plan: ${shop.subscriptionPlan.uppercase()} (${shop.subscriptionStatus})", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                Text("Logged-in Devices: ${shop.activeDevices.joinToString(", ").ifBlank { "This device" }}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPasswordDialog = true }, modifier = Modifier.weight(1f)) {
                        Text("Shop Password")
                    }
                    OutlinedButton(onClick = { showSecretCodeDialog = true }, modifier = Modifier.weight(1f)) {
                        Text("Secret Code")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSignOut,
                    border = BorderStroke(1.dp, DangerRed),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign Out Current Device", color = DangerRed) }
            }
        }

        // 3 & 7. Cloud Backup & Data Export
        item {
            PremiumCard {
                Text("3 & 7. Cloud Backup Vault & JSON Export", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                Spacer(Modifier.height(4.dp))
                Text("Verified Cloud Backups: ${backups.size}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                backups.firstOrNull()?.let { latest ->
                    Text("Latest: ${latest.backupType} • ${latest.recordCount} records (${latest.status})", style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRunManualBackup,
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                        modifier = Modifier.weight(1f)
                    ) { Text("Backup Now") }
                    OutlinedButton(
                        onClick = {
                            val json = onExportJsonSummary()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "${shop.shopName} Export JSON")
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Export Shop Data"))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Export JSON") }
                }
            }
        }

        // 6, 8, 12, 13. PDF, Language, Price & Auto-Attendance Controls
        item {
            PremiumCard {
                Text("6, 8, 12 & 13. PDF, Language & Attendance Settings", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Language (English / हिंदी)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = language == AppLanguage.HI, onCheckedChange = { onToggleLanguage() })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Show Prices on PDF by Default", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = shop.showPricesOnPdfDefault,
                        onCheckedChange = { onUpdateShop(shop.copy(showPricesOnPdfDefault = it), "PDF Price default updated") }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-Attendance (${shop.autoAttendanceTime})", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = shop.autoAttendanceEnabled,
                        onCheckedChange = { onUpdateShop(shop.copy(autoAttendanceEnabled = it), "Auto-Attendance updated") }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("Default PDF Paper Size", style = MaterialTheme.typography.labelMedium, color = RoyalNavy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("A4", "Letter", "Legal").forEach { paper ->
                        FilterChip(
                            selected = shop.defaultPaperSize.equals(paper, true),
                            onClick = { onUpdateShop(shop.copy(defaultPaperSize = paper), "Default paper size: $paper") },
                            label = { Text(paper) }
                        )
                    }
                }
            }
        }

        // 10. Configurable Tile Size & PDF Image Dimensions
        item {
            PremiumCard {
                SectionHeaderRow(
                    title = "10. Tile Size & PDF Dimensions",
                    actionText = "+ Add Size",
                    onAction = { editingSize = null; showSizeDialog = true }
                )
                tileSizes.forEach { ts ->
                    HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${ts.name} (${ts.piecesPerBox} pcs/box)", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                            Text("PDF Image: ${ts.pdfImageWidth}×${ts.pdfImageHeight} (${ts.displayRatio}) • ${ts.widthInches}×${ts.heightInches} in", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                        }
                        Switch(
                            checked = ts.enabled,
                            onCheckedChange = { onSaveTileSize(ts.copy(enabled = it)) }
                        )
                        IconButton(onClick = { editingSize = ts; showSizeDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Size", tint = RoyalNavy)
                        }
                    }
                }
            }
        }

        // 11. Stock Locations
        item {
            PremiumCard {
                SectionHeaderRow(
                    title = "11. Stock Locations (${locations.size})",
                    actionText = "+ Add Location",
                    onAction = { showLocationDialog = true }
                )
                locations.forEach { loc ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warehouse, contentDescription = null, tint = RoyalNavy, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(loc.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (loc.address.isNotBlank()) Text(loc.address, style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                        }
                        if (loc.isPrimary) StatusBadge("PRIMARY", AmberGoldSoft, AmberGoldDark)
                    }
                }
            }
        }

        // 9. Common Admin Support & Authorized Data Access Requests
        item {
            PremiumCard {
                Text("9. Help, Admin Support & Data Access Control", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                Spacer(Modifier.height(4.dp))
                Text("Common Support Phone: ${platformConfig.supportPhone}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                Text("Support Email: ${platformConfig.supportEmail}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { IntentUtils.callPhone(context, platformConfig.supportPhone) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Call Admin")
                    }
                    Button(
                        onClick = { IntentUtils.openWhatsApp(context, platformConfig.supportWhatsapp, "Support request for ${shop.shopName} (${shop.shopCode})") },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("WhatsApp")
                    }
                }
                if (dataAccessRequests.isNotEmpty()) {
                    HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 10.dp))
                    Text("Super Admin Data Access Permissions", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                    dataAccessRequests.forEach { req ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${req.permissionStatus} • ${req.reason}", style = MaterialTheme.typography.bodySmall, color = RoyalNavy)
                                Text("Admin: ${req.adminIdentity}", style = MaterialTheme.typography.labelSmall, color = SlateMuted)
                            }
                            if (req.permissionStatus == "PENDING") {
                                TextButton(onClick = { onRespondDataAccess(req, true) }) { Text("Approve", color = SuccessGreen) }
                            }
                            if (req.permissionStatus != "REVOKED") {
                                TextButton(onClick = { onRespondDataAccess(req, false) }) { Text("Revoke", color = DangerRed) }
                            }
                        }
                    }
                }
            }
        }

        // 4, 5 & 15. Staff Permissions, Notification Preferences & Activity Log
        item {
            PremiumCard {
                Text("4, 5 & 15. Permissions, Notifications & Activity Log", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Low Stock Threshold Alerts (${platformConfig.lowStockThreshold} boxes)", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = notifyLowStock, onCheckedChange = { notifyLowStock = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Pending Order Reminders", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = notifyOrders, onCheckedChange = { notifyOrders = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Customer Outstanding Alerts", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = notifyDues, onCheckedChange = { notifyDues = it })
                }
                HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 8.dp))
                Text("Recent Activity Log (${activityLogs.size})", style = MaterialTheme.typography.titleSmall, color = RoyalNavy)
                activityLogs.take(10).forEach { log ->
                    Text("• [${log.module}] ${log.action}: ${log.details}", style = MaterialTheme.typography.bodySmall, color = SlateDark)
                }
            }
        }
    }

    if (showSizeDialog) {
        var name by remember { mutableStateOf(editingSize?.name ?: "") }
        var pcs by remember { mutableStateOf((editingSize?.piecesPerBox ?: 4L).toString()) }
        var wIn by remember { mutableStateOf((editingSize?.widthInches ?: 24.0).toString()) }
        var hIn by remember { mutableStateOf((editingSize?.heightInches ?: 24.0).toString()) }
        var pdfW by remember { mutableStateOf((editingSize?.pdfImageWidth ?: 120L).toString()) }
        var pdfH by remember { mutableStateOf((editingSize?.pdfImageHeight ?: 120L).toString()) }
        var ratio by remember { mutableStateOf(editingSize?.displayRatio ?: "1:1") }

        AlertDialog(
            onDismissRequest = { showSizeDialog = false },
            containerColor = CardWhite,
            title = { Text(if (editingSize == null) "Add Tile Size" else "Edit Tile Size", color = RoyalNavy) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(name, { name = it }, "Size Name (e.g. 24x24, 12x18) *")
                    FormInputField(pcs, { pcs = it }, "Pieces Per Box *", keyboardType = KeyboardType.Number)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormInputField(wIn, { wIn = it }, "Width (in)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                        FormInputField(hIn, { hIn = it }, "Height (in)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormInputField(pdfW, { pdfW = it }, "PDF Img W", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                        FormInputField(pdfH, { pdfH = it }, "PDF Img H", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    }
                    FormInputField(ratio, { ratio = it }, "Display Aspect Ratio (e.g. 1:1, 1:2)")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isBlank()) return@Button
                        onSaveTileSize(
                            (editingSize ?: TileSizeConfig()).copy(
                                name = name.trim(),
                                piecesPerBox = pcs.toLongOrNull() ?: 4L,
                                widthInches = wIn.toDoubleOrNull() ?: 24.0,
                                heightInches = hIn.toDoubleOrNull() ?: 24.0,
                                pdfImageWidth = pdfW.toLongOrNull() ?: 120L,
                                pdfImageHeight = pdfH.toLongOrNull() ?: 120L,
                                displayRatio = ratio.trim().ifBlank { "1:1" }
                            )
                        )
                        showSizeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save Size") }
            },
            dismissButton = { TextButton(onClick = { showSizeDialog = false }) { Text("Cancel") } }
        )
    }

    if (showLocationDialog) {
        var name by remember { mutableStateOf("") }
        var addr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            containerColor = CardWhite,
            title = { Text("Add Stock Location", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormInputField(name, { name = it }, "Location Name (e.g. Warehouse 3) *")
                    FormInputField(addr, { addr = it }, "Address / Area")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isBlank()) return@Button
                        onSaveLocation(StockLocation(name = name.trim(), address = addr.trim()))
                        showLocationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Add Location") }
            },
            dismissButton = { TextButton(onClick = { showLocationDialog = false }) { Text("Cancel") } }
        )
    }

    if (showPasswordDialog) {
        var newPass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            containerColor = CardWhite,
            title = { Text("Update Shop Login Password", color = RoyalNavy) },
            text = { FormInputField(newPass, { newPass = it }, "New Password (leave empty to remove lock)", isPassword = true) },
            confirmButton = {
                Button(
                    onClick = {
                        val hash = if (newPass.isBlank()) "" else SecurityUtils.sha256(newPass)
                        onUpdateShop(shop.copy(passwordHash = hash), "Shop password updated")
                        showPasswordDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { showPasswordDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSecretCodeDialog) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSecretCodeDialog = false },
            containerColor = CardWhite,
            title = { Text("Update Attendance Secret Code", color = RoyalNavy) },
            text = { FormInputField(code, { code = it }, "New Secret Code *", keyboardType = KeyboardType.NumberPassword, isPassword = true) },
            confirmButton = {
                Button(
                    onClick = {
                        if (code.isBlank()) return@Button
                        onUpdateShop(shop.copy(secretCodeHash = SecurityUtils.sha256(code)), "Attendance Secret Code updated")
                        showSecretCodeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Save Code") }
            },
            dismissButton = { TextButton(onClick = { showSecretCodeDialog = false }) { Text("Cancel") } }
        )
    }
}
