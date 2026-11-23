package com.nemu.hibernate.core

import com.nemu.hibernate.TestSources
import com.nemu.hibernate.detection.FreezeMode
import com.nemu.hibernate.detection.FreezeSource
import com.nemu.hibernate.detection.FreezeState
import com.nemu.hibernate.ui.CatalogTargetUiState
import com.nemu.hibernate.ui.TargetActualUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T01–T12 from docs/17 §5.5. The point is to fail when copy drifts, not to
 * test Compose rendering; UI-facing strings are pure functions in TargetCopy.
 */
class CopyContractTest {
    private val presetPrefix =
        "^(观察|暂停|冻结|组件|停用|隐藏|限制后台|限制待机)："

    @Test
    fun t01_functionLinesCoverAllTargetsAndCustomFallback() {
        assertEquals(41, TargetCatalog.all.size)
        TargetCatalog.all.forEach { target ->
            assertTrue(
                "${target.packageName} 缺少功能行",
                TargetCopy.functionLine(target.packageName).isNotBlank()
            )
        }
        assertEquals("用户添加的应用", TargetCopy.functionLine("com.example.custom"))
    }

    @Test
    fun t02_functionLinesAreTerse() {
        TargetCatalog.all.forEach { target ->
            val line = TargetCopy.functionLine(target.packageName)
            assertTrue("${target.packageName} 过长：$line", line.length <= 24)
            assertFalse(line.contains("。"))
            assertFalse(line.contains("\n"))
            assertFalse(line.startsWith("这个应用"))
        }
    }

    @Test
    fun t03_impactLinesHaveWhitelistedPrefixAndLength() {
        StrategyPreset.entries.forEach { preset ->
            val line = TargetCopy.label(preset) + "：" + TargetCopy.impact(preset)
            assertTrue(
                "$preset 过长（${line.length}）：$line",
                line.length <= 28
            )
            assertTrue(
                "$preset 前缀不符：$line",
                line.matches(Regex("$presetPrefix[^\\n]+$"))
            )
            assertFalse("$preset 含句号：$line", line.contains("。"))
        }
        TargetCatalog.all.forEach { target ->
            StrategyPreset.entries.forEach { preset ->
                val line = TargetCopy.label(preset) + "：" +
                    TargetCopy.impactFor(target, preset)
                assertTrue(
                    "${target.packageName}/$preset 过长（${line.length}）：$line",
                    line.length <= 28
                )
                assertFalse(
                    "${target.packageName}/$preset 含句号：$line",
                    line.contains("。")
                )
            }
        }
    }

    @Test
    fun t04_unavailableLinesMatchFixedShape() {
        TargetCatalog.all.forEach { target ->
            TargetCopy.presetsFor(target).forEach { preset ->
                val reason = TargetCopy.unavailableReason(target, preset)
                    ?: return@forEach
                val line = "${TargetCopy.label(preset)}：不可用（$reason）"
                assertTrue(
                    "${target.packageName} 不可用行不符：$line",
                    line.matches(
                        Regex("${presetPrefix}不可用（[^）]{1,12}）$")
                    )
                )
            }
        }
    }

    @Test
    fun t05_everyPresetIsEitherAvailableOrExplained() {
        val registeredRestrictions = setOf(
            "com.hihonor.securityserver",
            "com.hihonor.awareness"
        )
        val union = TargetCatalog.all
            .flatMap { TargetCopy.presetsFor(it) }
            .toSet()
        assertEquals(StrategyPreset.entries.toSet(), union)

        TargetCatalog.all.forEach { target ->
            val expected = TargetCopy.mainPresets.toSet() +
                if (target.packageName in registeredRestrictions) {
                    setOf(
                        StrategyPreset.RESTRICT_BACKGROUND,
                        StrategyPreset.RESTRICT_STANDBY
                    )
                } else {
                    emptySet()
                }
            assertEquals(
                "${target.packageName} 预设集合不符",
                expected,
                TargetCopy.presetsFor(target).toSet()
            )
            TargetCopy.presetsFor(target).forEach { preset ->
                val reason = TargetCopy.unavailableReason(target, preset)
                if (reason != null) {
                    assertTrue(
                        "${target.packageName}/${preset} 原因为空",
                        reason.isNotBlank()
                    )
                }
            }
        }
    }

    @Test
    fun t06_statusWordsAreShortAndWhitelisted() {
        val allowed = setOf(
            // CatalogTargetUiState.actualText()
            "已隐藏", "他人隐藏", "隐藏待确认", "未隐藏",
            "组件已停用", "组件启用", "状态未知",
            "已停用·已暂停", "已停用", "已暂停·进程存活", "已暂停",
            "已停用·已冻结", "已冻结·进程存活",
            "他人暂停", "运行中", "未冻结",
            // FreezeState.statusLabel()
            "仅观察", "已限制", "未限制", "未安装", "读取失败",
            "组件状态未知", "组件部分停用", "不一致", "仍启用",
            "已暂停 · 存活", "已暂停 · 未知", "未暂停", "已冻结",
            "已冻结 · 存活", "已冻结 · 未知", "未冻结"
        )
        val labels = mutableSetOf<String>()
        val target = TargetCatalog.all.first()
        FreezeStrategy.entries.forEach { strategy ->
            listOf(null, true, false).forEach { suspended ->
                listOf(null, true, false).forEach { enabled ->
                    listOf(null, true, false).forEach { running ->
                        listOf(null, true, false).forEach { components ->
                            listOf(null, true, false).forEach { hidden ->
                                listOf(false, true).forEach { owned ->
                                    listOf(false, true).forEach { applied ->
                                        labels += CatalogTargetUiState(
                                            target = target,
                                            strategy = strategy,
                                            applied = applied,
                                            actual = TargetActualUiState(
                                                suspended = suspended,
                                                packageEnabled = enabled,
                                                processRunning = running,
                                                hidden = hidden
                                            ),
                                            componentsDisabled = components,
                                            ownedHidden = owned
                                        ).actualText()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val base = FreezeState(
            packageName = "com.example.app",
            label = "示例",
            source = FreezeSource.CORE,
            mode = FreezeMode.SUSPEND,
            policyEnabled = true,
            installed = true,
            actualKnown = true,
            enabled = true,
            suspended = false
        )
        FreezeMode.entries.forEach { mode ->
            (listOf<StrategyPreset?>(null) + StrategyPreset.entries).forEach { preset ->
                listOf(true, false).forEach { suspended ->
                    listOf(true, false).forEach { enabled ->
                        listOf(null, true, false).forEach { running ->
                            listOf(null, true, false).forEach { components ->
                                listOf(false, true).forEach { restrictions ->
                                    labels += base.copy(
                                        mode = mode,
                                        preset = preset,
                                        suspended = suspended,
                                        enabled = enabled,
                                        processRunning = running,
                                        componentDisabled = components,
                                        restrictionApplied = restrictions
                                    ).statusLabel()
                                }
                            }
                        }
                    }
                }
            }
        }

        labels.forEach { label ->
            assertTrue("状态未登记：$label", label in allowed)
            assertTrue("状态过长：$label", label.length <= 12)
            assertFalse("状态含英文：$label", label.any { it in 'A'..'Z' || it in 'a'..'z' })
            assertFalse("状态含 DRY-RUN：$label", label.contains("DRY-RUN"))
        }
    }

    @Test
    fun t07_confirmDialogHasAtMostThreePrefixedLines() {
        TargetCatalog.all.forEach { target ->
            StrategyPreset.entries.forEach { preset ->
                listOf(false, true).forEach { dryRun ->
                    listOf(false, true).forEach { enableOnly ->
                        val lines = TargetCopy.confirmLines(
                            target = target,
                            preset = preset,
                            enableOnly = enableOnly,
                            dryRun = dryRun
                        )
                        assertTrue(
                            "${target.packageName}/$preset 行数 ${lines.size}",
                            lines.size <= 3
                        )
                        lines.forEach { line ->
                            assertTrue(
                                "弹窗前缀不符：$line",
                                line.matches(Regex("^(执行|影响|恢复|计划)：.+"))
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun t08_noForbiddenPhrasesInCopy() {
        val forbidden = listOf(
            "这个应用是做什么的",
            "彻底伪装",
            "挂起",
            "最温和",
            "更彻底",
            "永久",
            "再也无法",
            "无法恢复"
        )
        allCopyStrings().forEach { line ->
            forbidden.forEach { word ->
                assertFalse("禁用词「$word」出现在：$line", line.contains(word))
            }
        }
    }

    @Test
    fun t09_uiNeverPrintsStrategyEnumNames() {
        val pattern = Regex("(strategy|previous)\\.name")
        TestSources.uiFiles().forEach { (name, text) ->
            assertFalse("$name 直接渲染枚举名", pattern.containsMatchIn(text))
        }
    }

    @Test
    fun t10_internalDescriptionsAndOldExplainApisAreNotRendered() {
        val forbidden = listOf(
            "target.description",
            "explain(",
            "genericConsequence(",
            "plainActualText("
        )
        TestSources.uiFiles().forEach { (name, text) ->
            forbidden.forEach { token ->
                assertFalse("$name 渲染了内部文案：$token", text.contains(token))
            }
        }
    }

    @Test
    fun t11_verifiedClaimsMustCiteDeviceEvidence() {
        val doc = TestSources.read("docs/18-target-matrix-2026-11-22.md")
        doc.lineSequence()
            .filter { it.contains("已验证") }
            .forEach { line ->
                assertTrue(
                    "“已验证”缺少 PF 证据：$line",
                    line.contains("不得") || line.contains("PF")
                )
            }
    }

    @Test
    fun t12_pseudoUninstallOnlyAppearsWithHide() {
        allCopyStrings().forEach { line ->
            if (line.contains("伪卸载")) {
                assertTrue("“伪卸载”必须与“隐藏”同句：$line", line.contains("隐藏"))
            }
            assertFalse("只允许说“隐藏”，不写“伪装卸载”：$line", line.contains("伪装卸载"))
        }
    }

    private fun allCopyStrings(): List<String> = buildList {
        TargetCatalog.all.forEach {
            add(TargetCopy.functionLine(it.packageName))
        }
        StrategyPreset.entries.forEach { preset ->
            add(TargetCopy.label(preset))
            add(TargetCopy.impact(preset))
            add(TargetCopy.restoreLine(preset))
            TargetCatalog.all.forEach { target ->
                add(
                    "${TargetCopy.label(preset)}：" +
                        TargetCopy.impactFor(target, preset)
                )
                TargetCopy.unavailableReason(target, preset)?.let { add(it) }
                addAll(
                    TargetCopy.confirmLines(
                        target = target,
                        preset = preset,
                        enableOnly = false,
                        dryRun = true
                    )
                )
            }
        }
        FreezeStrategy.entries.forEach { strategy ->
            add(TargetCopy.label(strategy))
            add(TargetCopy.impact(strategy))
            add(TargetCopy.restoreLine(strategy))
            TargetCatalog.all.forEach { target ->
                TargetCopy.unavailableReason(target, strategy)?.let { add(it) }
            }
        }
    }
}
