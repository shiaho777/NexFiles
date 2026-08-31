# NexFiles

**一个开源、深度理解 Linux 的 Android 文件管理器 —— 内置逆向分析工具箱。**

[![CI](https://github.com/shiaho777/NexFiles/actions/workflows/android.yml/badge.svg)](https://github.com/shiaho777/NexFiles/actions/workflows/android.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-green.svg)](README_zh-CN.md)
[![English docs](https://img.shields.io/badge/docs-English-yellow.svg)](README.md)

[English version](README.md)

> NexFiles 是一个独立项目，基于 [Hai Zhang](https://github.com/zhanghai) 的
> [Material Files](https://github.com/zhanghai/MaterialFiles) 分支开发。
> 核心文件管理器（NIO2 后端、Material Design 界面）来自那个优秀的原项目；
> 下文 [NexFiles 新增能力](#nexfiles-新增能力) 一节所描述的一切都是本仓库在其之上开发的功能。

<p align="center">
  <img src="docs/assets/architecture.svg" alt="NexFiles 架构：UI 层之下是 ViewModel/LiveData 状态层，之下是 Java NIO2 provider SPI，11 个可插拔文件系统 provider，以及分支新增的逆向工程栈" width="720">
</p>

## 特性

- **整洁的 Material Design** —— 遵循规范并注重细节：面包屑导航、平板双窗格、可定制配色与纯黑夜间模式。
- **深度理解 Linux** —— 类似 [Nautilus](https://apps.gnome.org/Nautilus/)：符号链接、文件权限、SELinux 上下文都是一等公民。底层通过 JNI 走 Linux 系统调用，而不是又一个 [`ls` 解析器](https://news.ycombinator.com/item?id=7994720)；路径以原始字节存储，非 UTF-8 文件名也能完整往返。
- **文件在哪里，管理就在哪里** —— 11 个文件系统 provider：本地（syscall）、root、压缩包（zip/tar/7z）、FTP、SFTP、SMB、WebDAV、SAF/文档、content URI，以及远程 fd 传递 provider。
- **健壮的工程实现** —— 基于 Java NIO2 文件 API 与 ViewModel/LiveData，正确处理文件操作错误、文件冲突和前台/后台状态。

## NexFiles 新增能力

Material Files 是一个出色的白纸起点。NexFiles 把它扩展成一件"还要看进文件内部"的工具：

- **免 root 运行时 Hook** —— 把目标 app 加载进隔离的 `:sandbox` 进程，在其中用
  [lsplant](https://github.com/LSPosed/LSPlant) + [ShadowHook](https://github.com/bytedance/android-inline-hook)
  hook 它的方法。无需 root、无需 debuggable、不碰 SELinux——目标崩溃只崩沙箱。另保留 ptrace 注入作为 root 降级路径。见 [沙箱 hook 方案](#沙箱-hook-方案)。
- **内置终端** —— 真 PTY（native `forkpty`）+ VT100 模拟器，proot 运行 Alpine/Debian rootfs，并有以 shell UID 运行的 Shizuku 路径。
- **APK 工具链** —— 签名查看（v1/v2/v3/v3.1 方案 + X.509 证书详情与指纹）、APK 签名（v1/v2/v3）、签名剥离、分包安装（`.apks`/`.xapk`/`.apkm`）、已安装应用提取。
- **深度查看与编辑** —— 支持文件内查找的文本编辑器、十六进制编辑器、AXML/ARSC 检视器、DEX 浏览/编辑（字符串池、基于 dexlib2 的 const-string 补丁）、图片与媒体查看器。
- **进阶文件管理** —— 正则/通配符 + 类型/大小/时间过滤的递归搜索、压缩包内编辑（copy-on-write 覆盖层）、回收站、批量校验和（MD5/SHA-1/SHA-256）、批量重命名、双窗格布局、对外共享文件的 FTP 与 WebDAV 服务端。
- **性能工程** —— 五轮有文档记录的优化，包括修复本地文件系统上 DiffUtil 相等性语义从未真正生效的问题，以及后台异步列表 differ。

<p align="center">
  <img src="docs/assets/stats.svg" alt="统计卡片：76.0k 行 Kotlin（779 个文件）、11 个文件系统 provider、2,615 行 JNI C/C++、32 个 AIDL 接口、provider 24.7k 行、hook 2,556 行、viewer 6,004 行、terminal 1,867 行" width="720">
</p>

## 预览

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="32%" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="32%" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="32%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="32%" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="32%" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="32%" />
</p>

## 架构

前端刻意朴素：`ViewModel` + `LiveData` + `RecyclerView`，不用 Compose。有意思的决策都在后端——
每个文件系统都是真正的 `java.nio.file.FileSystemProvider`，因此文件操作可以跨 provider 组合
（从 SFTP 拷进压缩包、对 root 文件算校验和），共用同一条代码路径。

<p align="center">
  <img src="docs/assets/perf-pipeline.svg" alt="列表刷新管线前后对比：正则重编译、主线程 DiffUtil、identity 相等性全量重绑，被懒编译、工作线程异步 differ、仅 payload 定向重绑取代" width="720">
</p>

### 沙箱 hook 方案

免 root 全局 hook（注入 Zygote）在 Android 10–14 上被 SELinux `neverallow` 彻底封死——这是 OS 级
边界，不是工程能绕过的。NexFiles 走了 MT 管理器和 LSPosed 都没走通的路：不去注入别人的进程，
而是把**目标 app 的代码加载进我们自己的沙箱进程**——在这里 lsplant 拥有完全的 `ArtMethod` 访问权，
不需要任何特殊权限。

<p align="center">
  <img src="docs/assets/hook-paths.svg" alt="两条 hook 路径：默认的沙箱路径免 root 把目标 APK 加载进隔离进程；ptrace 路径以 root attach 真实进程；两者共享 lsplant 核心" width="720">
</p>

## 构建

```sh
git clone https://github.com/shiaho777/NexFiles.git
cd NexFiles
./gradlew assembleDebug
```

- JDK 17+、Android SDK 36、NDK（JNI 部分：syscall 绑定、终端 PTY、hook 桥）。
- 终端的 proot 二进制需手动提供——见 `jniLibs/README-proot.md`。
- 签名：把 `signing.properties.example` 复制为 `signing.properties`（debug 构建可跳过）。

CI 在每次推送时构建 `assembleDebug lintVitalRelease`（[workflow](.github/workflows/android.yml)）。

## 路线图

已交付功能与精确的实施蓝图见 [ROADMAP.md](ROADMAP.md)。

## 上游致谢与许可证

NexFiles 采用与上游一致的 **GPLv3**。原文件管理器的设计与实现全部归功于
[Hai Zhang](https://github.com/zhanghai) 的 [Material Files](https://github.com/zhanghai/MaterialFiles)——
本项目建立在那份工作之上。

    Copyright (C) 2018 Hai Zhang (Material Files)
    Copyright (C) NexFiles contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
