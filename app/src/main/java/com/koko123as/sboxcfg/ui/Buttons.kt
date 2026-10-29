package com.koko123as.sboxcfg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ④ 全 App 按键视觉规范（v2.2）：
 * - PrimaryButton：主要操作，全宽，48.dp 高，15.sp，文字单行不折行；
 * - SecondaryButton：次要操作，48.dp 高，13.sp，文字单行不折行，danger 时红色文字；
 * - ButtonRow：次要按钮组，居中对齐，8.dp 间距；子按钮用 weight(1f) 等宽。
 * 弹窗的确定/取消保持 TextButton（Material 惯例，不动）。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp)
    ) {
        AutoSizeText(text, fontSize = 15.sp)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp)
    ) {
        // v3.1：溢出自动缩小字号（兜底；v3.1.1 起按钮已改短为"保存模版"）
        AutoSizeText(
            text,
            fontSize = 13.sp,
            color = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
