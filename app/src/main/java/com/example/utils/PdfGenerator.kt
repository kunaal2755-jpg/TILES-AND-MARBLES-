package com.example.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private val PRIMARY = Color.parseColor("#0F3D3E")
    private val ACCENT = Color.parseColor("#D97706")
    private val SLATE = Color.parseColor("#475569")
    private val BORDER = Color.parseColor("#D6D3CD")
    private val SOFT_BG = Color.parseColor("#F6F5F1")
    private val GREEN = Color.parseColor("#15803D")
    private val RED = Color.parseColor("#DC2626")

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
        val bgPaint = Paint().apply { color = PRIMARY; style = Paint.Style.FILL }
        val accentPaint = Paint().apply { color = ACCENT; style = Paint.Style.FILL }

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
            color = Color.parseColor("#FCD34D")
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val leftWidth = (pageWidth - headerPad * 2 - 175f).toInt().coerceAtLeast(180)
        val shopTitle = shop.shopName.ifBlank { "TileVault Showroom" }
        val nameLayout = StaticLayout.Builder.obtain(
            shopTitle,
            0,
            shopTitle.length,
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
        canvas.drawRect(0f, headerHeight - 4f, pageWidth.toFloat(), headerHeight, accentPaint)

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
        canvas.drawText("Tiles & Marbles Commercial Management System", 24f, pageHeight - 14f, paint)
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
            color = PRIMARY
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SLATE
            textSize = 8.5f
        }
        val pricePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN
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
        paperSize: String = shop.defaultPaperSize,
        tilePhotosById: Map<String, String> = emptyMap(),
        showImagesOverride: Boolean? = null,
        imageSizeOverride: Float? = null
    ): File {
        val (pageW, pageH) = pageDimensions(paperSize)
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas
        val title = if (isQuotation) "QUOTATION" else if (sale.orderStatus == "PENDING") "ORDER ESTIMATE" else "SALES INVOICE"
        var y = drawShopHeader(canvas, pageW, shop, "$title #${sale.invoiceNumber}", Formatters.date(sale.saleDateMillis))

        val includeImages = showImagesOverride ?: shop.showImagesOnSalePdf
        val imgDim = (imageSizeOverride ?: shop.salePdfImageSize.toFloat()).coerceIn(24f, 96f)
        val fallbackBmp = if (includeImages) {
            runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.img_sample_marble) }.getOrNull()
        } else null

        val margin = 24f
        val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIMARY; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        val itemBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIMARY; textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD }
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 9.2f }
        val smallTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 8.4f }
        val linePaint = Paint().apply { color = BORDER; strokeWidth = 0.8f }
        val imgBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BORDER; style = Paint.Style.STROKE; strokeWidth = 0.8f }
        val softBoxPaint = Paint().apply { color = SOFT_BG; style = Paint.Style.FILL }
        val headerBg = Paint().apply { color = PRIMARY }
        val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 8.8f; typeface = Typeface.DEFAULT_BOLD }
        val greenBold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; textSize = 10f; typeface = Typeface.DEFAULT_BOLD }
        val redBold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = RED; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD }

        fun ensureSpace(neededHeight: Float) {
            if (y + neededHeight > pageH - 42f) {
                drawFooter(canvas, pageW, pageH, pageNum)
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = 28f
            }
        }

        // Customer details box
        val custBoxTop = y
        val custInfoW = (pageW - margin * 2 - 20f).toInt()
        val addrText = if (sale.deliveryAddress.isNotBlank()) "Delivery Address / Village: ${sale.deliveryAddress}" else ""
        val addrLayout = if (addrText.isNotBlank()) {
            StaticLayout.Builder.obtain(addrText, 0, addrText.length, textPaint, custInfoW).build()
        } else null
        val custBoxH = 38f + (addrLayout?.height?.plus(6f) ?: 0f)
        canvas.drawRoundRect(RectF(margin, custBoxTop, pageW - margin, custBoxTop + custBoxH), 6f, 6f, softBoxPaint)
        canvas.drawRoundRect(RectF(margin, custBoxTop, pageW - margin, custBoxTop + custBoxH), 6f, 6f, imgBorderPaint)

        canvas.drawText("Bill To: ${sale.customerName}", margin + 10f, custBoxTop + 15f, boldPaint)
        canvas.drawText("Mobile: ${sale.customerMobile.ifBlank { "-" }}   |   Status: ${sale.orderStatus} / ${sale.paymentStatus}", margin + 10f, custBoxTop + 29f, textPaint)
        if (addrLayout != null) {
            canvas.save()
            canvas.translate(margin + 10f, custBoxTop + 34f)
            addrLayout.draw(canvas)
            canvas.restore()
        }
        y = custBoxTop + custBoxH + 12f

        val colPhotoW = if (includeImages) (imgDim + 10f) else 0f
        val colBoxesX = pageW - 228f
        val colLooseX = pageW - 188f
        val colPcsX = pageW - 150f
        val colRateX = pageW - 110f
        val colTotalX = pageW - 58f
        val itemTextX = margin + 6f + colPhotoW
        val itemTextMaxW = (colBoxesX - itemTextX - 6f).toInt().coerceAtLeast(90)

        fun drawItemsHeader() {
            canvas.drawRect(margin, y, pageW - margin, y + 22f, headerBg)
            if (includeImages) {
                canvas.drawText("Photo", margin + 6f, y + 14f, whiteBold)
            }
            canvas.drawText("Item / Size / Code", itemTextX, y + 14f, whiteBold)
            canvas.drawText("Boxes", colBoxesX, y + 14f, whiteBold)
            canvas.drawText("Loose", colLooseX, y + 14f, whiteBold)
            canvas.drawText("Pcs", colPcsX, y + 14f, whiteBold)
            if (sale.showPricesOnPdf) {
                canvas.drawText("Rate", colRateX, y + 14f, whiteBold)
                canvas.drawText("Total", colTotalX, y + 14f, whiteBold)
            }
            y += 26f
        }
        drawItemsHeader()

        sale.items.forEachIndexed { index, item ->
            val titleStr = "${index + 1}. ${item.tileName} (${item.tileSize})"
            val titleLayout = StaticLayout.Builder.obtain(titleStr, 0, titleStr.length, itemBoldPaint, itemTextMaxW).setMaxLines(2).build()
            val codeStr = item.tileCode.ifBlank { if (item.isManualItem) "Manual (${item.manualUnit})" else "-" }
            val textBlockH = titleLayout.height + 16f
            val rowH = maxOf(textBlockH, if (includeImages) imgDim + 8f else 30f)

            if (y + rowH + 8f > pageH - 44f) {
                drawFooter(canvas, pageW, pageH, pageNum)
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = 28f
                drawItemsHeader()
            }

            if (includeImages) {
                val imgTop = y + (rowH - imgDim) / 2f
                val imgRect = RectF(margin + 4f, imgTop, margin + 4f + imgDim, imgTop + imgDim)
                canvas.drawRect(imgRect, softBoxPaint)
                val rawPhoto = item.photoBase64.ifBlank { tilePhotosById[item.tileId].orEmpty() }
                val bmp = ImageUtils.decodeBase64Bitmap(rawPhoto) ?: fallbackBmp
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, imgRect, null)
                }
                canvas.drawRect(imgRect, imgBorderPaint)
            }

            canvas.save()
            canvas.translate(itemTextX, y + 4f)
            titleLayout.draw(canvas)
            canvas.restore()
            canvas.drawText("Code: $codeStr", itemTextX, y + titleLayout.height + 14f, smallTextPaint)

            val valBaseline = y + (rowH / 2f) + 3f
            canvas.drawText("${item.fullBoxes}", colBoxesX + 4f, valBaseline, textPaint)
            canvas.drawText("${item.loosePieces}", colLooseX + 4f, valBaseline, textPaint)
            canvas.drawText("${item.totalPieces()}", colPcsX + 2f, valBaseline, textPaint)
            if (sale.showPricesOnPdf) {
                canvas.drawText(Formatters.currency(item.ratePerBox), colRateX - 2f, valBaseline, smallTextPaint)
                canvas.drawText(Formatters.currency(item.lineTotal), colTotalX - 4f, valBaseline, itemBoldPaint)
            }
            y += rowH + 4f
            canvas.drawLine(margin, y, pageW - margin, y, linePaint)
            y += 5f
        }

        if (sale.showPricesOnPdf) {
            ensureSpace(135f)
            y += 6f
            val sumX = pageW - 220f
            val valX = pageW - 28f
            val rightText = Paint(textPaint).apply { textAlign = Paint.Align.RIGHT }
            val rightBold = Paint(boldPaint).apply { textAlign = Paint.Align.RIGHT; textSize = 11.5f }
            val rightGreen = Paint(greenBold).apply { textAlign = Paint.Align.RIGHT }
            val rightRed = Paint(redBold).apply { textAlign = Paint.Align.RIGHT }

            fun row(lbl: String, v: String, bold: Boolean = false, customValPaint: Paint? = null) {
                canvas.drawText(lbl, sumX, y + 12f, if (bold) itemBoldPaint else textPaint)
                canvas.drawText(v, valX, y + 12f, customValPaint ?: if (bold) rightBold else rightText)
                y += 17f
            }
            row("Subtotal:", Formatters.currency(sale.subtotal))
            if (sale.discount > 0) row("Discount:", "-${Formatters.currency(sale.discount)}")
            if (sale.loadingCharges > 0) row("Loading Charges:", Formatters.currency(sale.loadingCharges))
            if (sale.deliveryCharges > 0) row("Delivery Charges:", Formatters.currency(sale.deliveryCharges))
            if (sale.additionalCharges > 0) row("Additional Charges:", Formatters.currency(sale.additionalCharges))
            canvas.drawLine(sumX, y, valX, y, linePaint)
            y += 6f
            row("Grand Total (कुल बिल):", Formatters.currency(sale.totalPayable), bold = true)
            if (!isQuotation) {
                row("Total Received (कुल प्राप्त):", Formatters.currency(sale.receivedAmount), bold = true, customValPaint = rightGreen)
                val balLabel = if (sale.remainingBalance <= 0.01) "PAID (चुकता)" else Formatters.currency(sale.remainingBalance)
                row(
                    "Remaining Balance (शेष बकाया):",
                    balLabel,
                    bold = true,
                    customValPaint = if (sale.remainingBalance <= 0.01) rightGreen else rightRed
                )
            }

            // Detailed Payment History Section (Date, Mode, Cash/UPI/Bank breakdown & Remaining Due)
            if (!isQuotation) {
                val effectiveHistory = sale.paymentHistory.ifEmpty {
                    if (sale.receivedAmount > 0.0) {
                        listOf(
                            SalePaymentEntry(
                                dateMillis = if (sale.paymentDateMillis > 0L) sale.paymentDateMillis else sale.saleDateMillis,
                                amount = sale.receivedAmount,
                                mode = sale.paymentMode.ifBlank { "Cash" },
                                note = "Bill Payment"
                            )
                        )
                    } else emptyList()
                }.sortedBy { it.dateMillis }

                y += 10f
                ensureSpace(88f)
                canvas.drawText("PAYMENT HISTORY / भुगतान विवरण", margin, y + 12f, boldPaint)
                y += 18f

                if (effectiveHistory.isEmpty()) {
                    canvas.drawRoundRect(RectF(margin, y, pageW - margin, y + 26f), 5f, 5f, softBoxPaint)
                    canvas.drawRoundRect(RectF(margin, y, pageW - margin, y + 26f), 5f, 5f, imgBorderPaint)
                    canvas.drawText(
                        "No payment received yet. Full Balance Due: ${Formatters.currency(sale.remainingBalance)}",
                        margin + 10f,
                        y + 16f,
                        redBold.apply { textSize = 9.5f }
                    )
                    y += 32f
                } else {
                    fun drawPaymentHeader() {
                        canvas.drawRect(margin, y, pageW - margin, y + 20f, headerBg)
                        canvas.drawText("#", margin + 6f, y + 13f, whiteBold)
                        canvas.drawText("Date", margin + 28f, y + 13f, whiteBold)
                        canvas.drawText("Mode (Cash/UPI/Bank)", margin + 125f, y + 13f, whiteBold)
                        canvas.drawText("Note", margin + 255f, y + 13f, whiteBold)
                        canvas.drawText("Amount", pageW - margin - 62f, y + 13f, whiteBold)
                        y += 24f
                    }
                    drawPaymentHeader()

                    var runningReceived = 0.0
                    effectiveHistory.forEachIndexed { idx, entry ->
                        if (y > pageH - 65f) {
                            drawFooter(canvas, pageW, pageH, pageNum)
                            doc.finishPage(page)
                            pageNum++
                            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                            canvas = page.canvas
                            y = 28f
                            drawPaymentHeader()
                        }
                        runningReceived += entry.amount
                        canvas.drawText("${idx + 1}", margin + 6f, y + 11f, textPaint)
                        canvas.drawText(Formatters.date(entry.dateMillis), margin + 28f, y + 11f, textPaint)
                        canvas.drawText(entry.mode.ifBlank { "Cash" }, margin + 125f, y + 11f, itemBoldPaint)
                        canvas.drawText(entry.note.ifBlank { "Payment" }.take(28), margin + 255f, y + 11f, smallTextPaint)
                        canvas.drawText(Formatters.currency(entry.amount), pageW - margin - 62f, y + 11f, greenBold.apply { textSize = 9.5f })
                        y += 17f
                        canvas.drawLine(margin, y, pageW - margin, y, linePaint)
                        y += 4f
                    }

                    // Mode-wise summary strip (Cash / UPI / Bank) + Final Due
                    ensureSpace(46f)
                    y += 4f
                    val byMode = effectiveHistory.groupBy { it.mode.ifBlank { "Cash" } }
                        .entries.joinToString("   |   ") { (m, list) -> "$m: ${Formatters.currency(list.sumOf { it.amount })}" }
                    canvas.drawRoundRect(RectF(margin, y, pageW - margin, y + 40f), 6f, 6f, softBoxPaint)
                    canvas.drawRoundRect(RectF(margin, y, pageW - margin, y + 40f), 6f, 6f, imgBorderPaint)
                    canvas.drawText("Mode Breakdown: $byMode", margin + 10f, y + 15f, itemBoldPaint)
                    val summaryLine = "Total Bill: ${Formatters.currency(sale.totalPayable)}   |   Total Paid: ${Formatters.currency(sale.receivedAmount)}   |   Balance Due: ${Formatters.currency(sale.remainingBalance)}"
                    canvas.drawText(
                        summaryLine,
                        margin + 10f,
                        y + 31f,
                        if (sale.remainingBalance > 0.01) redBold.apply { textSize = 9.5f } else greenBold.apply { textSize = 9.5f }
                    )
                    y += 46f
                }
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
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIMARY; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 9.5f }
        val headerBg = Paint().apply { color = PRIMARY }
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
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIMARY; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 9.5f }
        val linePaint = Paint().apply { color = BORDER; strokeWidth = 0.8f }

        canvas.drawText("Staff: ${staff.fullName} (${staff.designation})  |  Mob: ${staff.mobile}", margin, y + 12f, boldPaint)
        canvas.drawText("Salary Method: ${staff.salaryMethod}  |  Base Monthly: ${Formatters.currency(staff.monthlySalary)}  |  Status: ${if (monthRecord.isFinalized) "FINALIZED" else "OPEN"}", margin, y + 28f, textPaint)
        y += 46f

        canvas.drawText("Attendance Summary: Present=${monthRecord.presentDays}, Half=${monthRecord.halfDays}, Absent=${monthRecord.absentDays}, Holiday/Off=${monthRecord.weeklyOffDays}", margin, y, boldPaint.apply { textSize = 9.5f })
        y += 18f

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

        val margin = 22f
        val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIMARY; textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD }
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = SLATE; textSize = 8.4f }
        val headerBg = Paint().apply { color = PRIMARY }
        val whiteBold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 8.2f; typeface = Typeface.DEFAULT_BOLD }
        val linePaint = Paint().apply { color = BORDER; strokeWidth = 0.7f }

        summaryLines.forEach { line ->
            canvas.drawText(line, margin, y + 10f, boldPaint)
            y += 15f
        }
        y += 8f

        val totalTableW = pageW - margin * 2
        val weights = headers.map { h ->
            when {
                h.contains("Address", true) || h.contains("Village", true) || h.contains("Description", true) || h.contains("Particulars", true) -> 1.65f
                h.contains("Customer", true) || h.contains("Supplier", true) || h.contains("Tile", true) || h.contains("Name", true) -> 1.25f
                else -> 0.9f
            }
        }
        val weightSum = weights.sum().coerceAtLeast(1f)
        val colWidths = weights.map { (it / weightSum) * totalTableW }
        val colOffsets = buildList {
            var acc = margin
            colWidths.forEach { w ->
                add(acc)
                acc += w
            }
        }

        fun drawTableHeader() {
            canvas.drawRect(margin, y, pageW - margin, y + 22f, headerBg)
            headers.forEachIndexed { i, h ->
                val maxW = (colWidths[i] - 6f).toInt().coerceAtLeast(24)
                val hl = StaticLayout.Builder.obtain(h, 0, h.length, whiteBold, maxW).setMaxLines(1).build()
                canvas.save()
                canvas.translate(colOffsets[i] + 3f, y + 5f)
                hl.draw(canvas)
                canvas.restore()
            }
            y += 26f
        }
        drawTableHeader()

        rows.forEach { row ->
            val layouts = row.mapIndexed { i, cell ->
                val maxW = (colWidths.getOrElse(i) { 60f } - 6f).toInt().coerceAtLeast(24)
                val clean = cell.ifBlank { "-" }
                StaticLayout.Builder.obtain(clean, 0, clean.length, textPaint, maxW).setMaxLines(3).build()
            }
            val rowH = (layouts.maxOfOrNull { it.height } ?: 12) + 8f
            if (y + rowH > pageH - 42f) {
                drawFooter(canvas, pageW, pageH, pageNum)
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = 28f
                drawTableHeader()
            }
            layouts.forEachIndexed { i, layout ->
                if (i < colOffsets.size) {
                    canvas.save()
                    canvas.translate(colOffsets[i] + 3f, y + 3f)
                    layout.draw(canvas)
                    canvas.restore()
                }
            }
            y += rowH
            canvas.drawLine(margin, y, pageW - margin, y, linePaint)
            y += 3f
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
