package com.nemu.hibernate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 三套可切换的视觉风格。
 *
 * - [MATERIAL3]：标准 Material 3，最克制。
 * - [M3E2025]：Material 3 Expressive 2025 观感，更大的圆角、更粗的标题。
 * - [MIUIX]：MIUI 风格，圆角更均匀、字重更轻、行距更紧凑。
 *
 * 枚举名（`name`）稳定，可直接用于 DataStore 持久化。
 */
enum class ThemeStyle(val label: String) {
    MATERIAL3("Material 3（标准）"),
    M3E2025("M3E（大圆角）"),
    MIUIX("MIUIX（紧凑）")
}

/**
 * 可切换的配色方案。每种配色都提供完整的浅色 / 深色 [androidx.compose.material3.ColorScheme]，
 * 见 [colorSchemeFor]。
 *
 * [swatchColor] 是供设置页色板使用的代表色（浅色主题下的 primary）。
 * 枚举名（`name`）稳定，可直接用于 DataStore 持久化。
 */
enum class ThemePalette(
    val label: String,
    val swatchColor: Color
) {
    /** 松林绿（默认）：整页控件都从绿色与白色之间取色。 */
    MINT("松林绿", Color(0xFF28805A)),

    /** 冰川蓝。 */
    ICE("冰川蓝", Color(0xFF236B85)),

    /** 暖琥珀。 */
    PEACH("暖琥珀", Color(0xFF834B25)),

    /** 极光紫。 */
    LAVENDER("极光紫", Color(0xFF4D3177)),

    /** 石墨灰。 */
    GRAPHITE("石墨灰", Color(0xFF4C565D)),

    /** 樱花粉：和看板娘图标呼应的柔和粉色。 */
    SAKURA("樱花粉", Color(0xFFB33B63))
}

/**
 * 明暗模式。[SYSTEM] 跟随系统设置，[LIGHT] / [DARK] 强制指定。
 *
 * 枚举名（`name`）稳定，可直接用于 DataStore 持久化。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}
