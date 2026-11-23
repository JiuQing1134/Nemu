# 22 FlClash 配色与外观移植（2026-11-23）

> 取代 `docs/19-copymap-2026-11-22.md` §15 中 Nemu 自创的六套 HSL 色板。
> 参考快照：FlClash `afe7136002271fd64f098b154e7fff40be721eb8`，
> prism_ui `8f5fc22ca1a6bccb064589494ca69cedb38edb50`。

## 1. 口径

Nemu 不再自己调色。颜色、表面层级、圆角、字体比例、分段控件、开关、
按钮和底部导航按 FlClash / prism_ui 的实现移植；页面内容仍使用 Nemu 的
信息结构（FlClash 没有冻结工具对应的页面，无法逐像素照搬布局）。

- 配色引擎：`plugins/prism_ui/lib/color.dart` 的 `seededColorScheme()`
  （HCT / Material Color Utilities 2021 + FlClash 的 accent 与 surface 覆盖）。
- 色板：`lib/common/constant.dart` 的 `defaultPrimaryColors` 原值，八色。
- 表面层级：`plugins/prism_ui/lib/surface.dart` 的 page / card / block / inset。
- 形状：`plugins/prism_ui/lib/shape.dart` 的 `AppCorner`。
- 字体：`plugins/prism_ui/lib/typography.dart` 的 iOS Dynamic Type 比例。
- 组件：FlClash 的 connected segmented control、floating navigation dock、
  switch、tonal button、filter chip、色板圆点与对勾。
- 深色：`ColorSchemeExtension.toPureBlack()`，窗口 / 页面 `#000000`，
  卡片保留 pure-black 变换后的抬升灰阶。

## 2. 色板

| 枚举 | 中文 | 种子色 |
|---|---|---|
| `BLUE`（默认） | 蓝色 | `#007AFF` |
| `MAGENTA` | 品红 | `#CB30E0` |
| `ROSE` | 玫红 | `#FF2D55` |
| `RED` | 红色 | `#FF383C` |
| `ORANGE` | 橙色 | `#FF8D28` |
| `YELLOW` | 黄色 | `#FFCC00` |
| `GREEN` | 绿色 | `#34C759` |
| `GRAPHITE` | 石墨 | `#8E8E93` |

配色风格为 FlClash 的四个原值：

| 枚举 | 标签 | 行为 |
|---|---|---|
| `VIVID`（默认） | 鲜明 | content scheme，主色保持高饱和 |
| `TINTED` | 氛围 | 页面与卡片带主色 |
| `SOFT` | 柔和 | tonal spot，主色降饱和 |
| `MONOCHROME` | 单色 | 灰阶 |

默认蓝色鲜明浅色的关键角色：

| 角色 | 值 |
|---|---|
| page / background | `#EFEDF0` |
| card / surfaceBright | `#FCF8F9` |
| inset / surfaceContainerHighest | `#E4E2E4` |
| primary | `#007AFF` |
| primaryContainer | `#D8E2FF` |
| onSurface | FlClash `#181C23`（Nemu 文字墨色另见 §4） |
| onSurfaceVariant | FlClash `#414755`（Nemu 文字墨色另见 §4） |

完整浅色 / 深色角色由 `tools/flclash_theme_gen.py` 生成到
`app/src/main/java/com/nemu/hibernate/ui/theme/FlClashColorData.kt`。
运行时不需要引入颜色科学库。

## 3. 外观 token

| 项目 | 移植结果 |
|---|---|
| 卡片 | 无描边，26dp 分组卡片，首页 hero 34dp；浅色 3dp 阴影 |
| 页面 | 使用 FlClash 的 page 表面，亮色 `surfaceContainer`，深色 pure black |
| 卡片层级 | page / card / block / inset 四级，不使用 Material 默认灰阶 |
| 分段控件 | connected buttons：组两端 26dp 半圆、内角 10dp、选中项完整胶囊、3dp 间隔 |
| 分段选中 | 实心 `primary` + `onPrimary` |
| 色板圆点 | 44dp 单元、32dp 圆点、选中同色环 + 对勾；底框 26dp |
| 开关 | 52×32 胶囊，24dp 实心 thumb，无 Material 方形波纹 |
| 按钮 | 实心主色或 inset tonal，无 1dp 细边框 |
| 底部导航 | 浮动 58dp 胶囊 dock，两端留 16dp，选中项主色 + inset lens |
| 顶栏 | 24pt 页面标题，不再显示 Nemu 小图标；右侧授权状态 chip |
| 字体 | 17pt 正文、15pt 中号、13pt 辅助、17pt 半粗标题；标题/正文墨色加深 |

## 4. 用户要求的偏离

这些是在 FlClash 颜色系统之上的明确要求，不再算“自创配色”：

1. **深色永远纯黑**：页面、窗口和状态栏 `#000000`；卡片使用 FlClash
   pure-black 抬升灰阶。
2. **控件统一主色**：成功 / 信息 / 授权 / 主题 chip、选中 chip、开关、
   选中导航项、分段选中项、进度与按钮都使用当前色板的实心 `primary`；
   不再使用固定的绿色或 `secondaryContainer` 的淡色。
3. **高风险正红**：`#FF383C` + 白字；中风险 `#FF8D28` + 深色字。
4. **文字加深**：浅色 `onSurface #0A0A0A`、`onSurfaceVariant #2E2E2E`；
   深色 `onSurface #F7F7F7`、`onSurfaceVariant #CECECE`。FlClash 的结构色
   不变，只把文字墨色压深。
5. **控制件无细边框**：去掉卡片、搜索框和按钮上的 1dp 描边，改为 surface
   色差 / 阴影 / inset 填充；不再出现“细线不和谐”。
6. **开关无方形波纹**：`NemuSwitch` 的 `toggleable` 使用
   `indication = null`，解决点击“开机冻结”出现方框的问题；批量复选框
   也已裁圆。

## 5. 验证

- 主题移植时 `testDebugUnitTest`：36 个测试类 / 302 个测试 / 0 失败；
  2026-11-23 审计修复后为 59 个测试类 / 367 个测试 / 0 失败。
- 实机浅色：首页 hero、授权卡、应用页、检测页、设置/外观页均使用
  FlClash 蓝、纸白卡片、灰紫 page；选中分段为完整蓝色胶囊。
- 实机深色：窗口/页面纯黑；卡片为纯黑抬升灰阶；蓝色控件保持高饱和。
- 截图比对：FlClash 实机浅色 page `#EEECEF`、card `#FBF8F9`，与 Nemu
  输出 `#EFEDF0` / `#FCF8F9` 在屏幕取色误差内一致。
- 已安装 debug APK；未执行冻结/修改手机的操作。

## 6. 产物

| 包 | 字节 | SHA-256 |
|---|---:|---|
| `dist/Nemu-1.3.3-arm64-v8a-debug.apk` | 34,703,294 | `2133a2d7ebac10c02d8ed224c8f3b7d7d01f3f6800ea0a1b1e406569bdff03e7` |
| `dist/Nemu-1.3.3-x86_64-debug.apk` | 34,703,955 | `f04824414657cab8401f169c5ed351b2cd920020972937835f2f22adbf92cd0b` |
| `dist/Nemu-1.3.3-arm64-v8a-release.apk` | 24,510,244 | `a62c3a6b50bb79a3a83f12cd565f226ec66dc27c3774c6c1bab1a04d1203c606` |
| `dist/Nemu-1.3.3-x86_64-release.apk` | 24,510,905 | `4e6a480884483dddb75ac714a8783ef493aa2a246b11a897c8cdc71615e18c7d` |

> 2026-11-23 审计修复后重新构建；修复清单见
> `docs/23-audit-fixes-2026-11-23.md`。

生成器：`tools/flclash_theme_gen.py`。注意：生成器会重写
`FlClashColorData.kt`，文字墨色的用户加深在 `ColorPalettes.kt` 中完成，
不在生成数据里。
