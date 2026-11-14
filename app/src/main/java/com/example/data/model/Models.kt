package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class AdminUser(
    @DocumentId val id: String = "",
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PlatformConfig(
    @DocumentId val id: String = "",
    val brandingName: String = "TileVault Pro",
    val emergencyLock: Boolean = false,
    val broadcastBanner: String = "",
    val registrationEnabled: Boolean = true,
    val adminApprovalRequired: Boolean = false,
    val defaultPlanId: String = "monthly",
    val billingDiscountAllowed: Boolean = true,
    val billDeletionAllowed: Boolean = true,
    val attendanceControlEnabled: Boolean = true,
    val staffAdvanceAllowed: Boolean = true,
    val lowStockThreshold: Long = 10L,
    val recycleBinDays: Long = 30L,
    val supportPhone: String = "+919876543210",
    val supportWhatsapp: String = "+919876543210",
    val supportEmail: String = "support@tilevault.app",
    val appVersionCode: Long = 1L,
    val appVersionName: String = "1.0.0",
    val apkDownloadUrl: String = "",
    val changelog: String = "Initial commercial release with multi-tenant sync and PDF engine.",
    val mandatoryUpdate: Boolean = false,
    val betaRelease: Boolean = false,
    val planMonthlyPrice: Double = 999.0,
    val planQuarterlyPrice: Double = 2499.0,
    val planHalfYearlyPrice: Double = 4499.0,
    val planYearlyPrice: Double = 7999.0,
    val customPlanPrice: Double = 14999.0,
    val freeTrialDays: Long = 14L,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AdminSecrets(
    @DocumentId val id: String = "",
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val alertNewSale: Boolean = true,
    val alertPayment: Boolean = true,
    val alertLowStock: Boolean = true,
    val alertAttendance: Boolean = false,
    val alertSalary: Boolean = true,
    val alertNewStock: Boolean = false,
    val alertBillDelete: Boolean = true,
    val alertSecurity: Boolean = true,
    val masterAdminPinHash: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Shop(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopCode: String = "",
    val shopName: String = "",
    val ownerName: String = "",
    val mobile: String = "",
    val email: String = "",
    val address: String = "",
    val gstNumber: String = "",
    val passwordHash: String = "",
    val secretCodeHash: String = "",
    val status: String = "active", // active, pending, blocked, suspended, archived
    val subscriptionPlan: String = "trial",
    val subscriptionStatus: String = "active", // active, trial, grace, expired
    val subscriptionStartMillis: Long = 0L,
    val subscriptionExpiryMillis: Long = 0L,
    val graceDays: Long = 7L,
    val subscriptionPaid: Double = 0.0,
    val primaryColorHex: String = "#0B192C",
    val showPricesOnPdfDefault: Boolean = true,
    val defaultPaperSize: String = "A4",
    val autoAttendanceEnabled: Boolean = false,
    val autoAttendanceTime: String = "09:30",
    val activeDevices: List<String> = emptyList(),
    val dataAccessGranted: Boolean = false,
    val dataAccessReason: String = "",
    val dataAccessAdminEmail: String = "",
    val dataAccessExpiryMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class TileSizeConfig(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val name: String = "",
    val piecesPerBox: Long = 4L,
    val widthInches: Double = 24.0,
    val heightInches: Double = 24.0,
    val pdfImageWidth: Long = 120L,
    val pdfImageHeight: Long = 120L,
    val displayRatio: String = "1:1",
    val enabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StockLocation(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val name: String = "",
    val address: String = "",
    @get:PropertyName("isPrimary")
    @field:PropertyName("isPrimary")
    val isPrimary: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class TileProduct(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val code: String = "",
    val name: String = "",
    val sizeId: String = "",
    val sizeName: String = "",
    val brand: String = "",
    val category: String = "Floor",
    val shadeNumber: String = "",
    val batchNumber: String = "",
    val quality: String = "Premium",
    val finish: String = "Glossy",
    val piecesPerBox: Long = 4L,
    val locationBoxes: Map<String, Long> = emptyMap(),
    val totalBoxes: Long = 0L,
    val loosePieces: Long = 0L,
    val damagedPieces: Long = 0L,
    val samplePieces: Long = 0L,
    val reservedPieces: Long = 0L,
    val sellingPricePerBox: Double = 0.0,
    val sellingPricePerPiece: Double = 0.0,
    val purchaseCost: Double = 0.0,
    val description: String = "",
    val photoBase64: String = "",
    @get:PropertyName("isDeleted")
    @field:PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    @Exclude
    fun totalSaleablePieces(): Long =
        ((totalBoxes * piecesPerBox) + loosePieces - reservedPieces).coerceAtLeast(0L)

    @Exclude
    fun combinedLocationBoxes(): Long =
        if (locationBoxes.isNotEmpty()) locationBoxes.values.sum() else totalBoxes

    @Exclude
    fun computeSqFt(sizeConfig: TileSizeConfig?): Double {
        val totalPcs = (totalBoxes * piecesPerBox) + loosePieces
        val w = sizeConfig?.widthInches ?: 12.0
        val h = sizeConfig?.heightInches ?: 12.0
        return (w * h / 144.0) * totalPcs
    }
}

@IgnoreExtraProperties
data class StockMovement(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val tileId: String = "",
    val tileName: String = "",
    val movementType: String = "STOCK_IN", // STOCK_IN, STOCK_OUT, TRANSFER, DAMAGE, SAMPLE, CORRECTION
    val fromLocation: String = "",
    val toLocation: String = "",
    val boxesDelta: Long = 0L,
    val piecesDelta: Long = 0L,
    val reason: String = "",
    val performedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Supplier(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val name: String = "",
    val mobile: String = "",
    val address: String = "",
    val gstNumber: String = "",
    val previousBalance: Double = 0.0,
    val totalPurchases: Double = 0.0,
    val totalPaid: Double = 0.0,
    val outstandingBalance: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PurchaseLineItem(
    val tileId: String = "",
    val tileCode: String = "",
    val tileName: String = "",
    val sizeName: String = "",
    val piecesPerBox: Long = 4L,
    val boxes: Long = 0L,
    val loosePieces: Long = 0L,
    val purchaseRatePerBox: Double = 0.0,
    val lineTotal: Double = 0.0
)

@IgnoreExtraProperties
data class PurchaseBill(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val invoiceNumber: String = "",
    val purchaseDateMillis: Long = System.currentTimeMillis(),
    val supplierId: String = "",
    val supplierName: String = "",
    val items: List<PurchaseLineItem> = emptyList(),
    val subtotal: Double = 0.0,
    val loadingCharges: Double = 0.0,
    val transportCharges: Double = 0.0,
    val otherExpenses: Double = 0.0,
    val discount: Double = 0.0,
    val totalAmount: Double = 0.0,
    val amountPaid: Double = 0.0,
    val remainingDue: Double = 0.0,
    val paymentMode: String = "Cash", // Cash, UPI, Bank, Credit
    @get:PropertyName("isReturn")
    @field:PropertyName("isReturn")
    val isReturn: Boolean = false,
    val remarks: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Customer(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val name: String = "",
    val mobile: String = "",
    val address: String = "",
    val previousBalance: Double = 0.0,
    val totalPurchases: Double = 0.0,
    val totalPayments: Double = 0.0,
    val outstandingBalance: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SaleLineItem(
    val tileId: String = "",
    val tileCode: String = "",
    val tileName: String = "",
    val tileSize: String = "",
    val piecesPerBox: Long = 4L,
    val fullBoxes: Long = 0L,
    val loosePieces: Long = 0L,
    val ratePerBox: Double = 0.0,
    val ratePerPiece: Double = 0.0,
    val lineTotal: Double = 0.0,
    @get:PropertyName("isManualItem")
    @field:PropertyName("isManualItem")
    val isManualItem: Boolean = false,
    val manualUnit: String = "Box",
    val photoBase64: String = "",
    val deliveredPieces: Long = 0L
) {
    @Exclude
    fun totalPieces(): Long = (fullBoxes * piecesPerBox) + loosePieces

    @Exclude
    fun remainingDeliveryPieces(): Long = (totalPieces() - deliveredPieces).coerceAtLeast(0L)
}

@IgnoreExtraProperties
data class SaleOrder(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val invoiceNumber: String = "",
    val saleDateMillis: Long = System.currentTimeMillis(),
    val customerId: String = "",
    val customerName: String = "",
    val customerMobile: String = "",
    val deliveryAddress: String = "",
    val showPricesOnPdf: Boolean = true,
    val items: List<SaleLineItem> = emptyList(),
    val subtotal: Double = 0.0,
    val discount: Double = 0.0,
    val additionalCharges: Double = 0.0,
    val loadingCharges: Double = 0.0,
    val deliveryCharges: Double = 0.0,
    val totalPayable: Double = 0.0,
    val receivedAmount: Double = 0.0,
    val remainingBalance: Double = 0.0,
    val paymentMode: String = "Cash",
    val paymentDateMillis: Long = System.currentTimeMillis(),
    val orderStatus: String = "CONFIRMED", // PENDING, CONFIRMED
    val paymentStatus: String = "UNPAID", // PAID, PARTIAL, UNPAID
    val remarks: String = "",
    @get:PropertyName("isDeleted")
    @field:PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DeliveryItemLine(
    val tileId: String = "",
    val tileName: String = "",
    val purchasedPieces: Long = 0L,
    val previouslyDeliveredPieces: Long = 0L,
    val newDeliveredPieces: Long = 0L,
    val remainingPieces: Long = 0L
)

@IgnoreExtraProperties
data class DeliveryRecord(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val saleId: String = "",
    val invoiceNumber: String = "",
    val customerId: String = "",
    val customerName: String = "",
    val items: List<DeliveryItemLine> = emptyList(),
    val deliveryDateMillis: Long = System.currentTimeMillis(),
    val loadingCharges: Double = 0.0,
    val remarks: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ReturnExchange(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val saleId: String = "",
    val invoiceNumber: String = "",
    val customerId: String = "",
    val customerName: String = "",
    val returnedTileId: String = "",
    val returnedTileName: String = "",
    val returnBoxes: Long = 0L,
    val returnPieces: Long = 0L,
    val reason: String = "",
    val condition: String = "GOOD", // GOOD, DAMAGED
    val returnDateMillis: Long = System.currentTimeMillis(),
    val replacementTileId: String = "",
    val replacementTileName: String = "",
    val replacementBoxes: Long = 0L,
    val replacementPieces: Long = 0L,
    val differenceAmount: Double = 0.0,
    val settlementType: String = "EVEN", // REFUND, ADDITIONAL_PAYMENT, EVEN
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StaffMember(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val fullName: String = "",
    val mobile: String = "",
    val designation: String = "Salesman",
    val joiningDateMillis: Long = System.currentTimeMillis(),
    val salaryMethod: String = "DAYS_30", // DAYS_30, ACTUAL_DAYS, WORKING_DAYS, DAILY_WAGE
    val monthlySalary: Double = 15000.0,
    val dailyRate: Double = 500.0,
    val weeklyOffDay: String = "Sunday",
    val previousBalance: Double = 0.0,
    val remarks: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AttendanceRecord(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val dateKey: String = "", // YYYY-MM-DD
    val monthKey: String = "", // YYYY-MM
    val staffId: String = "",
    val staffName: String = "",
    val status: String = "P", // P, HALF, A, HOLIDAY
    @get:PropertyName("isAutoMarked")
    @field:PropertyName("isAutoMarked")
    val isAutoMarked: Boolean = false,
    val lastEditedBy: String = "",
    val editReason: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StaffAdvance(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val dateMillis: Long = System.currentTimeMillis(),
    val amount: Double = 0.0,
    val paymentMode: String = "Cash",
    val reason: String = "",
    val purpose: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SalaryMonthRecord(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val monthKey: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val presentDays: Long = 0L,
    val halfDays: Long = 0L,
    val absentDays: Long = 0L,
    val weeklyOffDays: Long = 0L,
    val monthlySalary: Double = 0.0,
    val effectiveDailyRate: Double = 0.0,
    val earnedSalary: Double = 0.0,
    val previousArrears: Double = 0.0,
    val advancesDeducted: Double = 0.0,
    val netPayable: Double = 0.0,
    @get:PropertyName("isFinalized")
    @field:PropertyName("isFinalized")
    val isFinalized: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ExpenseEntry(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val dateMillis: Long = System.currentTimeMillis(),
    val category: String = "Loading",
    val amount: Double = 0.0,
    val paymentMode: String = "Cash",
    val description: String = "",
    val relatedInvoiceNumber: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ActivityLog(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val shopId: String = "",
    val module: String = "",
    val action: String = "",
    val details: String = "",
    val actorName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DataAccessRequest(
    @DocumentId val id: String = "",
    val shopId: String = "",
    val shopName: String = "",
    val ownerUid: String = "",
    val permissionStatus: String = "PENDING", // PENDING, APPROVED, REVOKED
    val reason: String = "",
    val adminIdentity: String = "",
    val startTimeMillis: Long = 0L,
    val endTimeMillis: Long = 0L,
    val snapshotSummaryJson: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ShopArchive(
    @DocumentId val id: String = "",
    val shopId: String = "",
    val shopName: String = "",
    val ownerUid: String = "",
    val backupVerified: Boolean = false,
    val archivedBy: String = "",
    val archivedAtMillis: Long = 0L,
    val backupPayloadJson: String = "",
    val restored: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BackupRecord(
    @DocumentId val id: String = "",
    val shopId: String = "",
    val shopName: String = "",
    val ownerUid: String = "",
    val backupType: String = "MANUAL", // AUTO, MANUAL, FULL
    val status: String = "VERIFIED", // VERIFIED, FAILED
    val recordCount: Long = 0L,
    val imageRefCount: Long = 0L,
    val backupJson: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AdminLog(
    @DocumentId val id: String = "",
    val actorEmail: String = "",
    val action: String = "",
    val targetShopId: String = "",
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
