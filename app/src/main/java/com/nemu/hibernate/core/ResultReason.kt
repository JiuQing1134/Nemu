package com.nemu.hibernate.core

/**
 * Machine-readable outcome reasons. Services return these instead of shell
 * output so the UI can render one short Chinese line; raw stdout/stderr stays
 * in [com.nemu.hibernate.data.ActionLog].
 *
 * [blocking] only affects the `ok` calculation of an apply result. Restore
 * results decide `ok` explicitly because some notes (e.g. "already disabled")
 * are informational while others must fail the run.
 */
enum class ResultReason(
    val line: String,
    val blocking: Boolean = false
) {
    BASELINE_READ_FAILED("无法读取原始状态，未执行"),
    BASELINE_WRITE_FAILED("无法写入恢复基线，未执行"),
    ALREADY_DISABLED("原本已停用，保持原状态"),
    ALREADY_RESTRICTED("已被其他设置限制，未接管"),
    ALREADY_VISIBLE("已可见，清理旧标记"),
    HIDDEN_BY_OTHER("操作前已被隐藏，不接管"),
    SUSPENDED_BY_OTHER("操作前已被暂停，不接管"),
    HIDE_ROLLED_BACK("隐藏未确认，已回滚"),
    HIDE_NOT_CONFIRMED("无法确认隐藏状态", blocking = true),
    HIDE_STATE_UNKNOWN("无法确认隐藏状态", blocking = true),
    UNHIDE_NOT_CONFIRMED("未确认解除隐藏", blocking = true),
    HIDE_NEEDS_ROOT_APPLY("伪装卸载需要 Root 权限", blocking = true),
    HIDE_NEEDS_ROOT_RESTORE("解除隐藏需要 Root 权限", blocking = true),
    RESTRICTION_NOT_CONFIRMED("限制未生效", blocking = true),
    RESTORE_NOT_CONFIRMED("恢复未生效", blocking = true),
    MARKER_WRITE_FAILED("救援标记写入失败，未执行", blocking = true),
    MARKER_CLEAR_FAILED("救援标记未清除", blocking = true),
    OWNERSHIP_WRITE_FAILED("所有权写入失败，未执行", blocking = true),
    COMMAND_FAILED("命令执行失败", blocking = true),
    COMMAND_TIMEOUT("命令超时，状态未知，已保留恢复记录", blocking = true),
    STILL_SUSPENDED("仍处于暂停", blocking = true),
    COMPONENT_STILL_DISABLED("组件仍停用", blocking = true),
    COMPONENT_NOT_DISABLED("组件未停用", blocking = true),
    COMPONENT_STATE_UNKNOWN("组件状态未知", blocking = true),
    COMPONENT_MISSING("组件已不存在，已清理"),
    PACKAGE_MISSING("应用已卸载，已清理所有权"),
    COMPONENT_OWNERSHIP_FAILED("组件所有权标记失败", blocking = true),
    NO_COMPONENTS("没有可停用的组件", blocking = true),
    COMPONENTS_ALL_DISABLED("组件已全部停用"),
    COMPONENT_BASELINE_MISSING("缺少组件原始状态，未恢复"),
    NO_REGISTERED_COMPONENTS("没有登记组件，无需恢复"),
    PACKAGE_NOT_CONFIRMED("系统未确认变更", blocking = true),
    PACKAGE_STATE_UNKNOWN("状态未知，未执行", blocking = true),
    PROCESS_STATE_UNKNOWN("进程状态未知"),
    PROCESS_STILL_RUNNING("进程仍存活"),
    BACKEND_UNAVAILABLE("无可用权限", blocking = true),
    NOT_INSTALLED("当前用户未安装", blocking = true),
    HIDE_NOT_OWNED("没有本应用的隐藏所有权", blocking = true),
    RESTORE_UNVERIFIED("恢复后状态未确认", blocking = true),
    PARTIAL_SUCCESS("部分成功"),
    PRECHECK_FAILED("预检未通过", blocking = true),
    NO_SELECTION("没有可用目标", blocking = true),
    NO_ENABLED_TARGETS("没有启用的目标"),
    COMPONENT_SELECTION_EMPTY("至少选择 1 个组件", blocking = true),
    RESCUE_MODE("自动救砖模式，已拒绝执行", blocking = true),
    SUSPEND_RESIDUAL("暂停残留未清除"),
    UNKNOWN("操作未完成");

    fun at(subject: String, detail: String? = null): ResultNote =
        ResultNote(this, subject.ifBlank { null }, detail)
}

/**
 * A UI-safe explanation. [subject] is the app/component label, [detail] is a
 * short Chinese parameter; never pass shell output here.
 */
data class ResultNote(
    val reason: ResultReason,
    val subject: String? = null,
    val detail: String? = null
) {
    fun line(): String = ResultCopy.line(this)
}

object ResultCopy {
    /** "天气：救援标记写入失败，未执行（风险不支持）" */
    fun line(note: ResultNote): String = buildString {
        if (!note.subject.isNullOrBlank()) {
            append(note.subject)
            append("：")
        }
        append(note.reason.line)
        if (!note.detail.isNullOrBlank()) {
            append("（")
            append(note.detail)
            append("）")
        }
    }

    /**
     * One target/action summary. The subject is already shown once, so the
     * reasons inside the parentheses omit it to avoid "天气：失败（天气：…）".
     */
    fun resultLine(
        ok: Boolean,
        subject: String,
        reasons: List<ResultNote>,
        qualifier: String? = null
    ): String {
        val outcome = (qualifier ?: "") + if (ok) "成功" else "失败"
        val head = if (subject.isBlank()) outcome else "$subject：$outcome"
        if (reasons.isEmpty()) return head
        val body = reasons.take(3).joinToString("；") { brief(it) }
        val suffix = if (reasons.size > 3) "；…" else ""
        return "$head（$body$suffix）"
    }

    /** Batch line: "有失败：尝试 3，成功 1，失败 2；天气：…". */
    fun runSummary(
        ok: Boolean,
        attempted: Int,
        applied: Int,
        failed: Int,
        reasons: List<ResultNote>
    ): String = buildString {
        append(if (ok) "成功" else "有失败")
        append("，尝试 ")
        append(attempted)
        append("，成功 ")
        append(applied)
        append("，失败 ")
        append(failed)
        if (reasons.isNotEmpty()) {
            append("；")
            append(reasons.take(4).joinToString("；") { line(it) })
            if (reasons.size > 4) append("；…")
        }
    }

    private fun brief(note: ResultNote): String = buildString {
        append(note.reason.line)
        if (!note.detail.isNullOrBlank()) {
            append("：")
            append(note.detail)
        }
    }
}
