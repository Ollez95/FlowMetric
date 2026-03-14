package com.flowmetric.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.min

@Composable
fun rememberFlowMetricIconPainter(): Painter = remember {
    object : Painter() {
        override val intrinsicSize: Size = Size(256f, 256f)

        override fun DrawScope.onDraw() {
            val iconSize = min(size.width, size.height)
            val inset = iconSize * 0.08f
            val surfaceSize = Size(iconSize - inset * 2, iconSize - inset * 2)
            val topLeft = Offset(
                x = (size.width - surfaceSize.width) / 2f,
                y = (size.height - surfaceSize.height) / 2f,
            )
            val radius = CornerRadius(iconSize * 0.18f, iconSize * 0.18f)

            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF10263B),
                        Color(0xFF21405B),
                    ),
                    start = topLeft,
                    end = Offset(topLeft.x + surfaceSize.width, topLeft.y + surfaceSize.height),
                ),
                topLeft = topLeft,
                size = surfaceSize,
                cornerRadius = radius,
            )

            drawRoundRect(
                color = Color(0x14FFF5E8),
                topLeft = Offset(topLeft.x + iconSize * 0.02f, topLeft.y + iconSize * 0.02f),
                size = Size(surfaceSize.width - iconSize * 0.04f, surfaceSize.height - iconSize * 0.04f),
                cornerRadius = CornerRadius(iconSize * 0.15f, iconSize * 0.15f),
            )

            val chartBaseY = topLeft.y + surfaceSize.height * 0.74f
            val barWidth = surfaceSize.width * 0.12f
            val barGap = surfaceSize.width * 0.07f
            val firstBarX = topLeft.x + surfaceSize.width * 0.18f
            val barRadius = CornerRadius(barWidth * 0.45f, barWidth * 0.45f)
            val barHeights = listOf(0.22f, 0.34f, 0.48f)
            val barColors = listOf(
                Color(0xFFF6E7D5),
                Color(0xFF9BD1BF),
                Color(0xFFF6E7D5),
            )

            barHeights.forEachIndexed { index, fraction ->
                val barHeight = surfaceSize.height * fraction
                val x = firstBarX + index * (barWidth + barGap)
                drawRoundRect(
                    color = barColors[index],
                    topLeft = Offset(x, chartBaseY - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = barRadius,
                )
            }

            val flowPath = Path().apply {
                moveTo(topLeft.x + surfaceSize.width * 0.16f, topLeft.y + surfaceSize.height * 0.58f)
                cubicTo(
                    topLeft.x + surfaceSize.width * 0.28f,
                    topLeft.y + surfaceSize.height * 0.44f,
                    topLeft.x + surfaceSize.width * 0.40f,
                    topLeft.y + surfaceSize.height * 0.63f,
                    topLeft.x + surfaceSize.width * 0.52f,
                    topLeft.y + surfaceSize.height * 0.49f,
                )
                cubicTo(
                    topLeft.x + surfaceSize.width * 0.62f,
                    topLeft.y + surfaceSize.height * 0.37f,
                    topLeft.x + surfaceSize.width * 0.76f,
                    topLeft.y + surfaceSize.height * 0.50f,
                    topLeft.x + surfaceSize.width * 0.85f,
                    topLeft.y + surfaceSize.height * 0.32f,
                )
            }

            drawPath(
                path = flowPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF08A36),
                        Color(0xFFFAAE54),
                    ),
                    start = Offset(topLeft.x, topLeft.y),
                    end = Offset(topLeft.x + surfaceSize.width, topLeft.y + surfaceSize.height),
                ),
                style = Stroke(
                    width = iconSize * 0.06f,
                    cap = StrokeCap.Round,
                ),
            )

            val dots = listOf(
                Offset(topLeft.x + surfaceSize.width * 0.18f, topLeft.y + surfaceSize.height * 0.56f),
                Offset(topLeft.x + surfaceSize.width * 0.51f, topLeft.y + surfaceSize.height * 0.50f),
                Offset(topLeft.x + surfaceSize.width * 0.85f, topLeft.y + surfaceSize.height * 0.32f),
            )
            val dotColors = listOf(
                Color(0xFFFCE5C8),
                Color(0xFF9BD1BF),
                Color(0xFFFCE5C8),
            )

            dots.forEachIndexed { index, center ->
                drawCircle(
                    color = dotColors[index],
                    radius = iconSize * 0.045f,
                    center = center,
                )
                drawCircle(
                    color = Color(0xFF10263B),
                    radius = iconSize * 0.018f,
                    center = center,
                )
            }
        }
    }
}
