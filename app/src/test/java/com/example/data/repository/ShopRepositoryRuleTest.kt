package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.firebase.ShopRepository
import com.example.data.firebase.SuperAdminRepository
import com.example.data.model.SaleLineItem
import com.example.data.model.SaleOrder
import com.example.data.model.StaffMember
import com.example.data.model.TileProduct
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createShopWithDefaults_authenticatedOwner_createsShopAndSeedsSizes() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = ShopRepository(firestore)

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createShopWithDefaults(
                shopName = "Morbi Ceramic Hub",
                ownerName = "Alice",
                mobile = "9876543210",
                address = "Morbi, GJ",
                gstNumber = "24ABCDE1234F1Z5",
                passwordPlain = "pass123",
                secretCodePlain = "4321",
                deviceName = "Pixel Test",
                requiresApproval = false,
                trialDays = 14L
            )
        }
        assertTrue("createShopWithDefaults failed: ${result.exceptionOrNull()?.stackTraceToString()}", result.isSuccess)
        val shopId = result.getOrThrow()

        val sizes = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTileSizes(shopId).first { it.isNotEmpty() }
        }
        assertTrue(sizes.any { it.name == "12x18" })
        assertTrue(sizes.any { it.name == "2x4" })
    }

    @Test
    fun saveTileProduct_authenticatedOwner_persistsAndEmitsRealtime() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = ShopRepository(firestore)
        val shopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createShopWithDefaults(
                shopName = "Granite World",
                ownerName = "Alice",
                mobile = "9876543210",
                address = "Surat",
                gstNumber = "",
                passwordPlain = "pass",
                secretCodePlain = "1111",
                deviceName = "TestPhone",
                requiresApproval = false,
                trialDays = 14L
            ).getOrThrow()
        }

        val tileId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.saveTileProduct(
                shopId = shopId,
                tile = TileProduct(
                    code = "CAL-01",
                    name = "Calacatta Gold",
                    sizeName = "2x4",
                    piecesPerBox = 2,
                    locationBoxes = mapOf("Main Shop" to 25L),
                    sellingPricePerBox = 1450.0,
                    sellingPricePerPiece = 725.0,
                    purchaseCost = 1050.0
                ),
                actorName = "Alice"
            ).getOrThrow()
        }

        val tiles = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTiles(shopId).first { list -> list.any { it.id == tileId } }
        }
        assertEquals(25L, tiles.first { it.id == tileId }.totalBoxes)
    }

    @Test
    fun observeOwnedShops_crossUserIsolation_bobCannotSeeAlicesShop() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val aliceRepo = ShopRepository(firestore)
        val shopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createShopWithDefaults(
                shopName = "Alice Exclusive Tiles",
                ownerName = "Alice",
                mobile = "9988776655",
                address = "Jaipur",
                gstNumber = "",
                passwordPlain = "p",
                secretCodePlain = "1234",
                deviceName = "Dev1",
                requiresApproval = false,
                trialDays = 14L
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = ShopRepository(firestore)
        val bobShops = withTimeout(FLOW_TIMEOUT_MS) { bobRepo.observeOwnedShops().first() }
        assertTrue(bobShops.none { it.id == shopId })
    }

    @Test
    fun observeOwnedShops_unauthenticatedUser_failsFastWithoutLeakingData() = runBlocking {
        auth.signOut()
        val repo = ShopRepository(firestore)
        var thrown: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) { repo.observeOwnedShops().first() }
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is IllegalStateException || thrown?.unwrapFirestoreException()?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    @Test
    fun firstTimeMasterSetup_createsMasterAdminAndShopkeeperSyncsAcrossDevices() = runBlocking {
        val adminRepo = SuperAdminRepository(firestore)

        // 1. Before any account exists, even an unauthenticated launch sees masterAdminInitialized == false
        auth.signOut()
        val preAuthInitial = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.fetchPreAuthBootstrapStatus().getOrThrow()
        }
        assertFalse(preAuthInitial.masterAdminInitialized)

        // 2. User authenticates via Google Sign-In and submits the First-Time Master Admin setup
        signInTestUser(ALICE_EMAIL)
        val initialStatus = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.fetchBootstrapStatusFromServer().getOrThrow()
        }
        assertFalse(initialStatus.masterAdminInitialized)

        val masterShopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.completeFirstTimeMasterAdminSetup(
                adminIdRaw = "MASTER01",
                shopName = "Royal Marble Master",
                ownerName = "Alice Master",
                mobile = "9876543210",
                passwordPlain = "Master@123",
                confirmPasswordPlain = "Master@123",
                secretCodePlain = "4321",
                deviceName = "MasterPhone"
            ).getOrThrow()
        }
        assertTrue(masterShopId.isNotBlank())

        // 3. After Master Admin setup completes & app signs out to Login Screen,
        //    unauthenticated launch now sees masterAdminInitialized == true
        auth.signOut()
        val preAuthAfter = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.fetchPreAuthBootstrapStatus().getOrThrow()
        }
        assertTrue(preAuthAfter.masterAdminInitialized)
        assertEquals("9876543210", preAuthAfter.supportPhone)

        // 4. Master Admin logs in via Login Screen with Admin ID + Password
        signInTestUser(ALICE_EMAIL)
        val masterLoginRepo = ShopRepository(firestore)
        val verifiedMasterShopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            masterLoginRepo.verifyAndSyncShopByCredentials("MASTER01", "Master@123", "MasterPhone2").getOrThrow()
        }
        assertEquals(masterShopId, verifiedMasterShopId)

        val afterStatus = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.fetchBootstrapStatusFromServer().getOrThrow()
        }
        assertTrue(afterStatus.masterAdminInitialized)
        assertTrue(afterStatus.isMasterAdmin)
        assertEquals("MASTER01", afterStatus.masterAdminId)

        // 5. Master Admin creates a separate Shopkeeper account
        val shopkeeperShopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.createShopByAdmin(
                shopCodeRaw = "SHOP-BOB",
                shopName = "Bob Granite Studio",
                ownerName = "Bob",
                ownerUid = "",
                mobile = "9123456789",
                email = BOB_EMAIL,
                passwordPlain = "BobPass@1",
                plan = "monthly",
                durationDays = 30L,
                amountPaid = 999.0
            ).getOrThrow()
        }

        // 6. Bob signs in on a fresh device and verifies with Shop ID + Password
        signInTestUser(BOB_EMAIL)
        val bobRepo = ShopRepository(firestore)
        val wrongPass = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.verifyAndSyncShopByCredentials("SHOP-BOB", "WrongPass", "BobPhone1")
        }
        assertTrue(wrongPass.isFailure)

        val syncedId = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.verifyAndSyncShopByCredentials("SHOP-BOB", "BobPass@1", "BobPhone1").getOrThrow()
        }
        assertEquals(shopkeeperShopId, syncedId)

        // 7. Verify Bob's shop is isolated and seeded cleanly
        val bobShops = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeOwnedShops().first { list -> list.any { it.id == shopkeeperShopId } }
        }
        assertTrue(bobShops.none { it.id == masterShopId })
        val bobSizes = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeTileSizes(shopkeeperShopId).first { it.isNotEmpty() }
        }
        assertTrue(bobSizes.any { it.name == "2x4" })
    }

    @Test
    fun shopSettings_pdfImagesAndAutoAttendanceTime_updatePersists() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = ShopRepository(firestore)
        val shopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createShopWithDefaults(
                shopName = "Ceramic Studio",
                ownerName = "Alice",
                mobile = "9876543210",
                address = "Morbi",
                gstNumber = "",
                passwordPlain = "pass",
                secretCodePlain = "1234",
                deviceName = "TestPhone",
                requiresApproval = false,
                trialDays = 14L
            ).getOrThrow()
        }

        val shop = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeOwnedShops().first { list -> list.any { it.id == shopId } }.first { it.id == shopId }
        }
        val res = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateShop(
                shop.copy(
                    showImagesOnSalePdf = false,
                    salePdfImageSize = 60L,
                    autoAttendanceTime = "10:15"
                )
            )
        }
        assertTrue("updateShop failed: ${res.exceptionOrNull()?.stackTraceToString()}", res.isSuccess)

        val updated = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeOwnedShops().first { list ->
                list.any { it.id == shopId && !it.showImagesOnSalePdf && it.salePdfImageSize == 60L && it.autoAttendanceTime == "10:15" }
            }.first { it.id == shopId }
        }
        assertFalse(updated.showImagesOnSalePdf)
        assertEquals(60L, updated.salePdfImageSize)
        assertEquals("10:15", updated.autoAttendanceTime)
    }

    @Test
    fun warehouse_renameAndDeleteWithMigration_movesTilesAtomically() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = ShopRepository(firestore)
        val shopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createShopWithDefaults(
                shopName = "Warehouse Hub",
                ownerName = "Alice",
                mobile = "9876543210",
                address = "Morbi",
                gstNumber = "",
                passwordPlain = "pass",
                secretCodePlain = "1234",
                deviceName = "TestPhone",
                requiresApproval = false,
                trialDays = 14L
            ).getOrThrow()
        }

        val initialLocs = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLocations(shopId).first { it.size >= 3 }
        }
        val wh1 = initialLocs.first { it.name == "Warehouse 1" }

        val tileId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.saveTileProduct(
                shopId = shopId,
                tile = TileProduct(
                    code = "WH-01",
                    name = "Statuario Matte",
                    sizeName = "2x4",
                    piecesPerBox = 2,
                    locationBoxes = mapOf("Main Shop" to 5L, "Warehouse 1" to 20L),
                    sellingPricePerBox = 1200.0
                ),
                actorName = "Alice"
            ).getOrThrow()
        }
        val tileBefore = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTiles(shopId).first { list -> list.any { it.id == tileId } }.first { it.id == tileId }
        }

        // Rename Warehouse 1 -> North Godown
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.renameLocation(shopId, wh1, "North Godown", "Ring Road", listOf(tileBefore), "Alice").getOrThrow()
        }
        val tileAfterRename = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTiles(shopId).first { list ->
                list.any { it.id == tileId && it.locationBoxes["North Godown"] == 20L }
            }.first { it.id == tileId }
        }
        assertFalse(tileAfterRename.locationBoxes.containsKey("Warehouse 1"))
        assertEquals(20L, tileAfterRename.locationBoxes["North Godown"])

        val renamedLoc = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLocations(shopId).first { list -> list.any { it.name == "North Godown" } }
                .first { it.name == "North Godown" }
        }

        // Delete North Godown by creating a brand-new warehouse "South Depot" and moving tiles there
        val movedCount = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.deleteLocationWithMigration(
                shopId = shopId,
                locationToDelete = renamedLoc,
                targetLocationNameRaw = "South Depot",
                createNewLocation = true,
                newLocationAddress = "Highway Sector 4",
                tiles = listOf(tileAfterRename),
                actorName = "Alice"
            ).getOrThrow()
        }
        assertEquals(1, movedCount)

        val tileAfterMigrate = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTiles(shopId).first { list ->
                list.any { it.id == tileId && it.locationBoxes["South Depot"] == 20L }
            }.first { it.id == tileId }
        }
        assertEquals(25L, tileAfterMigrate.totalBoxes)
        assertEquals(5L, tileAfterMigrate.locationBoxes["Main Shop"])
        assertEquals(20L, tileAfterMigrate.locationBoxes["South Depot"])

        val finalLocs = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeLocations(shopId).first { list ->
                list.any { it.name == "South Depot" } && list.none { it.name == "North Godown" }
            }
        }
        assertTrue(finalLocs.any { it.name == "South Depot" })
        assertTrue(finalLocs.none { it.name == "North Godown" })
    }

    @Test
    fun sale_paymentHistoryAndDeleteWithExactWarehouseStockRestore() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = ShopRepository(firestore)
        val shopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createShopWithDefaults(
                shopName = "Marble Plaza",
                ownerName = "Alice",
                mobile = "9876543210",
                address = "Morbi",
                gstNumber = "",
                passwordPlain = "pass",
                secretCodePlain = "1234",
                deviceName = "TestPhone",
                requiresApproval = false,
                trialDays = 14L
            ).getOrThrow()
        }

        val tileId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.saveTileProduct(
                shopId = shopId,
                tile = TileProduct(
                    code = "MP-01",
                    name = "Onyx Ivory",
                    sizeName = "2x2",
                    piecesPerBox = 4,
                    locationBoxes = mapOf("Main Shop" to 3L, "Warehouse 1" to 10L),
                    sellingPricePerBox = 1000.0
                ),
                actorName = "Alice"
            ).getOrThrow()
        }
        val tile = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTiles(shopId).first { list -> list.any { it.id == tileId } }.first { it.id == tileId }
        }

        // Sell 5 boxes -> should deduct 3 from Main Shop + 2 from Warehouse 1
        val saleId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.saveSaleOrder(
                shopId = shopId,
                sale = SaleOrder(
                    invoiceNumber = "INV-9001",
                    customerName = "Ramesh Patel",
                    customerMobile = "9898989898",
                    deliveryAddress = "Village Rampur",
                    items = listOf(
                        SaleLineItem(
                            tileId = tile.id,
                            tileCode = tile.code,
                            tileName = tile.name,
                            tileSize = tile.sizeName,
                            piecesPerBox = 4,
                            fullBoxes = 5L,
                            ratePerBox = 1000.0,
                            ratePerPiece = 250.0,
                            lineTotal = 5000.0
                        )
                    ),
                    subtotal = 5000.0,
                    totalPayable = 5000.0,
                    receivedAmount = 2000.0,
                    remainingBalance = 3000.0,
                    paymentMode = "Cash",
                    orderStatus = "CONFIRMED",
                    paymentStatus = "PARTIAL"
                ),
                existingCustomer = null,
                tilesMap = mapOf(tile.id to tile),
                previousOrderStatus = null,
                actorName = "Alice"
            ).getOrThrow()
        }

        val tileAfterSale = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTiles(shopId).first { list -> list.any { it.id == tileId && it.totalBoxes == 8L } }
                .first { it.id == tileId }
        }
        assertEquals(0L, tileAfterSale.locationBoxes["Main Shop"])
        assertEquals(8L, tileAfterSale.locationBoxes["Warehouse 1"])

        val savedSale = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSales(shopId).first { list -> list.any { it.id == saleId } }.first { it.id == saleId }
        }
        assertEquals(1, savedSale.paymentHistory.size)
        assertEquals(3L, savedSale.items.first().deductedLocations["Main Shop"])
        assertEquals(2L, savedSale.items.first().deductedLocations["Warehouse 1"])

        // Add second UPI installment payment
        val customer = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomers(shopId).first { it.isNotEmpty() }.first()
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.recordSalePayment(
                shopId = shopId,
                sale = savedSale,
                customer = customer,
                amount = 1500.0,
                paymentMode = "UPI",
                note = "GPay transfer",
                actorName = "Alice"
            ).getOrThrow()
        }
        val saleAfterInstallment = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSales(shopId).first { list ->
                list.any { it.id == saleId && it.paymentHistory.size == 2 }
            }.first { it.id == saleId }
        }
        assertEquals(3500.0, saleAfterInstallment.receivedAmount, 0.01)
        assertEquals(1500.0, saleAfterInstallment.remainingBalance, 0.01)
        assertEquals("UPI", saleAfterInstallment.paymentHistory[1].mode)

        // Delete bill WITH restoreStock = true -> restores exact 3 to Main Shop and 2 to Warehouse 1
        val custAfterPay = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCustomers(shopId).first { list -> list.any { it.id == customer.id && it.totalPayments >= 3500.0 } }
                .first { it.id == customer.id }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.deleteSaleOrder(
                shopId = shopId,
                sale = saleAfterInstallment,
                restoreStock = true,
                customer = custAfterPay,
                tilesMap = mapOf(tileAfterSale.id to tileAfterSale),
                actorName = "Alice"
            ).getOrThrow()
        }

        val tileAfterRestore = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTiles(shopId).first { list -> list.any { it.id == tileId && it.totalBoxes == 13L } }
                .first { it.id == tileId }
        }
        assertEquals(3L, tileAfterRestore.locationBoxes["Main Shop"])
        assertEquals(10L, tileAfterRestore.locationBoxes["Warehouse 1"])

        val activeSales = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSales(shopId).first { list -> list.none { it.id == saleId } }
        }
        assertTrue(activeSales.none { it.id == saleId })
    }

    @Test
    fun autoAttendanceBatch_marksPresentAndWeeklyOffHoliday() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = ShopRepository(firestore)
        val shopId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createShopWithDefaults(
                shopName = "Staff Studio",
                ownerName = "Alice",
                mobile = "9876543210",
                address = "Morbi",
                gstNumber = "",
                passwordPlain = "pass",
                secretCodePlain = "1234",
                deviceName = "TestPhone",
                requiresApproval = false,
                trialDays = 14L
            ).getOrThrow()
        }

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.saveStaffMember(
                shopId,
                StaffMember(fullName = "Raju", mobile = "9000011111", weeklyOffDay = "Sunday")
            ).getOrThrow()
            repo.saveStaffMember(
                shopId,
                StaffMember(fullName = "Suresh", mobile = "9000022222", weeklyOffDay = "Monday")
            ).getOrThrow()
        }
        val staffList = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeStaff(shopId).first { it.size == 2 }
        }

        // 2026-05-10 is a Sunday -> Raju gets HOLIDAY, Suresh gets P
        val count = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.markAutoAttendanceBatch(
                shopId = shopId,
                dateKey = "2026-05-10",
                unmarkedStaff = staffList,
                autoTime = "09:30",
                actorName = "Auto-Attendance"
            ).getOrThrow()
        }
        assertEquals(2, count)

        val records = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeAttendance(shopId).first { it.size == 2 }
        }
        val rajuRec = records.first { it.staffName == "Raju" }
        val sureshRec = records.first { it.staffName == "Suresh" }
        assertEquals("HOLIDAY", rajuRec.status)
        assertEquals("P", sureshRec.status)
        assertTrue(rajuRec.isAutoMarked)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_tile@test.com"
        const val BOB_EMAIL = "bob_tile@test.com"
        const val DEFAULT_TIMEOUT_MS = 8000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}
