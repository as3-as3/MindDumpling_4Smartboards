package com.neurodumpling.app.utils

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.neurodumpling.app.models.Node
import com.neurodumpling.app.models.Relationship
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

object ExportService {
    private const val LEGEND_WIDTH = 350f

    fun exportToPng(context: Context, nodes: List<Node>, relationships: List<Relationship>, isDark: Boolean, mode: String, title: String): Uri? {
        if (nodes.isEmpty()) return null
        val bounds = calculateBounds(nodes, true)
        val bitmap = Bitmap.createBitmap(bounds.width().toInt(), bounds.height().toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawMap(canvas, bounds, nodes, relationships, isDark, mode, title)
        
        return saveToSharedStorage(context, "ND_Export_${System.currentTimeMillis()}.png", "image/png") { os ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        }
    }

    fun exportToPdf(context: Context, nodes: List<Node>, relationships: List<Relationship>, isDark: Boolean, mode: String, title: String): Uri? {
        if (nodes.isEmpty()) return null
        val bounds = calculateBounds(nodes, true)
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(bounds.width().toInt(), bounds.height().toInt(), 1).create()
        val page = pdfDocument.startPage(pageInfo)
        drawMap(page.canvas, bounds, nodes, relationships, isDark, mode, title)
        pdfDocument.finishPage(page)
        
        val uri = saveToSharedStorage(context, "ND_Export_${System.currentTimeMillis()}.pdf", "application/pdf") { os ->
            pdfDocument.writeTo(os)
        }
        pdfDocument.close()
        return uri
    }

    fun exportToSvg(context: Context, nodes: List<Node>, relationships: List<Relationship>, isDark: Boolean, mode: String, title: String): Uri? {
        if (nodes.isEmpty()) return null
        val bounds = calculateBounds(nodes, true)
        val svg = StringBuilder()
        val bgColor = if (isDark) "#0A0A0C" else "#F5F5F0"
        val textColor = if (isDark) "#FFFFFF" else "#000000"
        val borderColor = if (isDark) "rgba(255,255,255,0.2)" else "rgba(0,0,0,0.1)"
        
        svg.append("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""").append("\n")
        svg.append("""<svg width="${bounds.width()}" height="${bounds.height()}" xmlns="http://www.w3.org/2000/svg">""").append("\n")
        svg.append("""<rect width="100%" height="100%" fill="$bgColor"/>""").append("\n")
        
        // 1. Sidebar (SVG)
        val date = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        svg.append("""<text x="40" y="80" fill="$textColor" font-family="sans-serif" font-size="32" font-weight="bold">$title</text>""").append("\n")
        svg.append("""<text x="40" y="120" fill="gray" font-family="sans-serif" font-size="14">Exportiert am: $date</text>""").append("\n")
        
        if (mode == "mindmap") {
            svg.append("""<text x="40" y="200" fill="$textColor" font-family="sans-serif" font-size="20" font-weight="bold">LEGENDE (PESR)</text>""").append("\n")
            val pesr = listOf(
                Triple("P", "Problem", "#EF4444"),
                Triple("E", "Einfluss/Herkunft", "#FACC15"),
                Triple("S", "Symptome", "#F97316"),
                Triple("R", "Ressourcen", "#22C55E")
            )
            var curY = 240
            pesr.forEach { (c, l, col) ->
                svg.append("""<rect x="40" y="${curY-25}" width="40" height="30" rx="6" fill="$col"/>""").append("\n")
                svg.append("""<text x="60" y="${curY-5}" fill="white" font-family="sans-serif" font-size="16" text-anchor="middle">$c</text>""").append("\n")
                svg.append("""<text x="95" y="${curY}" fill="$textColor" font-family="sans-serif" font-size="18">$l</text>""").append("\n")
                curY += 50
            }
        }
        
        svg.append("""<line x1="${LEGEND_WIDTH-20}" y1="40" x2="${LEGEND_WIDTH-20}" y2="${bounds.height()-40}" stroke="$textColor" stroke-opacity="0.2" stroke-width="1"/>""").append("\n")

        // 2. Branding (SVG)
        svg.append("""<circle cx="55" cy="${bounds.height()-45}" r="8" fill="#6366F1"/>""").append("\n")
        svg.append("""<text x="70" y="${bounds.height()-40}" fill="gray" font-family="sans-serif" font-size="12">created with NeuroDumpling</text>""").append("\n")

        svg.append("""<g transform="translate(${-bounds.left + LEGEND_WIDTH}, ${-bounds.top})">""").append("\n")
        
        // 2. Hierarchy Lines (SVG)
        if (mode == "mindmap" || mode == "conceptmap") {
            nodes.forEach { node ->
                node.parentId?.let { pId ->
                    val parent = nodes.find { it.id == pId }
                    if (parent != null) {
                        val hasRel = relationships.any { (it.fromId == parent.id && it.toId == node.id) || (it.fromId == node.id && it.toId == parent.id) }
                        if (!hasRel) {
                            val sX = parent.x + parent.width/2; val sY = parent.y + parent.height/2
                            val eX = node.x + node.width/2; val eY = node.y + node.height/2
                            svg.append("""<line x1="$sX" y1="$sY" x2="$eX" y2="$eY" stroke="#6366F1" stroke-width="2" stroke-opacity="0.3"/>""").append("\n")
                        }
                    }
                }
            }
        }

        relationships.forEach { rel ->
            val from = nodes.find { it.id == rel.fromId }
            val to = nodes.find { it.id == rel.toId }
            if (from != null && to != null) {
                val color = when(rel.color) {
                    "danger", "red" -> "#EF4444"
                    "success", "green" -> "#22C55E"
                    else -> "#6366F1"
                }
                val sX = from.x + from.width/2
                val sY = from.y + from.height/2
                val eX = to.x + to.width/2
                val eY = to.y + to.height/2
                val dx = eX - sX; val dy = eY - sY
                val dist = sqrt(dx*dx + dy*dy).coerceAtLeast(1f)
                val pnx = -dy/dist; val pny = dx/dist
                val cpX = (sX+eX)/2 + pnx * rel.curveOffset
                val cpY = (sY+eY)/2 + pny * rel.curveOffset
                
                svg.append("""<path d="M $sX $sY Q $cpX $cpY $eX $eY" fill="none" stroke="$color" stroke-width="4" stroke-opacity="0.6" stroke-linecap="round"/>""").append("\n")
                
                if (!rel.label.isNullOrEmpty()) {
                    val t = rel.labelT; val invT = 1-t
                    val lx = invT*invT*sX + 2*invT*t*cpX + t*t*eX
                    val ly = invT*invT*sY + 2*invT*t*cpY + t*t*eY
                    svg.append("""<text x="$lx" y="$ly" fill="$textColor" font-family="sans-serif" font-size="24" text-anchor="middle">${rel.label.replace("&","&amp;").replace("<","&lt;")}</text>""").append("\n")
                }
            }
        }
        
        nodes.forEach { node ->
            val baseColor = when (node.color) {
                "accent" -> "#6366F1"
                "danger" -> "#EF4444"
                "success" -> "#22C55E"
                "info" -> "#00BCD4"
                else -> if (isDark) "#1E1E22" else "#FFFFFF"
            }
            svg.append("""<rect x="${node.x}" y="${node.y}" width="${node.width}" height="${node.height}" rx="16" ry="16" fill="$baseColor" stroke="$borderColor" stroke-width="2"/>""").append("\n")
            
            node.category?.let { cat ->
                val catColor = when(cat) {
                    "P" -> "#EF4444"; "E" -> "#FACC15"; "S" -> "#F97316"; "R" -> "#22C55E"; else -> "#808080"
                }
                svg.append("""<rect x="${node.x + 10}" y="${node.y - 15}" width="30" height="20" rx="6" ry="6" fill="$catColor"/>""").append("\n")
                svg.append("""<text x="${node.x + 25}" y="${node.y - 1}" fill="#FFFFFF" font-family="sans-serif" font-size="14" font-weight="black" text-anchor="middle">$cat</text>""").append("\n")
            }

            val tx = node.x + node.width/2
            val ty = node.y + node.height/2 + 7
            svg.append("""<text x="$tx" y="$ty" fill="$textColor" font-family="sans-serif" font-size="20" font-weight="bold" text-anchor="middle">${node.text.replace("&","&amp;").replace("<","&lt;")}</text>""").append("\n")
        }
        svg.append("</g></svg>").append("\n")
        
        return saveToSharedStorage(context, "ND_Export_${System.currentTimeMillis()}.svg", "image/svg+xml") { os ->
            os.write(svg.toString().toByteArray())
        }
    }

    fun exportToJson(context: Context, jsonString: String): Uri? {
        return saveToSharedStorage(context, "ND_Data_${System.currentTimeMillis()}.json", "application/json") { os ->
            os.write(jsonString.toByteArray())
        }
    }

    fun readTextFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { 
                it.bufferedReader().readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToSharedStorage(context: Context, filename: String, mimeType: String, writer: (java.io.OutputStream) -> Unit): Uri? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOCUMENTS + "/NeuroDumpling")
            }
            
            val uri = resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { writer(it) }
                return uri
            }
        } else {
            // Legacy approach for API < 29
            try {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                val ndDir = File(dir, "NeuroDumpling")
                if (!ndDir.exists()) ndDir.mkdirs()
                
                val file = File(ndDir, filename)
                FileOutputStream(file).use { writer(it) }
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                android.util.Log.e("ND_Export", "Legacy export failed", e)
            }
        }
        return null
    }

    private fun calculateBounds(nodes: List<Node>, includeLegend: Boolean): RectF {
        if (nodes.isEmpty()) return RectF(0f, 0f, 100f, 100f)
        val minX = nodes.minOf { it.x }
        val minY = nodes.minOf { it.y }
        val maxX = nodes.maxOf { it.x + it.width }
        val maxY = nodes.maxOf { it.y + it.height }
        
        val marginX = (nodes.firstOrNull()?.width ?: 150f) / 2
        val marginY = (nodes.firstOrNull()?.height ?: 80f) / 2
        
        val bounds = RectF(minX - marginX, minY - marginY, maxX + marginX, maxY + marginY)
        if (includeLegend) {
            // Expand width to accommodate legend on the left
            return RectF(bounds.left - LEGEND_WIDTH, bounds.top, bounds.right, bounds.bottom)
        }
        return bounds
    }

    private fun drawMap(canvas: Canvas, bounds: RectF, nodes: List<Node>, relationships: List<Relationship>, isDark: Boolean, mode: String, title: String) {
        // Background
        canvas.drawColor(if (isDark) Color.parseColor("#0A0A0C") else Color.parseColor("#F5F5F0"))
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        // 1. Sidebar (Bitmap/PDF)
        drawLegend(canvas, bounds, title, isDark, paint, mode)
        
        canvas.save()
        // Always translate for sidebar
        canvas.translate(-bounds.left + LEGEND_WIDTH, -bounds.top)
        
        // 2. Hierarchy Lines (Bitmap/PDF)
        if (mode == "mindmap" || mode == "conceptmap") {
            paint.color = Color.parseColor("#6366F1")
            paint.strokeWidth = 2f
            paint.style = Paint.Style.STROKE
            paint.alpha = 76 // 0.3 * 255
            
            nodes.forEach { node ->
                node.parentId?.let { pId ->
                    val parent = nodes.find { it.id == pId }
                    if (parent != null) {
                        val hasRel = relationships.any { (it.fromId == parent.id && it.toId == node.id) || (it.fromId == node.id && it.toId == parent.id) }
                        if (!hasRel) {
                            canvas.drawLine(
                                parent.x + parent.width / 2, parent.y + parent.height / 2,
                                node.x + node.width / 2, node.y + node.height / 2,
                                paint
                            )
                        }
                    }
                }
            }
        }

        // 3. Draw Relationships
        relationships.forEach { rel ->
            val from = nodes.find { it.id == rel.fromId }
            val to = nodes.find { it.id == rel.toId }
            if (from != null && to != null) {
                drawConnection(canvas, from, to, rel, isDark, paint)
            }
        }
        
        // 2. Draw Nodes
        nodes.forEach { node ->
            drawNode(canvas, node, isDark, paint)
        }
        
        canvas.restore()
    }

    private fun drawLegend(canvas: Canvas, bounds: RectF, title: String, isDark: Boolean, paint: Paint, mode: String) {
        val margin = 40f
        var currentY = margin + 40f
        
        // 1. Title
        paint.style = Paint.Style.FILL
        paint.color = if (isDark) Color.WHITE else Color.BLACK
        paint.textSize = 32f
        paint.textAlign = Paint.Align.LEFT
        paint.isFakeBoldText = true
        canvas.drawText(title, margin, currentY, paint)
        
        currentY += 40f
        paint.textSize = 14f
        paint.isFakeBoldText = false
        paint.color = Color.GRAY
        val date = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        canvas.drawText("Exportiert am: $date", margin, currentY, paint)
        
        if (mode == "mindmap") {
            currentY += 80f
            paint.textSize = 20f
            paint.color = if (isDark) Color.WHITE else Color.BLACK
            paint.isFakeBoldText = true
            canvas.drawText("LEGENDE (PESR)", margin, currentY, paint)
            
            currentY += 40f
            val items = listOf(
                Triple("P", "Problem", "#EF4444"),
                Triple("E", "Einfluss/Herkunft", "#FACC15"),
                Triple("S", "Symptome", "#F97316"),
                Triple("R", "Ressourcen", "#22C55E")
            )
            
            items.forEach { (code, label, colorStr) ->
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor(colorStr)
                val badgeRect = RectF(margin, currentY - 25f, margin + 40f, currentY + 5f)
                canvas.drawRoundRect(badgeRect, 6f, 6f, paint)
                
                paint.color = Color.WHITE
                paint.textSize = 16f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(code, badgeRect.centerX(), badgeRect.centerY() + 6f, paint)
                
                paint.color = if (isDark) Color.WHITE else Color.BLACK
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 18f
                canvas.drawText(label, margin + 55f, currentY, paint)
                
                currentY += 50f
            }
        }
        
        // Separator line
        paint.color = if (isDark) Color.WHITE else Color.BLACK
        paint.alpha = 50
        canvas.drawLine(LEGEND_WIDTH - 20f, margin, LEGEND_WIDTH - 20f, bounds.height() - margin, paint)
        paint.alpha = 255

        // 4. Branding
        val bottomY = bounds.height() - 40f
        paint.textSize = 14f
        paint.color = Color.GRAY
        paint.textAlign = Paint.Align.LEFT
        paint.isFakeBoldText = false
        canvas.drawText("created with NeuroDumpling", margin + 30f, bottomY, paint)
        
        // Small icon placeholder
        paint.color = Color.parseColor("#6366F1")
        paint.style = Paint.Style.FILL
        canvas.drawCircle(margin + 15f, bottomY - 5f, 8f, paint)
    }

    private fun drawConnection(canvas: Canvas, from: Node, to: Node, rel: Relationship, isDark: Boolean, paint: Paint) {
        val startX = from.x + from.width / 2
        val startY = from.y + from.height / 2
        val endX = to.x + to.width / 2
        val endY = to.y + to.height / 2
        
        val dx = endX - startX
        val dy = endY - startY
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val pnx = -dy / dist
        val pny = dx / dist
        
        val cpX = (startX + endX) / 2 + pnx * rel.curveOffset
        val cpY = (startY + endY) / 2 + pny * rel.curveOffset
        
        val startEdge = getEdgePoint(startX, startY, from.width, from.height, cpX, cpY)
        val endEdge = getEdgePoint(endX, endY, to.width, to.height, cpX, cpY)
        
        val color = when(rel.color) {
            "danger", "red" -> Color.parseColor("#EF4444")
            "success", "green" -> Color.parseColor("#22C55E")
            else -> Color.parseColor("#6366F1")
        }
        
        paint.color = color
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        paint.alpha = 150
        
        val path = Path()
        path.moveTo(startEdge.x, startEdge.y)
        path.quadTo(cpX, cpY, endEdge.x, endEdge.y)
        canvas.drawPath(path, paint)
        
        // Arrowhead
        drawArrowhead(canvas, cpX, cpY, endEdge.x, endEdge.y, color, paint)
        
        // Label
        if (!rel.label.isNullOrEmpty()) {
            paint.style = Paint.Style.FILL
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = 255
            paint.color = if (isDark) Color.WHITE else Color.BLACK
            
            val t = rel.labelT
            val invT = 1 - t
            val lx = invT * invT * startEdge.x + 2 * invT * t * cpX + t * t * endEdge.x
            val ly = invT * invT * startEdge.y + 2 * invT * t * cpY + t * t * endEdge.y
            canvas.drawText(rel.label, lx, ly, paint)
        }
    }

    private fun drawNode(canvas: Canvas, node: Node, isDark: Boolean, paint: Paint) {
        val rect = RectF(node.x, node.y, node.x + node.width, node.y + node.height)
        
        // Background
        val baseColor = when (node.color) {
            "accent" -> Color.parseColor("#6366F1")
            "danger" -> Color.parseColor("#EF4444")
            "success" -> Color.parseColor("#22C55E")
            "info" -> Color.parseColor("#00BCD4")
            else -> if (isDark) Color.parseColor("#1E1E22") else Color.WHITE
        }
        
        paint.style = Paint.Style.FILL
        paint.color = baseColor
        if (node.color != null) paint.alpha = if (isDark) 200 else 50 else paint.alpha = 255
        
        canvas.drawRoundRect(rect, 16f, 16f, paint)
        
        // Border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1A000000")
        paint.alpha = 255
        canvas.drawRoundRect(rect, 16f, 16f, paint)
        
        // Category Badge
        node.category?.let { cat ->
            val catColor = when(cat) {
                "P" -> Color.parseColor("#EF4444")
                "E" -> Color.parseColor("#FACC15")
                "S" -> Color.parseColor("#F97316")
                "R" -> Color.parseColor("#22C55E")
                else -> Color.GRAY
            }
            paint.style = Paint.Style.FILL
            paint.color = catColor
            val badgeRect = RectF(node.x + 10f, node.y - 15f, node.x + 40f, node.y + 5f)
            canvas.drawRoundRect(badgeRect, 6f, 6f, paint)
            
            paint.color = Color.WHITE
            paint.textSize = 14f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(cat, badgeRect.centerX(), badgeRect.centerY() + 5f, paint)
        }
        
        // Text
        paint.style = Paint.Style.FILL
        paint.color = if (isDark) Color.WHITE else Color.BLACK
        paint.textSize = 20f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        
        val textX = rect.centerX()
        val textY = rect.centerY() - ((paint.descent() + paint.ascent()) / 2)
        canvas.drawText(node.text, textX, textY, paint)
    }

    private fun getEdgePoint(cx: Float, cy: Float, w: Float, h: Float, tx: Float, ty: Float): PointF {
        val dx = tx - cx
        val dy = ty - cy
        if (dx == 0f && dy == 0f) return PointF(cx, cy)
        
        val halfW = w / 2
        val halfH = h / 2
        
        val scaleW = if (dx != 0f) abs(halfW / dx) else Float.MAX_VALUE
        val scaleH = if (dy != 0f) abs(halfH / dy) else Float.MAX_VALUE
        
        val scale = min(scaleW, scaleH).coerceAtMost(1f)
        return PointF(cx + dx * scale, cy + dy * scale)
    }

    private fun drawArrowhead(canvas: Canvas, cpX: Float, cpY: Float, endX: Float, endY: Float, color: Int, paint: Paint) {
        val angle = atan2(endY - cpY, endX - cpX)
        val arrowSize = 20f
        val arrowAngle = Math.toRadians(30.0).toFloat()

        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = 4f
        paint.alpha = 255
        
        val p1x = endX - arrowSize * cos(angle - arrowAngle)
        val p1y = endY - arrowSize * sin(angle - arrowAngle)
        val p2x = endX - arrowSize * cos(angle + arrowAngle)
        val p2y = endY - arrowSize * sin(angle + arrowAngle)
        
        canvas.drawLine(endX, endY, p1x, p1y, paint)
        canvas.drawLine(endX, endY, p2x, p2y, paint)
    }
}
