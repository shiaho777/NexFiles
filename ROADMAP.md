# NexFiles 功能路线图

本文件记录已交付的功能增强，以及规划中但尚未实施的功能蓝图。蓝图条目均包含精确的
架构分析，作为后续实施的可靠指导——它们被推迟是因为在缺乏真机/native 验证的环境下
强行盲写会违背项目"确保功能有效性"的原则。

---

## 已交付（本轮）

| # | 功能 | 说明 |
|---|------|------|
| 1 | 递归 + 多条件搜索 | 通配符/正则匹配、类型/大小/时间过滤、结果高亮、过滤面板 |
| 2 | 文本编辑器增强 | 文件内查找（高亮+上下导航）、另存为 |
| 3 | 压缩创建选项 | 压缩级别、AES-256 加密（替代弱 ZipCrypto） |
| 4 | 常用密码管理 | 解压时自动尝试已存密码、"记住密码"复选框 |
| 5 | 视频/音频内置播放器 | 基于 VideoView 的轻量预览 |
| 6 | 回收站 | 本地文件删除移入回收站（设置开关） |
| 7 | 提取已安装应用 APK | 应用列表 → 选择 → 保存 |
| 8 | 批量校验和 | 多选文件 → 计算 MD5/SHA-1/SHA-256 → 复制 |
| 9 | APK 签名查看 | 直接读取 APK Signing Block（V1/V2/V3/V3.1 方案检测）+ X.509 证书详情（主题/颁发者/序列号/有效期/公钥/SHA-1/256/512 指纹）|
| 10 | fd 传递优化 | `RemoteSeekableByteChannel`/`RemoteInputStream` 首次访问时通过 `openFd()` 拿到 dup 的 fd，之后读写走 `Os.pread`/`pwrite`/`lseek`，零 Binder 往返；非 fd 通道自动回退到原 IPC 路径 |
| 11 | DEX 编辑器 | 基于 dexlib2（`org.smali:dexlib2:2.5.2`）：类列表 + 字符串池浏览、const-string/字段默认值字符串替换、写回时自动重算 checksum/signature |
| 12 | 运行时 Hook（免 root 主线已落地） | 见下文「运行时 Hook」章节：进程内托管（免 root，MT/LSPosed 都没做到）为主，ptrace 注入为 root 降级 |
| 13 | 双窗格 | sw600dp 横屏双 `FileListFragment`，共享 drawer/导航，激活窗格驱动菜单与返回 |
| 14 | 多包安装 | PackageInstaller Session 安装 `.apks`/`.xapk`/`.apkm`，支持多选 split APK |

---

## 运行时 Hook（免 root，进程内托管）

这是 4 个逆向功能里技术难度最高的一个，也是最有突破性的一个。

**MT 管理器和 LSPosed 都依赖 root**——它们靠 Magisk/Zygisk 注入 Zygote 来做全局 hook。
免 root 全局 hook（注入 Zygote）在 Android 10-14 上被 SELinux neverallow 彻底封死，
这是 OS 级边界，不是工程能绕过的（已通过源码核查确认）。

**但有一条 MT/LSPosed 都没走通的路**：与其和 SELinux 搏斗去注入别人的进程，**不如把
目标 app 的代码加载进我们自己的进程**。在我们自己的进程里，lsplant 有完全的 ArtMethod
访问权——不需要 root，不需要 debuggable，不碰 SELinux。目标 app 崩溃只崩沙箱进程，
主 app 不受影响。

### 双路径架构

```
主线（免 root，已落地）：
[HookTargetActivity]  ← 用户选目标 app
        ↓
[SandboxConnection]   ← 主进程，绑定 :sandbox 进程
        ↓ Binder IPC
[SandboxService]      ← :sandbox 独立进程
        ↓
[SandboxedAppLoader]  ← PathClassLoader/DexClassLoader 加载目标 APK
        ↓
[SandboxedHookSession] ← lsplant init（本进程内，无 SELinux 阻碍）
        ↓
[HookEngine] → [LsplantBridge] → lsplant::Hook（目标方法被拦截）

降级路径（需 root，ptrace 注入活进程）：
[RootHookService] → [HookInjector] → [PtraceRemoteInvoker]
→ libnexhook_inject.so ptrace attach + remote dlopen → libnexhook.so
→ lsplant 在目标真实进程内 hook
```

两条路径共享同一套 lsplant 核心（`LsplantBridge` / `HookEngine`）和 hook DSL
（`MethodHook`），只是交付机制不同。

### 已交付的文件

**免 root 主线（沙箱）**：
- `hook/sandbox/SandboxedAppLoader.kt`：用 `PathClassLoader`/`DexClassLoader` 把目标 APK
  加载进隔离 ClassLoader，支持已安装包和 APK 文件两条路径。
- `hook/sandbox/SandboxedHookSession.kt`：一次 hook 会话的生命周期，在沙箱进程内驱动
  lsplant 对目标方法逐个 hook。
- `hook/sandbox/SandboxService.kt`：跑在 `:sandbox` 独立进程的 Service，暴露 AIDL。
  目标代码在这个进程里运行，lsplant 有完全 ArtMethod 访问权。
- `hook/sandbox/SandboxConnection.kt`：主进程的 Binder 连接 + suspend 友好的高层 API。
- `hook/sandbox/HookRule.kt`：内置 hook 规则（返回常量/记录调用/替换字符串/阻断调用），
  因为 Binder 不能传 lambda。
- `hook/sandbox/ISandboxService.aidl` + Parcelable 类型。

**共享核心**：
- `hook/HookEngine.kt`：高层 hook DSL（`MethodHook(target, replacement)`）。
- `hook/LsplantBridge.kt`：nexhook.so 的 JNI 门面。
- `src/main/jni/nexhook.cpp`：C++17 bridge，实现 lsplant `InitInfo` 要求的三件事。

**root 降级路径（架构保留，待真机验证）**：
- `src/main/jni/ptrace_inject.cpp`：remote-dlopen 注入器。
- `hook/PtraceRemoteInvoker.kt` / `hook/HookInjector.kt` / `hook/RootHookService.kt`。

### 分析模式 UI（已落地）

| 文件 | 作用 |
|------|------|
| `hook/HookTargetActivity.kt` | 选目标 app → 加载到沙箱 → init lsplant |
| `hook/HookConfigActivity.kt` | 搜类 → 浏览方法 → 配 hook 规则 → 预设 → 启动分析 |
| `hook/HookLogActivity.kt` | 实时 hook 输出日志（LOG_CALLS 的结果流式展示） |
| `hook/sandbox/HookPresets.kt` | 一键预设：绕 root 检测 / 绕调试检测 / 记录网络 / 记录 SharedPreferences |
| `hook/sandbox/HookLogDispatcher.kt` | 进程内日志分发器，把 hook 活动从沙箱推回主进程 UI |

**完整分析流程**：
```
选 app → 加载到沙箱 → init lsplant(ShadowHook 自动激活)
  → 配 hook（手动选方法 或 用预设）
  → 启动分析（Application.onCreate 在 hook 下运行）
  → 查看 Hook 活动（实时日志流）
```

### 能力边界（诚实）

| 维度 | 沙箱（免 root，主线） | ptrace（root，降级） |
|------|---------------------|---------------------|
| 需要 root | ✗ | ✓ |
| 目标需 debuggable | ✗ | 生产版需要 |
| Hook 能力 | lsplant 全能力（同进程） | lsplant 全能力 |
| 目标多进程 | ✗（只 hook 沙箱内） | ✓（可注入每个进程） |
| 目标重启后 | 需重新加载 | 需重新注入 |
| 隔离性 | 沙箱隔离，安全实验 | 污染真实 app |
| Activity UI | ✓（沙箱内显示目标 UI） | 目标真实 UI |

**沙箱支持「运行模式」**：加载目标 APK → init lsplant（ShadowHook 后端）→ hook 目标方法
→ 调用 Application.onCreate → 安装 SandboxedActivityLauncher（替换 ActivityThread 的
Instrumentation，让 Activity 用目标的 ClassLoader 和 Resources）→ 启动主 Activity，
目标界面在沙箱进程内显示。

### inline-hook 后端（已落地）

lsplant 委派 native inline hook 给宿主——这是 lsplant::Init 能否成功的命门。我们接入了
**ShadowHook**（ByteDance 的生产级 inline-hook 库，MIT 许可，Maven Central prefab）：

- `com.bytedance.android:shadowhook:2.0.1` 作为 prefab 依赖
- `nexhook.cpp` 的 `shadowhook_inline_hooker` 把 lsplant 的 `inline_hooker(target, hooker)`
  契约翻译为 `shadowhook_hook_func_addr(target, hooker, &orig_addr)`，返回 backup trampoline
- `shadowhook_inline_unhooker` 维护 backup→stub 映射，把 lsplant 的 unhook 翻译为
  `shadowhook_unhook(stub)`
- ShadowHook 和 lsplant 都是 `stl:none` 的纯 C/C++ 库，不引入 STL 冲突

现在 lsplant::Init 在沙箱进程里无需任何手动后端接线即可成功。

### 待真机验证的部分

沙箱架构已完整落地（APK 加载 + lsplant + ShadowHook + Activity 代理），但因为当前构建
环境的 dav4jvm 依赖无法解析（项目预存问题），无法进行机器编译验证。所有代码已根据
实际 jar 的 API 人工核对。真机验证时需要关注的点：

1. **Instrumentation 替换的厂商兼容性**：`ActivityThread.mInstrumentation` 字段在大部分
   设备上一致，但部分 ROM 可能改名或加 final。
2. **资源绑定**：`AssetManager.addAssetPath` 是 hidden API，需要 `HiddenApi.disableHiddenApiChecks`
   （已接入）。
3. **目标 app 的 ContentProvider 初始化**：某些 app 在 Application.onCreate 里查询自己的
   ContentProvider，沙箱里这些 provider 没注册，可能报错（可后续通过 hook 解决）。

---

## 已评估后跳过

- **书签底栏上滑手势**：MT 的该手势是为它自己的常驻底栏设计；NexFiles 没有那个底栏
  （书签已在侧拉栏），强行移植会与现有侧拉栏/全面屏手势冲突。不实现。

---

## 蓝图（待实施）

### #9 双窗格（Dual-pane）

**状态**：✅ 已实现（B drawer 上移 + D 双窗格容器；主 toolbar 仍 per-pane，由激活窗格 setSupportActionBar；设置可关；菜单交换/另一窗格打开；选择器强制单窗格）。

**进度**：
- ✅ **阶段 A**：引入 `FileListFragmentHost` 接口（`filelist/FileListFragmentHost.kt`），
  `FileListFragment` 不再 `requireActivity() as AppCompatActivity`，所有 host 访问
  （setSupportActionBar/invalidateOptionsMenu/setTitle/finish/hasSw600Dp/isLandscape）
  走接口。`FileListActivity` 实现该接口。**零功能变化，纯解耦。**

#### 阶段 B：drawer / NavigationFragment 上移到 Activity（最优解）

**目标**：drawer 与 NavigationFragment 从 fragment layout 移到 activity layout，
fragment 只保留 pane 内容。双窗格时两个 pane 共享一个 drawer。

**精确改动**：

1. **新建 Activity 布局**（两套）：
   - `res/layout/file_list_activity.xml`：`DrawerLayout`（id `drawerLayout`）包裹
     一个 `FrameLayout`（id `paneContainer`，装 fragment）+ `NavigationFrameLayout`
     （id `navigationFragment`，start drawer）。**直接照搬当前
     `file_list_fragment_include.xml` 的 DrawerLayout 外壳**，只是把内部的
     PersistentBarLayout/CoordinatorLayout 内容替换为空的 paneContainer。
   - `res/layout-sw600dp-land/file_list_activity.xml`：
     `PersistentDrawerLayout`（id `persistentDrawerLayout`）并排
     `NavigationFrameLayout` + `LinearLayout`（含两个 paneContainer：
     `paneContainerPrimary` / `paneContainerSecondary`）。

2. **NavigationFragment 改为 Activity 的 child**：
   - 当前 `FileListFragment` line 228-234 用 `childFragmentManager` 加 NavigationFragment。
   - 改为 `FileListActivity` 在 `onCreate` 用 `supportFragmentManager` 加
     `NavigationFragment` 到 `R.id.navigationFragment`。
   - **NavigationFragment.Listener 的实现者从 fragment 改为 activity**——但 activity
     不持有 path/导航（那是 pane 的事）。**最优解**：activity 实现 Listener，但所有
     方法委托给"当前激活 pane"。引入 `activePane: FileListFragment?`，Listener 方法
     （navigateTo/navigateToRoot/observeCurrentPath 等）转发 `activePane?.X`。
     `observeCurrentPath` 订阅激活 pane 的 currentPathLiveData（切换激活 pane 时重订）。

3. **fragment layout 瘦身**：
   - `file_list_fragment.xml` / `file_list_fragment_include.xml` 删除 DrawerLayout /
     PersistentDrawerLayout / NavigationFrameLayout，根改为
     `PersistentBarLayout`（含 app_bar + content + bottom_bar + speed_dial）。
   - 对应 `layout-sw600dp-land/file_list_fragment_include.xml` 同样删除 drawer 外壳。
   - fragment 不再 inflate/引用 `drawerLayout`/`persistentDrawerLayout`/`navigationFragment`。

4. **fragment 代码改动**（删除 drawer 相关，改走 host）：
   - 删除 `navigationFragment` 字段（174）及其 add/find/listener 代码（228-235）。
   - 删除 `binding.drawerLayout`（292, 479, 1545）/`binding.persistentDrawerLayout`
     （373, 480, 600, 708-710）的所有引用。
   - `android.R.id.home`（479-487）打开 drawer 的逻辑改为
     `(requireActivity() as FileListFragmentHost).openDrawer()`（接口加该方法）。
   - drawer 状态同步（373-378 observe `FILE_LIST_PERSISTENT_DRAWER_OPEN`）移到 activity。
   - `Binding` 内部类（1742-1787）删除 `drawerLayout`/`persistentDrawerLayout` 字段。

5. **host 接口扩展**（`FileListFragmentHost`）：
   - 加 `fun openDrawer()`、`fun closeDrawer()`、`fun isDrawerOpen(): Boolean`。
   - activity 实现这些，操作自己的 drawerLayout/persistentDrawerLayout。

**验证点**：单窗格下抽屉仍能开/关/导航，`FILE_LIST_PERSISTENT_DRAWER_OPEN` 设置仍生效，
NavigationFragment 显示的当前路径正确跟随 pane。

#### 阶段 C：主 toolbar 上移到 Activity（最优解）

**目标**：主 toolbar（`binding.toolbar`，CrossfadeSubtitleToolbar）从 fragment 移到
activity，解决 `supportActionBar` 单例冲突。fragment 只保留 overlay toolbar（多选）和
bottom toolbar（粘贴）——这两个是 per-pane 的。

**核心难点与最优解**：当前 fragment 的菜单（`onCreateOptionsMenu/onPrepareOptionsMenu/
onOptionsItemSelected`）依赖 `activity.setSupportActionBar(binding.toolbar)` 把菜单注入
toolbar。双窗格下不能两个 pane 都 setSupportActionBar。

**最优解：菜单改由激活 pane 驱动，toolbar 归 activity**：

1. **toolbar 上移**：`file_list_fragment_app_bar_include.xml` 里的 toolbar 移到
   activity 布局的顶部（单窗格）或 persistentDrawerLayout 外（平板）。
   fragment 的 app_bar_include 只留 overlayToolbar + breadcrumb。
   - 但 breadcrumb 是 per-pane 的（每个 pane 显示自己的路径）！**矛盾点**：
     breadcrumb 必须留在 fragment，toolbar 上移后 breadcrumb 在 toolbar 下方、
     属于 pane。这是可行的——toolbar（标题/菜单）在 activity 顶部，breadcrumb
     （路径）在 pane 顶部。

2. **菜单归属**：
   - activity 持有 toolbar，`onCreateOptionsMenu` 在 activity 膨胀 `R.menu.file_list`。
   - 但菜单项的操作目标（sort/search/navigate）是**激活 pane**。activity 的
     `onOptionsItemSelected` 转发给 `activePane?.onMenuItemSelected(item)`。
   - fragment 暴露 `fun onMenuItemSelected(item: MenuItem): Boolean`（把当前
     onOptionsItemSelected 的逻辑搬进去）。
   - **SearchView** 特殊：它绑定到 pane 的 searchState。activity 持有 SearchView，
     但 query 变化转发给激活 pane。激活 pane 切换时，SearchView 的 query 同步到
     新 pane 的 searchViewQuery。

3. **subtitle**：当前 `binding.toolbar.subtitle` 显示文件计数。移到 activity toolbar，
   但内容由激活 pane 提供。pane 暴露 `val subtitle: LiveData<CharSequence>`，
   activity 观察激活 pane 的 subtitle 并设置。

4. **overlay/bottom action mode 不动**：它们用各自的 overlayToolbar/bottomToolbar
   （per-pane），不走 supportActionBar，无冲突。保留在 fragment。

**验证点**：单窗格下菜单/搜索/排序/副标题全部如常；双窗格下激活 pane 切换时菜单、
SearchView query、副标题正确跟随。

#### 阶段 D：双窗格容器 + 激活窗格状态（最优解）

**目标**：sw600dp-land 下 activity 装两个 fragment 实例，激活窗格驱动 toolbar/菜单/
drawer；非激活窗格淡化。

**精确改动**：

1. **Activity 装载逻辑**：
   - `FileListActivity.onCreate`：判断 `hasSw600Dp && isLandscape`。
   - 单窗格：加一个 `FileListFragment` 到 `paneContainer`（tag `pane_primary`）。
   - 双窗格：加两个，到 `paneContainerPrimary`（tag `pane_primary`）和
     `paneContainerSecondary`（tag `pane_secondary`）。secondary 初始路径可设为
     primary 的兄弟目录或默认根。

2. **激活窗格状态**：
   - activity 持有 `activePaneTag: String`（默认 `pane_primary`）。
   - pane 的 `onViewCreated` 里给 root 设置 `setOnClickListener { becomeActive() }`，
     点击 pane 切换激活。
   - `becomeActive()` 通知 activity：`activePaneTag = this.tag`，刷新菜单、
     重订 NavigationFragment 的 currentPath 观察、同步 SearchView query。
   - 视觉：非激活 pane 设 `alpha = 0.6f`（或 background tint），激活 pane 正常。

3. **activePane 属性**：
   - `val activePane: FileListFragment? get() = supportFragmentManager
       .findFragmentByTag(activePaneTag) as? FileListFragment`

4. **返回键**（最优解）：
   - Activity 的 `OnBackPressedCallback`：先让 activePane 处理
     （`activePane?.onBackPressed() == true`），处理不了（已到根、无选择、
     搜索关闭、drawer 关闭）才 `finish()`。
   - pane 暴露 `fun onBackPressed(): Boolean`，聚合当前 pane 的 overlayActionMode
     /speedDial/search 的返回逻辑。

5. **跨窗格 paste**：`PasteState` 是 companion 静态共享——天然支持。pane A 复制，
   pane B 的 bottom action mode 显示粘贴按钮，`pasteFiles()` 用 pane B 的
   currentPath 作目标。**无需额外代码**，验证语义正确即可。

6. **旋转/配置变更**：单窗格↔双窗格切换时，secondary pane 销毁，primary 保留。
   ViewModel 各自存活（Fragment-scoped，配置变更保留）。已下载的 rootfs、
   搜索状态等不丢。

**验证矩阵**（必须真机）：
- 手机竖屏：单窗格，所有功能零回归（最重要）。
- 平板横屏进入：双窗格，左右独立导航。
- 点击右 pane：激活切换，菜单/搜索/副标题/drawer 跟随右 pane。
- 左 pane 复制、右 pane 粘贴：文件跨 pane 复制成功。
- 旋转到竖屏：退化为单窗格，左 pane 保留、右 pane 销毁，无崩溃。
- 返回键：激活 pane 先 navigateUp，到根才 finish。

**风险点**：
- SearchView 跨 pane 同步（query/session 状态迁移）是最易出错的——务必在
  activate/deactivate 时显式保存/恢复 SearchView 状态。
- NavigationFragment 的 currentPath 观察在激活切换时取消旧订、建新订，避免
  重复观察导致 UI 错乱。
- overlay action mode 在非激活 pane 应禁用（或隐藏），避免两个 pane 同时
  显示多选菜单造成混淆。

**实施顺序建议**：严格 B→C→D，每步真机验证后再下一步。B 是地基（drawer 上移），
C 依赖 B（toolbar 上移后菜单机制重写），D 依赖 B+C（双容器 + 激活协调）。
跳步会纠缠。

---


### #11 内置终端（Built-in terminal）—— ✅ 已实现

**状态**：已完成实现（proot 终端，8 阶段全部落地）。原推迟理由（native 验证）通过
分阶段 + 真机测试解决。实现细节见代码：
- `jni/terminal.c` + `CMakeLists.txt`（native PTY：forkpty/read/write/setSize/close/wait）
- `terminal/TerminalNative.kt`（JNI 桥）
- `aidl/.../IRemoteTerminalService.aidl` + `IRemotePty.aidl`（IPC）
- `terminal/remote/TerminalServiceInterface.kt`（远程 Stub + pump 线程）
- `terminal/remote/ShizukuTerminalServiceLauncher.kt`（**项目第一条 shell-uid Shizuku 路径**）
- `terminal/TerminalSession.kt` + `TerminalService.kt`（app facade）
- `terminal/TerminalDistro.kt`（Alpine/Debian 预设）+ `RootfsManager.kt`（下载/SHA256/解压）
- `terminal/ui/TerminalBuffer.kt` + `TerminalEmulator.kt` + `TerminalView.kt`（VT100 模拟器）
- `terminal/TerminalActivity.kt`（编排）
- `jniLibs/README-proot.md`（proot 二进制获取——需手动提供）

**部署依赖**：用户需按 `jniLibs/README-proot.md` 获取 proot 二进制放入 jniLibs。

**原推迟分析（保留供参考）**：真 PTY 终端依赖 native（forkpty/exec）+ ANSI 模拟器
无法保证可用。

**现状**：
- `terminal/Terminal.kt` 只是调用外部终端 App（Termux/TermHere），非内置。
- Shizuku（`dev.rikka.shizuku:api`）和 libsu（`topjohnwu.libsu:service`）均已集成，
  用于 root 文件操作——可作为终端的后端。

**两条路径**：

A. **真 PTY 终端**（体验完整，工程量大）：
   - JNI 层：用 `forkpty()`/`execvp()` 创建 PTY 并 fork shell（root 时 `su -`）。
     需在 `app/src/main/jni/` 加 C 代码 + CMakeLists。
   - Kotlin 层：PTY 文件描述符读写循环、会话管理。
   - UI 层：终端模拟器 View（处理 ANSI/VT100 转义、输入法、滚动、复制）——可用
     `termux/termux-view`（EPL）或自写。
   - 依赖 Shizuku UserService 在非 root 下以 shell 权限创建会话。
   - 这基本是移植 Termux 的核心，工作量数千行 + native。

B. **简化命令执行器**（非交互式，工程量小）：
   - 一个输入框 + 输出区，用 `Runtime.exec()` 或 Shizuku 执行单条命令，显示 stdout/stderr。
   - 不能跑 vim/top 等交互程序，价值有限。

**推荐**：若做，选 A 但作为独立大版本，参考 Termux 的实现；B 的体验不值得引入。

---

### #12 WebDAV 服务端 —— ✅ 已实现

**状态**：已完成实现。原推迟理由（新依赖 + 协议繁琐）通过引入 NanoHTTPD + 复用项目
IPC 模式解决。实现细节见代码：
- `webdavserver/WebDavServer.kt`（NanoHTTPD + WebDAV 协议：OPTIONS/PROPFIND/GET/PUT/
  DELETE/MKCOL/MOVE/COPY + Basic Auth）
- `webdavserver/WebDavServerService.kt`（前台服务 + 状态机 + 通知）
- `webdavserver/WebDavServerNotification.kt`
- 设置页完整配置（端口/用户名/密码/主目录 + 启停开关）
- 依赖：`org.nanohttpd:nanohttpd:2.3.1`

**原推迟分析（保留供参考）**：需引入 HTTP 服务器新依赖 + WebDAV 协议实现繁琐，T3 优先级。

**现状**：
- FTP 服务端已有（`ftpserver/`，基于 `org.apache.ftpserver:ftpserver-core`）。
- `ProviderFileSystemView`/`ProviderFtpFile` 是 provider 无关的文件系统抽象（基于项目的
  NIO2 Path），WebDAV 服务端可直接复用。
- `dav4jvm` 是**客户端**库，不能用于服务端。

**实施路径**：

1. **HTTP 服务器**：引入 `org.nanohttpd:nanohttpd`（轻量嵌入式 HTTP 服务器，~50KB）。

2. **WebDAV 协议处理器**：继承 NanoHTTPD，为各 WebDAV 方法实现 handler：
   - `OPTIONS`（能力声明）、`PROPFIND`（列目录/属性，XML body 解析 with ` XmlPullParser`）
   - `GET`/`PUT`（下载/上传）、`DELETE`、`MKCOL`（建目录）
   - `MOVE`/`COPY`（含 `Destination` 头解析）
   - `PROPPATCH`（可选，大多数客户端不依赖）
   - 锁（`LOCK`/`UNLOCK`）可选，Windows 客户端需要，Linux/mac 挂载不需要。

3. **复用文件抽象**：`WebDavFile = ProviderFtpFile` 的 WebDAV 对应物，同样基于
   `ProviderFileSystemView`，复用现有的 provider 层（本地/任意挂载的 NIO2 文件系统）。

4. **设置与 UI**：复用 FTP 服务端的设置面板模式（`FtpServerPreferenceFragment`），
   加端口、主目录、用户名密码、启停开关。可考虑与 FTP 服务端合并为一个"远程访问"面板。

5. **价值定位**：相比 FTP 服务端的增量是 HTTP 协议（可走 HTTPS、可被 macOS Finder/Windows
   资源管理器原生挂载为 WebDAV）。FTP 服务端已覆盖核心的"电脑访问手机"场景，WebDAV 是
   面向加密/原生挂载的增量。

---

## 后续小项（本轮已建机制，待补 UI/完善）

- **#1 搜索：显式"在结果中筛选"二次搜索**（本轮用 debounce 合并连续输入，显式二次搜索
  作为独立交互待补）。
- **#3 压缩：包内编辑** ✅ 已实现（见下文）。
- **#6 回收站：浏览/恢复 UI** ✅ 已实现（机制 + UI 均已就绪：`RecycleBinActivity` 列表/
  恢复/永久删除/清空，设置页入口，Manifest 注册）。
- **#7 多包安装** ✅ 已实现（`.apks`/`.xapk`/`.apkm` 分包 session 安装 + 多选 APK 合并安装；PackageInstaller 状态回调）。

---

### #3 压缩包内编辑 —— ✅ 已实现

**状态**：已完成实现。archive provider 从只读挂载升级为 copy-on-write（COW）可写。

**架构**：
```
用户在 archive 内编辑文件
    ↓
ArchiveEditByteChannel（newByteChannel WRITE 路径）
    ↓ close()
ArchiveEditLayer.putFile()（内存暂存替换/新增/删除）
    ↓ 用户点击 "Save archive changes"
ArchiveFileSystem.commitEdits()
    ↓ ArchiveWriter 重写整个 archive（原条目 - 删除 + 替换 + 新增）
    ↓ 原子替换（temp file + rename）
archive 刷新
```

**改动**：
- `ArchiveEditLayer`：in-memory overlay，记录 replacements（替换/新增）、addedDirectories
  （新建目录）、deletions（删除）。
- `ArchiveEditByteChannel`：可写 SeekableByteChannel，close 时提交到 overlay。
- `ArchiveFileSystem`：
  - `isReadOnly()` 返回 false（不再永远只读）
  - `exists()` 感知 overlay 的新增/删除
  - `newInputStream()` 优先读 overlay 替换
  - `getDirectoryChildren()` 合并 overlay 子项
  - `writeFile` / `createDirectoryInLayer` / `deleteInLayer` 写入 overlay
  - `commitEdits()` 用 ArchiveWriter 重写 archive（原子替换）
  - `writeEntriesLocked()` 遍历原条目 + overlay 新增
- `ArchiveFileSystemProvider`：
  - `newByteChannel` WRITE 路径返回 ArchiveEditByteChannel
  - `copy` / `move` 改为走 overlay（不再 throw ReadOnlyFileSystemException）
  - `checkAccess` WRITE 始终允许（COW 层使 archive 可写）
- `OpenOptionsArchiveExtensions`：允许 WRITE/CREATE/TRUNCATE_EXISTING，仅拒绝
  APPEND/DELETE_ON_CLOSE/SYNC/DSYNC。
- `FileListFragment`：`action_archive_save` 菜单项在有 pending edits 时显示，
  `saveArchiveChanges()` 调用 `commitEdits()`。

**限制**：
- 仅支持 zip/tar/7z（由 `inferFormatFilter()` 决定，取决于 commons-compress）。
- 编辑期间改动全在内存，commit 时一次性重写——大 archive 可能 OOM。
- 不支持 symlink/hardlink（`createSymbolicLink`/`createLink` 仍 throw）。
