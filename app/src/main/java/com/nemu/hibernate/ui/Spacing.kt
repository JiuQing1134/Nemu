package com.nemu.hibernate.ui

import androidx.compose.ui.unit.dp

/**
 * Gap between adjacent status chips that form one status group
 * (e.g. 已开启 + 开机冻结 33, Root + 已授权, category + 已卸载).
 * Capsules draw their own 6dp inner padding; this is the layout gap.
 */
val StatusChipGap = 3.dp

/** Gap between adjacent filter chips (全部 / 开机冻结 / 已卸载 …). */
val FilterChipGap = 8.dp

/**
 * Table rule for header→content rows: labels stay flush-left (first glyph
 * aligned) and the same gap follows every label. Because 总开关 is one
 * character shorter than 开机冻结, the following value starts one character
 * earlier; the gap itself never changes.
 */
val TableContentGap = 8.dp
