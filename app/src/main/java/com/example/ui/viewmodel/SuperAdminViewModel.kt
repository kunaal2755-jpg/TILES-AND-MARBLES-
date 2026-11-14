package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.SecurityUtils
import com.example.data.firebase.SuperAdminRepository
import com.example.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SuperAdminViewModel(
    private val repository: SuperAdminRepository,
    val currentUserId: String
) : ViewModel() {

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val message: SharedFlow<String> = _message.asSharedFlow()

    val isAuthorizedEmail: Boolean get() = repository.isCurrentUserSuperAdminEmail()

    val shops: StateFlow<List<Shop>> = repository.observeAllShops()
        .catch { e ->
            if (e is CancellationException && e.cause == null) throw e
            Log.w("SuperAdminVM", "Error observing shops", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val platformConfig: StateFlow<PlatformConfig> = repository.observePlatformConfig()
        .catch { emit(PlatformConfig()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), PlatformConfig())

    val adminSecrets: StateFlow<AdminSecrets> = repository.observeAdminSecrets()
        .catch { emit(AdminSecrets()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AdminSecrets())

    val dataAccessRequests: StateFlow<List<DataAccessRequest>> = repository.observeAllDataAccessRequests()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val shopArchives: StateFlow<List<ShopArchive>> = repository.observeShopArchives()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val backups: StateFlow<List<BackupRecord>> = repository.observeAllBackups()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val adminLogs: StateFlow<List<AdminLog>> = repository.observeAdminLogs()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        viewModelScope.launch {
            repository.ensureSuperAdminBootstrapped()
        }
    }

    fun notify(msg: String) {
        _message.tryEmit(msg)
    }

    fun createShopAccount(
        shopCode: String,
        shopName: String,
        ownerName: String,
        ownerUid: String,
        mobile: String,
        email: String,
        passwordPlain: String,
        plan: String,
        durationDays: Long,
        amountPaid: Double,
        onResult: (Result<String>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.createShopByAdmin(
                shopCode, shopName, ownerName, ownerUid, mobile, email, passwordPlain, plan, durationDays, amountPaid
            )
            res.onSuccess { notify("Shop ${shopCode.trim().uppercase()} created & synced!") }
                .onFailure { notify(it.localizedMessage ?: "Create failed") }
            onResult(res)
        }
    }

    fun updateShopStatus(shop: Shop, newStatus: String) {
        viewModelScope.launch {
            repository.updateShopByAdmin(shop.copy(status = newStatus), "Set Status: $newStatus")
                .onSuccess { notify("${shop.shopName} -> $newStatus") }
                .onFailure { notify("Failed: ${it.localizedMessage}") }
        }
    }

    fun updateShopCredentials(
        shop: Shop,
        newShopCode: String,
        newPassword: String,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.updateShopCredentialsByAdmin(shop, newShopCode, newPassword)
            res.onSuccess { notify("Credentials updated for ${shop.shopName}") }
                .onFailure { notify(it.localizedMessage ?: "Failed to update credentials") }
            onResult(res)
        }
    }

    fun updateSubscription(shop: Shop, plan: String, addDays: Long, amountPaid: Double, graceDays: Long) {
        viewModelScope.launch {
            val base = maxOf(System.currentTimeMillis(), shop.subscriptionExpiryMillis)
            val newExpiry = base + addDays * 86_400_000L
            repository.updateShopByAdmin(
                shop.copy(
                    subscriptionPlan = plan,
                    subscriptionStatus = "active",
                    subscriptionExpiryMillis = newExpiry,
                    subscriptionPaid = shop.subscriptionPaid + amountPaid,
                    graceDays = graceDays
                ),
                "Subscription Updated ($plan)"
            ).onSuccess { notify("Subscription updated for ${shop.shopName}") }
                .onFailure { notify("Failed: ${it.localizedMessage}") }
        }
    }

    fun archiveShop(shop: Shop) {
        viewModelScope.launch {
            repository.archiveShopWithVerifiedBackup(shop)
                .onSuccess { notify("Backup verified & ${shop.shopName} archived safely") }
                .onFailure { notify("Archive failed: ${it.localizedMessage}") }
        }
    }

    fun restoreShop(shop: Shop) {
        val archive = shopArchives.value.firstOrNull { it.shopId == shop.id && !it.restored }
        viewModelScope.launch {
            repository.restoreArchivedShop(shop, archive)
                .onSuccess { notify("${shop.shopName} restored to active!") }
                .onFailure { notify("Restore failed: ${it.localizedMessage}") }
        }
    }

    fun savePlatformConfig(config: PlatformConfig) {
        viewModelScope.launch {
            repository.savePlatformConfig(config)
                .onSuccess { notify("Global configuration synced to all User Apps") }
                .onFailure { notify("Save failed: ${it.localizedMessage}") }
        }
    }

    fun saveAdminSecrets(secrets: AdminSecrets) {
        viewModelScope.launch {
            repository.saveAdminSecrets(secrets)
                .onSuccess { notify("Admin security & Telegram settings saved") }
                .onFailure { notify("Save failed: ${it.localizedMessage}") }
        }
    }

    fun sendTestTelegramAlert(botToken: String, chatId: String) {
        viewModelScope.launch {
            repository.sendTelegramAlert(botToken, chatId, "TileVault Super Admin Test Alert: Connection Verified!")
                .onSuccess { notify(it) }
                .onFailure { notify("Telegram failed: ${it.localizedMessage}") }
        }
    }

    fun requestDataAccess(shop: Shop, reason: String) {
        viewModelScope.launch {
            repository.requestShopDataAccess(shop, reason)
                .onSuccess { notify("Data access request sent to ${shop.shopName}") }
                .onFailure { notify("Request failed: ${it.localizedMessage}") }
        }
    }

    fun revokeDataAccess(request: DataAccessRequest) {
        viewModelScope.launch {
            repository.revokeDataAccess(request)
                .onSuccess { notify("Access revoked immediately") }
                .onFailure { notify("Revoke failed: ${it.localizedMessage}") }
        }
    }
}
