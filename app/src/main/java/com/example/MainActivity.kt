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
import com.example.ui.viewmodel.BootstrapUiState
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
import kotlinx.coroutines.launch
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

private sealed interface PreAuthBootstrapState {
    data object Checking : PreAuthBootstrapState
    data object NeedsFirstTimeMasterSetup : PreAuthBootstrapState
    data class ReadyToLogin(
        val supportPhone: String = "+919876543210",
        val supportWhatsapp: String = "+919876543210"
    ) : PreAuthBootstrapState
    data class NetworkError(val message: String) : PreAuthBootstrapState
}

@Composable
fun TileVaultAppRoot() {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val currentUser by authStateFlow().collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)
    val user = currentUser

    val prefs = remember { PreferencesRepository(appCtx) }
    val preAuthLanguage by prefs.languageFlow.collectAsStateWithLifecycle(initialValue = AppLanguage.EN)

    var preAuthState by remember { mutableStateOf<PreAuthBootstrapState>(PreAuthBootstrapState.Checking) }
    var prefillShopCode by remember { mutableStateOf("") }
    var prefillPassword by remember { mutableStateOf("") }
    var justRegisteredAdminCode by remember { mutableStateOf("") }
    var registrationSuccessBanner by remember { mutableStateOf<String?>(null) }
    var skipAutoSignInOnce by remember { mutableStateOf(false) }

    val refreshPreAuthBootstrap: () -> Unit = {
        scope.launch {
            preAuthState = PreAuthBootstrapState.Checking
            if (!NetworkUtils.isOnline(appCtx)) {
                preAuthState = PreAuthBootstrapState.NetworkError(
                    "Firebase / Internet connection is unavailable. Please check your Wi-Fi or Mobile Data and retry.\nFirebase या इंटरनेट उपलब्ध नहीं है। कृपया नेटवर्क जांचें और पुनः प्रयास करें।"
                )
                return@launch
            }
            val res = SuperAdminRepository(appCtx).fetchPreAuthBootstrapStatus()
            preAuthState = res.fold(
                onSuccess = { status ->
                    if (!status.masterAdminInitialized) {
                        PreAuthBootstrapState.NeedsFirstTimeMasterSetup
                    } else {
                        PreAuthBootstrapState.ReadyToLogin(
                            supportPhone = status.supportPhone,
                            supportWhatsapp = status.supportWhatsapp
                        )
                    }
                },
                onFailure = { err ->
                    PreAuthBootstrapState.NetworkError(NetworkUtils.mapCloudError(err))
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        refreshPreAuthBootstrap()
    }

    when (val preBoot = preAuthState) {
        PreAuthBootstrapState.Checking -> {
            TileVaultTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = RoyalNavy)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (preAuthLanguage == AppLanguage.HI) {
                                "Firebase से कनेक्ट हो रहा है..."
                            } else {
                                "Connecting to TileVault Firebase..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateMuted
                        )
                    }
                }
            }
            return
        }

        is PreAuthBootstrapState.NetworkError -> {
            TileVaultTheme {
                CompositionLocalProvider(LocalAppStrings provides stringsFor(preAuthLanguage)) {
                    NetworkErrorRetryScreen(
                        message = preBoot.message,
                        language = preAuthLanguage,
                        onToggleLanguage = {
                            scope.launch {
                                val next = if (preAuthLanguage == AppLanguage.EN) AppLanguage.HI else AppLanguage.EN
                                prefs.setLanguage(next)
                            }
                        },
                        onRetry = refreshPreAuthBootstrap,
                        onSignOut = if (user != null) { { signOut(credentialManager, scope) } } else null
                    )
                }
            }
            return
        }

        PreAuthBootstrapState.NeedsFirstTimeMasterSetup -> {
            TileVaultTheme {
                CompositionLocalProvider(LocalAppStrings provides stringsFor(preAuthLanguage)) {
                    FirstTimeSuperAdminSetupScreen(
                        language = preAuthLanguage,
                        authenticatedEmail = user?.email,
                        onToggleLanguage = {
                            scope.launch {
                                val next = if (preAuthLanguage == AppLanguage.EN) AppLanguage.HI else AppLanguage.EN
                                prefs.setLanguage(next)
                            }
                        },
                        onSubmit = { adminId, shopName, ownerName, mobile, pass, confirmPass, device, onResult ->
                            scope.launch {
                                val adminRepo = SuperAdminRepository(appCtx)
                                val res = adminRepo.completeFirstTimeMasterAdminSetup(
                                    adminIdRaw = adminId,
                                    shopName = shopName,
                                    ownerName = ownerName,
                                    mobile = mobile,
                                    passwordPlain = pass,
                                    confirmPasswordPlain = confirmPass,
                                    secretCodePlain = "1234",
                                    deviceName = device
                                )
                                if (res.isSuccess) {
                                    val normalizedId = ValidationUtils.normalizeCode(adminId)
                                    prefillShopCode = ""
                                    prefillPassword = ""
                                    justRegisteredAdminCode = normalizedId
                                    registrationSuccessBanner = if (preAuthLanguage == AppLanguage.HI) {
                                        "मास्टर एडमिन अकाउंट '$normalizedId' Firebase में बन गया है। अब नीचे लॉगिन करें।"
                                    } else {
                                        "Master Admin Account '$normalizedId' registered in Firebase! Sign in below to continue."
                                    }
                                    skipAutoSignInOnce = true
                                    signOut(credentialManager, scope) {
                                        preAuthState = PreAuthBootstrapState.ReadyToLogin(
                                            supportPhone = mobile.trim(),
                                            supportWhatsapp = mobile.trim()
                                        )
                                    }
                                }
                                onResult(res)
                            }
                        },
                        onSignOut = { signOut(credentialManager, scope) }
                    )
                }
            }
            return
        }

        is PreAuthBootstrapState.ReadyToLogin -> {
            if (user == null) {
                TileVaultTheme {
                    CompositionLocalProvider(LocalAppStrings provides stringsFor(preAuthLanguage)) {
                        AuthScreen(
                            supportPhone = preBoot.supportPhone,
                            supportWhatsapp = preBoot.supportWhatsapp,
                            initialShopCode = justRegisteredAdminCode,
                            registrationSuccessMessage = registrationSuccessBanner,
                            skipAutoSignIn = skipAutoSignInOnce,
                            onAuthSuccess = { code, pass ->
                                prefillShopCode = code
                                prefillPassword = pass
                                registrationSuccessBanner = null
                                skipAutoSignInOnce = false
                            }
                        )
                    }
                }
                return
            }
        }
    }

    val uid = user!!.uid
    val shopViewModel: ShopViewModel = viewModel(
        key = "shop_vm_$uid",
        factory = viewModelFactory {
            initializer {
                val repo = ShopRepository(appCtx)
                val prefsRepo = PreferencesRepository(appCtx)
                val adminRepo = SuperAdminRepository(appCtx)
                ShopViewModel(repo, prefsRepo, adminRepo, appCtx, uid)
            }
        }
    )

    val bootstrapState by shopViewModel.bootstrapState.collectAsStateWithLifecycle()
    val isSuperAdmin = (bootstrapState as? BootstrapUiState.Ready)?.isAdmin == true
    val adminViewModel: SuperAdminViewModel? = if (isSuperAdmin) {
        viewModel(
            key = "admin_vm_$uid",
            factory = viewModelFactory {
                initializer {
                    val repo = SuperAdminRepository(appCtx)
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
            Box(modifier = Modifier.fillMaxSize()) {
                when (val boot = bootstrapState) {
                    BootstrapUiState.Checking -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = RoyalNavy)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Syncing TileVault Cloud...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SlateMuted
                            )
                        }
                    }

                    is BootstrapUiState.NetworkError -> {
                        NetworkErrorRetryScreen(
                            message = boot.message,
                            language = language,
                            onToggleLanguage = shopViewModel::toggleLanguage,
                            onRetry = shopViewModel::checkBootstrapStatus,
                            onSignOut = { signOut(credentialManager, scope) }
                        )
                    }

                    BootstrapUiState.NeedsFirstTimeMasterSetup -> {
                        LaunchedEffect(Unit) {
                            preAuthState = PreAuthBootstrapState.NeedsFirstTimeMasterSetup
                        }
                    }

                    is BootstrapUiState.Ready -> {
                        AuthenticatedWorkspace(
                            shopViewModel = shopViewModel,
                            adminViewModel = adminViewModel,
                            ownedShopsState = ownedShopsState,
                            activeShop = activeShop,
                            platformConfig = platformConfig,
                            language = language,
                            isSuperAdmin = boot.isAdmin,
                            masterAdminId = boot.masterAdminId,
                            prefillShopCode = prefillShopCode,
                            prefillPassword = prefillPassword,
                            onClearPrefill = { clearCode ->
                                if (clearCode) prefillShopCode = ""
                                prefillPassword = ""
                            },
                            snackbarHostState = snackbarHostState
                        )
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
    masterAdminId: String,
    prefillShopCode: String,
    prefillPassword: String,
    onClearPrefill: (clearCode: Boolean) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    var appMode by remember { mutableStateOf(AppMode.GATE) }
    var unlockedShopIds by remember { mutableStateOf(setOf<String>()) }
    var autoEnteredOnce by remember { mutableStateOf(false) }
    var autoVerifiedKey by remember { mutableStateOf("") }
    var initialCredentialError by remember { mutableStateOf<String?>(null) }

    val shops = (ownedShopsState as? UiState.Success)?.data ?: emptyList()

    LaunchedEffect(prefillShopCode, prefillPassword, isSuperAdmin, masterAdminId) {
        val normalized = ValidationUtils.normalizeCode(prefillShopCode)
        val key = "$normalized::$prefillPassword"
        if (normalized.isNotBlank() && prefillPassword.isNotBlank() && autoVerifiedKey != key) {
            autoVerifiedKey = key
            initialCredentialError = null
            val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            shopViewModel.loginWithShopCredentials(normalized, prefillPassword, device) { res ->
                res.onSuccess {
                    onClearPrefill(true)
                    val resolvedMasterId = ValidationUtils.normalizeCode(
                        masterAdminId.ifBlank { platformConfig.masterAdminId }
                    )
                    appMode = if (isSuperAdmin && normalized == resolvedMasterId) {
                        AppMode.SUPER_ADMIN
                    } else {
                        AppMode.USER_APP
                    }
                }.onFailure { err ->
                    initialCredentialError = NetworkUtils.mapCloudError(err)
                    onClearPrefill(false)
                }
            }
        }
    }

    LaunchedEffect(ownedShopsState, activeShop?.id, prefillShopCode) {
        if (!autoEnteredOnce && prefillShopCode.isBlank() && appMode == AppMode.GATE && ownedShopsState is UiState.Success && activeShop != null) {
            if (activeShop.status == "active" && activeShop.passwordHash.isBlank()) {
                appMode = AppMode.USER_APP
                autoEnteredOnce = true
            }
        }
    }

    val doSignOut: () -> Unit = {
        onClearPrefill(true)
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
                when (ownedShopsState) {
                    UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = RoyalNavy)
                        }
                    }

                    is UiState.Error -> {
                        NetworkErrorRetryScreen(
                            message = ownedShopsState.message,
                            language = language,
                            onToggleLanguage = shopViewModel::toggleLanguage,
                            onRetry = shopViewModel::checkBootstrapStatus,
                            onSignOut = doSignOut
                        )
                    }

                    is UiState.Success -> {
                        ShopOnboardingAndGateScreen(
                            shops = shops,
                            platformConfig = platformConfig,
                            isSuperAdmin = isSuperAdmin,
                            language = language,
                            prefillShopCode = prefillShopCode,
                            prefillPassword = prefillPassword,
                            initialCredentialError = initialCredentialError,
                            onToggleLanguage = shopViewModel::toggleLanguage,
                            onSelectShop = { chosen ->
                                shopViewModel.selectShop(chosen.id)
                                appMode = AppMode.USER_APP
                            },
                            onCreateShop = { name, owner, mobile, addr, gst, pass, sec, dev, customCode, onResult ->
                                shopViewModel.createShop(name, owner, mobile, addr, gst, pass, sec, dev, customCode) { res ->
                                    if (res.isSuccess) {
                                        appMode = AppMode.USER_APP
                                    }
                                    onResult(res)
                                }
                            },
                            onLoginWithShopCredentials = { code, pass, dev, onResult ->
                                initialCredentialError = null
                                shopViewModel.loginWithShopCredentials(code, pass, dev) { res ->
                                    if (res.isSuccess) {
                                        onClearPrefill(true)
                                        val normalized = ValidationUtils.normalizeCode(code)
                                        val resolvedMasterId = ValidationUtils.normalizeCode(
                                            masterAdminId.ifBlank { platformConfig.masterAdminId }
                                        )
                                        appMode = if (isSuperAdmin && normalized == resolvedMasterId) {
                                            AppMode.SUPER_ADMIN
                                        } else {
                                            AppMode.USER_APP
                                        }
                                    }
                                    onResult(res)
                                }
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
