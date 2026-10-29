package com.koko123as.sboxcfg.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * v3.1 单行文本溢出时自动缩小字号（兜底用）。
 *
 * 原理：overflow 必须设 Visible 才能让 onTextLayout 报告 hasVisualOverflow；
 * 一旦溢出就阶梯下调 1.sp，直到不溢出或触及下限。
 * - minFontSize：下限，默认 10.sp；
 * - maxAdjusts：防抖上限，默认 10 次（避免极端窄宽度下无限重组）；
 * - text/fontSize 变化时重置。
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = 1,
    minFontSize: TextUnit = 10.sp,
    maxAdjusts: Int = 10
) {
    val base = when {
        fontSize != TextUnit.Unspecified -> fontSize
        style.fontSize != TextUnit.Unspecified -> style.fontSize
        else -> 14.sp
    }
    var size by remember(text, base) { mutableStateOf(base) }
    var adjusts by remember(text, base) { mutableStateOf(0) }
    Text(
        text = text,
        modifier = modifier,
        fontSize = size,
        color = color,
        style = style,
        maxLines = maxLines,
        softWrap = false,
        // 必须 Visible，Ellipsis 会吞掉 overflow 信号
        overflow = TextOverflow.Visible,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && adjusts < maxAdjusts) {
                val next = (size.value - 1f).coerceAtLeast(minFontSize.value)
                if (next < size.value) {
                    size = next.sp
                    adjusts++
                }
            }
        }
    )
}
