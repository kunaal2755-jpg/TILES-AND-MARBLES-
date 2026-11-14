package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.firebase.*
import com.example.data.model.PlatformConfig
import com.example.data.model.SaleOrder
import com.example.data.model.Shop
import com.example.ui.components.NavyTopHeader
import com.example.ui.components.PremiumCard
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.stringsFor
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.ShopViewModel
import com.example.ui.viewmodel.SuperAdminViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TileVaultAppRoot()
        }
    }
}

private fun authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val auth = Firebase.auth
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    auth.addAuthStateListener(listener)
    awaitClose { auth.removeAuthStateListener(listener) }
}

private enum class AppMode { GATE, USER_APP, SUPER_ADMIN }

@Composable
fun TileVaultAppRoot() {
    val context = LocalContext.current
    val currentUser by authStateFlow().collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)
    val user = currentUser

    if (user == null) {
        val publicSupportConfig = remember { mutableStateOf(PlatformConfig()) }
        DisposableEffect(Unit) {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance(
                context.getString(R.string.firestore_database_id)
            )
            val reg = db.collection("platform_config").document("global")
                .addSnapshotListener { snap, _ ->
                    if (snap != null && snap.exists()) {
                        snap.toObject(PlatformConfig::class.java)?.let {
                            publicSupportConfig.value = it
                        }
                    }
                }
            onDispose { reg.remove() }
        }
        TileVaultTheme {
            AuthScreen(
                supportPhone = publicSupportConfig.value.supportPhone,
                supportWhatsapp = publicSupportConfig.value.supportWhatsapp,
                onAuthSuccess = {}
            )
        }
        return
    }

    val uid = user.uid
    val shopViewModel: ShopViewModel = viewModel(
        key = "shop_vm_$uid",
        factory = viewModelFactory {
            initializer {
                val repo = ShopRepository(context.applicationContext)
                val prefs = PreferencesRepository(context.applicationContext)
                ShopViewModel(repo, prefs, uid)
            }
        }
    )

    val isSuperAdmin = remember(uid, user.email) {
        user.email.equals(SuperAdminRepository.BOOTSTRAP_ADMIN_EMAIL, ignoreCase = true)
    }
    val adminViewModel: SuperAdminViewModel? = if (isSuperAdmin) {
        viewModel(
            key = "admin_vm_$uid",
            factory = viewModelFactory {
                initializer {
                    val repo = SuperAdminRepository(context.applicationContext)
                    SuperAdminViewModel(repo, uid)
                }
            }
        )
    } else null

    val ownedShopsState by shopViewModel.ownedShops.collectAsStateWithLifecycle()
    val activeShop by shopViewModel.activeShop.collectAsStateWithLifecycle()
    val platformConfig by shopViewModel.platformConfig.collectAsStateWithLifecycle()
    val language by shopViewModel.language.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(shopViewModel) {
        shopViewModel.actionMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(adminViewModel) {
        adminViewModel?.message?.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    TileVaultTheme(customPrimaryHex = activeShop?.primaryColorHex) {
        CompositionLocalProvider(LocalAppStrings provides stringsFor(language)) {
            AuthenticatedWorkspace(
                shopViewModel = shopViewModel,
                adminViewModel = adminViewModel,
                ownedShopsState = ownedShopsState,
                activeShop = activeShop,
                platformConfig = platformConfig,
                language = language,
                isSuperAdmin = isSuperAdmin,
                snackbarHostState = snackbarHostState
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedWorkspace(
    shopViewModel: ShopViewModel,
    adminViewModel: SuperAdminViewModel?,
    ownedShopsState: UiState<List<Shop>>,
    activeShop: Shop?,
    platformConfig: PlatformConfig,
    language: AppLanguage,
    isSuperAdmin: Boolean,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val s = LocalAppStrings.current

    var appMode by remember { mutableStateOf(AppMode.GATE) }
    var unlockedShopIds by remember { mutableStateOf(setOf<String>()) }
    var autoEnteredOnce by remember { mutableStateOf(false) }

    val shops = (ownedShopsState as? UiState.Success)?.data ?: emptyList()

    LaunchedEffect(ownedShopsState, activeShop?.id) {
        if (!autoEnteredOnce && ownedShopsState is UiState.Success && activeShop != null) {
            if (activeShop.status == "active" && activeShop.passwordHash.isBlank()) {
                appMode = AppMode.USER_APP
                autoEnteredOnce = true
            }
        }
    }

    val doSignOut: () -> Unit = {
        signOut(credentialManager, scope)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (appMode) {
            AppMode.SUPER_ADMIN -> {
                if (adminViewModel != null) {
                    BackHandler { appMode = if (activeShop != null) AppMode.USER_APP else AppMode.GATE }
                    val allShops by adminViewModel.shops.collectAsStateWithLifecycle()
                    val adminCfg by adminViewModel.platformConfig.collectAsStateWithLifecycle()
                    val secrets by adminViewModel.adminSecrets.collectAsStateWithLifecycle()
                    val requests by adminViewModel.dataAccessRequests.collectAsStateWithLifecycle()
                    val archives by adminViewModel.shopArchives.collectAsStateWithLifecycle()
                    val backups by adminViewModel.backups.collectAsStateWithLifecycle()
                    val logs by adminViewModel.adminLogs.collectAsStateWithLifecycle()

                    SuperAdminConsoleScreen(
                        shops = allShops,
                        platformConfig = adminCfg,
                        adminSecrets = secrets,
                        dataAccessRequests = requests,
                        shopArchives = archives,
                        backups = backups,
                        adminLogs = logs,
                        onCreateShopAccount = adminViewModel::createShopAccount,
                        onUpdateShopStatus = adminViewModel::updateShopStatus,
                        onUpdateShopCredentials = adminViewModel::updateShopCredentials,
                        onUpdateSubscription = adminViewModel::updateSubscription,
                        onArchiveShop = adminViewModel::archiveShop,
                        onRestoreShop = adminViewModel::restoreShop,
                        onSavePlatformConfig = adminViewModel::savePlatformConfig,
                        onSaveAdminSecrets = adminViewModel::saveAdminSecrets,
                        onTestTelegram = adminViewModel::sendTestTelegramAlert,
                        onRequestDataAccess = adminViewModel::requestDataAccess,
                        onRevokeDataAccess = adminViewModel::revokeDataAccess,
                        onSwitchToUserApp = {
                            appMode = if (activeShop != null) AppMode.USER_APP else AppMode.GATE
                        },
                        onSignOut = doSignOut
                    )
                } else {
                    appMode = AppMode.GATE
                }
            }

            AppMode.GATE -> {
                if (ownedShopsState is UiState.Loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RoyalNavy)
                    }
                } else {
                    ShopOnboardingAndGateScreen(
                        shops = shops,
                        platformConfig = platformConfig,
                        isSuperAdmin = isSuperAdmin,
                        language = language,
                        onToggleLanguage = shopViewModel::toggleLanguage,
                        onSelectShop = { chosen ->
                            shopViewModel.selectShop(chosen.id)
                            appMode = AppMode.USER_APP
                        },
                        onCreateShop = { name, owner, mobile, addr, gst, pass, sec, dev ->
                            shopViewModel.createShop(name, owner, mobile, addr, gst, pass, sec, dev)
                            appMode = AppMode.USER_APP
                        },
                        onVerifyPasswordAndEnter = { target, pass ->
                            if (target.passwordHash.isBlank() || SecurityUtils.sha256(pass) == target.passwordHash) {
                                unlockedShopIds = unlockedShopIds + target.id
                                shopViewModel.selectShop(target.id)
                                appMode = AppMode.USER_APP
                                true
                            } else {
                                false
                            }
                        },
                        onOpenSuperAdmin = { appMode = AppMode.SUPER_ADMIN },
                        onSignOut = doSignOut
                    )
                }
            }

            AppMode.USER_APP -> {
                val shop = activeShop
                if (shop == null) {
                    appMode = AppMode.GATE
                } else {
                    UserShopWorkspace(
                        shop = shop,
                        platformConfig = platformConfig,
                        language = language,
                        isSuperAdmin = isSuperAdmin,
                        shopViewModel = shopViewModel,
                        onSwitchShop = { appMode = AppMode.GATE },
                        onOpenSuperAdmin = { appMode = AppMode.SUPER_ADMIN },
                        onSignOut = doSignOut
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 76.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserShopWorkspace(
    shop: Shop,
    platformConfig: PlatformConfig,
    language: AppLanguage,
    isSuperAdmin: Boolean,
    shopViewModel: ShopViewModel,
    onSwitchShop: () -> Unit,
    onOpenSuperAdmin: () -> Unit,
    onSignOut: () -> Unit
) {
    val s = LocalAppStrings.current
    var currentRoute by remember { mutableStateOf("dashboard") }
    var showMoreSheet by remember { mutableStateOf(false) }

    var addTileTrigger by remember { mutableStateOf(0) }
    var newSaleTrigger by remember { mutableStateOf(0) }
    var newPurchaseTrigger by remember { mutableStateOf(0) }
    var newExpenseTrigger by remember { mutableStateOf(0) }
    var preselectedDeliverySale by remember { mutableStateOf<SaleOrder?>(null) }

    val tileSizes by shopViewModel.tileSizes.collectAsStateWithLifecycle()
    val locations by shopViewModel.locations.collectAsStateWithLifecycle()
    val tiles by shopViewModel.tiles.collectAsStateWithLifecycle()
    val stockMovements by shopViewModel.stockMovements.collectAsStateWithLifecycle()
    val suppliers by shopViewModel.suppliers.collectAsStateWithLifecycle()
    val purchases by shopViewModel.purchases.collectAsStateWithLifecycle()
    val customers by shopViewModel.customers.collectAsStateWithLifecycle()
    val sales by shopViewModel.sales.collectAsStateWithLifecycle()
    val deliveries by shopViewModel.deliveries.collectAsStateWithLifecycle()
    val returns by shopViewModel.returns.collectAsStateWithLifecycle()
    val staff by shopViewModel.staff.collectAsStateWithLifecycle()
    val attendance by shopViewModel.attendance.collectAsStateWithLifecycle()
    val advances by shopViewModel.advances.collectAsStateWithLifecycle()
    val salaryMonths by shopViewModel.salaryMonths.collectAsStateWithLifecycle()
    val expenses by shopViewModel.expenses.collectAsStateWithLifecycle()
    val activityLogs by shopViewModel.activityLogs.collectAsStateWithLifecycle()
    val dataAccessRequests by shopViewModel.dataAccessRequests.collectAsStateWithLifecycle()
    val backups by shopViewModel.backups.collectAsStateWithLifecycle()

    val primaryRoutes = remember(s) {
        listOf(
            NavDest("dashboard", s.navDashboard, Icons.Default.Dashboard),
            NavDest("tiles", s.navTiles, Icons.Default.GridView),
            NavDest("sales", s.navSales, Icons.Default.PointOfSale),
            NavDest("staff", s.navStaff, Icons.Default.Badge),
            NavDest("customers", s.navCustomers, Icons.Default.People)
        )
    }

    val isSecondaryRoute = currentRoute !in primaryRoutes.map { it.route }
    if (isSecondaryRoute) {
        BackHandler { currentRoute = "dashboard" }
    }

    if (platformConfig.emergencyLock && !isSuperAdmin) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PremiumCard(modifier = Modifier.padding(24.dp)) {
                Text("Maintenance / Emergency Lock Active", style = MaterialTheme.typography.titleLarge, color = DangerRed)
                Spacer(Modifier.height(6.dp))
                Text("Please contact Super Admin (${platformConfig.supportPhone}) for assistance.", color = SlateMuted)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSignOut) { Text(s.logout) }
            }
        }
        return
    }

    val screenTitle = when (currentRoute) {
        "dashboard" -> shop.shopName
        "tiles" -> s.navTiles
        "sales" -> s.navSales
        "staff" -> s.navStaff
        "customers" -> s.navCustomers
        "purchase" -> s.navPurchase
        "delivery" -> s.navDelivery
        "expenses" -> s.navExpenses
        "reports" -> s.navReports
        "settings" -> s.navSettings
        else -> shop.shopName
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavRail) {
                NavigationRail(
                    containerColor = RoyalNavy,
                    contentColor = Color.White
                ) {
                    Spacer(Modifier.height(12.dp))
                    primaryRoutes.forEach { dest ->
                        NavigationRailItem(
                            selected = currentRoute == dest.route,
                            onClick = { currentRoute = dest.route },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = RoyalNavy,
                                selectedTextColor = AmberGold,
                                indicatorColor = AmberGold,
                                unselectedIconColor = Color.White.copy(alpha = 0.75f),
                                unselectedTextColor = Color.White.copy(alpha = 0.75f)
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = isSecondaryRoute,
                        onClick = { showMoreSheet = true },
                        icon = { Icon(Icons.Default.Menu, contentDescription = "More") },
                        label = { Text("More") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = RoyalNavy,
                            selectedTextColor = AmberGold,
                            indicatorColor = AmberGold,
                            unselectedIconColor = Color.White.copy(alpha = 0.75f),
                            unselectedTextColor = Color.White.copy(alpha = 0.75f)
                        )
                    )
                }
            }

            Scaffold(
                topBar = {
                    NavyTopHeader(
                        title = screenTitle,
                        subtitle = "${shop.shopCode} • ${shop.subscriptionPlan.uppercase()}",
                        onBack = if (isSecondaryRoute) { { currentRoute = "dashboard" } } else null,
                        language = language,
                        onToggleLanguage = shopViewModel::toggleLanguage,
                        actions = {
                            if (isSuperAdmin) {
                                IconButton(
                                    onClick = onOpenSuperAdmin,
                                    modifier = Modifier.testTag("header_super_admin_btn")
                                ) {
                                    Icon(Icons.Default.AdminPanelSettings, contentDescription = s.superAdminConsole, tint = AmberGold)
                                }
                            }
                            IconButton(
                                onClick = { showMoreSheet = true },
                                modifier = Modifier.testTag("more_menu_button")
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Modules", tint = Color.White)
                            }
                        }
                    )
                },
                bottomBar = {
                    if (!useNavRail) {
                        NavigationBar(containerColor = RoyalNavy, contentColor = Color.White) {
                            primaryRoutes.forEach { dest ->
                                NavigationBarItem(
                                    selected = currentRoute == dest.route,
                                    onClick = { currentRoute = dest.route },
                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                    label = { Text(dest.label, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = RoyalNavy,
                                        selectedTextColor = AmberGold,
                                        indicatorColor = AmberGold,
                                        unselectedIconColor = Color.White.copy(alpha = 0.7f),
                                        unselectedTextColor = Color.White.copy(alpha = 0.7f)
                                    ),
                                    modifier = Modifier.testTag("nav_${dest.route}")
                                )
                            }
                        }
                    }
                },
                containerColor = LightBackground
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (currentRoute) {
                        "dashboard" -> DashboardScreen(
                            shop = shop,
                            platformConfig = platformConfig,
                            tiles = tiles,
                            customers = customers,
                            suppliers = suppliers,
                            sales = sales,
                            dataAccessRequests = dataAccessRequests,
                            onQuickAddTile = {
                                currentRoute = "tiles"
                                addTileTrigger++
                            },
                            onQuickNewSale = {
                                currentRoute = "sales"
                                newSaleTrigger++
                            },
                            onQuickPurchase = {
                                currentRoute = "purchase"
                                newPurchaseTrigger++
                            },
                            onQuickExpense = {
                                currentRoute = "expenses"
                                newExpenseTrigger++
                            },
                            onOpenDelivery = {
                                preselectedDeliverySale = null
                                currentRoute = "delivery"
                            },
                            onOpenReports = { currentRoute = "reports" },
                            onOpenSettings = { currentRoute = "settings" },
                            onRespondDataAccess = shopViewModel::respondDataAccess
                        )

                        "tiles" -> TilesInventoryScreen(
                            shop = shop,
                            tiles = tiles,
                            tileSizes = tileSizes,
                            locations = locations,
                            movements = stockMovements,
                            lowStockThreshold = platformConfig.lowStockThreshold,
                            openAddTrigger = addTileTrigger,
                            onSaveTile = shopViewModel::saveTile,
                            onDeleteTile = shopViewModel::deleteTileSoft,
                            onAdjustStock = shopViewModel::adjustStock
                        )

                        "sales" -> SalesAndBillingScreen(
                            shop = shop,
                            platformConfig = platformConfig,
                            sales = sales,
                            tiles = tiles,
                            customers = customers,
                            openNewTrigger = newSaleTrigger,
                            onSaveSale = shopViewModel::saveSale,
                            onOpenDeliveryForSale = { saleOrder ->
                                preselectedDeliverySale = saleOrder
                                currentRoute = "delivery"
                            }
                        )

                        "staff" -> StaffAndSalaryScreen(
                            shop = shop,
                            staff = staff,
                            attendance = attendance,
                            advances = advances,
                            salaryMonths = salaryMonths,
                            activityLogs = activityLogs,
                            onSaveStaff = shopViewModel::saveStaff,
                            onMarkAttendance = shopViewModel::markAttendance,
                            onClearAttendanceForDate = shopViewModel::clearAttendanceForDate,
                            onUpdateShop = shopViewModel::updateShopProfile,
                            onSaveAdvance = shopViewModel::saveAdvance,
                            onFinalizeSalaryMonth = shopViewModel::finalizeSalaryMonth
                        )

                        "customers" -> CustomersScreen(
                            shop = shop,
                            customers = customers,
                            sales = sales,
                            deliveries = deliveries,
                            returns = returns,
                            onSaveCustomer = shopViewModel::saveCustomer,
                            onRecordPayment = shopViewModel::recordCustomerPayment
                        )

                        "purchase" -> PurchaseAndSuppliersScreen(
                            shop = shop,
                            suppliers = suppliers,
                            purchases = purchases,
                            tiles = tiles,
                            openPurchaseTrigger = newPurchaseTrigger,
                            onSaveSupplier = shopViewModel::saveSupplier,
                            onRecordPurchase = shopViewModel::recordPurchase
                        )

                        "delivery" -> DeliveryAndReturnScreen(
                            shop = shop,
                            sales = sales,
                            tiles = tiles,
                            customers = customers,
                            deliveries = deliveries,
                            returns = returns,
                            preselectedSale = preselectedDeliverySale,
                            onRecordPartialDelivery = shopViewModel::recordPartialDelivery,
                            onRecordReturnExchange = shopViewModel::recordReturnOrExchange
                        )

                        "expenses" -> ExpensesScreen(
                            shop = shop,
                            expenses = expenses,
                            openNewTrigger = newExpenseTrigger,
                            onSaveExpense = shopViewModel::saveExpense
                        )

                        "reports" -> ReportsScreen(
                            shop = shop,
                            platformConfig = platformConfig,
                            tiles = tiles,
                            sales = sales,
                            purchases = purchases,
                            customers = customers,
                            suppliers = suppliers,
                            expenses = expenses,
                            returns = returns,
                            staff = staff,
                            attendance = attendance,
                            salaryMonths = salaryMonths
                        )

                        "settings" -> SettingsScreen(
                            shop = shop,
                            platformConfig = platformConfig,
                            tileSizes = tileSizes,
                            locations = locations,
                            activityLogs = activityLogs,
                            backups = backups,
                            dataAccessRequests = dataAccessRequests,
                            language = language,
                            onToggleLanguage = shopViewModel::toggleLanguage,
                            onUpdateShop = shopViewModel::updateShopProfile,
                            onSaveTileSize = shopViewModel::saveTileSize,
                            onSaveLocation = shopViewModel::saveLocation,
                            onRunManualBackup = shopViewModel::runManualBackup,
                            onRespondDataAccess = shopViewModel::respondDataAccess,
                            onExportJsonSummary = {
                                JSONObject().apply {
                                    put("shopName", shop.shopName)
                                    put("shopCode", shop.shopCode)
                                    put("tilesCount", tiles.size)
                                    put("customersCount", customers.size)
                                    put("salesCount", sales.size)
                                    put("suppliersCount", suppliers.size)
                                    put("staffCount", staff.size)
                                    put("expensesCount", expenses.size)
                                }.toString(2)
                            },
                            onSignOut = onSignOut
                        )
                    }
                }
            }
        }
    }

    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            containerColor = CardWhite
        ) {
            MoreModulesSheetContent(
                isSuperAdmin = isSuperAdmin,
                onNavigate = { route ->
                    if (route == "delivery") preselectedDeliverySale = null
                    currentRoute = route
                    showMoreSheet = false
                },
                onOpenSuperAdmin = {
                    showMoreSheet = false
                    onOpenSuperAdmin()
                },
                onSwitchShop = {
                    showMoreSheet = false
                    onSwitchShop()
                },
                onSignOut = {
                    showMoreSheet = false
                    onSignOut()
                }
            )
        }
    }
}

private data class NavDest(val route: String, val label: String, val icon: ImageVector)
