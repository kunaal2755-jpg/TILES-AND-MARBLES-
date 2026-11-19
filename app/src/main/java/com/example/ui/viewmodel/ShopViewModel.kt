package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.*
import com.example.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface BootstrapUiState {
    data object Checking : BootstrapUiState
    data object NeedsFirstTimeMasterSetup : BootstrapUiState
    data class Ready(
        val isAdmin: Boolean,
        val isMasterAdmin: Boolean,
        val masterAdminId: String = ""
    ) : BootstrapUiState
    data class NetworkError(val message: String) : BootstrapUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShopViewModel(
    private val repository: ShopRepository,
    private val prefs: PreferencesRepository,
    private val adminRepo: SuperAdminRepository,
    private val appContext: Context,
    val currentUserId: String
) : ViewModel() {

    private val _selectedShopId = MutableStateFlow("")
    private val _actionMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val actionMessage: SharedFlow<String> = _actionMessage.asSharedFlow()

    private val _bootstrapState = MutableStateFlow<BootstrapUiState>(BootstrapUiState.Checking)
    val bootstrapState: StateFlow<BootstrapUiState> = _bootstrapState.asStateFlow()

    private var failedLoginAttempts = 0
    private var loginLockoutUntilMillis = 0L

    val language: StateFlow<AppLanguage> = prefs.languageFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AppLanguage.EN)

    val platformConfig: StateFlow<PlatformConfig> = repository.observePlatformConfig()
        .map { it ?: PlatformConfig() }
        .catch { emit(PlatformConfig()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), PlatformConfig())

    val ownedShops: StateFlow<UiState<List<Shop>>> = repository.observeOwnedShops()
        .map<List<Shop>, UiState<List<Shop>>> { shops ->
            val savedId = _selectedShopId.value
            if (savedId.isBlank() && shops.isNotEmpty()) {
                _selectedShopId.value = shops.first().id
            }
            UiState.Success(shops)
        }
        .catch { error ->
            if (error is CancellationException && error.cause == null) throw error
            Log.w("ShopVM", "Error observing shops", error)
            emit(UiState.Error(NetworkUtils.mapCloudError(error)))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val activeShop: StateFlow<Shop?> = combine(ownedShops, _selectedShopId) { state, selId ->
        val list = (state as? UiState.Success)?.data ?: emptyList()
        list.firstOrNull { it.id == selId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    private val activeShopIdFlow: Flow<String> = activeShop.map { it?.id ?: "" }.distinctUntilChanged()

    private fun <T> observeActive(block: (String) -> Flow<List<T>>): StateFlow<List<T>> =
        activeShopIdFlow.flatMapLatest { id ->
            if (id.isBlank()) flowOf(emptyList()) else block(id).catch { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val tileSizes: StateFlow<List<TileSizeConfig>> = observeActive { repository.observeTileSizes(it) }
    val locations: StateFlow<List<StockLocation>> = observeActive { repository.observeLocations(it) }
    val tiles: StateFlow<List<TileProduct>> = observeActive { repository.observeTiles(it) }
    val deletedTiles: StateFlow<List<TileProduct>> = observeActive { repository.observeDeletedTiles(it) }
    val stockMovements: StateFlow<List<StockMovement>> = observeActive { repository.observeStockMovements(it) }
    val suppliers: StateFlow<List<Supplier>> = observeActive { repository.observeSuppliers(it) }
    val purchases: StateFlow<List<PurchaseBill>> = observeActive { repository.observePurchases(it) }
    val customers: StateFlow<List<Customer>> = observeActive { repository.observeCustomers(it) }
    val sales: StateFlow<List<SaleOrder>> = observeActive { repository.observeSales(it) }
    val deliveries: StateFlow<List<DeliveryRecord>> = observeActive { repository.observeDeliveries(it) }
    val returns: StateFlow<List<ReturnExchange>> = observeActive { repository.observeReturns(it) }
    val staff: StateFlow<List<StaffMember>> = observeActive { repository.observeStaff(it) }
    val attendance: StateFlow<List<AttendanceRecord>> = observeActive { repository.observeAttendance(it) }
    val advances: StateFlow<List<StaffAdvance>> = observeActive { repository.observeAdvances(it) }
    val salaryMonths: StateFlow<List<SalaryMonthRecord>> = observeActive { repository.observeSalaryMonths(it) }
    val expenses: StateFlow<List<ExpenseEntry>> = observeActive { repository.observeExpenses(it) }
    val activityLogs: StateFlow<List<ActivityLog>> = observeActive { repository.observeActivityLogs(it) }

    val dataAccessRequests: StateFlow<List<DataAccessRequest>> = repository.observeShopDataAccessRequests()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val backups: StateFlow<List<BackupRecord>> = repository.observeShopBackups()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var lastAutoAttendanceKey = ""

    init {
        viewModelScope.launch {
            prefs.activeShopIdFlow.collect { saved ->
                if (saved.isNotBlank() && _selectedShopId.value.isBlank()) {
                    _selectedShopId.value = saved
                }
            }
        }
        checkBootstrapStatus()
        startAutoAttendanceWatcher()
    }

    private fun startAutoAttendanceWatcher() {
        val minuteTicker = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000L)
            }
        }
        viewModelScope.launch {
            combine(activeShop, staff, attendance, minuteTicker) { shop, staffList, attList, nowMillis ->
                Triple(shop, staffList, Pair(attList, nowMillis))
            }.collect { (shop, staffList, attAndNow) ->
                val (attList, nowMillis) = attAndNow
                if (shop == null || !shop.autoAttendanceEnabled || staffList.isEmpty()) return@collect

                val parts = shop.autoAttendanceTime.trim().split(":")
                val targetHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
                val targetMin = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
                val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
                val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val scheduledMinutes = targetHour * 60 + targetMin
                if (nowMinutes < scheduledMinutes) return@collect

                val todayKey = Formatters.dateKey(nowMillis)
                val markedIds = attList.asSequence()
                    .filter { it.dateKey == todayKey }
                    .map { it.staffId }
                    .toSet()
                val unmarked = staffList.filter { it.id.isNotBlank() && it.id !in markedIds }
                if (unmarked.isEmpty()) return@collect

                val runKey = "${shop.id}_${todayKey}_${unmarked.joinToString(",") { it.id }}"
                if (runKey == lastAutoAttendanceKey) return@collect
                lastAutoAttendanceKey = runKey

                repository.markAutoAttendanceBatch(
                    shopId = shop.id,
                    dateKey = todayKey,
                    unmarkedStaff = unmarked,
                    autoTime = shop.autoAttendanceTime,
                    actorName = "Auto-Attendance"
                ).onSuccess { count ->
                    if (count > 0) {
                        notify("Auto-Attendance marked for $count staff ($todayKey)")
                    }
                }.onFailure {
                    lastAutoAttendanceKey = ""
                }
            }
        }
    }

    fun checkBootstrapStatus() {
        viewModelScope.launch {
            _bootstrapState.value = BootstrapUiState.Checking
            if (!NetworkUtils.isOnline(appContext)) {
                _bootstrapState.value = BootstrapUiState.NetworkError(
                    "Internet connection is unavailable. Please connect to Wi-Fi or Mobile Data and retry.\nइंटरनेट उपलब्ध नहीं है। कृपया नेटवर्क जांचें और पुनः प्रयास करें।"
                )
                return@launch
            }
            adminRepo.fetchBootstrapStatusFromServer()
                .onSuccess { status ->
                    if (!status.masterAdminInitialized) {
                        _bootstrapState.value = BootstrapUiState.NeedsFirstTimeMasterSetup
                    } else {
                        if (status.isCurrentUserAdmin) {
                            adminRepo.ensureSuperAdminBootstrapped()
                        }
                        _bootstrapState.value = BootstrapUiState.Ready(
                            isAdmin = status.isCurrentUserAdmin,
                            isMasterAdmin = status.isMasterAdmin,
                            masterAdminId = status.masterAdminId
                        )
                    }
                }
                .onFailure { error ->
                    _bootstrapState.value = BootstrapUiState.NetworkError(NetworkUtils.mapCloudError(error))
                }
        }
    }

    fun completeFirstTimeMasterSetup(
        adminId: String,
        shopName: String,
        ownerName: String,
        mobile: String,
        passwordPlain: String,
        confirmPasswordPlain: String,
        secretCodePlain: String,
        deviceName: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            if (!NetworkUtils.isOnline(appContext)) {
                onResult(
                    Result.failure(
                        IllegalStateException(
                            "Internet connection is unavailable. Connect to internet before creating Master Admin.\nइंटरनेट उपलब्ध नहीं है। कृपया नेटवर्क जांचें।"
                        )
                    )
                )
                return@launch
            }
            val result = adminRepo.completeFirstTimeMasterAdminSetup(
                adminIdRaw = adminId,
                shopName = shopName,
                ownerName = ownerName,
                mobile = mobile,
                passwordPlain = passwordPlain,
                confirmPasswordPlain = confirmPasswordPlain,
                secretCodePlain = secretCodePlain,
                deviceName = deviceName
            )
            result.onSuccess { shopId ->
                selectShop(shopId)
                _bootstrapState.value = BootstrapUiState.Ready(
                    isAdmin = true,
                    isMasterAdmin = true,
                    masterAdminId = ValidationUtils.normalizeCode(adminId)
                )
                notify("Master Admin & Primary Shop created successfully!")
            }
            onResult(result)
        }
    }

    fun loginWithShopCredentials(
        shopCode: String,
        passwordPlain: String,
        deviceName: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now < loginLockoutUntilMillis) {
                val secs = ((loginLockoutUntilMillis - now) / 1000L).coerceAtLeast(1L)
                onResult(
                    Result.failure(
                        IllegalStateException("Too many wrong password attempts. Try again in ${secs}s.\nकई गलत प्रयासों के कारण ${secs} सेकंड बाद प्रयास करें।")
                    )
                )
                return@launch
            }
            if (!NetworkUtils.isOnline(appContext)) {
                onResult(
                    Result.failure(
                        IllegalStateException(
                            "Internet connection or cloud server is unavailable.\nइंटरनेट या सर्वर उपलब्ध नहीं है।"
                        )
                    )
                )
                return@launch
            }
            val result = repository.verifyAndSyncShopByCredentials(shopCode, passwordPlain, deviceName)
            result.onSuccess { shopId ->
                failedLoginAttempts = 0
                loginLockoutUntilMillis = 0L
                selectShop(shopId)
                notify("Shop synced cleanly from cloud!")
            }.onFailure { err ->
                if (err is IllegalArgumentException) {
                    failedLoginAttempts++
                    if (failedLoginAttempts >= 5) {
                        loginLockoutUntilMillis = System.currentTimeMillis() + 30_000L
                    }
                }
            }
            onResult(result)
        }
    }

    fun selectShop(shopId: String) {
        _selectedShopId.value = shopId
        viewModelScope.launch { prefs.setActiveShopId(shopId) }
    }

    fun toggleLanguage() {
        viewModelScope.launch {
            val next = if (language.value == AppLanguage.EN) AppLanguage.HI else AppLanguage.EN
            prefs.setLanguage(next)
        }
    }

    fun notify(msg: String) {
        _actionMessage.tryEmit(msg)
    }

    fun createShop(
        shopName: String,
        ownerName: String,
        mobile: String,
        address: String,
        gstNumber: String,
        passwordPlain: String,
        secretCodePlain: String,
        deviceName: String,
        customShopCode: String = "",
        onResult: (Result<String>) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (!NetworkUtils.isOnline(appContext)) {
                val err = IllegalStateException("Internet unavailable. Cannot sync new shop to cloud.\nइंटरनेट उपलब्ध नहीं है।")
                notify(err.message ?: "Offline")
                onResult(Result.failure(err))
                return@launch
            }
            val cfg = platformConfig.value
            if (!cfg.registrationEnabled) {
                val err = IllegalStateException("New shop registration is temporarily disabled by Super Admin.")
                notify(err.message ?: "Disabled")
                onResult(Result.failure(err))
                return@launch
            }
            val result = repository.createShopWithDefaults(
                shopName = shopName,
                ownerName = ownerName,
                mobile = mobile,
                address = address,
                gstNumber = gstNumber,
                passwordPlain = passwordPlain,
                secretCodePlain = secretCodePlain,
                deviceName = deviceName,
                requiresApproval = cfg.adminApprovalRequired,
                trialDays = cfg.freeTrialDays,
                customShopCode = customShopCode
            )
            result.onSuccess { id ->
                selectShop(id)
                notify(if (cfg.adminApprovalRequired) "Shop registered! Pending Admin Approval." else "Shop created & synced to cloud!")
            }.onFailure {
                notify(NetworkUtils.mapCloudError(it))
            }
            onResult(result)
        }
    }

    fun updateShopProfile(updated: Shop, msg: String = "Shop settings updated") {
        viewModelScope.launch {
            repository.updateShop(updated)
                .onSuccess { notify(msg) }
                .onFailure { notify("Update failed: ${NetworkUtils.mapCloudError(it)}") }
        }
    }

    fun saveTileSize(size: TileSizeConfig) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.saveTileSize(shopId, size)
                .onSuccess { notify("Tile size ${size.name} saved") }
                .onFailure { notify("Error: ${it.localizedMessage}") }
        }
    }

    fun saveLocation(location: StockLocation) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.saveLocation(shopId, location)
                .onSuccess { notify("Location ${location.name} saved") }
                .onFailure { notify("Error: ${it.localizedMessage}") }
        }
    }

    fun renameLocation(location: StockLocation, newName: String, newAddress: String) {
        val shop = activeShop.value ?: return
        viewModelScope.launch {
            repository.renameLocation(
                shopId = shop.id,
                location = location,
                newNameRaw = newName,
                newAddressRaw = newAddress,
                tiles = tiles.value,
                actorName = shop.ownerName.ifBlank { "Owner" }
            ).onSuccess {
                notify("Warehouse renamed to '${newName.trim()}'")
            }.onFailure {
                notify("Rename failed: ${it.localizedMessage}")
            }
        }
    }

    fun deleteLocationWithMigration(
        locationToDelete: StockLocation,
        targetLocationName: String,
        createNewLocation: Boolean,
        newLocationAddress: String
    ) {
        val shop = activeShop.value ?: return
        viewModelScope.launch {
            repository.deleteLocationWithMigration(
                shopId = shop.id,
                locationToDelete = locationToDelete,
                targetLocationNameRaw = targetLocationName,
                createNewLocation = createNewLocation,
                newLocationAddress = newLocationAddress,
                tiles = tiles.value,
                actorName = shop.ownerName.ifBlank { "Owner" }
            ).onSuccess { moved ->
                if (moved > 0) {
                    notify("Moved $moved tiles to '${targetLocationName.trim()}' & deleted '${locationToDelete.name}'")
                } else {
                    notify("Deleted warehouse '${locationToDelete.name}'")
                }
            }.onFailure {
                notify("Delete failed: ${it.localizedMessage}")
            }
        }
    }

    fun saveTile(tile: TileProduct) {
        val shop = activeShop.value ?: return
        viewModelScope.launch {
            repository.saveTileProduct(shop.id, tile, shop.ownerName.ifBlank { "Owner" })
                .onSuccess { notify("Tile ${tile.name} saved") }
                .onFailure { notify("Error: ${it.localizedMessage}") }
        }
    }

    fun deleteTileSoft(tile: TileProduct) {
        val shop = activeShop.value ?: return
        viewModelScope.launch {
            repository.softDeleteTile(shop.id, tile, shop.ownerName.ifBlank { "Owner" })
                .onSuccess { notify("${tile.name} moved to recycle bin") }
                .onFailure { notify("Delete failed: ${it.localizedMessage}") }
        }
    }

    fun adjustStock(
        tile: TileProduct,
        movementType: String,
        fromLocation: String,
        toLocation: String,
        boxesQty: Long,
        piecesQty: Long,
        reason: String
    ) {
        val shop = activeShop.value ?: return
        viewModelScope.launch {
            repository.adjustTileStock(
                shopId = shop.id,
                tile = tile,
                movementType = movementType,
                fromLocation = fromLocation,
                toLocation = toLocation,
                boxesQty = boxesQty,
                piecesQty = piecesQty,
                reason = reason,
                actorName = shop.ownerName.ifBlank { "Owner" }
            ).onSuccess { notify("Stock adjusted ($movementType)") }
                .onFailure { notify("Adjustment failed: ${it.localizedMessage}") }
        }
    }

    fun saveSupplier(supplier: Supplier) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.saveSupplier(shopId, supplier)
                .onSuccess { notify("Supplier ${supplier.name} saved") }
                .onFailure { notify("Error: ${it.localizedMessage}") }
        }
    }

    fun recordPurchase(bill: PurchaseBill, supplier: Supplier?) {
        val shop = activeShop.value ?: return
        val tilesMap = tiles.value.associateBy { it.id }
        viewModelScope.launch {
            repository.recordPurchaseBill(shop.id, bill, supplier, tilesMap, shop.ownerName.ifBlank { "Owner" })
                .onSuccess { notify("Purchase #${bill.invoiceNumber} saved & stock updated") }
                .onFailure { notify("Purchase failed: ${it.localizedMessage}") }
        }
    }

    fun saveCustomer(customer: Customer) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.saveCustomer(shopId, customer)
                .onSuccess { notify("Customer ${customer.name} saved") }
                .onFailure { notify("Error: ${it.localizedMessage}") }
        }
    }

    fun recordCustomerPayment(customer: Customer, amount: Double, sale: SaleOrder?, mode: String) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.recordCustomerPayment(shopId, customer, amount, sale, mode)
                .onSuccess { notify("Payment ${Formatters.currency(amount)} recorded") }
                .onFailure { notify("Payment failed: ${it.localizedMessage}") }
        }
    }

    fun saveSale(sale: SaleOrder, customer: Customer?, previousStatus: String?) {
        val shop = activeShop.value ?: return
        val tilesMap = tiles.value.associateBy { it.id }
        viewModelScope.launch {
            repository.saveSaleOrder(
                shopId = shop.id,
                sale = sale,
                existingCustomer = customer,
                tilesMap = tilesMap,
                previousOrderStatus = previousStatus,
                actorName = shop.ownerName.ifBlank { "Owner" }
            ).onSuccess {
                val statusLabel = if (sale.orderStatus == "CONFIRMED") "Confirmed & Stock Deducted" else "Saved as Pending Order"
                notify("Bill #${sale.invoiceNumber}: $statusLabel")
            }.onFailure {
                notify("Save failed: ${it.localizedMessage}")
            }
        }
    }

    fun deleteSale(sale: SaleOrder, restoreStock: Boolean) {
        val shop = activeShop.value ?: return
        val tilesMap = tiles.value.associateBy { it.id }
        val cust = customers.value.firstOrNull { it.id == sale.customerId }
        viewModelScope.launch {
            repository.deleteSaleOrder(
                shopId = shop.id,
                sale = sale,
                restoreStock = restoreStock,
                customer = cust,
                tilesMap = tilesMap,
                actorName = shop.ownerName.ifBlank { "Owner" }
            ).onSuccess {
                val suffix = if (restoreStock && sale.orderStatus == "CONFIRMED") " & stock restored!" else ""
                notify("Bill #${sale.invoiceNumber} deleted$suffix")
            }.onFailure {
                notify("Delete failed: ${it.localizedMessage}")
            }
        }
    }

    fun recordSalePayment(sale: SaleOrder, amount: Double, mode: String, note: String) {
        val shop = activeShop.value ?: return
        val cust = customers.value.firstOrNull { it.id == sale.customerId }
        viewModelScope.launch {
            repository.recordSalePayment(
                shopId = shop.id,
                sale = sale,
                customer = cust,
                amount = amount,
                paymentMode = mode,
                note = note,
                paymentDateMillis = System.currentTimeMillis(),
                actorName = shop.ownerName.ifBlank { "Owner" }
            ).onSuccess {
                notify("Recorded ${Formatters.currency(amount)} ($mode) for #${sale.invoiceNumber}")
            }.onFailure {
                notify("Payment failed: ${it.localizedMessage}")
            }
        }
    }

    fun recordPartialDelivery(
        sale: SaleOrder,
        deliveriesPerIndex: Map<Int, Long>,
        loadingCharges: Double,
        remarks: String,
        onDone: (DeliveryRecord) -> Unit
    ) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.recordPartialDelivery(shopId, sale, deliveriesPerIndex, loadingCharges, remarks)
                .onSuccess {
                    notify("Delivery challan recorded!")
                    onDone(it)
                }
                .onFailure { notify("Delivery failed: ${it.localizedMessage}") }
        }
    }

    fun recordReturnOrExchange(record: ReturnExchange, returnedTile: TileProduct?, replacementTile: TileProduct?, customer: Customer?) {
        val shop = activeShop.value ?: return
        viewModelScope.launch {
            repository.recordReturnOrExchange(shop.id, record, returnedTile, replacementTile, customer, shop.ownerName.ifBlank { "Owner" })
                .onSuccess { notify("Return/Exchange recorded") }
                .onFailure { notify("Return failed: ${it.localizedMessage}") }
        }
    }

    fun saveStaff(member: StaffMember) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.saveStaffMember(shopId, member)
                .onSuccess { notify("Staff ${member.fullName} saved") }
                .onFailure { notify("Error: ${it.localizedMessage}") }
        }
    }

    fun markAttendance(dateKey: String, member: StaffMember, status: String, secretCodeInput: String?, isAuto: Boolean = false, reason: String = "Daily entry") {
        val shop = activeShop.value ?: return
        if (!isAuto && shop.secretCodeHash.isNotBlank()) {
            if (secretCodeInput == null || SecurityUtils.sha256(secretCodeInput) != shop.secretCodeHash) {
                notify("Invalid Secret Code! Attendance edit blocked.")
                return
            }
        }
        viewModelScope.launch {
            repository.markAttendance(shop.id, dateKey, member, status, isAuto, shop.ownerName.ifBlank { "Owner" }, reason)
                .onSuccess { notify("${member.fullName}: $status") }
                .onFailure { notify("Attendance failed: ${it.localizedMessage}") }
        }
    }

    fun runAutoAttendanceForDate(dateKey: String = Formatters.dateKey()) {
        val shop = activeShop.value ?: return
        val markedIds = attendance.value.filter { it.dateKey == dateKey }.map { it.staffId }.toSet()
        val unmarked = staff.value.filter { it.id.isNotBlank() && it.id !in markedIds }
        if (unmarked.isEmpty()) {
            notify("All staff are already marked for $dateKey")
            return
        }
        viewModelScope.launch {
            repository.markAutoAttendanceBatch(
                shopId = shop.id,
                dateKey = dateKey,
                unmarkedStaff = unmarked,
                autoTime = shop.autoAttendanceTime,
                actorName = shop.ownerName.ifBlank { "Owner" }
            ).onSuccess { count ->
                notify("Auto-marked $count staff for $dateKey")
            }.onFailure {
                notify("Auto-attendance failed: ${it.localizedMessage}")
            }
        }
    }

    fun clearAttendanceForDate(dateKey: String, secretCodeInput: String) {
        val shop = activeShop.value ?: return
        if (shop.secretCodeHash.isNotBlank() && SecurityUtils.sha256(secretCodeInput) != shop.secretCodeHash) {
            notify("Invalid Secret Code!")
            return
        }
        val list = attendance.value.filter { it.dateKey == dateKey }
        if (list.isEmpty()) {
            notify("No records for $dateKey")
            return
        }
        viewModelScope.launch {
            repository.clearAttendanceForDate(shop.id, list, shop.ownerName.ifBlank { "Owner" })
                .onSuccess { notify("Cleared attendance for $dateKey") }
                .onFailure { notify("Clear failed: ${it.localizedMessage}") }
        }
    }

    fun saveAdvance(advance: StaffAdvance) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.saveStaffAdvance(shopId, advance)
                .onSuccess { notify("Advance ${Formatters.currency(advance.amount)} saved") }
                .onFailure { notify("Advance failed: ${it.localizedMessage}") }
        }
    }

    fun finalizeSalaryMonth(record: SalaryMonthRecord, staffMember: StaffMember?, finalize: Boolean) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.finalizeOrUpdateSalaryMonth(shopId, record, staffMember, finalize)
                .onSuccess { notify(if (finalize) "Month finalized & balance carried forward" else "Month unfinalized") }
                .onFailure { notify("Salary action failed: ${it.localizedMessage}") }
        }
    }

    fun saveExpense(expense: ExpenseEntry) {
        val shopId = activeShop.value?.id ?: return
        viewModelScope.launch {
            repository.saveExpense(shopId, expense)
                .onSuccess { notify("Expense ${Formatters.currency(expense.amount)} saved") }
                .onFailure { notify("Expense failed: ${it.localizedMessage}") }
        }
    }

    fun respondDataAccess(req: DataAccessRequest, approve: Boolean) {
        val summary = """{"tiles":${tiles.value.size},"sales":${sales.value.size},"customers":${customers.value.size},"staff":${staff.value.size}}"""
        viewModelScope.launch {
            repository.respondToDataAccessRequest(req, approve, summary)
                .onSuccess { notify(if (approve) "Data Access Approved" else "Data Access Revoked Immediately") }
                .onFailure { notify("Failed: ${it.localizedMessage}") }
        }
    }

    fun runManualBackup() {
        val shop = activeShop.value ?: return
        viewModelScope.launch {
            repository.createShopBackup(
                shop = shop,
                tiles = tiles.value,
                customers = customers.value,
                suppliers = suppliers.value,
                sales = sales.value,
                staff = staff.value,
                expenses = expenses.value,
                backupType = "MANUAL"
            ).onSuccess { rec ->
                notify("Verified backup created (${rec.recordCount} records, ${rec.imageRefCount} images)")
            }.onFailure {
                notify("Backup failed: ${it.localizedMessage}")
            }
        }
    }
}
