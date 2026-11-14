package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.firebase.ShopRepository
import com.example.data.model.TileProduct
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
        assertTrue(result.isSuccess)
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

    private companion object {
        const val ALICE_EMAIL = "alice_tile@test.com"
        const val BOB_EMAIL = "bob_tile@test.com"
        const val DEFAULT_TIMEOUT_MS = 8000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}
