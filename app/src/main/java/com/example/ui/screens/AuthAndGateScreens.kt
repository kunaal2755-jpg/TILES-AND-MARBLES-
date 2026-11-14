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
import com.example.data.firebase.NetworkUtils
import com.example.data.firebase.SecurityUtils
import com.example.data.firebase.ValidationUtils
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
    if (!NetworkUtils.isOnline(context)) {
        onAuthError("Internet connection is unavailable. Please connect to Wi-Fi or Mobile Data.\nइंटरनेट उपलब्ध नहीं है। कृपया नेटवर्क जांचें।")
        return
    }
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
            onAuthError(NetworkUtils.mapCloudError(e))
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
    initialShopCode: String = "",
    registrationSuccessMessage: String? = null,
    skipAutoSignIn: Boolean = false,
    onAuthSuccess: (prefillShopCode: String, prefillPassword: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var shopCodeInput by remember(initialShopCode) { mutableStateOf(initialShopCode) }
    var shopPinInput by remember { mutableStateOf("") }

    LaunchedEffect(skipAutoSignIn) {
        if (!skipAutoSignIn) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = { onAuthSuccess(shopCodeInput.trim(), shopPinInput) },
                onUnauthenticated = {},
                scope = scope
            )
        }
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
                    if (registrationSuccessMessage != null) {
                        Surface(
                            color = SuccessGreenSoft,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = registrationSuccessMessage,
                                    color = SuccessGreen,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        text = "Shop Owner & Multi-Device Cloud Login",
                        style = MaterialTheme.typography.titleLarge,
                        color = RoyalNavy
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Sign in with Google and enter your Shop ID / Admin ID & Password to sync inventory, bills, ledgers, staff salary & backups on any phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateMuted
                    )
                    Spacer(Modifier.height(14.dp))
                    FormInputField(
                        value = shopCodeInput,
                        onValueChange = { shopCodeInput = it.uppercase(); errorMessage = null },
                        label = "Shop ID / Admin ID",
                        leadingIcon = Icons.Default.Store
                    )
                    Spacer(Modifier.height(10.dp))
                    FormInputField(
                        value = shopPinInput,
                        onValueChange = { shopPinInput = it; errorMessage = null },
                        label = "Password",
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
                            if (shopCodeInput.isNotBlank() || shopPinInput.isNotBlank()) {
                                val idErr = ValidationUtils.validateShopOrAdminId(shopCodeInput)
                                val passErr = if (shopPinInput.isBlank()) {
                                    "Please enter your Password / कृपया पासवर्ड डालें"
                                } else null
                                val err = idErr ?: passErr
                                if (err != null) {
                                    errorMessage = err
                                    return@Button
                                }
                            }
                            isLoading = true
                            errorMessage = null
                            onGoogleSignInClicked(
                                context = context,
                                credentialManager = credentialManager,
                                onAuthSuccess = {
                                    isLoading = false
                                    onAuthSuccess(shopCodeInput.trim(), shopPinInput)
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
                    Text("• Login Problem: Sign in with Google, then enter the Shop ID & Password created by Super Admin.", style = MaterialTheme.typography.bodyMedium)
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
fun NetworkErrorRetryScreen(
    message: String,
    language: AppLanguage,
    onToggleLanguage: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: (() -> Unit)? = null
) {
    val s = LocalAppStrings.current
    Scaffold(
        topBar = {
            NavyTopHeader(
                title = s.appTitle,
                subtitle = s.subtitle,
                language = language,
                onToggleLanguage = onToggleLanguage,
                actions = {
                    if (onSignOut != null) {
                        IconButton(onClick = onSignOut) {
                            Icon(Icons.Default.Logout, contentDescription = s.logout, tint = Color.White)
                        }
                    }
                }
            )
        },
        containerColor = LightBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            PremiumCard(modifier = Modifier.widthIn(max = 520.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(DangerRedSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = DangerRed, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.networkErrorTitle, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                        Text(s.networkErrorSubtitle, style = MaterialTheme.typography.bodySmall, color = SlateMuted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = DangerRedSoft,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message,
                        color = DangerRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("retry_connection_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AmberGold)
                    Spacer(Modifier.width(8.dp))
                    Text(s.retryButton, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun FirstTimeSuperAdminSetupScreen(
    language: AppLanguage,
    authenticatedEmail: String?,
    onToggleLanguage: () -> Unit,
    onSubmit: (
        adminId: String,
        shopName: String,
        ownerName: String,
        mobile: String,
        passwordPlain: String,
        confirmPasswordPlain: String,
        deviceName: String,
        onResult: (Result<String>) -> Unit
    ) -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val s = LocalAppStrings.current

    var shopName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf(Firebase.auth.currentUser?.displayName ?: "") }
    var mobile by remember { mutableStateOf("") }
    var adminId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            NavyTopHeader(
                title = s.appTitle,
                subtitle = s.createSuperAdminTitle,
                language = language,
                onToggleLanguage = onToggleLanguage,
                actions = {
                    if (!authenticatedEmail.isNullOrBlank()) {
                        IconButton(onClick = onSignOut) {
                            Icon(Icons.Default.Logout, contentDescription = s.logout, tint = Color.White)
                        }
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 580.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = RoyalNavy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        StatusBadge(s.masterAdminBadge, AmberGold, RoyalNavy)
                        Spacer(Modifier.height(8.dp))
                        Text(s.createSuperAdminTitle, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Text(s.createSuperAdminSubtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                    }
                }

                PremiumCard {
                    FormInputField(
                        value = shopName,
                        onValueChange = { shopName = it; errorText = null },
                        label = s.shopNameLabel,
                        leadingIcon = Icons.Default.Store
                    )
                    Spacer(Modifier.height(10.dp))
                    FormInputField(
                        value = ownerName,
                        onValueChange = { ownerName = it; errorText = null },
                        label = s.ownerNameLabel,
                        leadingIcon = Icons.Default.Person
                    )
                    Spacer(Modifier.height(10.dp))
                    FormInputField(
                        value = mobile,
                        onValueChange = { mobile = it; errorText = null },
                        label = s.mobileNumberLabel,
                        keyboardType = KeyboardType.Phone,
                        leadingIcon = Icons.Default.Phone
                    )
                    Spacer(Modifier.height(10.dp))
                    FormInputField(
                        value = adminId,
                        onValueChange = { adminId = it.uppercase(); errorText = null },
                        label = s.adminIdLabel,
                        leadingIcon = Icons.Default.AdminPanelSettings,
                        supportingText = "Letters, numbers, - or _ (4-30 chars, e.g. ADMIN01)"
                    )
                    Spacer(Modifier.height(10.dp))
                    FormInputField(
                        value = password,
                        onValueChange = { password = it; errorText = null },
                        label = s.passwordLabel,
                        isPassword = true,
                        leadingIcon = Icons.Default.Lock
                    )
                    Spacer(Modifier.height(10.dp))
                    FormInputField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorText = null },
                        label = s.confirmPasswordLabel,
                        isPassword = true,
                        leadingIcon = Icons.Default.LockReset
                    )

                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = LightBackground,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, RoyalNavy.copy(alpha = 0.14f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (authenticatedEmail.isNullOrBlank()) Icons.Default.Security else Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = if (authenticatedEmail.isNullOrBlank()) RoyalNavy else SuccessGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (authenticatedEmail.isNullOrBlank()) {
                                    "Firebase Authentication (Google Sign-In) will verify and link your Master Admin account in Cloud Firestore."
                                } else {
                                    "Verified Google Account: $authenticatedEmail"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = RoyalNavy,
                                modifier = Modifier.weight(1f)
                            )
                            if (!authenticatedEmail.isNullOrBlank()) {
                                TextButton(onClick = onSignOut) {
                                    Text("Switch", color = AmberGoldDark)
                                }
                            }
                        }
                    }

                    if (errorText != null) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = DangerRedSoft,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorText!!,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val validationError =
                                ValidationUtils.validateShopName(shopName)
                                    ?: ValidationUtils.validatePersonName(ownerName)
                                    ?: ValidationUtils.validateMobile(mobile)
                                    ?: ValidationUtils.validateShopOrAdminId(adminId)
                                    ?: ValidationUtils.validatePassword(password, confirmPassword)
                            if (validationError != null) {
                                errorText = validationError
                                return@Button
                            }
                            if (!NetworkUtils.isOnline(context)) {
                                errorText = "Firebase / Internet connection is unavailable. Connect to internet and try again.\nFirebase या इंटरनेट उपलब्ध नहीं है। कृपया नेटवर्क जांचें।"
                                return@Button
                            }
                            errorText = null
                            isSubmitting = true
                            val device = "${Build.MANUFACTURER} ${Build.MODEL}"

                            val commitMasterAdmin: () -> Unit = {
                                onSubmit(
                                    adminId,
                                    shopName,
                                    ownerName,
                                    mobile,
                                    password,
                                    confirmPassword,
                                    device
                                ) { res ->
                                    isSubmitting = false
                                    res.onFailure { err ->
                                        errorText = NetworkUtils.mapCloudError(err)
                                    }
                                }
                            }

                            if (Firebase.auth.currentUser != null) {
                                commitMasterAdmin()
                            } else {
                                onGoogleSignInClicked(
                                    context = context,
                                    credentialManager = credentialManager,
                                    onAuthSuccess = { commitMasterAdmin() },
                                    onAuthError = { msg ->
                                        isSubmitting = false
                                        errorText = msg
                                    },
                                    scope = scope,
                                    onAuthCancelled = {
                                        isSubmitting = false
                                        errorText = "Google Sign-In is required to register the Master Admin account in Firebase.\nFirebase में मास्टर एडमिन अकाउंट बनाने के लिए Google सत्यापन ज़रूरी है।"
                                    }
                                )
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("create_master_admin_button")
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = AmberGold,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AmberGold)
                            Spacer(Modifier.width(8.dp))
                            Text(s.createMasterAdminButton, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShopOnboardingAndGateScreen(
    shops: List<Shop>,
    platformConfig: PlatformConfig,
    isSuperAdmin: Boolean,
    language: AppLanguage,
    prefillShopCode: String = "",
    prefillPassword: String = "",
    initialCredentialError: String? = null,
    onToggleLanguage: () -> Unit,
    onSelectShop: (Shop) -> Unit,
    onCreateShop: (String, String, String, String, String, String, String, String, String, (Result<String>) -> Unit) -> Unit,
    onLoginWithShopCredentials: (String, String, String, (Result<String>) -> Unit) -> Unit,
    onVerifyPasswordAndEnter: (Shop, String) -> Boolean,
    onOpenSuperAdmin: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    var showCreateForm by remember { mutableStateOf(false) }
    var customShopCode by remember { mutableStateOf("") }
    var shopName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf(Firebase.auth.currentUser?.displayName ?: "") }
    var mobile by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var gst by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secretCode by remember { mutableStateOf("1234") }
    var isCreatingShop by remember { mutableStateOf(false) }
    var validationErr by remember { mutableStateOf<String?>(null) }

    var credShopCode by remember(prefillShopCode) { mutableStateOf(prefillShopCode) }
    var credPassword by remember(prefillPassword) { mutableStateOf(prefillPassword) }
    var isCredentialLoggingIn by remember { mutableStateOf(false) }
    var credLoginError by remember(initialCredentialError) { mutableStateOf(initialCredentialError) }

    var pendingPasswordShop by remember { mutableStateOf<Shop?>(null) }
    var enteredShopPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            NavyTopHeader(
                title = platformConfig.brandingName.ifBlank { "TileVault" },
                subtitle = if (isSuperAdmin) "Master Admin & Shop Workspace" else "Select or Sync Your Shop",
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
                // Cloud Multi-Device Login with Shop ID & Password
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(RoyalNavy),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = AmberGold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.shopIdLoginTitle, style = MaterialTheme.typography.titleMedium, color = RoyalNavy)
                            Text(
                                "Enter the Shop ID & Password assigned by Master Admin to sync all cloud records on this phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FormInputField(
                        value = credShopCode,
                        onValueChange = { credShopCode = it.uppercase(); credLoginError = null },
                        label = s.shopIdLabel,
                        leadingIcon = Icons.Default.Store
                    )
                    Spacer(Modifier.height(8.dp))
                    FormInputField(
                        value = credPassword,
                        onValueChange = { credPassword = it; credLoginError = null },
                        label = s.passwordLabel,
                        isPassword = true,
                        leadingIcon = Icons.Default.Lock
                    )
                    if (credLoginError != null) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = DangerRedSoft,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = credLoginError!!,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val err = ValidationUtils.validateShopOrAdminId(credShopCode)
                                ?: if (credPassword.isBlank()) "Password is required / पासवर्ड डालना ज़रूरी है" else null
                            if (err != null) {
                                credLoginError = err
                                return@Button
                            }
                            credLoginError = null
                            isCredentialLoggingIn = true
                            val device = "${Build.MANUFACTURER} ${Build.MODEL}"
                            onLoginWithShopCredentials(credShopCode, credPassword, device) { res ->
                                isCredentialLoggingIn = false
                                res.onFailure { e ->
                                    credLoginError = NetworkUtils.mapCloudError(e)
                                }
                            }
                        },
                        enabled = !isCredentialLoggingIn,
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("shop_id_login_button")
                    ) {
                        if (isCredentialLoggingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AmberGold,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AmberGold)
                            Spacer(Modifier.width(8.dp))
                            Text(s.loginAndSyncButton, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (shops.isNotEmpty()) {
                    SectionHeaderRow(
                        title = "Your Synced Shops (${shops.size})",
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
                } else {
                    SectionHeaderRow(
                        title = "Register New Shop",
                        actionText = if (showCreateForm) "Hide" else "+ Register Shop",
                        onAction = { showCreateForm = !showCreateForm }
                    )
                }

                if (showCreateForm) {
                    PremiumCard {
                        Text("Create New Tiles & Marbles Shop", style = MaterialTheme.typography.titleLarge, color = RoyalNavy)
                        Spacer(Modifier.height(10.dp))
                        FormInputField(customShopCode, { customShopCode = it.uppercase(); validationErr = null }, "Custom Shop ID (Optional, e.g. SHP101)", leadingIcon = Icons.Default.Badge)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(shopName, { shopName = it; validationErr = null }, "Shop Name *", leadingIcon = Icons.Default.Store)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(ownerName, { ownerName = it; validationErr = null }, "Owner Full Name *", leadingIcon = Icons.Default.Person)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(mobile, { mobile = it; validationErr = null }, "Mobile Number *", keyboardType = KeyboardType.Phone, leadingIcon = Icons.Default.Phone)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(address, { address = it }, "Shop Address", singleLine = false, leadingIcon = Icons.Default.LocationOn)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(gst, { gst = it }, "GST Number (Optional)", leadingIcon = Icons.Default.Receipt)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(password, { password = it; validationErr = null }, "Shop Login Password (min 6 chars) *", isPassword = true, leadingIcon = Icons.Default.Lock)
                        Spacer(Modifier.height(8.dp))
                        FormInputField(secretCode, { secretCode = it; validationErr = null }, "Attendance Secret Code (e.g. 1234) *", keyboardType = KeyboardType.NumberPassword, isPassword = true, leadingIcon = Icons.Default.Pin)

                        if (validationErr != null) {
                            Spacer(Modifier.height(10.dp))
                            Surface(color = DangerRedSoft, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(validationErr!!, color = DangerRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                val err =
                                    (if (customShopCode.isNotBlank()) ValidationUtils.validateShopOrAdminId(customShopCode) else null)
                                        ?: ValidationUtils.validateShopName(shopName)
                                        ?: ValidationUtils.validatePersonName(ownerName)
                                        ?: ValidationUtils.validateMobile(mobile)
                                        ?: ValidationUtils.validatePassword(password)
                                        ?: ValidationUtils.validateSecretCode(secretCode)
                                if (err != null) {
                                    validationErr = err
                                    return@Button
                                }
                                validationErr = null
                                isCreatingShop = true
                                val device = "${Build.MANUFACTURER} ${Build.MODEL}"
                                onCreateShop(shopName, ownerName, mobile, address, gst, password, secretCode, device, customShopCode) { res ->
                                    isCreatingShop = false
                                    res.onFailure { e ->
                                        validationErr = NetworkUtils.mapCloudError(e)
                                    }
                                }
                            },
                            enabled = !isCreatingShop,
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("create_shop_button")
                        ) {
                            if (isCreatingShop) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AmberGold, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.AddBusiness, contentDescription = null, tint = AmberGold)
                                Spacer(Modifier.width(8.dp))
                                Text("Create Shop & Sync Cloud", fontWeight = FontWeight.Bold)
                            }
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
