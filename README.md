# DirtyFrag-Android-Root/Jailbreak（中文汉化版）

基于 DirtyFrag（CVE-2026-43284）的 Android Root 利用链，独立 DFRoot fork。

## 已成功越狱设备

- **拯救者 Y70 新一代**（Legion Y70）
- **拯救者 Y700 五代**（Legion Y700 2025）

## 设备支持

内置辅助模块覆盖 **8 种 Android KMI/内核版本组合**
（`android12-5.10`、`android13-5.10`、`android13-5.15`、`android14-5.15`、
`android14-6.1`、`android15-6.6`、`android16-6.12`、`android17-6.18`）。
KMI 匹配只是初步兼容性检查，内核配置、导出符号、SELinux 行为和设备路径同样重要。

## 使用方法

下载最新 APK，安装后打开 **DirtyFrag Android Root**。

### Root 管理器

启动前先安装兼容的管理器。自动加载器按以下顺序检测已安装管理器的 `ksud`：
KernelSU → ReSukiSU → 系统 ksud 路径；以上全部找不到时，自动遍历 `/data/app` 全局搜索 `libksud.so`。

- [KernelSU Manager](https://github.com/tiann/KernelSU/releases)
- [ReSukiSU Manager](https://github.com/ReSukiSU/ReSukiSU/releases)

1. 安装 APK 并打开 **DirtyFrag Android Root**
2. 在设置中确认设备信息和检测到的 KMI
3. 保持自动 `ksud` 选择，或手动选择兼容的自定义加载器
4. 点击 **启动 Root** 并查看运行日志

### 自定义 patch #2 目标

如果设备 vendor 库路径不同，在 **设置 → 高级 patch #2 目标** 中每行输入一个已存在的 `/vendor/` 或 `/system/vendor/` 库路径。

## 利用概述

利用构造的 ESP 流量和页缓存写原语，将辅助代码写入映射的系统文件，触发特权 hook，加载 DirtyFrag 辅助模块。辅助模块调用暂存的 `ksud` 加载器。清理阶段恢复临时库补丁。

项目基于以下工作：

- DirtyFrag PoC：<https://github.com/lsposed/lspromise>
- SELinux 辅助模块：<https://github.com/polygraphene/DFReroot>
- 非特权 XFRM socket 方法：<https://github.com/combeng6th/DirtyInit>
