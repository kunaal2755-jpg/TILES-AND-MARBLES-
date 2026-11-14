package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.data.model.*
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

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

    suspend fun ensureSuperAdminBootstrapped() {
        val user = auth.currentUser ?: return
        if (!user.email.equals(BOOTSTRAP_ADMIN_EMAIL, ignoreCase = true)) return
        runCatching {
            val adminRef = db.collection("admins").document(user.uid)
            if (!adminRef.get().await().exists()) {
                adminRef.set(
                    AdminUser(
                        uid = user.uid,
                        email = user.email ?: BOOTSTRAP_ADMIN_EMAIL,
                        name = user.displayName ?: "Master Admin"
                    )
                ).await()
            }
            val cfgRef = db.collection("platform_config").document("global")
            if (!cfgRef.get().await().exists()) {
                cfgRef.set(PlatformConfig()).await()
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
        shopCode: String,
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
        val shopId = "shop_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val now = System.currentTimeMillis()
        val shop = Shop(
            ownerUid = ownerUid.ifBlank { auth.currentUser?.uid ?: "unassigned" },
            shopCode = shopCode.trim(),
            shopName = shopName.trim(),
            ownerName = ownerName.trim(),
            mobile = mobile.trim(),
            email = email.trim(),
            passwordHash = SecurityUtils.sha256(passwordPlain),
            secretCodeHash = SecurityUtils.sha256("1234"),
            status = "active",
            subscriptionPlan = plan,
            subscriptionStatus = "active",
            subscriptionStartMillis = now,
            subscriptionExpiryMillis = now + durationDays * 86_400_000L,
            subscriptionPaid = amountPaid
        )
        val batch = db.batch()
        val ref = db.collection("shops").document(shopId)
        batch.set(ref, shop)
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
            throw e
        }
    }

    suspend fun updateShopByAdmin(shop: Shop, actionDescription: String): Result<Unit> = runCatching {
        require(shop.id.isNotBlank() && shop.createdAt != null)
        val adminEmail = auth.currentUser?.email ?: BOOTSTRAP_ADMIN_EMAIL
        val batch = db.batch()
        val ref = db.collection("shops").document(shop.id)
        batch.set(ref, shop.copy(updatedAt = null))
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
            throw e
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
