package com.nemu.hibernate.core

/**
 * Short, stable presentation for persisted boot-freeze results. The raw
 * strings in SharedPreferences include legacy long sentences such as
 * "手动选择性解冻：本次开机不再自动冻结"; a status chip can only show a few
 * characters, so the UI maps them to a token and keeps the raw text as detail.
 */
data class BootStatusView(
    val chip: String,
    val detail: String? = null,
    /** Short reason for the "this boot is skipped" note; null when not a skip. */
    val skipReason: String? = null
)

object BootStatusCopy {
    fun of(raw: String?): BootStatusView {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return BootStatusView("尚未执行")
        return when {
            text.startsWith("手动选择性解冻") -> skipped("手动解冻")
            text.startsWith("手动全部解冻") -> skipped("全部解冻")
            text.startsWith("已跳过") -> skippedSkip(text)
            text.startsWith("预览") -> BootStatusView("预览 · 未改动", text)
            text.startsWith("没有启用") || text == "无计划" ->
                BootStatusView("无计划")
            text == "尚未执行" -> BootStatusView("尚未执行")
            text == "成功" -> BootStatusView("成功")
            text == "失败" -> BootStatusView("失败")
            text.startsWith("部分成功") ->
                BootStatusView("部分成功", text)
            text.startsWith("开机冻结成功") ->
                BootStatusView("成功 · 开机冻结")
            text.startsWith("手动冻结成功") ->
                BootStatusView("成功 · 手动冻结")
            text.startsWith("策略切换成功") ->
                BootStatusView("成功 · 策略切换")
            text.contains("失败") -> BootStatusView("失败 · 冻结", text)
            text.contains("成功") -> BootStatusView("成功 · 冻结", text)
            else -> BootStatusView("已记录", text)
        }
    }

    private fun skipped(reason: String): BootStatusView = BootStatusView(
        chip = "已跳过 · $reason",
        detail = "本次开机不自动冻结",
        skipReason = reason
    )

    private fun skippedSkip(text: String): BootStatusView {
        val reason = text.removePrefix("已跳过")
            .trimStart('：', ':', ' ')
        val short = when {
            reason.contains("手动") -> "手动解冻"
            reason.contains("全部解冻") -> "全部解冻"
            reason.contains("干净关机") || reason.contains("守卫") -> "守卫触发"
            reason.contains("窗口") || reason.contains("超时") -> "窗口已过"
            reason.contains("无法确认") || reason.contains("未知") -> "状态未知"
            reason.contains("救援") || reason.contains("救砖") -> "救援模式"
            reason.contains("未开启") -> "未开启"
            reason.contains("用户已跳过") -> "用户跳过"
            reason.contains("操作") || reason.contains("执行") ||
                reason.contains("处理") -> "已操作"
            else -> reason.takeIf { it.isNotBlank() } ?: "已跳过"
        }
        return BootStatusView(
            chip = "已跳过 · $short",
            detail = "本次开机不自动冻结",
            skipReason = short
        )
    }
}
