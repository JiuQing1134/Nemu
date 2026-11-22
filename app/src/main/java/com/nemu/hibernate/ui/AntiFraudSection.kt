package com.nemu.hibernate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.AntiFraudResult
import com.nemu.hibernate.core.AntiFraudTargets
import com.nemu.hibernate.core.NumberIdentityMode

data class AntiFraudUiState(
    val config: AntiFraudConfig,
    val results: List<AntiFraudResult>,
    val lastResult: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiFraudSection(
    state: AntiFraudUiState,
    busy: Boolean,
    onApply: (AntiFraudConfig) -> Unit,
    onRestore: () -> Unit
) {
    var draft by remember(state.config) { mutableStateOf(state.config) }
    var pendingApply by remember { mutableStateOf<AntiFraudConfig?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var disableAcknowledged by remember { mutableStateOf(false) }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "反诈与安全（高级，默认关闭）",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "与更新/AI 列表分开管理。securityserver 与 awareness 永不整包暂停/停用，只能限制后台与待机。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "可能降低反诈、安全或人脸活体能力；系统更新后开关可能被重置。预览模式下只记录命令（设置会保存，但不执行）。",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "号码识别（${AntiFraudTargets.NUMBER_IDENTITY}）· 黄",
                style = MaterialTheme.typography.titleSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberIdentityMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.numberIdentityMode == mode,
                        onClick = {
                            draft = draft.copy(numberIdentityMode = mode)
                        },
                        label = { Text(numberIdentityLabel(mode)) },
                        enabled = !busy
                    )
                }
            }
            Text(
                "暂停不阻止已绑定的系统服务；更彻底请用停用。停用后需“恢复反诈设置”。",
                style = MaterialTheme.typography.bodySmall
            )

            SecurityRestrictionRow(
                title = "安全公共服务（${AntiFraudTargets.SECURITY_SERVER}）· 红",
                checked = draft.restrictSecurityServer,
                enabled = !busy,
                description = "只禁止后台活动并限制待机（AppOps/standby）；不整包暂停/停用；反诈/安全服务可能降级。",
                onCheckedChange = {
                    draft = draft.copy(restrictSecurityServer = it)
                }
            )
            SecurityRestrictionRow(
                title = "情景感知（${AntiFraudTargets.AWARENESS}）· 红",
                checked = draft.restrictAwareness,
                enabled = !busy,
                description = "仅限制后台/待机；可能影响反诈、人脸活体/深伪。",
                onCheckedChange = {
                    draft = draft.copy(restrictAwareness = it)
                }
            )

            Text(
                "安全插件底座（${AntiFraudTargets.SECURITY_PLUGIN_BASE}）· 黄：仅观察，不提供一键操作。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "已知问题：部分机型 securityserver（libdexkit）存在开机崩溃，与本模块无关；不要归因于 Nemu。",
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pendingApply = draft },
                    enabled = !busy && draft.isActive
                ) { Text("应用反诈设置") }
                OutlinedButton(
                    onClick = { showRestoreConfirm = true },
                    enabled = !busy
                ) { Text("恢复反诈设置") }
            }
            Text(
                "取消勾选会随下一次“应用反诈设置”恢复对应限制；号码识别切回“不处理”不会自动恢复，请用“恢复反诈设置”。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "上次反诈操作：${state.lastResult}",
                style = MaterialTheme.typography.bodySmall
            )
            state.results.takeLast(20).forEach { result ->
                Text(resultLine(result), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    pendingApply?.let { config ->
        val commands = AntiFraudPolicy.planApply(config, state.config)
        val needsDisableAck =
            config.numberIdentityMode == NumberIdentityMode.DISABLE_USER
        AlertDialog(
            onDismissRequest = {
                pendingApply = null
                disableAcknowledged = false
            },
            title = { Text("确认反诈设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "将执行 ${commands.size} 条命令：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    commands.forEach { command ->
                        Text(
                            "${command.packageName} · ${command.action.label}\n" +
                                command.shell,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "这些设置可能降低反诈、安全或人脸活体能力；系统更新后可能需要重新应用。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (needsDisableAck) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = disableAcknowledged,
                                onCheckedChange = { disableAcknowledged = it }
                            )
                            Text(
                                "我确认停用号码识别，并接受失去来电识别/诈骗标记",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingApply = null
                        disableAcknowledged = false
                        onApply(config)
                    },
                    enabled = !needsDisableAck || disableAcknowledged
                ) { Text("确认执行") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingApply = null
                        disableAcknowledged = false
                    }
                ) { Text("取消") }
            }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("恢复反诈设置") },
            text = {
                Text(
                    "将取消号码识别的暂停/停用，并把 securityserver 与 awareness 的后台与待机限制恢复为 allow/active。不会卸载任何应用。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        onRestore()
                    }
                ) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestoreConfirm = false }
                ) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SecurityRestrictionRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

private fun numberIdentityLabel(mode: NumberIdentityMode): String = when (mode) {
    NumberIdentityMode.OFF -> "不处理"
    NumberIdentityMode.SUSPEND -> "暂停"
    NumberIdentityMode.DISABLE_USER -> "停用（更强）"
}

private fun resultLine(result: AntiFraudResult): String {
    val prefix = when {
        result.dryRun -> "预览"
        result.ok -> "OK"
        else -> "FAIL"
    }
    return "$prefix · ${result.packageName} · ${result.action.label}"
}
