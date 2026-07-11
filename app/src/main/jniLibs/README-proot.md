# proot 终端：获取并部署 proot 二进制

NexFiles 的内置终端需要 **proot 二进制**才能挂载 Linux rootfs。出于体积和平台构建的
考虑，二进制本身不随仓库分发——需要你按以下步骤交叉编译并放入 jniLibs。

## 为什么需要单独提供

proot 是一个原生可执行文件（非 Java 库），它用 `ptrace` 实现用户态文件系统重定向和
伪 root。它必须在目标设备架构上以原生代码运行。仓库里不放二进制是因为：

1. 它要按目标 ABI 分别交叉编译（arm64-v8a / armeabi-v7a / x86_64）
2. 二进制较大（~1-2MB/ABI），且许可证（GPLv2+）与项目 GPLv3 兼容但需单独注明
3. 不同 Android 版本的 ptrace 行为差异可能需要特定补丁版本

## 部署位置

```
app/src/main/jniLibs/<abi>/libproot.so
```

注意文件名必须是 `libproot.so`（即使它不是共享库）——这是 Android 的约定：jniLibs 里
只装 `.so` 后缀的文件，它们会被安装到 `nativeLibraryDir`（**不可写**路径，绕过 W^X 限制，
使 proot 能被 exec）。`RootfsManager.PROOT_BINARY_NAME` 和 `prootBinaryPath()` 按这个名字
查找。

## 交叉编译步骤（arm64-v8a，推荐）

### 方法 A：用 Termux 的预编译（最快）

Termux 已为 Android 编译好 proot。从 Termux 包仓库取对应 ABI 的二进制：

```bash
# arm64-v8a
wget https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.0-56_aarch64.deb
mkdir proot-extract && cd proot-extract
ar x ../proot_5.1.0-56_aarch64.deb
tar xf data.tar.xz
# 二进制在 data/data/com.termux/files/usr/bin/proot
cp data/data/com.termux/files/usr/bin/proot ../libproot.so
```

放到 `app/src/main/jniLibs/arm64-v8a/libproot.so`。

### 方法 B：从源码交叉编译

```bash
git clone --recursive https://github.com/proot/proot.git
cd proot
# 需要 Android NDK 的 aarch64 工具链
export NDK=/path/to/android-ndk
export CC=$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android34-clang
make -C src proot CARE=0   # CARE（可选的 libcare）不需要
cp src/proot /path/to/NexFiles/app/src/main/jniLibs/arm64-v8a/libproot.so
```

## 验证

构建并安装 APK 后，在文件列表点"在终端中打开"：
1. 首次会提示选 Alpine/Debian → 下载 → 解压
2. 进入终端后跑 `whoami`（应显示 root，proot 的伪 root）、`ls /`（应见 Linux 目录结构）
3. Alpine：`apk add vim && vim`；Debian：`apt update && apt install vim && vim`

如果 `prootBinaryPath()` 返回 null（提示"proot missing"），检查：
- jniLibs 路径和文件名（必须 `libproot.so`）
- 设备 ABI（`adb shell getprop ro.product.cpu.abi` 应为 `arm64-v8a`）
- APK 是否真的打包了该 .so（解包 APK 检查 `lib/arm64-v8a/libproot.so` 存在）

## 其它 ABI

当前构建只支持 arm64-v8a（覆盖 99% 现代设备）。要支持 armeabi-v7a/x86_64，把对应 ABI
编译的 proot 放到 `jniLibs/<abi>/`，并在 `RootfsManager.prootBinaryPath()` 和
`TerminalDistro` 的 ABI 检查里放开。

## 许可证

proot 是 GPLv2+。与本项目 GPLv3 兼容。分发二进制时需在 NOTICE/LICENSE 里注明 proot 的
来源和许可证。
