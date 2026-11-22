package com.nemu.hibernate.ui

import androidx.compose.runtime.Composable
import com.nemu.hibernate.ui.theme.NemuAppTheme

/**
 * 兼容入口：MainActivity 仍然调用 `NemuTheme`，实际视觉由
 * `com.nemu.hibernate.ui.theme.NemuAppTheme` 提供。
 */
@Composable
fun NemuTheme(content: @Composable () -> Unit) {
    NemuAppTheme(content = content)
}
