package com.nemu.hibernate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AllAppsClassificationTest {
    @Test
    fun rootManagersAreTheirOwnCategory() {
        listOf(
            "me.yuki.fina",
            "me.weishu.kernelsu",
            "com.topjohnwu.magisk",
            "com.rifsxd.ksunext",
            "com.sukisu.ultra",
            "me.bmax.apatch"
        ).forEach { packageName ->
            assertEquals(
                packageName,
                AppKind.ROOT,
                classifyApp(packageName, "tool", systemFlag = true)
            )
        }
        // Future/forked managers without a known package are matched by the
        // manager name; a root-requesting label alone does not qualify.
        assertEquals(
            AppKind.ROOT,
            classifyApp("com.example.manager", "WildKSU", systemFlag = false)
        )
        assertEquals(
            AppKind.USER,
            classifyApp("com.example.rootapp", "Root Tool", systemFlag = false)
        )
    }

    @Test
    fun shizukuItselfAndItsClientAppsAreSeparateCategories() {
        assertEquals(
            AppKind.SHIZUKU,
            classifyApp(
                "moe.shizuku.privileged.api",
                "Shizuku",
                systemFlag = false
            )
        )
        assertEquals(
            AppKind.SHIZUKU,
            classifyApp("roro.stellar.manager", "Stellar", systemFlag = false)
        )
        assertEquals(
            AppKind.SHIZUKU_APP,
            classifyApp(
                "me.timschneeberger.shizustore",
                "ShizuStore",
                systemFlag = false
            )
        )
        assertEquals(
            AppKind.SHIZUKU_APP,
            classifyApp(
                "dev.zwander.installwithoptions",
                "Install with Options",
                systemFlag = false
            )
        )
        assertEquals(
            AppKind.SHIZUKU_APP,
            classifyApp(
                "io.github.vvb2060.packageinstaller",
                "Shizuku 安装器",
                systemFlag = false
            )
        )
        // Root-requesting tools without module files stay user tools…
        assertEquals(
            AppKind.USER,
            classifyApp("com.aistra.hail", "Hail", systemFlag = false)
        )
        assertEquals(
            AppKind.USER,
            classifyApp("com.omarea.vtools", "Scene", systemFlag = false)
        )
        // …but once the APK carries an Xposed module (meta-data or
        // META-INF/xposed/module.prop), it is an LSP plugin.
        assertEquals(
            AppKind.LSP_PLUGIN,
            classifyApp(
                "com.aistra.hail",
                "Hail",
                systemFlag = false,
                hasXposedModuleFiles = true
            )
        )
        assertEquals(
            AppKind.LSP_PLUGIN,
            classifyApp(
                "com.omarea.vtools",
                "Scene",
                systemFlag = false,
                xposedModule = true
            )
        )
        assertEquals(
            AppKind.LSP_PLUGIN,
            classifyApp(
                "io.github.xiaotong6666.fusehide",
                "FuseHide",
                systemFlag = false,
                hasXposedModuleFiles = true
            )
        )
        // A future/forked module is detected the same way, without a list.
        assertEquals(
            AppKind.LSP_PLUGIN,
            classifyApp(
                "com.example.futuremodule",
                "Future Module",
                systemFlag = false,
                hasXposedModuleFiles = true
            )
        )
        // An app store that merely supports silent install is a user app.
        assertEquals(
            AppKind.USER,
            classifyApp(
                "zed.rainxch.githubstore",
                "Komi Store",
                systemFlag = false
            )
        )
    }

    @Test
    fun lsposedManagerAndPluginsAreSeparateCategories() {
        assertEquals(
            AppKind.LSPOSED,
            classifyApp("org.lsposed.manager", "LSPosed", systemFlag = false)
        )
        assertEquals(
            AppKind.LSP_PLUGIN,
            classifyApp(
                "com.example.module",
                "智软件",
                systemFlag = false,
                xposedModule = true
            )
        )
        // Manager wins even if it also carries the module flag.
        assertEquals(
            AppKind.LSPOSED,
            classifyApp(
                "org.lsposed.manager",
                "LSPosed",
                systemFlag = false,
                xposedModule = true
            )
        )
    }

    @Test
    fun preinstalledSystemAppsAreSystem() {
        assertEquals(
            AppKind.SYSTEM,
            classifyApp("com.hihonor.ouc", "软件更新", systemFlag = true)
        )
        assertEquals(
            AppKind.SYSTEM,
            classifyApp("cn.honor.qinxuan", "荣耀亲选", systemFlag = true)
        )
        // Factory preloads are system apps even when the brand is third-party;
        // only user-installed packages lack FLAG_SYSTEM/UPDATED_SYSTEM_APP.
        assertEquals(
            AppKind.SYSTEM,
            classifyApp(
                "com.baidu.input_hihonor",
                "百度输入法荣耀版",
                systemFlag = true
            )
        )
        // A preinstalled package that is also an Xposed module keeps the
        // plugin role so the freeze warning names the right subsystem.
        assertEquals(
            AppKind.LSP_PLUGIN,
            classifyApp(
                "com.example.systemmodule",
                "System Module",
                systemFlag = true,
                hasXposedModuleFiles = true
            )
        )
        assertEquals(
            AppKind.SYSTEM,
            classifyApp("com.android.systemui", "系统界面", systemFlag = true)
        )
        assertEquals(
            AppKind.SYSTEM,
            classifyApp("android", "Android 系统", systemFlag = true)
        )
        assertEquals(
            AppKind.SYSTEM,
            classifyApp(
                "android.ext.services",
                "Android Services Library",
                systemFlag = true
            )
        )
        assertEquals(
            AppKind.SYSTEM,
            classifyApp(
                "vendor.qti.iwlan",
                "IWLAN",
                systemFlag = true
            )
        )
        // A vendor-named package installed by the user is still a user app.
        assertEquals(
            AppKind.USER,
            classifyApp(
                "vendor.example.tool",
                "Vendor Tool",
                systemFlag = false
            )
        )
    }

    @Test
    fun userInstalledAppsAreTheUserCategory() {
        assertEquals(
            AppKind.USER,
            classifyApp("com.tencent.mm", "微信", systemFlag = false)
        )
        assertEquals(
            AppKind.USER,
            classifyApp("com.example.app", "Example", systemFlag = false)
        )
        // A user-installed app cannot fake system status through its package
        // name; only PackageManager's preinstall flags matter.
        assertEquals(
            AppKind.USER,
            classifyApp("vendor.qti.fake", "QTI Tool", systemFlag = false)
        )
    }
}
