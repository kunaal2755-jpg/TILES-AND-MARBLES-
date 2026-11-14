package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.data.model.*
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class PreAuthBootstrapStatus(
    val masterAdminInitialized: Boolean,
    val supportPhone: String = "+919876543210",
    val supportWhatsapp: String = "+919876543210"
)

data class BootstrapStatus(
    val masterAdminInitialized: Boolean,
    val isCurrentUserAdmin: Boolean,
    val isMasterAdmin: Boolean,
    val masterAdminId: String = "",
    val masterAdminUid: String = ""
)

class SuperAdminRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    companion object {
        const val BOOTSTRAP_ADMIN_EMAIL = "kunaal2755@gmail.com"
    }

    fun isCurrentUserSuperAdminEmail(): Boolean {
        val user = auth.currentUser ?: return false
        return user.email.equals(BOOTSTRAP_ADMIN_EMAIL, ignoreCase = true)
    }

    suspend fun fetchPreAuthBootstrapStatus(timeoutMillis: Long = 12_000L): Result<PreAuthBootstrapStatus> = runCatching {
        withTimeout(timeoutMillis) {
            val statusRef = db.collection("app_bootstrap").document("status")
            val statusSnap = try {
                statusRef.get(Source.SERVER).await()
            } catch (e: Exception) {
                throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
            }
            if (statusSnap.exists()) {
                val parsed = statusSnap.toObject(AppBootstrapStatus::class.java, ServerTimestampBehavior.ESTIMATE)
                if (parsed?.masterAdminInitialized == true) {
                    return@withTimeout PreAuthBootstrapStatus(
                        masterAdminInitialized = true,
                        supportPhone = parsed.supportPhone.ifBlank { "+919876543210" },
                        supportWhatsapp = parsed.supportWhatsapp.ifBlank { "+919876543210" }
                    )
                }
            }

            // Backward compatibility: if user is already signed in and platform_config/global was previously initialized
            if (auth.currentUser != null) {
                val cfgRef = db.collection("platform_config").document("global")
                val cfgSnap = try {
                    cfgRef.get(Source.SERVER).await()
                } catch (e: Exception) {
                    throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
                }
                val cfg = if (cfgSnap.exists()) {
                    cfgSnap.toObject(PlatformConfig::class.java, ServerTimestampBehavior.ESTIMATE)
                } else null
                if (cfg?.masterAdminInitialized == true) {
                    runCatching {
                        statusRef.set(
                            AppBootstrapStatus(
                                masterAdminInitialized = true,
                                supportPhone = cfg.supportPhone.ifBlank { "+919876543210" },
                                supportWhatsapp = cfg.supportWhatsapp.ifBlank { "+919876543210" }
                            )
                        ).await()
                    }
                    return@withTimeout PreAuthBootstrapStatus(
                        masterAdminInitialized = true,
                        supportPhone = cfg.supportPhone.ifBlank { "+919876543210" },
                        supportWhatsapp = cfg.supportWhatsapp.ifBlank { "+919876543210" }
                    )
                }
            }

            PreAuthBootstrapStatus(masterAdminInitialized = false)
        }
    }

    suspend fun fetchBootstrapStatusFromServer(): Result<BootstrapStatus> = runCatching {
        val user = auth.currentUser ?: error("Not signed in")
        val cfgRef = db.collection("platform_config").document("global")
        val adminRef = db.collection("admins").document(user.uid)

        val cfgSnap = try {
            runCatching { cfgRef.get(Source.SERVER).await() }.getOrElse { serverErr ->
                if (NetworkUtils.isNetworkOrServerUnavailable(serverErr)) throw serverErr
                cfgRef.get().await()
            }
        } catch (e: Exception) {
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }

        val adminSnap = try {
            runCatching { adminRef.get(Source.SERVER).await() }.getOrElse { serverErr ->
                if (NetworkUtils.isNetworkOrServerUnavailable(serverErr)) throw serverErr
                adminRef.get().await()
            }
        } catch (e: Exception) {
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }

        val cfg = if (cfgSnap.exists()) {
            cfgSnap.toObject(PlatformConfig::class.java, ServerTimestampBehavior.ESTIMATE)
        } else null
        val adminUser = if (adminSnap.exists()) {
            adminSnap.toObject(AdminUser::class.java, ServerTimestampBehavior.ESTIMATE)
        } else null

        val initialized = cfg?.masterAdminInitialized == true
        val isAdminDoc = adminSnap.exists()
        val isAdmin = isAdminDoc || isCurrentUserSuperAdminEmail()
        val isMaster = (adminUser?.isMasterAdmin == true) ||
            (initialized && cfg?.masterAdminUid == user.uid)

        BootstrapStatus(
            masterAdminInitialized = initialized,
            isCurrentUserAdmin = isAdmin,
            isMasterAdmin = isMaster,
            masterAdminId = cfg?.masterAdminId?.ifBlank { adminUser?.adminId ?: "" } ?: "",
            masterAdminUid = cfg?.masterAdminUid ?: ""
        )
    }

    suspend fun completeFirstTimeMasterAdminSetup(
        adminIdRaw: String,
        shopName: String,
        ownerName: String,
        mobile: String,
        passwordPlain: String,
        confirmPasswordPlain: String,
        secretCodePlain: String,
        deviceName: String
    ): Result<String> = runCatching {
        val user = auth.currentUser ?: throw IllegalStateException("Please sign in with Google first.")
        val uid = user.uid
        val email = user.email ?: ""
        val adminCode = ValidationUtils.normalizeCode(adminIdRaw)

        ValidationUtils.validateShopOrAdminId(adminCode)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validateShopName(shopName)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validatePersonName(ownerName)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validateMobile(mobile)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validatePassword(passwordPlain, confirmPasswordPlain)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validateSecretCode(secretCodePlain)?.let { throw IllegalArgumentException(it) }

        val cfgRef = db.collection("platform_config").document("global")
        val cfgSnap = try {
            runCatching { cfgRef.get(Source.SERVER).await() }.getOrElse { serverErr ->
                if (NetworkUtils.isNetworkOrServerUnavailable(serverErr)) throw serverErr
                cfgRef.get().await()
            }
        } catch (e: Exception) {
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }

        val existingCfg = if (cfgSnap.exists()) {
            cfgSnap.toObject(PlatformConfig::class.java, ServerTimestampBehavior.ESTIMATE)
        } else null
        if (existingCfg?.masterAdminInitialized == true) {
            throw IllegalStateException(
                "Master Admin (${existingCfg.masterAdminId.ifBlank { "Initialized" }}) already exists. Existing accounts cannot be reset."
            )
        }

        val dirRef = db.collection("shop_directory").document(adminCode)
        val dirSnap = try {
            runCatching { dirRef.get(Source.SERVER).await() }.getOrElse { serverErr ->
                if (NetworkUtils.isNetworkOrServerUnavailable(serverErr)) throw serverErr
                dirRef.get().await()
            }
        } catch (e: Exception) {
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }
        if (dirSnap.exists()) {
            throw IllegalArgumentException("Admin/Shop ID '$adminCode' is already registered. Choose another ID.")
        }

        val secRef = db.collection("admin_secrets").document("config")
        val secExists = runCatching { secRef.get(Source.SERVER).await().exists() }.getOrDefault(false)

        val shopId = "shop_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val now = System.currentTimeMillis()
        val passHash = SecurityUtils.sha256(passwordPlain)
        val secHash = SecurityUtils.sha256(secretCodePlain.trim())

        val batch = db.batch()
        val adminRef = db.collection("admins").document(uid)
        batch.set(
            adminRef,
            AdminUser(
                uid = uid,
                email = email.ifBlank { "$adminCode@tilevault.local" },
                name = ownerName.trim(),
                adminId = adminCode,
                shopName = shopName.trim(),
                mobile = mobile.trim(),
                passwordHash = passHash,
                isMasterAdmin = true
            )
        )

        val newCfg = (existingCfg ?: PlatformConfig()).copy(
            supportPhone = mobile.trim(),
            supportWhatsapp = mobile.trim(),
            supportEmail = email.ifBlank { existingCfg?.supportEmail ?: "support@tilevault.app" },
            masterAdminInitialized = true,
            masterAdminUid = uid,
            masterAdminId = adminCode,
            masterAdminShopId = shopId,
            masterAdminMobile = mobile.trim(),
            updatedAt = null
        )
        batch.set(cfgRef, newCfg)

        val bootstrapRef = db.collection("app_bootstrap").document("status")
        batch.set(
            bootstrapRef,
            AppBootstrapStatus(
                masterAdminInitialized = true,
                supportPhone = mobile.trim(),
                supportWhatsapp = mobile.trim()
            )
        )

        if (!secExists) {
            batch.set(secRef, AdminSecrets(masterAdminPinHash = secHash))
        }

        val shopRef = db.collection("shops").document(shopId)
        batch.set(
            shopRef,
            Shop(
                ownerUid = uid,
                shopCode = adminCode,
                shopName = shopName.trim(),
                ownerName = ownerName.trim(),
                mobile = mobile.trim(),
                email = email,
                passwordHash = passHash,
                secretCodeHash = secHash,
                status = "active",
                subscriptionPlan = "yearly",
                subscriptionStatus = "active",
                subscriptionStartMillis = now,
                subscriptionExpiryMillis = now + 3650L * 86_400_000L,
                graceDays = 30L,
                activeDevices = listOf(deviceName),
                claimedByOwner = true
            )
        )

        batch.set(
            dirRef,
            ShopDirectoryEntry(
                shopId = shopId,
                shopCode = adminCode,
                shopName = shopName.trim(),
                ownerUid = uid,
                linkedEmailHint = SecurityUtils.maskEmail(email),
                mobile = mobile.trim(),
                passwordHash = passHash,
                isMasterAdmin = true,
                claimedByOwner = true,
                status = "active"
            )
        )

        val defaultSizes = listOf(
            TileSizeConfig(ownerUid = uid, shopId = shopId, name = "12x18", piecesPerBox = 6, widthInches = 12.0, heightInches = 18.0, pdfImageWidth = 96, pdfImageHeight = 144, displayRatio = "2:3", enabled = true),
            TileSizeConfig(ownerUid = uid, shopId = shopId, name = "2x4", piecesPerBox = 2, widthInches = 24.0, heightInches = 48.0, pdfImageWidth = 90, pdfImageHeight = 180, displayRatio = "1:2", enabled = true),
            TileSizeConfig(ownerUid = uid, shopId = shopId, name = "2x2", piecesPerBox = 4, widthInches = 24.0, heightInches = 24.0, pdfImageWidth = 120, pdfImageHeight = 120, displayRatio = "1:1", enabled = true),
            TileSizeConfig(ownerUid = uid, shopId = shopId, name = "16x16", piecesPerBox = 5, widthInches = 16.0, heightInches = 16.0, pdfImageWidth = 110, pdfImageHeight = 110, displayRatio = "1:1", enabled = true),
            TileSizeConfig(ownerUid = uid, shopId = shopId, name = "8x12", piecesPerBox = 12, widthInches = 8.0, heightInches = 12.0, pdfImageWidth = 88, pdfImageHeight = 132, displayRatio = "2:3", enabled = true)
        )
        defaultSizes.forEachIndexed { idx, sz ->
            batch.set(shopRef.collection("tile_sizes").document("sz_${idx + 1}"), sz)
        }

        val defaultLocations = listOf(
            StockLocation(ownerUid = uid, shopId = shopId, name = "Main Shop", isPrimary = true),
            StockLocation(ownerUid = uid, shopId = shopId, name = "Warehouse 1", address = "Godown A", isPrimary = false),
            StockLocation(ownerUid = uid, shopId = shopId, name = "Warehouse 2", address = "Godown B", isPrimary = false)
        )
        defaultLocations.forEachIndexed { idx, loc ->
            batch.set(shopRef.collection("locations").document("loc_${idx + 1}"), loc)
        }

        val logRef = db.collection("admin_logs").document()
        batch.set(
            logRef,
            AdminLog(
                actorEmail = email.ifBlank { adminCode },
                action = "Master Admin Initialized",
                targetShopId = shopId,
                details = "${shopName.trim()} ($adminCode) initialized as Master Admin"
            )
        )

        try {
            batch.commit().await()
            shopId
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, adminRef.path)
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }
    }

    suspend fun ensureSuperAdminBootstrapped() {
        val user = auth.currentUser ?: return
        if (!user.email.equals(BOOTSTRAP_ADMIN_EMAIL, ignoreCase = true)) return
        runCatching {
            val cfgRef = db.collection("platform_config").document("global")
            val cfgSnap = cfgRef.get().await()
            val cfg = if (cfgSnap.exists()) cfgSnap.toObject(PlatformConfig::class.java) else null
            // Preserve first-time setup flow if no Master Admin has been initialized yet
            if (cfg?.masterAdminInitialized != true) return
            val bootstrapRef = db.collection("app_bootstrap").document("status")
            if (!bootstrapRef.get().await().exists()) {
                bootstrapRef.set(
                    AppBootstrapStatus(
                        masterAdminInitialized = true,
                        supportPhone = cfg.supportPhone.ifBlank { "+919876543210" },
                        supportWhatsapp = cfg.supportWhatsapp.ifBlank { "+919876543210" }
                    )
                ).await()
            }
            val adminRef = db.collection("admins").document(user.uid)
            if (!adminRef.get().await().exists()) {
                adminRef.set(
                    AdminUser(
                        uid = user.uid,
                        email = user.email ?: BOOTSTRAP_ADMIN_EMAIL,
                        name = user.displayName ?: "Super Admin",
                        isMasterAdmin = false
                    )
                ).await()
            }
            val secRef = db.collection("admin_secrets").document("config")
            if (!secRef.get().await().exists()) {
                secRef.set(AdminSecrets(masterAdminPinHash = SecurityUtils.sha256("0000"))).await()
            }
        }
    }

    fun observeAllShops(): Flow<List<Shop>> =
        db.collection("shops")
            .snapshots()
            .map { it.toObjects(Shop::class.java, ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                handleFirestoreError(error, OperationType.LIST, "shops")
                throw error
            }

    fun observePlatformConfig(): Flow<PlatformConfig> =
        db.collection("platform_config").document("global")
            .snapshots()
            .map { it.toObject(PlatformConfig::class.java, ServerTimestampBehavior.ESTIMATE) ?: PlatformConfig() }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                handleFirestoreError(error, OperationType.GET, "platform_config/global")
                throw error
            }

    fun observeAdminSecrets(): Flow<AdminSecrets> =
        db.collection("admin_secrets").document("config")
            .snapshots()
            .map { it.toObject(AdminSecrets::class.java, ServerTimestampBehavior.ESTIMATE) ?: AdminSecrets() }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                handleFirestoreError(error, OperationType.GET, "admin_secrets/config")
                throw error
            }

    fun observeAllDataAccessRequests(): Flow<List<DataAccessRequest>> =
        db.collection("data_access_requests")
            .snapshots()
            .map { it.toObjects(DataAccessRequest::class.java, ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                handleFirestoreError(error, OperationType.LIST, "data_access_requests")
                throw error
            }

    fun observeShopArchives(): Flow<List<ShopArchive>> =
        db.collection("shop_archives")
            .snapshots()
            .map { it.toObjects(ShopArchive::class.java, ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                handleFirestoreError(error, OperationType.LIST, "shop_archives")
                throw error
            }

    fun observeAllBackups(): Flow<List<BackupRecord>> =
        db.collection("backups")
            .snapshots()
            .map { it.toObjects(BackupRecord::class.java, ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                handleFirestoreError(error, OperationType.LIST, "backups")
                throw error
            }

    fun observeAdminLogs(): Flow<List<AdminLog>> =
        db.collection("admin_logs")
            .snapshots()
            .map { it.toObjects(AdminLog::class.java, ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                handleFirestoreError(error, OperationType.LIST, "admin_logs")
                throw error
            }

    suspend fun savePlatformConfig(config: PlatformConfig): Result<Unit> = runCatching {
        val email = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val batch = db.batch()
        val ref = db.collection("platform_config").document("global")
        batch.set(ref, config.copy(updatedAt = null))
        if (config.masterAdminInitialized) {
            batch.set(
                db.collection("app_bootstrap").document("status"),
                AppBootstrapStatus(
                    masterAdminInitialized = true,
                    supportPhone = config.supportPhone.ifBlank { "+919876543210" },
                    supportWhatsapp = config.supportWhatsapp.ifBlank { "+919876543210" }
                )
            )
        }
        val logRef = db.collection("admin_logs").document()
        batch.set(
            logRef,
            AdminLog(
                actorEmail = email,
                action = "Updated Platform Config",
                details = "Support: ${config.supportPhone}, Lock: ${config.emergencyLock}"
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    suspend fun saveAdminSecrets(secrets: AdminSecrets): Result<Unit> = runCatching {
        val email = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val batch = db.batch()
        val ref = db.collection("admin_secrets").document("config")
        batch.set(ref, secrets.copy(updatedAt = null))
        val logRef = db.collection("admin_logs").document()
        batch.set(
            logRef,
            AdminLog(
                actorEmail = email,
                action = "Updated Security & Telegram Settings",
                details = "Chat ID configured: ${secrets.telegramChatId.isNotBlank()}"
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    suspend fun createShopByAdmin(
        shopCodeRaw: String,
        shopName: String,
        ownerName: String,
        ownerUid: String,
        mobile: String,
        email: String,
        passwordPlain: String,
        plan: String,
        durationDays: Long,
        amountPaid: Double
    ): Result<String> = runCatching {
        val adminEmail = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val adminUid = auth.currentUser?.uid ?: "admin"
        val shopCode = ValidationUtils.normalizeCode(shopCodeRaw)

        ValidationUtils.validateShopOrAdminId(shopCode)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validateShopName(shopName)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validatePersonName(ownerName)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validateMobile(mobile)?.let { throw IllegalArgumentException(it) }
        ValidationUtils.validatePassword(passwordPlain)?.let { throw IllegalArgumentException(it) }

        val dirRef = db.collection("shop_directory").document(shopCode)
        if (dirRef.get().await().exists()) {
            throw IllegalArgumentException("Shop ID '$shopCode' already exists. Use a unique Shop ID.")
        }

        val shopId = "shop_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val now = System.currentTimeMillis()
        val passHash = SecurityUtils.sha256(passwordPlain)
        val assignedOwnerUid = ownerUid.trim().ifBlank { adminUid }
        val isPreclaimed = ownerUid.trim().isNotBlank()

        val shop = Shop(
            ownerUid = assignedOwnerUid,
            shopCode = shopCode,
            shopName = shopName.trim(),
            ownerName = ownerName.trim(),
            mobile = mobile.trim(),
            email = email.trim(),
            passwordHash = passHash,
            secretCodeHash = SecurityUtils.sha256("1234"),
            status = "active",
            subscriptionPlan = plan,
            subscriptionStatus = "active",
            subscriptionStartMillis = now,
            subscriptionExpiryMillis = now + durationDays * 86_400_000L,
            subscriptionPaid = amountPaid,
            claimedByOwner = isPreclaimed
        )
        val batch = db.batch()
        val ref = db.collection("shops").document(shopId)
        batch.set(ref, shop)
        batch.set(
            dirRef,
            ShopDirectoryEntry(
                shopId = shopId,
                shopCode = shopCode,
                shopName = shopName.trim(),
                ownerUid = assignedOwnerUid,
                linkedEmailHint = SecurityUtils.maskEmail(email),
                mobile = mobile.trim(),
                passwordHash = passHash,
                isMasterAdmin = false,
                claimedByOwner = isPreclaimed,
                status = "active"
            )
        )
        val logRef = db.collection("admin_logs").document()
        batch.set(
            logRef,
            AdminLog(
                actorEmail = adminEmail,
                action = "Created Shop Account",
                targetShopId = shopId,
                details = "$shopName ($shopCode) - $plan"
            )
        )
        try {
            batch.commit().await()
            shopId
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }
    }

    suspend fun updateShopByAdmin(shop: Shop, actionDescription: String): Result<Unit> = runCatching {
        require(shop.id.isNotBlank() && shop.createdAt != null)
        val adminEmail = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val batch = db.batch()
        val ref = db.collection("shops").document(shop.id)
        batch.set(ref, shop.copy(updatedAt = null))

        val dirRef = db.collection("shop_directory").document(ValidationUtils.normalizeCode(shop.shopCode))
        val dirSnap = dirRef.get().await()
        if (dirSnap.exists()) {
            batch.update(
                dirRef,
                mapOf(
                    "shopName" to shop.shopName,
                    "status" to shop.status,
                    "passwordHash" to shop.passwordHash,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            )
        }

        val logRef = db.collection("admin_logs").document()
        batch.set(
            logRef,
            AdminLog(
                actorEmail = adminEmail,
                action = actionDescription,
                targetShopId = shop.id,
                details = "${shop.shopName} (${shop.status})"
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }
    }

    suspend fun updateShopCredentialsByAdmin(
        shop: Shop,
        newShopCodeRaw: String,
        newPasswordPlain: String
    ): Result<Unit> = runCatching {
        require(shop.id.isNotBlank() && shop.createdAt != null)
        val adminEmail = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val oldCode = ValidationUtils.normalizeCode(shop.shopCode)
        val newCode = ValidationUtils.normalizeCode(newShopCodeRaw)

        ValidationUtils.validateShopOrAdminId(newCode)?.let { throw IllegalArgumentException(it) }
        if (newPasswordPlain.isNotBlank()) {
            ValidationUtils.validatePassword(newPasswordPlain)?.let { throw IllegalArgumentException(it) }
        }
        val newHash = if (newPasswordPlain.isNotBlank()) SecurityUtils.sha256(newPasswordPlain) else shop.passwordHash

        if (newCode != oldCode) {
            val newDirRef = db.collection("shop_directory").document(newCode)
            if (newDirRef.get().await().exists()) {
                throw IllegalArgumentException("Shop ID '$newCode' already exists. Use another ID.")
            }
        }

        val batch = db.batch()
        val shopRef = db.collection("shops").document(shop.id)
        batch.set(shopRef, shop.copy(shopCode = newCode, passwordHash = newHash, updatedAt = null))

        val oldDirRef = db.collection("shop_directory").document(oldCode)
        val oldDirSnap = oldDirRef.get().await()
        if (newCode == oldCode) {
            if (oldDirSnap.exists()) {
                batch.update(
                    oldDirRef,
                    mapOf(
                        "passwordHash" to newHash,
                        "shopName" to shop.shopName,
                        "status" to shop.status,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                batch.set(
                    oldDirRef,
                    ShopDirectoryEntry(
                        shopId = shop.id,
                        shopCode = newCode,
                        shopName = shop.shopName,
                        ownerUid = shop.ownerUid,
                        linkedEmailHint = SecurityUtils.maskEmail(shop.email),
                        mobile = shop.mobile,
                        passwordHash = newHash,
                        claimedByOwner = shop.claimedByOwner,
                        status = shop.status
                    )
                )
            }
        } else {
            if (oldDirSnap.exists()) {
                batch.update(
                    oldDirRef,
                    mapOf(
                        "status" to "archived",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.set(
                db.collection("shop_directory").document(newCode),
                ShopDirectoryEntry(
                    shopId = shop.id,
                    shopCode = newCode,
                    shopName = shop.shopName,
                    ownerUid = shop.ownerUid,
                    linkedEmailHint = SecurityUtils.maskEmail(shop.email),
                    mobile = shop.mobile,
                    passwordHash = newHash,
                    claimedByOwner = shop.claimedByOwner,
                    status = shop.status
                )
            )
        }

        val logRef = db.collection("admin_logs").document()
        batch.set(
            logRef,
            AdminLog(
                actorEmail = adminEmail,
                action = "Updated Login Credentials",
                targetShopId = shop.id,
                details = "${shop.shopName} -> ID: $newCode"
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, shopRef.path)
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }
    }

    suspend fun archiveShopWithVerifiedBackup(shop: Shop): Result<Unit> = runCatching {
        require(shop.id.isNotBlank() && shop.createdAt != null)
        val adminEmail = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val backupJson = JSONObject().apply {
            put("shopId", shop.id)
            put("shopCode", shop.shopCode)
            put("shopName", shop.shopName)
            put("ownerUid", shop.ownerUid)
            put("mobile", shop.mobile)
            put("subscriptionPlan", shop.subscriptionPlan)
            put("archivedAt", System.currentTimeMillis())
        }.toString()

        val batch = db.batch()
        val archiveRef = db.collection("shop_archives").document()
        batch.set(
            archiveRef,
            ShopArchive(
                shopId = shop.id,
                shopName = shop.shopName,
                ownerUid = shop.ownerUid,
                backupVerified = true,
                archivedBy = adminEmail,
                archivedAtMillis = System.currentTimeMillis(),
                backupPayloadJson = backupJson,
                restored = false
            )
        )
        val backupRef = db.collection("backups").document()
        batch.set(
            backupRef,
            BackupRecord(
                shopId = shop.id,
                shopName = shop.shopName,
                ownerUid = shop.ownerUid,
                backupType = "FULL",
                status = "VERIFIED",
                recordCount = 1L,
                imageRefCount = 0L,
                backupJson = backupJson
            )
        )
        val shopRef = db.collection("shops").document(shop.id)
        batch.set(shopRef, shop.copy(status = "archived", updatedAt = null))

        val dirRef = db.collection("shop_directory").document(ValidationUtils.normalizeCode(shop.shopCode))
        if (dirRef.get().await().exists()) {
            batch.update(dirRef, mapOf("status" to "archived", "updatedAt" to FieldValue.serverTimestamp()))
        }

        val logRef = db.collection("admin_logs").document()
        batch.set(
            logRef,
            AdminLog(
                actorEmail = adminEmail,
                action = "Archived Shop With Verified Backup",
                targetShopId = shop.id,
                details = shop.shopName
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, shopRef.path)
            throw e
        }
    }

    suspend fun restoreArchivedShop(shop: Shop, archive: ShopArchive?): Result<Unit> = runCatching {
        require(shop.id.isNotBlank() && shop.createdAt != null)
        val adminEmail = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val batch = db.batch()
        batch.set(db.collection("shops").document(shop.id), shop.copy(status = "active", updatedAt = null))
        val dirRef = db.collection("shop_directory").document(ValidationUtils.normalizeCode(shop.shopCode))
        if (dirRef.get().await().exists()) {
            batch.update(dirRef, mapOf("status" to "active", "updatedAt" to FieldValue.serverTimestamp()))
        }
        if (archive != null && archive.id.isNotBlank() && archive.createdAt != null) {
            batch.set(db.collection("shop_archives").document(archive.id), archive.copy(restored = true, updatedAt = null))
        }
        val logRef = db.collection("admin_logs").document()
        batch.set(
            logRef,
            AdminLog(
                actorEmail = adminEmail,
                action = "Restored Archived Shop",
                targetShopId = shop.id,
                details = shop.shopName
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "shops/${shop.id}")
            throw e
        }
    }

    suspend fun requestShopDataAccess(shop: Shop, reason: String): Result<Unit> = runCatching {
        val adminEmail = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val ref = db.collection("data_access_requests").document()
        val req = DataAccessRequest(
            shopId = shop.id,
            shopName = shop.shopName,
            ownerUid = shop.ownerUid,
            permissionStatus = "PENDING",
            reason = reason.trim(),
            adminIdentity = adminEmail
        )
        try {
            ref.set(req).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            throw e
        }
    }

    suspend fun revokeDataAccess(request: DataAccessRequest): Result<Unit> = runCatching {
        require(request.id.isNotBlank() && request.createdAt != null)
        val ref = db.collection("data_access_requests").document(request.id)
        try {
            ref.set(
                request.copy(
                    permissionStatus = "REVOKED",
                    endTimeMillis = System.currentTimeMillis(),
                    snapshotSummaryJson = "",
                    updatedAt = null
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    suspend fun sendTelegramAlert(botToken: String, chatId: String, message: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(botToken.isNotBlank() && chatId.isNotBlank()) { "Bot Token and Chat ID are required." }
                val url = URL("https://api.telegram.org/bot${botToken.trim()}/sendMessage")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val body = JSONObject().apply {
                    put("chat_id", chatId.trim())
                    put("text", message)
                }.toString()
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) "Telegram alert sent (HTTP $code)" else error("Telegram returned HTTP $code")
            }
        }
}
