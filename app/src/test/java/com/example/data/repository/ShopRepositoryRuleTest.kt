package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.firebase.ShopRepository
import com.example.data.firebase.SuperAdminRepository
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

    private companion object {
        const val ALICE_EMAIL = "alice_tile@test.com"
        const val BOB_EMAIL = "bob_tile@test.com"
        const val DEFAULT_TIMEOUT_MS = 8000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}
