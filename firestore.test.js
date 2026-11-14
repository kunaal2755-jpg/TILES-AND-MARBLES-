const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const tsNow = () => new Date(Date.now() - 1000);

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_shop_owner";
const BOB_UID = "bob_other_owner";
const ADMIN_UID = "super_admin_kunaal";
const ADMIN_EMAIL = "kunaal2755@gmail.com";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function shopPayload(ownerUid, shopCode = "SHP001") {
  return {
    ownerUid,
    shopCode,
    shopName: "Royal Marble & Tiles",
    ownerName: "Alice",
    mobile: "9876543210",
    email: "alice@tiles.com",
    address: "Morbi Highway, Gujarat",
    gstNumber: "24AAAAA0000A1Z5",
    passwordHash: "hash123",
    secretCodeHash: "pin123",
    status: "active",
    subscriptionPlan: "yearly",
    subscriptionStatus: "active",
    subscriptionStartMillis: 1700000000000,
    subscriptionExpiryMillis: 1800000000000,
    graceDays: 7,
    subscriptionPaid: 9999,
    primaryColorHex: "#0B192C",
    showPricesOnPdfDefault: true,
    defaultPaperSize: "A4",
    autoAttendanceEnabled: true,
    autoAttendanceTime: "09:30",
    activeDevices: ["Pixel 8"],
    dataAccessGranted: false,
    dataAccessReason: "",
    dataAccessAdminEmail: "",
    dataAccessExpiryMillis: 0,
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write shops or platform_config", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("shops").get());
  await assertFails(unauth.collection("platform_config").doc("global").get());
  await assertFails(unauth.collection("admin_secrets").doc("config").get());
});

test("Shop owner can create and query own shop and tile inventory", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true }).firestore();
  await assertSucceeds(alice.collection("shops").doc("shop_alice").set(shopPayload(ALICE_UID)));
  await assertSucceeds(alice.collection("shops").where("ownerUid", "==", ALICE_UID).get());

  await assertSucceeds(
    alice.collection("shops").doc("shop_alice").collection("tiles").doc("tile_1").set({
      ownerUid: ALICE_UID,
      shopId: "shop_alice",
      code: "CAL-24",
      name: "Calacatta Gold 2x4",
      sizeId: "sz_2x4",
      sizeName: "2x4",
      brand: "Kajaria",
      category: "Floor",
      shadeNumber: "SH-01",
      batchNumber: "B-102",
      quality: "Premium",
      finish: "Glossy",
      piecesPerBox: 2,
      locationBoxes: { "Main Shop": 20, "Warehouse 1": 30 },
      totalBoxes: 50,
      loosePieces: 1,
      damagedPieces: 0,
      samplePieces: 2,
      reservedPieces: 0,
      sellingPricePerBox: 1450,
      sellingPricePerPiece: 725,
      purchaseCost: 1100,
      description: "High gloss Italian finish",
      photoBase64: "",
      isDeleted: false,
      createdAt: tsNow(),
      updatedAt: tsNow(),
    })
  );

  await assertSucceeds(
    alice.collection("shops").doc("shop_alice").collection("tiles").where("ownerUid", "==", ALICE_UID).get()
  );
});

test("Cross-user isolation: Bob cannot read, list, or update Alice's shop or tiles", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(alice.collection("shops").doc("shop_alice").set(shopPayload(ALICE_UID)));

  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bob.collection("shops").doc("shop_alice").get());
  await assertFails(bob.collection("shops").get());
  await assertFails(bob.collection("shops").doc("shop_alice").update({ shopName: "Hacked" }));
  await assertFails(bob.collection("shops").doc("shop_alice").collection("tiles").where("ownerUid", "==", ALICE_UID).get());
});

test("Shadow update & hard delete protections hold on shops and tiles", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(alice.collection("shops").doc("shop_alice").set(shopPayload(ALICE_UID)));

  // Ghost field / privilege escalation attempt
  await assertFails(alice.collection("shops").doc("shop_alice").update({ isVerified: true, status: "active" }));
  // Hard delete blocked
  await assertFails(alice.collection("shops").doc("shop_alice").delete());
});

test("Email spoofing test: unverified admin email cannot access admin_secrets or platform_config writes", async () => {
  const spoofed = testEnv.authenticatedContext("spoof_uid", {
    email: ADMIN_EMAIL,
    email_verified: false,
  }).firestore();

  await assertFails(spoofed.collection("admin_secrets").doc("config").get());
  await assertFails(
    spoofed.collection("platform_config").doc("global").set({
      brandingName: "Spoofed",
      supportPhone: "9999999999",
      updatedAt: tsNow(),
    })
  );
});

test("Verified Super Admin can manage platform_config, admin_secrets, and shop status", async () => {
  const admin = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  await assertSucceeds(
    admin.collection("platform_config").doc("global").set({
      brandingName: "TileVault Pro",
      supportPhone: "+919800012345",
      supportWhatsapp: "+919800012345",
      supportEmail: "support@tilevault.app",
      emergencyLock: false,
      broadcastBanner: "Welcome to TileVault",
      updatedAt: tsNow(),
    })
  );

  await assertSucceeds(
    admin.collection("admin_secrets").doc("config").set({
      telegramBotToken: "123:abc",
      telegramChatId: "-10099",
      masterAdminPinHash: "hashedpin",
      updatedAt: tsNow(),
    })
  );

  // Regular user can read platform_config (for live support number) but CANNOT read admin_secrets
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(alice.collection("platform_config").doc("global").get());
  await assertFails(alice.collection("admin_secrets").doc("config").get());
});

test("Data Access Request workflow: Admin requests, Shop Owner approves or revokes", async () => {
  const admin = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  await assertSucceeds(
    admin.collection("data_access_requests").doc("req_1").set({
      shopId: "shop_alice",
      shopName: "Royal Marble",
      ownerUid: ALICE_UID,
      permissionStatus: "PENDING",
      reason: "GST audit support",
      adminIdentity: ADMIN_EMAIL,
      startTimeMillis: 0,
      endTimeMillis: 0,
      snapshotSummaryJson: "",
      createdAt: tsNow(),
      updatedAt: tsNow(),
    })
  );

  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bob.collection("data_access_requests").doc("req_1").get());

  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    alice.collection("data_access_requests").doc("req_1").update({
      permissionStatus: "APPROVED",
      startTimeMillis: 1700000000000,
      endTimeMillis: 1700003600000,
      snapshotSummaryJson: "{\"tiles\": 50}",
      updatedAt: tsNow(),
    })
  );
});
