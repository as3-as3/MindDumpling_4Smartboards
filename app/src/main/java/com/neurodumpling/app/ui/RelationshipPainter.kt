package com.neurodumpling.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.*
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Paint
import android.graphics.Rect

object RelationshipPainter {
    fun drawConnection(
        drawScope: DrawScope,
        from: Offset,
        to: Offset,
        fromSize: Offset,
        toSize: Offset,
        color: Color,
        curveOffset: Float = 30f,
        labelT: Float = 0.5f,
        showArrow: Boolean = true
    ) {
        val centerStart = Offset(from.x + fromSize.x / 2, from.y + fromSize.y / 2)
        val centerEnd = Offset(to.x + toSize.x / 2, to.y + toSize.y / 2)

        val dx = centerEnd.x - centerStart.x
        val dy = centerEnd.y - centerStart.y
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        
        // Perpendicular vector for curve
        val pnx = -dy / dist
        val pny = dx / dist
        
        val cpX = (centerStart.x + centerEnd.x) / 2 + pnx * curveOffset
        val cpY = (centerStart.y + centerEnd.y) / 2 + pny * curveOffset

        // Intersection points with node edges
        val startEdge = getEdgePoint(centerStart, fromSize, Offset(cpX, cpY))
        val endEdge = getEdgePoint(centerEnd, toSize, Offset(cpX, cpY))

        val path = Path().apply {
            moveTo(startEdge.x, startEdge.y)
            quadraticBezierTo(cpX, cpY, endEdge.x, endEdge.y)
        }

        drawScope.drawPath(
            path = path,
            color = color.copy(alpha = 0.6f),
            style = Stroke(width = 5f, cap = StrokeCap.Round)
        )
        
        // Draw Arrowhead only if requested
        if (showArrow) {
            drawArrowhead(drawScope, cpX, cpY, endEdge.x, endEdge.y, color)
        }
    }

    private fun getEdgePoint(center: Offset, size: Offset, target: Offset): Offset {
        val dx = target.x - center.x
        val dy = target.y - center.y
        if (dx == 0f && dy == 0f) return center
        
        // Tightened padding for precise docking
        val halfW = size.x / 2
        val halfH = size.y / 2
        
        val scaleW = if (dx != 0f) abs(halfW / dx) else Float.MAX_VALUE
        val scaleH = if (dy != 0f) abs(halfH / dy) else Float.MAX_VALUE
        
        val scale = min(scaleW, scaleH).coerceAtMost(1f)
        return Offset(center.x + dx * scale, center.y + dy * scale)
    }

    private fun drawArrowhead(drawScope: DrawScope, cpX: Float, cpY: Float, endX: Float, endY: Float, color: Color) {
        val angle = atan2(endY - cpY, endX - cpX)
        val arrowSize = 20f
        val arrowAngle = Math.toRadians(30.0).toFloat()

        val p1 = Offset(
            endX - arrowSize * cos(angle - arrowAngle),
            endY - arrowSize * sin(angle - arrowAngle)
        )
        val p2 = Offset(
            endX - arrowSize * cos(angle + arrowAngle),
            endY - arrowSize * sin(angle + arrowAngle)
        )

        drawScope.drawLine(color, Offset(endX, endY), p1, strokeWidth = 5f, cap = StrokeCap.Round)
        drawScope.drawLine(color, Offset(endX, endY), p2, strokeWidth = 5f, cap = StrokeCap.Round)
    }
}
