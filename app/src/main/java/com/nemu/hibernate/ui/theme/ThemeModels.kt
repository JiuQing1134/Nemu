package com.nemu.hibernate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * FlClash 的四种配色风格，对应上游 `ColorStyle`：
 *
 * - [VIVID]：鲜明（默认），高饱和主色 + 纸白卡片。
 * - [TINTED]：氛围，连页面与卡片都带一层主色。
 * - [SOFT]：柔和，降低主色饱和度。
 * - [MONOCHROME]：单色，只保留灰阶。
 *
 * 枚举名用于 DataStore 持久化。
 */
enum class ThemeStyle(val label: String) {
    VIVID("鲜明"),
    TINTED("氛围"),
    SOFT("柔和"),
    MONOCHROME("单色")
}

/**
 * FlClash 默认的八色 iOS 系统色板。`swatchColor` 是上游原始种子色，
 * 不是生成后的 primary；完整浅色 / 深色角色见 [colorSchemeFor]。
 *
 * 枚举名用于 DataStore 持久化。
 */
enum class ThemePalette(
    val label: String,
    val swatchColor: Color
) {
    /** FlClash 默认色 #007AFF。 */
    BLUE("蓝色", Color(0xFF007AFF)),

    MAGENTA("品红", Color(0xFFCB30E0)),

    ROSE("玫红", Color(0xFFFF2D55)),

    RED("红色", Color(0xFFFF383C)),

    ORANGE("橙色", Color(0xFFFF8D28)),

    YELLOW("黄色", Color(0xFFFFCC00)),

    GREEN("绿色", Color(0xFF34C759)),

    GRAPHITE("石墨", Color(0xFF8E8E93))
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
