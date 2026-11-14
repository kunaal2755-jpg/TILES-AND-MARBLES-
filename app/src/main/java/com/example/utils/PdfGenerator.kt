package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.R
import com.example.data.firebase.Formatters
import com.example.data.model.*
import java.io.File
import java.io.FileOutputStream

data class CataloguePdfConfig(
    val title: String = "TILE SAMPLE CATALOGUE",
    val paperSize: String = "A4",
    val showPrices: Boolean = true,
    val includeImage: Boolean = true,
    val includeCode: Boolean = true,
    val includeName: Boolean = true,
    val includeQuality: Boolean = true,
    val includeFinish: Boolean = true,
    val includeSize: Boolean = true,
    val includePiecesPerBox: Boolean = true,
    val includeBoxes: Boolean = true,
    val includeLoosePieces: Boolean = true,
    val includeTotalSqFt: Boolean = true,
    val includeSellingPrice: Boolean = true,
    val includePurchaseCost: Boolean = false
)

object PdfGenerator {

    private val NAVY = Color.parseColor("#0B192C")
    private val GOLD = Color.parseColor("#EAB308")
    private val SLATE = Color.parseColor("#475569")
    private val BORDER = Color.parseColor("#CBD5E1")
    private val SOFT_BG = Color.parseColor("#F8FAFC")

    private fun pageDimensions(paperSize: String): Pair<Int, Int> = when (paperSize.uppercase()) {
        "LETTER" -> 612 to 792
        "LEGAL" -> 612 to 1008
        else -> 595 to 842 // A4
    }

    private fun outputFile(context: Context, prefix: String): File {
        val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        return File(dir, "${prefix}_${System.currentTimeMillis()}.pdf")
    }

    private fun drawShopHeader(
        canvas: Canvas,
        pageWidth: Int,
        shop: Shop,
        docTitle: String,
        subtitleDate: String = Formatters.date(System.currentTimeMillis())
    ): Float {
        val headerPad = 24f
        val bgPaint = Paint().apply { color = NAVY; style = Paint.Style.FILL }
        val goldPaint = Paint().apply { color = GOLD; style = Paint.Style.FILL }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = 9.5f
        }
        val rightTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val leftWidth = (pageWidth - headerPad * 2 - 170f).toInt().coerceAtLeast(180)
        val nameLayout = StaticLayout.Builder.obtain(
            shop.shopName.ifBlank { "TileVault Showroom" },
            0,
            shop.shopName.ifBlank { "TileVault Showroom" }.length,
            titlePaint,
            leftWidth
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val detailsText = buildString {
            if (shop.address.isNotBlank()) append(shop.address)
            if (shop.mobile.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("Mob: ${shop.mobile}")
            }
            if (shop.gstNumber.isNotBlank()) append("  |  GST: ${shop.gstNumber}")
        }.ifBlank { "Tiles & Marbles Authorized Dealer" }

        val detailsLayout = StaticLayout.Builder.obtain(
            detailsText,
            0,
            detailsText.length,
            infoPaint,
            leftWidth
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val headerHeight = maxOf(86f, 24f + nameLayout.height + 6f + detailsLayout.height + 16f)
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), headerHeight, bgPaint)
        canvas.drawRect(0f, headerHeight - 4f, pageWidth.toFloat(), headerHeight, goldPaint)

        canvas.save()
        canvas.translate(headerPad, 16f)
        nameLayout.draw(canvas)
        canvas.translate(0f, nameLayout.height + 4f)
        detailsLayout.draw(canvas)
        canvas.restore()

        canvas.drawText(docTitle, pageWidth - headerPad, 30f, rightTitlePaint)
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("Date: $subtitleDate", pageWidth - headerPad, 46f, datePaint)
        if (shop.shopCode.isNotBlank()) {
            canvas.drawText("Shop ID: ${shop.shopCode}", pageWidth - headerPad, 60f, datePaint)
        }

        return headerHeight + 14f
    }

    private fun drawFooter(canvas: Canvas, pageWidth: Int, pageHeight: Int, pageNum: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SLATE
            textSize = 8.5f
        }
        val linePaint = Paint().apply { color = BORDER; strokeWidth = 0.8f }
        canvas.drawLine(24f, pageHeight - 28f, pageWidth - 24f, pageHeight - 28f, linePaint)
        canvas.drawText("TileVault Commercial Management System", 24f, pageHeight - 14f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Page $pageNum", pageWidth - 24f, pageHeight - 14f, paint)
    }

    fun generateTileCataloguePdf(
        context: Context,
        shop: Shop,
        tiles: List<TileProduct>,
        sizeConfigs: List<TileSizeConfig>,
        config: CataloguePdfConfig
    ): File {
        val (pageW, pageH) = pageDimensions(config.paperSize)
        val doc = PdfDocument()
        val sizeMap = sizeConfigs.associateBy { it.name }
        val fallbackBmp = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.img_sample_marble)
        }.getOrNull()

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas
        var y = drawShopHeader(canvas, pageW, shop, config.title)

        val margin = 20f
        val colGap = 12f
        val cardW = (pageW - margin * 2 - colGap) / 2f

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val cardBgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val headStripPaint = Paint().apply { color = SOFT_BG; style = Paint.Style.FILL }
        val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SLATE
            textSize = 8.5f
        }
        val pricePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#16A34A")
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var idx = 0
        while (idx < tiles.size) {
            val leftTile = tiles[idx]
            val rightTile = tiles.getOrNull(idx + 1)

            fun measureCardHeight(tile: TileProduct): Pair<Float, Float> {
                val sc = sizeMap[tile.sizeName]
                val rawW = (sc?.pdfImageWidth ?: 110L).toFloat().coerceIn(40f, cardW - 20f)
                val rawH = (sc?.pdfImageHeight ?: 110L).toFloat().coerceIn(40f, 175f)
                val imgH = if (config.includeImage) rawH else 0f
                var lines = 0
                if (config.includeName) lines += 2
                if (config.includeCode) lines += 1
                if (config.includeSize || config.includeFinish || config.includeQuality) lines += 2
                if (config.includePiecesPerBox || config.includeBoxes || config.includeLoosePieces) lines += 2
                if (config.includeTotalSqFt) lines += 1
                if (config.showPrices && config.includeSellingPrice) lines += 1
                if (config.showPrices && config.includePurchaseCost) lines += 1
                return (imgH + 18f + lines * 13f + 12f) to rawW
            }

            val (h1, _) = measureCardHeight(leftTile)
            val (h2, _) = if (rightTile != null) measureCardHeight(rightTile) else (0f to 0f)
            val rowH = maxOf(h1, h2).coerceAtLeast(120f)

            if (y + rowH > pageH - 40f) {
                drawFooter(canvas, pageW, pageH, pageNum)
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = 24f
            }

            listOfNotNull(leftTile to margin, rightTile?.let { it to (margin + cardW + colGap) }).forEach { (tile, x) ->
                val rect = RectF(x, y, x + cardW, y + rowH)
                canvas.drawRoundRect(rect, 8f, 8f, cardBgPaint)
                canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

                var curY = y + 8f
                val sc = sizeMap[tile.sizeName]
                if (config.includeImage) {
                    val targetW = (sc?.pdfImageWidth ?: 110L).toFloat().coerceIn(50f, cardW - 18f)
                    val targetH = (sc?.pdfImageHeight ?: 110L).toFloat().coerceIn(48f, 165f)
                    val imgX = x + (cardW - targetW) / 2f
                    val imgRect = RectF(imgX, curY, imgX + targetW, curY + targetH)
                    canvas.drawRect(imgRect, headStripPaint)
                    val bmp = ImageUtils.decodeBase64Bitmap(tile.photoBase64) ?: fallbackBmp
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, null, imgRect, null)
                    }
                    canvas.drawRect(imgRect, borderPaint)
                    curY += targetH + 8f
                }

                val textX = x + 10f
                val maxTextW = (cardW - 20f).toInt()
                if (config.includeName) {
                    val nl = StaticLayout.Builder.obtain(tile.name, 0, tile.name.length, boldPaint, maxTextW).setMaxLines(2).build()
                    canvas.save()
                    canvas.translate(textX, curY)
                    nl.draw(canvas)
                    canvas.restore()
                    curY += nl.height + 3f
                }
                if (config.includeCode) {
                    canvas.drawText("Code: ${tile.code}  ${if (tile.shadeNumber.isNotBlank()) "Shade: ${tile.shadeNumber}" else ""}", textX, curY + 9f, bodyPaint)
                    curY += 13f
                }
                if (config.includeSize || config.includeQuality || config.includeFinish) {
                    val parts = buildList {
                        if (config.includeSize) add("Size: ${tile.sizeName}")
                        if (config.includeQuality) add(tile.quality)
                        if (config.includeFinish) add(tile.finish)
                    }.joinToString(" | ")
                    canvas.drawText(parts, textX, curY + 9f, bodyPaint)
                    curY += 13f
                }
                if (config.includePiecesPerBox || config.includeBoxes || config.includeLoosePieces) {
                    val stockParts = buildList {
                        if (config.includePiecesPerBox) add("Pcs/Box: ${tile.piecesPerBox}")
                        if (config.includeBoxes) add("Boxes: ${tile.totalBoxes}")
                        if (config.includeLoosePieces) add("Loose: ${tile.loosePieces}")
                    }.joinToString(" | ")
                    canvas.drawText(stockParts, textX, curY + 9f, bodyPaint)
                    curY += 13f
                }
                if (config.includeTotalSqFt) {
                    val sqFt = tile.computeSqFt(sc)
                    canvas.drawText("Total Area: ${"%.1f".format(sqFt)} Sq.Ft", textX, curY + 9f, bodyPaint)
                    curY += 13f
                }
                if (config.showPrices && config.includeSellingPrice) {
                    canvas.drawText("Price: ${Formatters.currency(tile.sellingPricePerBox)} / Box", textX, curY + 10f, pricePaint)
                    curY += 14f
                }
                if (config.showPrices && config.includePurchaseCost) {
                    canvas.drawText("Cost: ${Formatters.currency(tile.purchaseCost)}", textX, curY + 9f, bodyPaint)
                }
            }

            y += rowH + 12f
            idx += 2
        }

        drawFooter(canvas, pageW, pageH, pageNum)
        doc.finishPage(page)

        val file = outputFile(context, "TileCatalogue")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun generateSalePdf(
        context: Context,
        shop: Shop,
        sale: SaleOrder,
        isQuotation: Boolean = false,
        paperSize: String = shop.defaultPaperSize
    ): File {
        val (pageW, pageH) = pageDimensions(paperSize)
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas
        val title = if (isQuotation) "QUOTATION" else if (sale.orderStatus == "PENDING") "ORDER ESTIMATE" else "SALES INVOICE"
        var y = drawShopHeader(canvas, pageW, shop, "$title #${sale.invoiceNumber}", Formatters.date(sale.saleDateMillis))

        val margin = 24f
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 9.5f }
        val linePaint = Paint().apply { color = BORDER; strokeWidth = 0.8f }
        val headerBg = Paint().apply { color = NAVY }
        val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 9f; typeface = Typeface.DEFAULT_BOLD }

        canvas.drawText("Bill To: ${sale.customerName}", margin, y + 12f, boldPaint)
        canvas.drawText("Mobile: ${sale.customerMobile}   |   Status: ${sale.orderStatus} / ${sale.paymentStatus}", margin, y + 26f, textPaint)
        if (sale.deliveryAddress.isNotBlank()) {
            canvas.drawText("Delivery Address: ${sale.deliveryAddress}", margin, y + 40f, textPaint)
            y += 54f
        } else {
            y += 40f
        }

        // Table Header
        canvas.drawRect(margin, y, pageW - margin, y + 22f, headerBg)
        canvas.drawText("Item / Size / Code", margin + 6f, y + 14f, whiteBold)
        canvas.drawText("Boxes", pageW - 235f, y + 14f, whiteBold)
        canvas.drawText("Loose", pageW - 190f, y + 14f, whiteBold)
        canvas.drawText("Pcs", pageW - 145f, y + 14f, whiteBold)
        if (sale.showPricesOnPdf) {
            canvas.drawText("Rate", pageW - 105f, y + 14f, whiteBold)
            canvas.drawText("Total", pageW - 55f, y + 14f, whiteBold)
        }
        y += 28f

        sale.items.forEachIndexed { index, item ->
            if (y > pageH - 120f) {
                drawFooter(canvas, pageW, pageH, pageNum)
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = 30f
            }
            canvas.drawText("${index + 1}. ${item.tileName} (${item.tileSize})", margin + 6f, y + 10f, boldPaint.apply { textSize = 9.5f })
            canvas.drawText(item.tileCode.ifBlank { if (item.isManualItem) "Manual" else "-" }, margin + 16f, y + 22f, textPaint)
            canvas.drawText("${item.fullBoxes}", pageW - 230f, y + 12f, textPaint)
            canvas.drawText("${item.loosePieces}", pageW - 185f, y + 12f, textPaint)
            canvas.drawText("${item.totalPieces()}", pageW - 142f, y + 12f, textPaint)
            if (sale.showPricesOnPdf) {
                canvas.drawText(Formatters.currency(item.ratePerBox), pageW - 108f, y + 12f, textPaint)
                canvas.drawText(Formatters.currency(item.lineTotal), pageW - 60f, y + 12f, boldPaint)
            }
            y += 28f
            canvas.drawLine(margin, y, pageW - margin, y, linePaint)
            y += 6f
        }

        if (sale.showPricesOnPdf) {
            y += 10f
            val sumX = pageW - 200f
            val valX = pageW - 28f
            val rightText = Paint(textPaint).apply { textAlign = Paint.Align.RIGHT }
            val rightBold = Paint(boldPaint).apply { textAlign = Paint.Align.RIGHT; textSize = 11.5f }

            fun row(lbl: String, v: String, bold: Boolean = false) {
                canvas.drawText(lbl, sumX, y + 12f, if (bold) boldPaint else textPaint)
                canvas.drawText(v, valX, y + 12f, if (bold) rightBold else rightText)
                y += 17f
            }
            row("Subtotal:", Formatters.currency(sale.subtotal))
            if (sale.discount > 0) row("Discount:", "-${Formatters.currency(sale.discount)}")
            if (sale.loadingCharges > 0) row("Loading Charges:", Formatters.currency(sale.loadingCharges))
            if (sale.deliveryCharges > 0) row("Delivery Charges:", Formatters.currency(sale.deliveryCharges))
            if (sale.additionalCharges > 0) row("Additional Charges:", Formatters.currency(sale.additionalCharges))
            canvas.drawLine(sumX, y, valX, y, linePaint)
            y += 6f
            row("Grand Total:", Formatters.currency(sale.totalPayable), bold = true)
            if (!isQuotation) {
                row("Received (${sale.paymentMode}):", Formatters.currency(sale.receivedAmount))
                row("Balance Due:", Formatters.currency(sale.remainingBalance), bold = true)
            }
        }

        drawFooter(canvas, pageW, pageH, pageNum)
        doc.finishPage(page)
        val file = outputFile(context, if (isQuotation) "Quotation_${sale.invoiceNumber}" else "Invoice_${sale.invoiceNumber}")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun generateDeliveryChallanPdf(context: Context, shop: Shop, delivery: DeliveryRecord): File {
        val (pageW, pageH) = pageDimensions(shop.defaultPaperSize)
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        val canvas = page.canvas
        var y = drawShopHeader(canvas, pageW, shop, "DELIVERY CHALLAN", Formatters.date(delivery.deliveryDateMillis))

        val margin = 24f
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 9.5f }
        val headerBg = Paint().apply { color = NAVY }
        val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 9f; typeface = Typeface.DEFAULT_BOLD }

        canvas.drawText("Invoice #: ${delivery.invoiceNumber}   |   Customer: ${delivery.customerName}", margin, y + 12f, boldPaint)
        canvas.drawText("Loading Charges: ${Formatters.currency(delivery.loadingCharges)}   |   Remarks: ${delivery.remarks.ifBlank { "-" }}", margin, y + 28f, textPaint)
        y += 44f

        canvas.drawRect(margin, y, pageW - margin, y + 22f, headerBg)
        canvas.drawText("Product", margin + 6f, y + 14f, whiteBold)
        canvas.drawText("Purchased", pageW - 230f, y + 14f, whiteBold)
        canvas.drawText("Prev. Del", pageW - 165f, y + 14f, whiteBold)
        canvas.drawText("Delivered Now", pageW - 105f, y + 14f, whiteBold)
        canvas.drawText("Balance", pageW - 45f, y + 14f, whiteBold)
        y += 28f

        delivery.items.forEach { item ->
            canvas.drawText(item.tileName, margin + 6f, y + 12f, boldPaint.apply { textSize = 9.5f })
            canvas.drawText("${item.purchasedPieces} pcs", pageW - 225f, y + 12f, textPaint)
            canvas.drawText("${item.previouslyDeliveredPieces} pcs", pageW - 160f, y + 12f, textPaint)
            canvas.drawText("${item.newDeliveredPieces} pcs", pageW - 95f, y + 12f, boldPaint)
            canvas.drawText("${item.remainingPieces} pcs", pageW - 42f, y + 12f, textPaint)
            y += 22f
        }

        drawFooter(canvas, pageW, pageH, 1)
        doc.finishPage(page)
        val file = outputFile(context, "Challan_${delivery.invoiceNumber}")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun generateSalaryPdf(
        context: Context,
        shop: Shop,
        staff: StaffMember,
        monthRecord: SalaryMonthRecord,
        attendanceList: List<AttendanceRecord>,
        advances: List<StaffAdvance>
    ): File {
        val (pageW, pageH) = pageDimensions(shop.defaultPaperSize)
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        val canvas = page.canvas
        var y = drawShopHeader(canvas, pageW, shop, "STAFF SALARY SLIP", monthRecord.monthKey)

        val margin = 24f
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 9.5f }
        val linePaint = Paint().apply { color = BORDER; strokeWidth = 0.8f }

        canvas.drawText("Staff: ${staff.fullName} (${staff.designation})  |  Mob: ${staff.mobile}", margin, y + 12f, boldPaint)
        canvas.drawText("Salary Method: ${staff.salaryMethod}  |  Base Monthly: ${Formatters.currency(staff.monthlySalary)}  |  Status: ${if (monthRecord.isFinalized) "FINALIZED" else "OPEN"}", margin, y + 28f, textPaint)
        y += 46f

        canvas.drawText("Attendance Summary: Present=${monthRecord.presentDays}, Half=${monthRecord.halfDays}, Absent=${monthRecord.absentDays}, Holiday/Off=${monthRecord.weeklyOffDays}", margin, y, boldPaint.apply { textSize = 9.5f })
        y += 18f

        // Attendance Dates grid
        attendanceList.sortedBy { it.dateKey }.chunked(4).forEach { row ->
            val line = row.joinToString("   |   ") { "${it.dateKey.takeLast(5)}: ${it.status}" }
            canvas.drawText(line, margin, y, textPaint)
            y += 14f
        }

        y += 10f
        canvas.drawLine(margin, y, pageW - margin, y, linePaint)
        y += 16f
        canvas.drawText("Effective Daily Rate: ${Formatters.currency(monthRecord.effectiveDailyRate)}", margin, y, textPaint); y += 15f
        canvas.drawText("Earned Salary: ${Formatters.currency(monthRecord.earnedSalary)}", margin, y, boldPaint); y += 15f
        canvas.drawText("Previous Month Arrears: ${Formatters.currency(monthRecord.previousArrears)}", margin, y, textPaint); y += 15f
        canvas.drawText("Advances Deducted: -${Formatters.currency(monthRecord.advancesDeducted)}", margin, y, textPaint); y += 18f
        canvas.drawText("NET PAYABLE SALARY: ${Formatters.currency(monthRecord.netPayable)}", margin, y, boldPaint.apply { textSize = 12f }); y += 24f

        if (advances.isNotEmpty()) {
            canvas.drawText("Advance Transactions:", margin, y, boldPaint.apply { textSize = 10f }); y += 15f
            advances.forEach { adv ->
                canvas.drawText("${Formatters.date(adv.dateMillis)}  -  ${Formatters.currency(adv.amount)} (${adv.paymentMode})  -  ${adv.reason}", margin + 8f, y, textPaint)
                y += 14f
            }
        }

        drawFooter(canvas, pageW, pageH, 1)
        doc.finishPage(page)
        val file = outputFile(context, "Salary_${staff.fullName.replace(" ", "_")}_${monthRecord.monthKey}")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun generateTableReportPdf(
        context: Context,
        shop: Shop,
        reportTitle: String,
        dateFilterSummary: String,
        summaryLines: List<String>,
        headers: List<String>,
        rows: List<List<String>>
    ): File {
        val (pageW, pageH) = pageDimensions(shop.defaultPaperSize)
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas
        var y = drawShopHeader(canvas, pageW, shop, reportTitle.uppercase(), dateFilterSummary)

        val margin = 24f
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY; textSize = 10f; typeface = Typeface.DEFAULT_BOLD }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 9f }
        val headerBg = Paint().apply { color = NAVY }
        val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 8.5f; typeface = Typeface.DEFAULT_BOLD }
        val linePaint = Paint().apply { color = BORDER; strokeWidth = 0.7f }

        summaryLines.forEach { line ->
            canvas.drawText(line, margin, y + 10f, boldPaint)
            y += 15f
        }
        y += 8f

        val colCount = headers.size.coerceAtLeast(1)
        val colW = (pageW - margin * 2) / colCount

        fun drawTableHeader() {
            canvas.drawRect(margin, y, pageW - margin, y + 20f, headerBg)
            headers.forEachIndexed { i, h ->
                canvas.drawText(h.take(18), margin + 4f + i * colW, y + 13f, whiteBold)
            }
            y += 25f
        }
        drawTableHeader()

        rows.forEach { row ->
            if (y > pageH - 45f) {
                drawFooter(canvas, pageW, pageH, pageNum)
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = 28f
                drawTableHeader()
            }
            row.forEachIndexed { i, cell ->
                canvas.drawText(cell.take(22), margin + 4f + i * colW, y + 11f, textPaint)
            }
            y += 17f
            canvas.drawLine(margin, y, pageW - margin, y, linePaint)
            y += 4f
        }

        drawFooter(canvas, pageW, pageH, pageNum)
        doc.finishPage(page)
        val safeName = reportTitle.replace(Regex("[^A-Za-z0-9]"), "_")
        val file = outputFile(context, safeName)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
