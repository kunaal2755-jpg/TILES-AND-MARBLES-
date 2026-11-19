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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class ShopRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun <T> observeSubcollection(
        shopId: String,
        subcollection: String,
        clazz: Class<T>
    ): Flow<List<T>> = flow {
        val uid = requireUserId()
        val path = "shops/$shopId/$subcollection"
        emitAll(
            db.collection("shops").document(shopId).collection(subcollection)
                .whereEqualTo("ownerUid", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(clazz, ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is CancellationException && error.cause == null) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeOwnedShops(): Flow<List<Shop>> = flow {
        val uid = requireUserId()
        val path = "shops"
        emitAll(
            db.collection("shops")
                .whereEqualTo("ownerUid", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(Shop::class.java, ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    if (error is CancellationException && error.cause == null) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observePlatformConfig(): Flow<PlatformConfig?> =
        db.collection("platform_config").document("global")
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(PlatformConfig::class.java, ServerTimestampBehavior.ESTIMATE)
                } else {
                    PlatformConfig()
                }
            }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                handleFirestoreError(error, OperationType.GET, "platform_config/global")
                emit(PlatformConfig())
            }

    fun observeTileSizes(shopId: String) = observeSubcollection(shopId, "tile_sizes", TileSizeConfig::class.java)
    fun observeLocations(shopId: String) = observeSubcollection(shopId, "locations", StockLocation::class.java)
    fun observeTiles(shopId: String) = observeSubcollection(shopId, "tiles", TileProduct::class.java)
        .map { list -> list.filter { !it.isDeleted } }
    fun observeDeletedTiles(shopId: String) = observeSubcollection(shopId, "tiles", TileProduct::class.java)
        .map { list -> list.filter { it.isDeleted } }
    fun observeStockMovements(shopId: String) = observeSubcollection(shopId, "stock_movements", StockMovement::class.java)
    fun observeSuppliers(shopId: String) = observeSubcollection(shopId, "suppliers", Supplier::class.java)
    fun observePurchases(shopId: String) = observeSubcollection(shopId, "purchases", PurchaseBill::class.java)
    fun observeCustomers(shopId: String) = observeSubcollection(shopId, "customers", Customer::class.java)
    fun observeSales(shopId: String) = observeSubcollection(shopId, "sales", SaleOrder::class.java)
        .map { list -> list.filter { !it.isDeleted } }
    fun observeDeliveries(shopId: String) = observeSubcollection(shopId, "deliveries", DeliveryRecord::class.java)
    fun observeReturns(shopId: String) = observeSubcollection(shopId, "returns", ReturnExchange::class.java)
    fun observeStaff(shopId: String) = observeSubcollection(shopId, "staff", StaffMember::class.java)
    fun observeAttendance(shopId: String) = observeSubcollection(shopId, "attendance", AttendanceRecord::class.java)
    fun observeAdvances(shopId: String) = observeSubcollection(shopId, "advances", StaffAdvance::class.java)
    fun observeSalaryMonths(shopId: String) = observeSubcollection(shopId, "salary_months", SalaryMonthRecord::class.java)
    fun observeExpenses(shopId: String) = observeSubcollection(shopId, "expenses", ExpenseEntry::class.java)
    fun observeActivityLogs(shopId: String) = observeSubcollection(shopId, "activity_logs", ActivityLog::class.java)

    fun observeShopDataAccessRequests(): Flow<List<DataAccessRequest>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("data_access_requests")
                .whereEqualTo("ownerUid", uid)
                .snapshots()
                .map { it.toObjects(DataAccessRequest::class.java, ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    if (error is CancellationException && error.cause == null) throw error
                    handleFirestoreError(error, OperationType.LIST, "data_access_requests")
                    throw error
                }
        )
    }

    fun observeShopBackups(): Flow<List<BackupRecord>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("backups")
                .whereEqualTo("ownerUid", uid)
                .snapshots()
                .map { it.toObjects(BackupRecord::class.java, ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    if (error is CancellationException && error.cause == null) throw error
                    handleFirestoreError(error, OperationType.LIST, "backups")
                    throw error
                }
        )
    }

    suspend fun createShopWithDefaults(
        shopName: String,
        ownerName: String,
        mobile: String,
        address: String,
        gstNumber: String,
        passwordPlain: String,
        secretCodePlain: String,
        deviceName: String,
        requiresApproval: Boolean,
        trialDays: Long,
        customShopCode: String = ""
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val email = auth.currentUser?.email ?: ""
        val shopId = "shop_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val shopCode = if (customShopCode.isNotBlank()) {
            ValidationUtils.normalizeCode(customShopCode)
        } else {
            "TV-${(1000..9999).random()}"
        }
        val dirRef = db.collection("shop_directory").document(shopCode)
        if (dirRef.get().await().exists()) {
            throw IllegalArgumentException("Shop ID '$shopCode' already exists. Please pick another Shop ID.")
        }

        val now = System.currentTimeMillis()
        val expiry = now + (trialDays.coerceAtLeast(1L) * 86_400_000L)
        val passHash = SecurityUtils.sha256(passwordPlain)

        val shopRef = db.collection("shops").document(shopId)
        val batch = db.batch()

        val shop = Shop(
            ownerUid = uid,
            shopCode = shopCode,
            shopName = shopName.trim(),
            ownerName = ownerName.trim(),
            mobile = mobile.trim(),
            email = email,
            address = address.trim(),
            gstNumber = gstNumber.trim(),
            passwordHash = passHash,
            secretCodeHash = SecurityUtils.sha256(secretCodePlain.ifBlank { "1234" }),
            status = if (requiresApproval) "pending" else "active",
            subscriptionPlan = "trial",
            subscriptionStatus = "trial",
            subscriptionStartMillis = now,
            subscriptionExpiryMillis = expiry,
            graceDays = 7L,
            activeDevices = listOf(deviceName),
            claimedByOwner = true,
            createdAt = null,
            updatedAt = null
        )
        batch.set(shopRef, shop)
        batch.set(
            dirRef,
            ShopDirectoryEntry(
                shopId = shopId,
                shopCode = shopCode,
                shopName = shopName.trim(),
                ownerUid = uid,
                linkedEmailHint = SecurityUtils.maskEmail(email),
                mobile = mobile.trim(),
                passwordHash = passHash,
                isMasterAdmin = false,
                claimedByOwner = true,
                status = shop.status
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
            val ref = shopRef.collection("tile_sizes").document("sz_${idx + 1}")
            batch.set(ref, sz)
        }

        val defaultLocations = listOf(
            StockLocation(ownerUid = uid, shopId = shopId, name = "Main Shop", address = address.trim(), isPrimary = true),
            StockLocation(ownerUid = uid, shopId = shopId, name = "Warehouse 1", address = "Godown A", isPrimary = false),
            StockLocation(ownerUid = uid, shopId = shopId, name = "Warehouse 2", address = "Godown B", isPrimary = false)
        )
        defaultLocations.forEachIndexed { idx, loc ->
            val ref = shopRef.collection("locations").document("loc_${idx + 1}")
            batch.set(ref, loc)
        }

        val logRef = shopRef.collection("activity_logs").document(UUID.randomUUID().toString())
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Shop",
                action = "Shop Created",
                details = "Shop $shopCode registered on $deviceName",
                actorName = ownerName
            )
        )

        try {
            batch.commit().await()
            shopId
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, shopRef.path)
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }
    }

    suspend fun verifyAndSyncShopByCredentials(
        shopCodeRaw: String,
        passwordPlain: String,
        deviceName: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val email = auth.currentUser?.email ?: ""
        val code = ValidationUtils.normalizeCode(shopCodeRaw)
        ValidationUtils.validateShopOrAdminId(code)?.let { throw IllegalArgumentException(it) }
        if (passwordPlain.isBlank()) throw IllegalArgumentException("Password is required / पासवर्ड डालना ज़रूरी है")

        val dirRef = db.collection("shop_directory").document(code)
        val snap = try {
            runCatching { dirRef.get(Source.SERVER).await() }.getOrElse { serverErr ->
                if (NetworkUtils.isNetworkOrServerUnavailable(serverErr)) throw serverErr
                dirRef.get().await()
            }
        } catch (e: Exception) {
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }

        if (!snap.exists()) {
            throw IllegalArgumentException("Shop ID '$code' not found. Please check your ID or contact Master Admin.\nयह Shop ID '$code' मौजूद नहीं है।")
        }
        val entry = snap.toObject(ShopDirectoryEntry::class.java, ServerTimestampBehavior.ESTIMATE)
            ?: throw IllegalStateException("Invalid shop record.")

        if (entry.status in listOf("blocked", "suspended", "archived", "pending")) {
            throw IllegalStateException("This shop account is currently ${entry.status}. Contact Master Admin.\nयह दुकान अकाउंट अभी ${entry.status} है।")
        }

        val inputHash = SecurityUtils.sha256(passwordPlain)
        if (entry.passwordHash.isNotBlank() && inputHash != entry.passwordHash) {
            throw IllegalArgumentException("Incorrect Password for Shop ID '$code'.\nShop ID '$code' का पासवर्ड गलत है।")
        }

        val shopRef = db.collection("shops").document(entry.shopId)

        if (!entry.claimedByOwner) {
            // First login by the assigned shopkeeper -> bind this Google UID & seed default sizes/locations
            val claimBatch = db.batch()
            claimBatch.update(
                shopRef,
                mapOf(
                    "ownerUid" to uid,
                    "claimedByOwner" to true,
                    "lastClaimProof" to inputHash,
                    "activeDevices" to listOf(deviceName),
                    "email" to email,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            claimBatch.update(
                dirRef,
                mapOf(
                    "ownerUid" to uid,
                    "linkedEmailHint" to SecurityUtils.maskEmail(email),
                    "claimedByOwner" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            claimBatch.commit().await()

            // Seed default tile sizes and locations for the newly claimed shop
            val seedBatch = db.batch()
            val defaultSizes = listOf(
                TileSizeConfig(ownerUid = uid, shopId = entry.shopId, name = "12x18", piecesPerBox = 6, widthInches = 12.0, heightInches = 18.0, pdfImageWidth = 96, pdfImageHeight = 144, displayRatio = "2:3", enabled = true),
                TileSizeConfig(ownerUid = uid, shopId = entry.shopId, name = "2x4", piecesPerBox = 2, widthInches = 24.0, heightInches = 48.0, pdfImageWidth = 90, pdfImageHeight = 180, displayRatio = "1:2", enabled = true),
                TileSizeConfig(ownerUid = uid, shopId = entry.shopId, name = "2x2", piecesPerBox = 4, widthInches = 24.0, heightInches = 24.0, pdfImageWidth = 120, pdfImageHeight = 120, displayRatio = "1:1", enabled = true),
                TileSizeConfig(ownerUid = uid, shopId = entry.shopId, name = "16x16", piecesPerBox = 5, widthInches = 16.0, heightInches = 16.0, pdfImageWidth = 110, pdfImageHeight = 110, displayRatio = "1:1", enabled = true),
                TileSizeConfig(ownerUid = uid, shopId = entry.shopId, name = "8x12", piecesPerBox = 12, widthInches = 8.0, heightInches = 12.0, pdfImageWidth = 88, pdfImageHeight = 132, displayRatio = "2:3", enabled = true)
            )
            defaultSizes.forEachIndexed { idx, sz ->
                seedBatch.set(shopRef.collection("tile_sizes").document("sz_${idx + 1}"), sz)
            }
            val defaultLocations = listOf(
                StockLocation(ownerUid = uid, shopId = entry.shopId, name = "Main Shop", isPrimary = true),
                StockLocation(ownerUid = uid, shopId = entry.shopId, name = "Warehouse 1", address = "Godown A", isPrimary = false),
                StockLocation(ownerUid = uid, shopId = entry.shopId, name = "Warehouse 2", address = "Godown B", isPrimary = false)
            )
            defaultLocations.forEachIndexed { idx, loc ->
                seedBatch.set(shopRef.collection("locations").document("loc_${idx + 1}"), loc)
            }
            seedBatch.commit().await()
            return@runCatching entry.shopId
        }

        if (entry.ownerUid != uid) {
            val hint = entry.linkedEmailHint.ifBlank { "registered owner account" }
            throw IllegalStateException(
                "Shop ID '$code' is synced with Google account ($hint). Sign in with that Google account on this device to sync all cloud records."
            )
        }

        // Same Google owner signing in on another mobile phone -> register device & return shopId
        val shopSnap = shopRef.get().await().toObject(Shop::class.java, ServerTimestampBehavior.ESTIMATE)
        if (shopSnap != null && deviceName !in shopSnap.activeDevices) {
            shopRef.update(
                mapOf(
                    "activeDevices" to (shopSnap.activeDevices + deviceName).distinct().takeLast(20),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        entry.shopId
    }

    suspend fun updateShop(shop: Shop): Result<Unit> = runCatching {
        requireUserId()
        require(shop.id.isNotBlank())
        requireNotNull(shop.createdAt)
        val ref = db.collection("shops").document(shop.id)
        val batch = db.batch()
        batch.set(ref, shop.copy(updatedAt = null))
        val code = ValidationUtils.normalizeCode(shop.shopCode)
        if (code.isNotBlank()) {
            val dirRef = db.collection("shop_directory").document(code)
            val dirSnap = dirRef.get().await()
            if (dirSnap.exists()) {
                batch.update(
                    dirRef,
                    mapOf(
                        "shopName" to shop.shopName,
                        "mobile" to shop.mobile,
                        "passwordHash" to shop.passwordHash,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw IllegalStateException(NetworkUtils.mapCloudError(e), e)
        }
    }

    suspend fun saveTileSize(shopId: String, size: TileSizeConfig): Result<Unit> = runCatching {
        val uid = requireUserId()
        val col = db.collection("shops").document(shopId).collection("tile_sizes")
        val ref = if (size.id.isBlank()) col.document() else col.document(size.id)
        val payload = if (size.id.isBlank()) {
            size.copy(ownerUid = uid, shopId = shopId, createdAt = null, updatedAt = null)
        } else {
            requireNotNull(size.createdAt)
            size.copy(ownerUid = uid, shopId = shopId, updatedAt = null)
        }
        try {
            ref.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun saveLocation(shopId: String, location: StockLocation): Result<Unit> = runCatching {
        val uid = requireUserId()
        val col = db.collection("shops").document(shopId).collection("locations")
        val ref = if (location.id.isBlank()) col.document() else col.document(location.id)
        val payload = if (location.id.isBlank()) {
            location.copy(ownerUid = uid, shopId = shopId, createdAt = null, updatedAt = null)
        } else {
            requireNotNull(location.createdAt)
            location.copy(ownerUid = uid, shopId = shopId, updatedAt = null)
        }
        try {
            ref.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun renameLocation(
        shopId: String,
        location: StockLocation,
        newNameRaw: String,
        newAddressRaw: String,
        tiles: List<TileProduct>,
        actorName: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val newName = newNameRaw.trim()
        val newAddress = newAddressRaw.trim()
        require(location.id.isNotBlank() && location.createdAt != null) { "Invalid warehouse record." }
        require(newName.isNotBlank()) { "Warehouse name cannot be empty." }

        val oldName = location.name
        val batch = db.batch()
        val locRef = db.collection("shops").document(shopId).collection("locations").document(location.id)
        batch.set(
            locRef,
            location.copy(
                ownerUid = uid,
                shopId = shopId,
                name = newName,
                address = newAddress,
                updatedAt = null
            )
        )

        var migratedCount = 0
        if (oldName != newName) {
            tiles.forEach { tile ->
                if (tile.createdAt != null && tile.locationBoxes.containsKey(oldName)) {
                    val locMap = tile.locationBoxes.toMutableMap()
                    val movedBoxes = locMap.remove(oldName) ?: 0L
                    locMap[newName] = (locMap[newName] ?: 0L) + movedBoxes
                    val tileRef = db.collection("shops").document(shopId).collection("tiles").document(tile.id)
                    batch.set(
                        tileRef,
                        tile.copy(
                            locationBoxes = locMap,
                            totalBoxes = locMap.values.sum(),
                            updatedAt = null
                        )
                    )
                    migratedCount++
                }
            }
        }

        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Inventory",
                action = "Renamed Warehouse",
                details = "'$oldName' -> '$newName' ($migratedCount tiles updated)",
                actorName = actorName
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, locRef.path)
            throw e
        }
    }

    suspend fun deleteLocationWithMigration(
        shopId: String,
        locationToDelete: StockLocation,
        targetLocationNameRaw: String,
        createNewLocation: Boolean,
        newLocationAddress: String,
        tiles: List<TileProduct>,
        actorName: String
    ): Result<Int> = runCatching {
        val uid = requireUserId()
        require(locationToDelete.id.isNotBlank()) { "Invalid warehouse to delete." }
        val oldName = locationToDelete.name
        val targetName = targetLocationNameRaw.trim()

        val affectedTiles = tiles.filter {
            it.createdAt != null && ((it.locationBoxes[oldName] ?: 0L) > 0L || it.locationBoxes.containsKey(oldName))
        }
        val hasPositiveStock = affectedTiles.any { (it.locationBoxes[oldName] ?: 0L) > 0L }
        if (hasPositiveStock) {
            require(targetName.isNotBlank() && !targetName.equals(oldName, ignoreCase = true)) {
                "Select an existing warehouse or create a new warehouse to move tiles before deleting '$oldName'."
            }
        }

        val batch = db.batch()
        if (createNewLocation && targetName.isNotBlank()) {
            val newLocRef = db.collection("shops").document(shopId).collection("locations").document()
            batch.set(
                newLocRef,
                StockLocation(
                    ownerUid = uid,
                    shopId = shopId,
                    name = targetName,
                    address = newLocationAddress.trim(),
                    isPrimary = false,
                    createdAt = null,
                    updatedAt = null
                )
            )
        }

        var movedTilesCount = 0
        affectedTiles.forEach { tile ->
            val locMap = tile.locationBoxes.toMutableMap()
            val movedBoxes = locMap.remove(oldName) ?: 0L
            if (movedBoxes > 0L && targetName.isNotBlank()) {
                locMap[targetName] = (locMap[targetName] ?: 0L) + movedBoxes
                movedTilesCount++
                val moveRef = db.collection("shops").document(shopId).collection("stock_movements").document()
                batch.set(
                    moveRef,
                    StockMovement(
                        ownerUid = uid,
                        shopId = shopId,
                        tileId = tile.id,
                        tileName = tile.name,
                        movementType = "TRANSFER",
                        fromLocation = oldName,
                        toLocation = targetName,
                        boxesDelta = movedBoxes,
                        piecesDelta = 0L,
                        reason = "Warehouse '$oldName' deleted -> moved to '$targetName'",
                        performedBy = actorName
                    )
                )
            }
            val tileRef = db.collection("shops").document(shopId).collection("tiles").document(tile.id)
            batch.set(
                tileRef,
                tile.copy(
                    locationBoxes = locMap,
                    totalBoxes = locMap.values.sum(),
                    updatedAt = null
                )
            )
        }

        val deleteRef = db.collection("shops").document(shopId).collection("locations").document(locationToDelete.id)
        batch.delete(deleteRef)

        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Inventory",
                action = "Deleted Warehouse",
                details = if (movedTilesCount > 0) {
                    "Deleted '$oldName' after migrating $movedTilesCount tiles to '$targetName'"
                } else {
                    "Deleted empty warehouse '$oldName'"
                },
                actorName = actorName
            )
        )

        try {
            batch.commit().await()
            movedTilesCount
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, deleteRef.path)
            throw e
        }
    }

    suspend fun saveTileProduct(shopId: String, tile: TileProduct, actorName: String): Result<String> = runCatching {
        val uid = requireUserId()
        val col = db.collection("shops").document(shopId).collection("tiles")
        val isNew = tile.id.isBlank()
        val ref = if (isNew) col.document() else col.document(tile.id)
        val combinedBoxes = if (tile.locationBoxes.isNotEmpty()) tile.locationBoxes.values.sum() else tile.totalBoxes
        val payload = if (isNew) {
            tile.copy(ownerUid = uid, shopId = shopId, totalBoxes = combinedBoxes, createdAt = null, updatedAt = null)
        } else {
            requireNotNull(tile.createdAt)
            tile.copy(ownerUid = uid, shopId = shopId, totalBoxes = combinedBoxes, updatedAt = null)
        }
        val batch = db.batch()
        batch.set(ref, payload)
        if (isNew && combinedBoxes > 0) {
            val moveRef = db.collection("shops").document(shopId).collection("stock_movements").document()
            batch.set(
                moveRef,
                StockMovement(
                    ownerUid = uid,
                    shopId = shopId,
                    tileId = ref.id,
                    tileName = tile.name,
                    movementType = "STOCK_IN",
                    toLocation = tile.locationBoxes.keys.firstOrNull() ?: "Main Shop",
                    boxesDelta = combinedBoxes,
                    piecesDelta = tile.loosePieces,
                    reason = "Initial opening stock",
                    performedBy = actorName
                )
            )
        }
        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Inventory",
                action = if (isNew) "Added Tile" else "Updated Tile",
                details = "${tile.code} - ${tile.name}",
                actorName = actorName
            )
        )
        try {
            batch.commit().await()
            ref.id
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun softDeleteTile(shopId: String, tile: TileProduct, actorName: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(tile.id.isNotBlank())
        requireNotNull(tile.createdAt)
        val ref = db.collection("shops").document(shopId).collection("tiles").document(tile.id)
        val batch = db.batch()
        batch.set(ref, tile.copy(isDeleted = true, updatedAt = null))
        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Inventory",
                action = "Soft Deleted Tile",
                details = "${tile.code} - ${tile.name}",
                actorName = actorName
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    suspend fun adjustTileStock(
        shopId: String,
        tile: TileProduct,
        movementType: String,
        fromLocation: String,
        toLocation: String,
        boxesQty: Long,
        piecesQty: Long,
        reason: String,
        actorName: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(tile.id.isNotBlank())
        requireNotNull(tile.createdAt)

        val locMap = tile.locationBoxes.toMutableMap()
        var loose = tile.loosePieces
        var damaged = tile.damagedPieces
        var sample = tile.samplePieces

        when (movementType) {
            "STOCK_IN" -> {
                val target = toLocation.ifBlank { "Main Shop" }
                locMap[target] = (locMap[target] ?: 0L) + boxesQty
                loose += piecesQty
            }
            "STOCK_OUT" -> {
                val src = fromLocation.ifBlank { "Main Shop" }
                locMap[src] = ((locMap[src] ?: 0L) - boxesQty).coerceAtLeast(0L)
                loose = (loose - piecesQty).coerceAtLeast(0L)
            }
            "TRANSFER" -> {
                val src = fromLocation.ifBlank { "Main Shop" }
                val dst = toLocation.ifBlank { "Warehouse 1" }
                val currentSrc = locMap[src] ?: 0L
                val transfer = boxesQty.coerceAtMost(currentSrc)
                locMap[src] = currentSrc - transfer
                locMap[dst] = (locMap[dst] ?: 0L) + transfer
            }
            "DAMAGE" -> {
                val src = fromLocation.ifBlank { "Main Shop" }
                locMap[src] = ((locMap[src] ?: 0L) - boxesQty).coerceAtLeast(0L)
                damaged += (boxesQty * tile.piecesPerBox) + piecesQty
            }
            "SAMPLE" -> {
                val src = fromLocation.ifBlank { "Main Shop" }
                locMap[src] = ((locMap[src] ?: 0L) - boxesQty).coerceAtLeast(0L)
                sample += (boxesQty * tile.piecesPerBox) + piecesQty
            }
            "CORRECTION" -> {
                val target = toLocation.ifBlank { "Main Shop" }
                locMap[target] = boxesQty.coerceAtLeast(0L)
                loose = piecesQty.coerceAtLeast(0L)
            }
        }

        val updatedTotalBoxes = locMap.values.sum()
        val ref = db.collection("shops").document(shopId).collection("tiles").document(tile.id)
        val moveRef = db.collection("shops").document(shopId).collection("stock_movements").document()
        val batch = db.batch()
        batch.set(
            ref,
            tile.copy(
                locationBoxes = locMap,
                totalBoxes = updatedTotalBoxes,
                loosePieces = loose,
                damagedPieces = damaged,
                samplePieces = sample,
                updatedAt = null
            )
        )
        batch.set(
            moveRef,
            StockMovement(
                ownerUid = uid,
                shopId = shopId,
                tileId = tile.id,
                tileName = tile.name,
                movementType = movementType,
                fromLocation = fromLocation,
                toLocation = toLocation,
                boxesDelta = boxesQty,
                piecesDelta = piecesQty,
                reason = reason,
                performedBy = actorName
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    suspend fun saveSupplier(shopId: String, supplier: Supplier): Result<Unit> = runCatching {
        val uid = requireUserId()
        val col = db.collection("shops").document(shopId).collection("suppliers")
        val ref = if (supplier.id.isBlank()) col.document() else col.document(supplier.id)
        val payload = if (supplier.id.isBlank()) {
            supplier.copy(
                ownerUid = uid,
                shopId = shopId,
                outstandingBalance = supplier.previousBalance,
                createdAt = null,
                updatedAt = null
            )
        } else {
            requireNotNull(supplier.createdAt)
            supplier.copy(ownerUid = uid, shopId = shopId, updatedAt = null)
        }
        try {
            ref.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun recordPurchaseBill(
        shopId: String,
        bill: PurchaseBill,
        supplier: Supplier?,
        tilesMap: Map<String, TileProduct>,
        actorName: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val batch = db.batch()
        val billRef = db.collection("shops").document(shopId).collection("purchases").document()
        batch.set(billRef, bill.copy(ownerUid = uid, shopId = shopId, createdAt = null, updatedAt = null))

        // Update tile inventory
        val sign = if (bill.isReturn) -1L else 1L
        bill.items.forEach { item ->
            val tile = tilesMap[item.tileId]
            if (tile != null && tile.createdAt != null) {
                val locMap = tile.locationBoxes.toMutableMap()
                val primaryLoc = locMap.keys.firstOrNull() ?: "Main Shop"
                locMap[primaryLoc] = ((locMap[primaryLoc] ?: 0L) + sign * item.boxes).coerceAtLeast(0L)
                val newLoose = (tile.loosePieces + sign * item.loosePieces).coerceAtLeast(0L)
                val tileRef = db.collection("shops").document(shopId).collection("tiles").document(tile.id)
                batch.set(
                    tileRef,
                    tile.copy(
                        locationBoxes = locMap,
                        totalBoxes = locMap.values.sum(),
                        loosePieces = newLoose,
                        purchaseCost = if (!bill.isReturn && item.purchaseRatePerBox > 0) item.purchaseRatePerBox else tile.purchaseCost,
                        updatedAt = null
                    )
                )
                val moveRef = db.collection("shops").document(shopId).collection("stock_movements").document()
                batch.set(
                    moveRef,
                    StockMovement(
                        ownerUid = uid,
                        shopId = shopId,
                        tileId = tile.id,
                        tileName = tile.name,
                        movementType = if (bill.isReturn) "STOCK_OUT" else "STOCK_IN",
                        toLocation = primaryLoc,
                        boxesDelta = item.boxes,
                        piecesDelta = item.loosePieces,
                        reason = "${if (bill.isReturn) "Purchase Return" else "Purchase"} #${bill.invoiceNumber}",
                        performedBy = actorName
                    )
                )
            }
        }

        // Update supplier ledger
        if (supplier != null && supplier.createdAt != null) {
            val sRef = db.collection("shops").document(shopId).collection("suppliers").document(supplier.id)
            val deltaPurchase = if (bill.isReturn) -bill.totalAmount else bill.totalAmount
            val newPurchases = supplier.totalPurchases + deltaPurchase
            val newPaid = supplier.totalPaid + bill.amountPaid
            val newOut = supplier.previousBalance + newPurchases - newPaid
            batch.set(
                sRef,
                supplier.copy(
                    totalPurchases = newPurchases,
                    totalPaid = newPaid,
                    outstandingBalance = newOut,
                    updatedAt = null
                )
            )
        }

        // Record loading/transport charges as expense if > 0
        val logistics = bill.loadingCharges + bill.transportCharges
        if (logistics > 0.0) {
            val expRef = db.collection("shops").document(shopId).collection("expenses").document()
            batch.set(
                expRef,
                ExpenseEntry(
                    ownerUid = uid,
                    shopId = shopId,
                    dateMillis = bill.purchaseDateMillis,
                    category = if (bill.loadingCharges >= bill.transportCharges) "Loading" else "Transport",
                    amount = logistics,
                    paymentMode = bill.paymentMode,
                    description = "Purchase #${bill.invoiceNumber} Loading & Transport",
                    relatedInvoiceNumber = bill.invoiceNumber
                )
            )
        }

        try {
            batch.commit().await()
            billRef.id
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, billRef.path)
            throw e
        }
    }

    suspend fun saveCustomer(shopId: String, customer: Customer): Result<String> = runCatching {
        val uid = requireUserId()
        val col = db.collection("shops").document(shopId).collection("customers")
        val isNew = customer.id.isBlank()
        val ref = if (isNew) col.document() else col.document(customer.id)
        val payload = if (isNew) {
            customer.copy(
                ownerUid = uid,
                shopId = shopId,
                outstandingBalance = customer.previousBalance,
                createdAt = null,
                updatedAt = null
            )
        } else {
            requireNotNull(customer.createdAt)
            customer.copy(ownerUid = uid, shopId = shopId, updatedAt = null)
        }
        try {
            ref.set(payload).await()
            ref.id
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun recordCustomerPayment(
        shopId: String,
        customer: Customer,
        amount: Double,
        saleToCredit: SaleOrder? = null,
        paymentMode: String = "Cash"
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(customer.id.isNotBlank() && customer.createdAt != null)
        val now = System.currentTimeMillis()
        val batch = db.batch()
        val cRef = db.collection("shops").document(shopId).collection("customers").document(customer.id)
        val newPaid = customer.totalPayments + amount
        val newOut = (customer.previousBalance + customer.totalPurchases - newPaid)
        batch.set(cRef, customer.copy(totalPayments = newPaid, outstandingBalance = newOut, updatedAt = null))

        if (saleToCredit != null && saleToCredit.id.isNotBlank() && saleToCredit.createdAt != null) {
            val sRef = db.collection("shops").document(shopId).collection("sales").document(saleToCredit.id)
            val newRec = saleToCredit.receivedAmount + amount
            val newRem = (saleToCredit.totalPayable - newRec).coerceAtLeast(0.0)
            val pStatus = when {
                newRem <= 0.01 -> "PAID"
                newRec > 0.0 -> "PARTIAL"
                else -> "UNPAID"
            }
            val updatedHistory = buildList {
                if (saleToCredit.paymentHistory.isEmpty() && saleToCredit.receivedAmount > 0.0) {
                    add(
                        SalePaymentEntry(
                            dateMillis = saleToCredit.paymentDateMillis,
                            amount = saleToCredit.receivedAmount,
                            mode = saleToCredit.paymentMode.ifBlank { "Cash" },
                            note = "Initial payment"
                        )
                    )
                }
                addAll(saleToCredit.paymentHistory)
                add(
                    SalePaymentEntry(
                        dateMillis = now,
                        amount = amount,
                        mode = paymentMode,
                        note = "Customer payment"
                    )
                )
            }
            batch.set(
                sRef,
                saleToCredit.copy(
                    receivedAmount = newRec,
                    remainingBalance = newRem,
                    paymentStatus = pStatus,
                    paymentMode = paymentMode,
                    paymentDateMillis = now,
                    paymentHistory = updatedHistory,
                    updatedAt = null
                )
            )
        }

        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Customer",
                action = "Payment Received",
                details = "${customer.name}: ${Formatters.currency(amount)} ($paymentMode)",
                actorName = "Owner"
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, cRef.path)
            throw e
        }
    }

    suspend fun recordSalePayment(
        shopId: String,
        sale: SaleOrder,
        customer: Customer?,
        amount: Double,
        paymentMode: String,
        note: String = "",
        paymentDateMillis: Long = System.currentTimeMillis(),
        actorName: String = "Owner"
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(sale.id.isNotBlank() && sale.createdAt != null) { "Invalid sale record." }
        require(amount > 0.0) { "Payment amount must be greater than zero." }

        val batch = db.batch()
        val sRef = db.collection("shops").document(shopId).collection("sales").document(sale.id)
        val newRec = sale.receivedAmount + amount
        val newRem = (sale.totalPayable - newRec).coerceAtLeast(0.0)
        val pStatus = when {
            newRem <= 0.01 -> "PAID"
            newRec > 0.0 -> "PARTIAL"
            else -> "UNPAID"
        }
        val updatedHistory = buildList {
            if (sale.paymentHistory.isEmpty() && sale.receivedAmount > 0.0) {
                add(
                    SalePaymentEntry(
                        dateMillis = sale.paymentDateMillis,
                        amount = sale.receivedAmount,
                        mode = sale.paymentMode.ifBlank { "Cash" },
                        note = "Initial payment"
                    )
                )
            }
            addAll(sale.paymentHistory)
            add(
                SalePaymentEntry(
                    dateMillis = paymentDateMillis,
                    amount = amount,
                    mode = paymentMode.ifBlank { "Cash" },
                    note = note.trim().ifBlank { "Installment received" }
                )
            )
        }
        batch.set(
            sRef,
            sale.copy(
                receivedAmount = newRec,
                remainingBalance = newRem,
                paymentStatus = pStatus,
                paymentMode = paymentMode,
                paymentDateMillis = paymentDateMillis,
                paymentHistory = updatedHistory,
                updatedAt = null
            )
        )

        if (customer != null && customer.id.isNotBlank() && customer.createdAt != null) {
            val cRef = db.collection("shops").document(shopId).collection("customers").document(customer.id)
            val newPaid = customer.totalPayments + amount
            val newOut = customer.previousBalance + customer.totalPurchases - newPaid
            batch.set(
                cRef,
                customer.copy(
                    totalPayments = newPaid,
                    outstandingBalance = newOut,
                    updatedAt = null
                )
            )
        }

        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Sales",
                action = "Installment Payment",
                details = "#${sale.invoiceNumber} (${sale.customerName}): ${Formatters.currency(amount)} via $paymentMode",
                actorName = actorName
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, sRef.path)
            throw e
        }
    }

    suspend fun saveSaleOrder(
        shopId: String,
        sale: SaleOrder,
        existingCustomer: Customer?,
        tilesMap: Map<String, TileProduct>,
        previousOrderStatus: String?,
        actorName: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val batch = db.batch()
        val isNew = sale.id.isBlank()
        val col = db.collection("shops").document(shopId).collection("sales")
        val saleRef = if (isNew) col.document() else col.document(sale.id)

        // Ensure customer record exists if customerName is provided
        var resolvedCustomerId = sale.customerId
        var custToUpdate = existingCustomer
        if (resolvedCustomerId.isBlank() && sale.customerName.isNotBlank()) {
            val custRef = db.collection("shops").document(shopId).collection("customers").document()
            resolvedCustomerId = custRef.id
            val newCust = Customer(
                ownerUid = uid,
                shopId = shopId,
                name = sale.customerName,
                mobile = sale.customerMobile.ifBlank { "0000000000" },
                address = sale.deliveryAddress,
                totalPurchases = if (sale.orderStatus == "CONFIRMED") sale.totalPayable else 0.0,
                totalPayments = sale.receivedAmount,
                outstandingBalance = if (sale.orderStatus == "CONFIRMED") sale.remainingBalance else -sale.receivedAmount
            )
            batch.set(custRef, newCust)
            custToUpdate = null
        }

        // Build chronological payment history entries
        val resolvedHistory: List<SalePaymentEntry> = when {
            isNew && sale.receivedAmount > 0.0 && sale.paymentHistory.isEmpty() -> {
                listOf(
                    SalePaymentEntry(
                        dateMillis = sale.paymentDateMillis,
                        amount = sale.receivedAmount,
                        mode = sale.paymentMode.ifBlank { "Cash" },
                        note = "Initial payment"
                    )
                )
            }
            !isNew && sale.receivedAmount > 0.0 -> {
                val historySum = sale.paymentHistory.sumOf { it.amount }
                val delta = sale.receivedAmount - historySum
                if (delta > 0.01) {
                    sale.paymentHistory + SalePaymentEntry(
                        dateMillis = System.currentTimeMillis(),
                        amount = delta,
                        mode = sale.paymentMode.ifBlank { "Cash" },
                        note = if (sale.paymentHistory.isEmpty()) "Initial payment" else "Payment received"
                    )
                } else {
                    sale.paymentHistory
                }
            }
            else -> sale.paymentHistory
        }

        // Deduct inventory ONLY when transitioning into CONFIRMED (never on PENDING!)
        val newlyConfirmed = sale.orderStatus == "CONFIRMED" && previousOrderStatus != "CONFIRMED"
        val workingTiles = tilesMap.toMutableMap()
        val updatedItems: List<SaleLineItem> = if (newlyConfirmed) {
            sale.items.map { item ->
                if (item.isManualItem || item.tileId.isBlank()) {
                    item
                } else {
                    val tile = workingTiles[item.tileId]
                    if (tile != null && tile.createdAt != null) {
                        val totalAvailablePcs = (tile.totalBoxes * tile.piecesPerBox) + tile.loosePieces
                        val deductPcs = item.totalPieces()
                        val remainingPcs = (totalAvailablePcs - deductPcs).coerceAtLeast(0L)
                        val newBoxes = if (tile.piecesPerBox > 0) remainingPcs / tile.piecesPerBox else 0L
                        val newLoose = if (tile.piecesPerBox > 0) remainingPcs % tile.piecesPerBox else 0L

                        val locMap = tile.locationBoxes.toMutableMap()
                        val deductedFrom = linkedMapOf<String, Long>()
                        if (locMap.isNotEmpty()) {
                            val diffBoxes = (tile.totalBoxes - newBoxes).coerceAtLeast(0L)
                            var toDeduct = diffBoxes
                            val orderedKeys = locMap.keys.sortedWith(
                                compareBy<String> { if (it.equals("Main Shop", ignoreCase = true)) 0 else 1 }
                                    .thenBy { it.lowercase() }
                            )
                            for (key in orderedKeys) {
                                if (toDeduct <= 0L) break
                                val cur = locMap[key] ?: 0L
                                val take = toDeduct.coerceAtMost(cur)
                                if (take > 0L) {
                                    locMap[key] = cur - take
                                    deductedFrom[key] = (deductedFrom[key] ?: 0L) + take
                                    toDeduct -= take
                                }
                            }
                        }

                        val updatedTile = tile.copy(
                            locationBoxes = locMap,
                            totalBoxes = newBoxes,
                            loosePieces = newLoose,
                            updatedAt = null
                        )
                        workingTiles[tile.id] = updatedTile

                        val tRef = db.collection("shops").document(shopId).collection("tiles").document(tile.id)
                        batch.set(tRef, updatedTile)

                        val moveRef = db.collection("shops").document(shopId).collection("stock_movements").document()
                        batch.set(
                            moveRef,
                            StockMovement(
                                ownerUid = uid,
                                shopId = shopId,
                                tileId = tile.id,
                                tileName = tile.name,
                                movementType = "STOCK_OUT",
                                fromLocation = deductedFrom.keys.joinToString(", ").ifBlank {
                                    tile.locationBoxes.keys.firstOrNull() ?: "Main Shop"
                                },
                                boxesDelta = item.fullBoxes,
                                piecesDelta = item.loosePieces,
                                reason = "Confirmed Sale #${sale.invoiceNumber}",
                                performedBy = actorName
                            )
                        )
                        item.copy(
                            photoBase64 = item.photoBase64.ifBlank { tile.photoBase64 },
                            deductedLocations = deductedFrom
                        )
                    } else {
                        item
                    }
                }
            }
        } else {
            sale.items
        }

        val finalizedSale = sale.copy(
            ownerUid = uid,
            shopId = shopId,
            customerId = resolvedCustomerId,
            items = updatedItems,
            paymentHistory = resolvedHistory,
            createdAt = if (isNew) null else sale.createdAt,
            updatedAt = null
        )
        batch.set(saleRef, finalizedSale)

        if (newlyConfirmed && custToUpdate != null && custToUpdate.createdAt != null) {
            val cRef = db.collection("shops").document(shopId).collection("customers").document(custToUpdate.id)
            val newPurchases = custToUpdate.totalPurchases + sale.totalPayable
            val newPayments = custToUpdate.totalPayments + sale.receivedAmount
            val newOut = custToUpdate.previousBalance + newPurchases - newPayments
            batch.set(
                cRef,
                custToUpdate.copy(
                    totalPurchases = newPurchases,
                    totalPayments = newPayments,
                    outstandingBalance = newOut,
                    updatedAt = null
                )
            )
        }

        if (sale.loadingCharges > 0.0 && isNew) {
            val expRef = db.collection("shops").document(shopId).collection("expenses").document()
            batch.set(
                expRef,
                ExpenseEntry(
                    ownerUid = uid,
                    shopId = shopId,
                    dateMillis = sale.saleDateMillis,
                    category = "Loading",
                    amount = sale.loadingCharges,
                    paymentMode = sale.paymentMode,
                    description = "Sale #${sale.invoiceNumber} Loading",
                    relatedInvoiceNumber = sale.invoiceNumber
                )
            )
        }

        try {
            batch.commit().await()
            saleRef.id
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, saleRef.path)
            throw e
        }
    }

    suspend fun deleteSaleOrder(
        shopId: String,
        sale: SaleOrder,
        restoreStock: Boolean,
        customer: Customer?,
        tilesMap: Map<String, TileProduct>,
        actorName: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(sale.id.isNotBlank() && sale.createdAt != null) { "Invalid sale record to delete." }

        val batch = db.batch()
        val saleRef = db.collection("shops").document(shopId).collection("sales").document(sale.id)
        batch.set(saleRef, sale.copy(isDeleted = true, updatedAt = null))

        if (sale.orderStatus == "CONFIRMED") {
            if (restoreStock) {
                val workingTiles = tilesMap.toMutableMap()
                sale.items.filter { !it.isManualItem && it.tileId.isNotBlank() }.forEach { item ->
                    val tile = workingTiles[item.tileId]
                    if (tile != null && tile.createdAt != null) {
                        val addPcs = item.totalPieces()
                        val curTotalPcs = (tile.totalBoxes * tile.piecesPerBox) + tile.loosePieces
                        val newTotalPcs = curTotalPcs + addPcs
                        val newBoxes = if (tile.piecesPerBox > 0) newTotalPcs / tile.piecesPerBox else tile.totalBoxes
                        val newLoose = if (tile.piecesPerBox > 0) newTotalPcs % tile.piecesPerBox else tile.loosePieces

                        val boxesToRestore = (newBoxes - tile.totalBoxes).coerceAtLeast(0L)
                        val locMap = tile.locationBoxes.toMutableMap()
                        var remainingBoxes = boxesToRestore
                        val restoredTo = mutableListOf<String>()

                        // Restore to the EXACT warehouse(s) from which boxes were deducted
                        if (item.deductedLocations.isNotEmpty()) {
                            for ((locName, deductedQty) in item.deductedLocations) {
                                if (remainingBoxes <= 0L) break
                                val putBack = deductedQty.coerceAtMost(remainingBoxes)
                                if (putBack > 0L) {
                                    locMap[locName] = (locMap[locName] ?: 0L) + putBack
                                    remainingBoxes -= putBack
                                    restoredTo += locName
                                }
                            }
                        }
                        if (remainingBoxes > 0L) {
                            val fallbackLoc = item.deductedLocations.keys.firstOrNull()
                                ?: locMap.keys.firstOrNull()
                                ?: "Main Shop"
                            locMap[fallbackLoc] = (locMap[fallbackLoc] ?: 0L) + remainingBoxes
                            if (fallbackLoc !in restoredTo) restoredTo += fallbackLoc
                        }

                        val updatedTile = tile.copy(
                            locationBoxes = locMap,
                            totalBoxes = locMap.values.sum().coerceAtLeast(newBoxes),
                            loosePieces = newLoose,
                            updatedAt = null
                        )
                        workingTiles[tile.id] = updatedTile

                        val tRef = db.collection("shops").document(shopId).collection("tiles").document(tile.id)
                        batch.set(tRef, updatedTile)

                        val moveRef = db.collection("shops").document(shopId).collection("stock_movements").document()
                        batch.set(
                            moveRef,
                            StockMovement(
                                ownerUid = uid,
                                shopId = shopId,
                                tileId = tile.id,
                                tileName = tile.name,
                                movementType = "STOCK_IN",
                                toLocation = restoredTo.joinToString(", ").ifBlank { "Main Shop" },
                                boxesDelta = item.fullBoxes,
                                piecesDelta = item.loosePieces,
                                reason = "Sale Deleted (Stock Restored) #${sale.invoiceNumber}",
                                performedBy = actorName
                            )
                        )
                    }
                }
            }

            if (customer != null && customer.id.isNotBlank() && customer.createdAt != null) {
                val cRef = db.collection("shops").document(shopId).collection("customers").document(customer.id)
                val newPurchases = (customer.totalPurchases - sale.totalPayable).coerceAtLeast(0.0)
                val newPayments = (customer.totalPayments - sale.receivedAmount).coerceAtLeast(0.0)
                val newOut = customer.previousBalance + newPurchases - newPayments
                batch.set(
                    cRef,
                    customer.copy(
                        totalPurchases = newPurchases,
                        totalPayments = newPayments,
                        outstandingBalance = newOut,
                        updatedAt = null
                    )
                )
            }
        }

        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Sales",
                action = "Deleted Sale Bill",
                details = "#${sale.invoiceNumber} (${sale.customerName}) • ${if (restoreStock) "Stock Restored" else "No Stock Restore"}",
                actorName = actorName
            )
        )

        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, saleRef.path)
            throw e
        }
    }

    suspend fun recordPartialDelivery(
        shopId: String,
        sale: SaleOrder,
        deliveriesPerIndex: Map<Int, Long>,
        loadingCharges: Double,
        remarks: String
    ): Result<DeliveryRecord> = runCatching {
        val uid = requireUserId()
        require(sale.id.isNotBlank() && sale.createdAt != null)
        val batch = db.batch()
        val delLines = mutableListOf<DeliveryItemLine>()
        val updatedItems = sale.items.mapIndexed { idx, item ->
            val addPcs = (deliveriesPerIndex[idx] ?: 0L).coerceAtLeast(0L)
                .coerceAtMost(item.remainingDeliveryPieces())
            if (addPcs > 0L) {
                val newDelivered = item.deliveredPieces + addPcs
                delLines += DeliveryItemLine(
                    tileId = item.tileId,
                    tileName = item.tileName,
                    purchasedPieces = item.totalPieces(),
                    previouslyDeliveredPieces = item.deliveredPieces,
                    newDeliveredPieces = addPcs,
                    remainingPieces = (item.totalPieces() - newDelivered).coerceAtLeast(0L)
                )
                item.copy(deliveredPieces = newDelivered)
            } else {
                item
            }
        }
        require(delLines.isNotEmpty()) { "Enter at least one delivered quantity." }

        val record = DeliveryRecord(
            ownerUid = uid,
            shopId = shopId,
            saleId = sale.id,
            invoiceNumber = sale.invoiceNumber,
            customerId = sale.customerId,
            customerName = sale.customerName,
            items = delLines,
            deliveryDateMillis = System.currentTimeMillis(),
            loadingCharges = loadingCharges,
            remarks = remarks
        )

        val delRef = db.collection("shops").document(shopId).collection("deliveries").document()
        batch.set(delRef, record)
        val saleRef = db.collection("shops").document(shopId).collection("sales").document(sale.id)
        batch.set(saleRef, sale.copy(items = updatedItems, updatedAt = null))

        if (loadingCharges > 0.0) {
            val expRef = db.collection("shops").document(shopId).collection("expenses").document()
            batch.set(
                expRef,
                ExpenseEntry(
                    ownerUid = uid,
                    shopId = shopId,
                    category = "Loading",
                    amount = loadingCharges,
                    paymentMode = "Cash",
                    description = "Partial Delivery #${sale.invoiceNumber}",
                    relatedInvoiceNumber = sale.invoiceNumber
                )
            )
        }

        try {
            batch.commit().await()
            record.copy(id = delRef.id)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, delRef.path)
            throw e
        }
    }

    suspend fun recordReturnOrExchange(
        shopId: String,
        record: ReturnExchange,
        returnedTile: TileProduct?,
        replacementTile: TileProduct?,
        customer: Customer?,
        actorName: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val batch = db.batch()
        val retRef = db.collection("shops").document(shopId).collection("returns").document()
        batch.set(retRef, record.copy(ownerUid = uid, shopId = shopId))

        // Returned goods handling: GOOD -> saleable stock; DAMAGED -> damagedPieces ONLY!
        if (returnedTile != null && returnedTile.createdAt != null) {
            val retTotalPcs = (record.returnBoxes * returnedTile.piecesPerBox) + record.returnPieces
            val tRef = db.collection("shops").document(shopId).collection("tiles").document(returnedTile.id)
            if (record.condition == "GOOD") {
                val curTotal = (returnedTile.totalBoxes * returnedTile.piecesPerBox) + returnedTile.loosePieces + retTotalPcs
                val newBoxes = curTotal / returnedTile.piecesPerBox
                val newLoose = curTotal % returnedTile.piecesPerBox
                val locMap = returnedTile.locationBoxes.toMutableMap()
                val primary = locMap.keys.firstOrNull() ?: "Main Shop"
                locMap[primary] = (locMap[primary] ?: 0L) + (newBoxes - returnedTile.totalBoxes)
                batch.set(tRef, returnedTile.copy(locationBoxes = locMap, totalBoxes = newBoxes, loosePieces = newLoose, updatedAt = null))
            } else {
                batch.set(tRef, returnedTile.copy(damagedPieces = returnedTile.damagedPieces + retTotalPcs, updatedAt = null))
            }
            val moveRef = db.collection("shops").document(shopId).collection("stock_movements").document()
            batch.set(
                moveRef,
                StockMovement(
                    ownerUid = uid,
                    shopId = shopId,
                    tileId = returnedTile.id,
                    tileName = returnedTile.name,
                    movementType = if (record.condition == "DAMAGED") "DAMAGE" else "STOCK_IN",
                    toLocation = "Main Shop",
                    boxesDelta = record.returnBoxes,
                    piecesDelta = record.returnPieces,
                    reason = "Return (${record.condition}) #${record.invoiceNumber}: ${record.reason}",
                    performedBy = actorName
                )
            )
        }

        // Replacement tile deduction if exchange
        if (replacementTile != null && replacementTile.createdAt != null && (record.replacementBoxes > 0 || record.replacementPieces > 0)) {
            val repPcs = (record.replacementBoxes * replacementTile.piecesPerBox) + record.replacementPieces
            val curTotal = ((replacementTile.totalBoxes * replacementTile.piecesPerBox) + replacementTile.loosePieces - repPcs).coerceAtLeast(0L)
            val newBoxes = curTotal / replacementTile.piecesPerBox
            val newLoose = curTotal % replacementTile.piecesPerBox
            val rRef = db.collection("shops").document(shopId).collection("tiles").document(replacementTile.id)
            batch.set(rRef, replacementTile.copy(totalBoxes = newBoxes, loosePieces = newLoose, updatedAt = null))
        }

        if (customer != null && customer.createdAt != null && record.differenceAmount != 0.0) {
            val cRef = db.collection("shops").document(shopId).collection("customers").document(customer.id)
            val deltaOut = when (record.settlementType) {
                "REFUND" -> -kotlin.math.abs(record.differenceAmount)
                "ADDITIONAL_PAYMENT" -> kotlin.math.abs(record.differenceAmount)
                else -> 0.0
            }
            batch.set(cRef, customer.copy(outstandingBalance = customer.outstandingBalance + deltaOut, updatedAt = null))
        }

        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, retRef.path)
            throw e
        }
    }

    suspend fun saveStaffMember(shopId: String, staff: StaffMember): Result<Unit> = runCatching {
        val uid = requireUserId()
        val col = db.collection("shops").document(shopId).collection("staff")
        val ref = if (staff.id.isBlank()) col.document() else col.document(staff.id)
        val payload = if (staff.id.isBlank()) {
            staff.copy(ownerUid = uid, shopId = shopId, createdAt = null, updatedAt = null)
        } else {
            requireNotNull(staff.createdAt)
            staff.copy(ownerUid = uid, shopId = shopId, updatedAt = null)
        }
        try {
            ref.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun markAttendance(
        shopId: String,
        dateKey: String,
        staff: StaffMember,
        status: String,
        isAuto: Boolean,
        editedBy: String,
        reason: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docId = "${dateKey}_${staff.id}"
        val monthKey = dateKey.take(7)
        val ref = db.collection("shops").document(shopId).collection("attendance").document(docId)
        val existingSnap = ref.get().await()
        val payload = if (existingSnap.exists()) {
            val old = existingSnap.toObject(AttendanceRecord::class.java, ServerTimestampBehavior.ESTIMATE)!!
            old.copy(status = status, isAutoMarked = isAuto, lastEditedBy = editedBy, editReason = reason, updatedAt = null)
        } else {
            AttendanceRecord(
                ownerUid = uid,
                shopId = shopId,
                dateKey = dateKey,
                monthKey = monthKey,
                staffId = staff.id,
                staffName = staff.fullName,
                status = status,
                isAutoMarked = isAuto,
                lastEditedBy = editedBy,
                editReason = reason
            )
        }
        val batch = db.batch()
        batch.set(ref, payload)
        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Attendance",
                action = "Marked $status",
                details = "${staff.fullName} on $dateKey ($reason)",
                actorName = editedBy
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun markAutoAttendanceBatch(
        shopId: String,
        dateKey: String,
        unmarkedStaff: List<StaffMember>,
        autoTime: String,
        actorName: String
    ): Result<Int> = runCatching {
        val uid = requireUserId()
        if (unmarkedStaff.isEmpty()) return@runCatching 0

        val dayName = runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
            val cal = Calendar.getInstance().apply { if (parsed != null) time = parsed }
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> "Sunday"
                Calendar.MONDAY -> "Monday"
                Calendar.TUESDAY -> "Tuesday"
                Calendar.WEDNESDAY -> "Wednesday"
                Calendar.THURSDAY -> "Thursday"
                Calendar.FRIDAY -> "Friday"
                Calendar.SATURDAY -> "Saturday"
                else -> ""
            }
        }.getOrDefault("")

        val monthKey = dateKey.take(7)
        val batch = db.batch()
        var markedCount = 0

        unmarkedStaff.forEach { member ->
            if (member.id.isNotBlank()) {
                val docId = "${dateKey}_${member.id}"
                val ref = db.collection("shops").document(shopId).collection("attendance").document(docId)
                val existingSnap = ref.get().await()
                if (!existingSnap.exists()) {
                    val isWeeklyOff = dayName.isNotBlank() && member.weeklyOffDay.equals(dayName, ignoreCase = true)
                    val status = if (isWeeklyOff) "HOLIDAY" else "P"
                    batch.set(
                        ref,
                        AttendanceRecord(
                            ownerUid = uid,
                            shopId = shopId,
                            dateKey = dateKey,
                            monthKey = monthKey,
                            staffId = member.id,
                            staffName = member.fullName,
                            status = status,
                            isAutoMarked = true,
                            lastEditedBy = actorName,
                            editReason = if (isWeeklyOff) "Auto-attendance Weekly Off ($autoTime)" else "Auto-attendance ($autoTime)"
                        )
                    )
                    markedCount++
                }
            }
        }

        if (markedCount == 0) return@runCatching 0

        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Attendance",
                action = "Auto-Marked Attendance",
                details = "Auto-marked $markedCount staff on $dateKey ($autoTime)",
                actorName = actorName
            )
        )

        try {
            batch.commit().await()
            markedCount
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "shops/$shopId/attendance")
            throw e
        }
    }

    suspend fun clearAttendanceForDate(shopId: String, records: List<AttendanceRecord>, actorName: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val batch = db.batch()
        records.forEach { rec ->
            if (rec.id.isNotBlank()) {
                batch.delete(db.collection("shops").document(shopId).collection("attendance").document(rec.id))
            }
        }
        val logRef = db.collection("shops").document(shopId).collection("activity_logs").document()
        batch.set(
            logRef,
            ActivityLog(
                ownerUid = uid,
                shopId = shopId,
                module = "Attendance",
                action = "Cleared Day Attendance",
                details = "Cleared ${records.size} records",
                actorName = actorName
            )
        )
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "shops/$shopId/attendance")
            throw e
        }
    }

    suspend fun saveStaffAdvance(shopId: String, advance: StaffAdvance): Result<Unit> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("shops").document(shopId).collection("advances").document()
        try {
            ref.set(advance.copy(ownerUid = uid, shopId = shopId)).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            throw e
        }
    }

    suspend fun finalizeOrUpdateSalaryMonth(
        shopId: String,
        record: SalaryMonthRecord,
        staff: StaffMember?,
        finalize: Boolean
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docId = "${record.monthKey}_${record.staffId}"
        val ref = db.collection("shops").document(shopId).collection("salary_months").document(docId)
        val snap = ref.get().await()
        val batch = db.batch()
        val toSave = if (snap.exists()) {
            val old = snap.toObject(SalaryMonthRecord::class.java, ServerTimestampBehavior.ESTIMATE)!!
            record.copy(ownerUid = uid, shopId = shopId, isFinalized = finalize, createdAt = old.createdAt, updatedAt = null)
        } else {
            record.copy(ownerUid = uid, shopId = shopId, isFinalized = finalize, createdAt = null, updatedAt = null)
        }
        batch.set(ref, toSave)
        if (finalize && staff != null && staff.createdAt != null) {
            val sRef = db.collection("shops").document(shopId).collection("staff").document(staff.id)
            batch.set(sRef, staff.copy(previousBalance = record.netPayable, updatedAt = null))
        }
        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun saveExpense(shopId: String, expense: ExpenseEntry): Result<Unit> = runCatching {
        val uid = requireUserId()
        val col = db.collection("shops").document(shopId).collection("expenses")
        val ref = if (expense.id.isBlank()) col.document() else col.document(expense.id)
        val payload = if (expense.id.isBlank()) {
            expense.copy(ownerUid = uid, shopId = shopId, createdAt = null, updatedAt = null)
        } else {
            requireNotNull(expense.createdAt)
            expense.copy(ownerUid = uid, shopId = shopId, updatedAt = null)
        }
        try {
            ref.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path)
            throw e
        }
    }

    suspend fun respondToDataAccessRequest(
        request: DataAccessRequest,
        approve: Boolean,
        snapshotJson: String
    ): Result<Unit> = runCatching {
        requireUserId()
        require(request.id.isNotBlank() && request.createdAt != null)
        val now = System.currentTimeMillis()
        val ref = db.collection("data_access_requests").document(request.id)
        val updated = request.copy(
            permissionStatus = if (approve) "APPROVED" else "REVOKED",
            startTimeMillis = if (approve) now else request.startTimeMillis,
            endTimeMillis = if (approve) now + 3_600_000L else now,
            snapshotSummaryJson = if (approve) snapshotJson else "",
            updatedAt = null
        )
        try {
            ref.set(updated).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    suspend fun createShopBackup(
        shop: Shop,
        tiles: List<TileProduct>,
        customers: List<Customer>,
        suppliers: List<Supplier>,
        sales: List<SaleOrder>,
        staff: List<StaffMember>,
        expenses: List<ExpenseEntry>,
        backupType: String = "MANUAL"
    ): Result<BackupRecord> = runCatching {
        val uid = requireUserId()
        val root = JSONObject().apply {
            put("shopCode", shop.shopCode)
            put("shopName", shop.shopName)
            put("timestamp", System.currentTimeMillis())
            put("tilesCount", tiles.size)
            put("customersCount", customers.size)
            put("suppliersCount", suppliers.size)
            put("salesCount", sales.size)
            put("staffCount", staff.size)
            put("expensesCount", expenses.size)
            put("tileCodes", JSONArray(tiles.map { it.code }))
            put("customerNames", JSONArray(customers.map { it.name }))
            put("invoiceNumbers", JSONArray(sales.map { it.invoiceNumber }))
        }
        val imageCount = tiles.count { it.photoBase64.isNotBlank() }.toLong()
        val totalRecords = (tiles.size + customers.size + suppliers.size + sales.size + staff.size + expenses.size).toLong()
        val rec = BackupRecord(
            shopId = shop.id,
            shopName = shop.shopName,
            ownerUid = uid,
            backupType = backupType,
            status = "VERIFIED",
            recordCount = totalRecords,
            imageRefCount = imageCount,
            backupJson = root.toString()
        )
        val ref = db.collection("backups").document()
        try {
            ref.set(rec).await()
            rec.copy(id = ref.id)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            throw e
        }
    }
}
