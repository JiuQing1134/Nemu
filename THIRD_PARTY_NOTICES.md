# Third-party notices

## FlClash / prism_ui

Nemu 的配色与外观 token 参考并移植自：

- FlClash — https://github.com/chen08209/FlClash
- prism_ui — https://github.com/chen08209/prism_ui

参考提交：FlClash `afe7136002271fd64f098b154e7fff40be721eb8`，
prism_ui `8f5fc22ca1a6bccb064589494ca69cedb38edb50`。

这两个项目使用 Apache License 2.0 授权：
https://www.apache.org/licenses/LICENSE-2.0

Nemu 没有复制 Flutter 运行时；颜色由
`tools/theme_gen.py` 按同一 HCT 算法预计算，
组件以 Jetpack Compose 重新实现。

## Material Color Utilities

颜色计算使用 Google Material Color Utilities（Apache License 2.0）的
HCT 实现，经由 Python 包 `materialyoucolor` 生成静态颜色表：
https://github.com/material-foundation/material-color-utilities
