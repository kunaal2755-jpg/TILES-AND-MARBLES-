package com.example.ui.screens

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.firebase.AppLanguage
import com.example.data.firebase.SecurityUtils
import com.example.data.model.PlatformConfig
import com.example.data.model.Shop
import com.example.ui.components.*
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.*
import com.example.utils.IntentUtils
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

fun attemptAutoSignIn(
    context: android.content.Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    val clientId = runCatching { context.getString(R.string.default_web_client_id) }.getOrNull()
    if (clientId.isNullOrBlank()) {
        onUnauthenticated()
        return
    }
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    scope.launch {
        try {
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (e: Exception) {
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: android.content.Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
        try {
            val activity = context as? Activity
                ?: throw IllegalStateException("Activity context required for Google Sign-In")
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onAuthError("Unexpected credential type")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Sign in failed")
        }
    }
}

fun signOut(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("Auth", "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}

@Composable
fun AuthScreen(
    supportPhone: String = "+919876543210",
    supportWhatsapp: String = "+919876543210",
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var shopCodeInput by remember { mutableStateOf("") }
    var shopPinInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {},
            scope = scope
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LightBackground)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Luxury Showroom Hero Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_login_hero),
                    contentDescription = "Tiles and Marbles Showroom",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(RoyalNavy.copy(alpha = 0.65f), RoyalNavy.copy(alpha = 0.92f))
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    StatusBadge("CLOUD MULTI-TENANT", AmberGold, RoyalNavy)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "TileVault",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White
                    )
                    Text(
                        text = "Tiles & Marbles Complete Shop Management",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberGold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PremiumCard {
                    Text(
                        text = "Shop Owner & Multi-Device Cloud Login",
                        style = MaterialTheme.typography.titleLarge,
                        color = RoyalNavy
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Sign in securely with Google to sync inventory, bills, ledgers, staff salary & backups across all your devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateMuted
                    )
                    Spacer(Modifier.height(14.dp))
                    FormInputField(
                        value = shopCodeInput,
                        onValueChange = { shopCodeInput = it },
                        label = "Shop ID / Mobile Number (Optional quick-link)",
                        leadingIcon = Icons.Default.Store
                    )
                    Spacer(Modifier.height(10.dp))
                    FormInputField(
                        value = shopPinInput,
                        onValueChange = { shopPinInput = it },
                        label = "Shop Password / Access PIN",
                        isPassword = true,
                        leadingIcon = Icons.Default.Lock
                    )
                    Spacer(Modifier.height(16.dp))

                    if (errorMessage != null) {
                        Surface(
                            color = DangerRedSoft,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage!!,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            onGoogleSignInClicked(
                                context = context,
                                credentialManager = credentialManager,
                                onAuthSuccess = {
                                    isLoading = false
                                    onAuthSuccess()
                                },
                                onAuthError = { msg ->
                                    isLoading = false
                                    errorMessage = msg
                                },
                                scope = scope,
                                onAuthCancelled = { isLoading = false }
                            )
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("google_sign_in_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = AmberGold,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AmberGold)
                            Spacer(Modifier.width(10.dp))
                            Text("Sign in with Google", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { showHelpDialog = true }) {
                            Text("Forgot Password?", color = RoyalNavy)
                        }
                        TextButton(onClick = { showHelpDialog = true }) {
                            Text("Help & Contact Admin", color = AmberGoldDark)
                        }
                    }
                }

                // Admin Support Card
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SupportAgent, contentDescription = null, tint = RoyalNavy)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Admin Support Desk", style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                            Text("Login, Account Block & Subscription Help", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { IntentUtils.callPhone(context, supportPhone) },
                            border = BorderStroke(1.dp, RoyalNavy),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("call_admin_button")
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, tint = RoyalNavy, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Call Admin", color = RoyalNavy)
                        }
                        Button(
                            onClick = {
                                IntentUtils.openWhatsApp(
                                    context,
                                    supportWhatsapp,
                                    "Hello TileVault Admin, I need login/account assistance."
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("whatsapp_admin_button")
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("WhatsApp")
                        }
                    }
                }
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            containerColor = CardWhite,
            title = { Text("Account & Admin Assistance", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Login Problem: Ensure you sign in with the Google account registered with your shop.", style = MaterialTheme.typography.bodyMedium)
                    Text("• Password Reset: Contact Admin Support for instant PIN/credential verification.", style = MaterialTheme.typography.bodyMedium)
                    Text("• Account Block / Subscription: Use Call or WhatsApp below to connect with Super Admin ($supportPhone).", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(
                    onClick = { IntentUtils.openWhatsApp(context, supportWhatsapp, "TileVault Support Request") },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) { Text("WhatsApp Admin") }
            },
            dismissButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
fun ShopOnboardingAndGateScreen(
    shops: List<Shop>,
    platformConfig: PlatformConfig,
    isSuperAdmin: Boolean,
    language: AppLanguage,
    onToggleLanguage: () -> Unit,
    onSelectShop: (Shop) -> Unit,
    onCreateShop: (String, String, String, String, String, String, String, String) -> Unit,
    onVerifyPasswordAndEnter: (Shop, String) -> Boolean,
    onOpenSuperAdmin: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var showCreateForm by remember { mutableStateOf(shops.isEmpty()) }
    var shopName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf(Firebase.auth.currentUser?.displayName ?: "") }
    var mobile by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var gst by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secretCode by remember { mutableStateOf("1234") }
    var validationErr by remember { mutableStateOf<String?>(null) }

    var pendingPasswordShop by remember { mutableStateOf<Shop?>(null) }
    var enteredShopPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            NavyTopHeader(
                title = platformConfig.brandingName.ifBlank { "TileVault" },
                subtitle = if (isSuperAdmin) "Super Admin & Shop Workspace" else "Select or Register Your Shop",
                language = language,
                onToggleLanguage = onToggleLanguage,
                actions = {
                    if (isSuperAdmin) {
                        Button(
                            onClick = onOpenSuperAdmin,
                            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = RoyalNavy),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("open_super_admin_button")
                        ) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Admin", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Default.Logout, contentDescription = "Sign Out", tint = Color.White)
                    }
                }
            )
        },
        containerColor = LightBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (platformConfig.emergencyLock && !isSuperAdmin) {
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = DangerRed, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Platform Maintenance / Emergency Lock", style = MaterialTheme.typography.titleMedium, color = DangerRed)
                            Text("Super Admin has temporarily locked access. Please contact support.", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { IntentUtils.callPhone(context, platformConfig.supportPhone) },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                    ) { Text("Call Admin (${platformConfig.supportPhone})") }
                }
                return@Column
            }

            if (platformConfig.broadcastBanner.isNotBlank()) {
                Surface(
                    color = AmberGoldSoft,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AmberGold),
                    modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Campaign, contentDescription = null, tint = AmberGoldDark)
                        Spacer(Modifier.width(8.dp))
                        Text(platformConfig.broadcastBanner, style = MaterialTheme.typography.bodyMedium, color = RoyalNavy)
                    }
                }
            }

            Column(modifier = Modifier.widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (shops.isNotEmpty()) {
                    SectionHeaderRow(
                        title = "Your Registered Shops (${shops.size})",
                        actionText = if (showCreateForm) "Hide Form" else "+ New Shop",
                        onAction = { showCreateForm = !showCreateForm }
                    )
                    shops.forEach { shop ->
                        val isBlocked = shop.status == "blocked" || shop.status == "suspended" || shop.status == "archived" || shop.status == "pending"
                        PremiumCard(
                            onClick = {
                                if (!isBlocked) {
                                    if (shop.passwordHash.isNotBlank()) {
                                        pendingPasswordShop = shop
                                        enteredShopPassword = ""
                                        passwordError = null
                                    } else {
                                        onSelectShop(shop)
                                    }
                                }
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(RoyalNavy),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Storefront, contentDescription = null, tint = AmberGold)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(shop.shopName, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                                    Text("ID: ${shop.shopCode}  •  ${shop.mobile}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                    Text("Plan: ${shop.subscriptionPlan.uppercase()}  •  Devices: ${shop.activeDevices.size}", style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                                }
                                StatusBadge(
                                    text = shop.status.uppercase(),
                                    bgColor = if (shop.status == "active") SuccessGreenSoft else DangerRedSoft,
                                    textColor = if (shop.status == "active") SuccessGreen else DangerRed
                                )
                            }
                            if (isBlocked) {
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Shop access is ${shop.status}. Contact Super Admin.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DangerRed,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(onClick = { IntentUtils.callPhone(context, platformConfig.supportPhone) }) {
                                        Text("Call Admin")
                                    }
                                }
                            }
                        }
                    }
                }

                if (showCreateForm) {
                    PremiumCard {
                        Text("Create New Tiles & Marbles Shop", style = MaterialTheme.typography.titleLarge, color = RoyalNavy)
                        Spacer(Modifier.height(10.dp))
                        FormInputField(shopName, { shopName = it }, "Shop Name *", leadingIcon = Icons.Default.Store)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(ownerName, { ownerName = it }, "Owner Full Name *", leadingIcon = Icons.Default.Person)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(mobile, { mobile = it }, "Mobile Number *", keyboardType = KeyboardType.Phone, leadingIcon = Icons.Default.Phone)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(address, { address = it }, "Shop Address", singleLine = false, leadingIcon = Icons.Default.LocationOn)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(gst, { gst = it }, "GST Number (Optional)", leadingIcon = Icons.Default.Receipt)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(password, { password = it }, "Shop Login Password (Optional)", isPassword = true, leadingIcon = Icons.Default.Lock)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(secretCode, { secretCode = it }, "Attendance Secret Code (e.g. 1234) *", keyboardType = KeyboardType.NumberPassword, isPassword = true, leadingIcon = Icons.Default.Pin)

                        if (validationErr != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(validationErr!!, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                when {
                                    shopName.isBlank() -> validationErr = "Shop Name is required"
                                    ownerName.isBlank() -> validationErr = "Owner Name is required"
                                    mobile.trim().length < 6 -> validationErr = "Valid mobile number required"
                                    secretCode.isBlank() -> validationErr = "Secret code for attendance lock is required"
                                    else -> {
                                        validationErr = null
                                        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
                                        onCreateShop(shopName, ownerName, mobile, address, gst, password, secretCode, device)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("create_shop_button")
                        ) {
                            Icon(Icons.Default.AddBusiness, contentDescription = null, tint = AmberGold)
                            Spacer(Modifier.width(8.dp))
                            Text("Create Shop & Sync Cloud", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (pendingPasswordShop != null) {
        val target = pendingPasswordShop!!
        AlertDialog(
            onDismissRequest = { pendingPasswordShop = null },
            containerColor = CardWhite,
            title = { Text("Enter Shop Password", color = RoyalNavy) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${target.shopName} (${target.shopCode})", style = MaterialTheme.typography.bodyMedium, color = SlateMuted)
                    FormInputField(
                        value = enteredShopPassword,
                        onValueChange = { enteredShopPassword = it; passwordError = null },
                        label = "Shop Password",
                        isPassword = true
                    )
                    if (passwordError != null) {
                        Text(passwordError!!, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (onVerifyPasswordAndEnter(target, enteredShopPassword)) {
                            pendingPasswordShop = null
                        } else {
                            passwordError = "Incorrect shop password"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy)
                ) { Text("Unlock Shop") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        IntentUtils.openWhatsApp(context, platformConfig.supportWhatsapp, "Forgot password for shop ${target.shopCode}")
                    }) { Text("Forgot?") }
                    TextButton(onClick = { pendingPasswordShop = null }) { Text("Cancel") }
                }
            }
        )
    }
}
