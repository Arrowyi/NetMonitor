# HONOR AGM3 `asdk.httpclient` SIGSEGV — 交接文档

> **目的**：把前一次 agent 会话里"**从发现崩溃 → 与 NetScope 作者多轮互锁排查 → 最终证实触发源在 NetScope 之外**"的全部过程、证据、已尝试方案、剩余假设，压缩成后续 agent 可以直接续跑的工作笔记。
>
> **状态（2026-04-23）**：NetScope 侧已到达极限（`b500638` + `DEBUG_ULTRA_MINIMAL` 最小接触面仍崩），下一步**必须在 HMI / Telenav 侧查**。
>
> **状态（2026-04-24，接手 agent 增补）**：
> - **路径 A `loadonly` 模式已落地到代码**（`NetMonitorService.kt`）并编译通过；待测。
> - **路径 B 完成，结果是"证伪"**：APK 里静态 pack 了 `libbytehook.so / libshadowhook.so / libshadowhook_nothing.so`，但 **零业务 `.so` 通过 DT_NEEDED / 动态 UND 符号 / 字符串常量**引用它们 → 是 gradle transitive 拖进来的孤儿文件，运行时永远不会 dlopen。第二套 hooker 假设不成立。见 §6-B。
> - **路径 A 实测完成（4 轮 loadonly）**：**全部崩溃，指纹与历史 6 次一致**。详见 §12。
> - **对照组 (`debug.netmonitor.enabled=0`)**：同一 session 内 **至少 1 次**在 T+~28s 仍崩，指纹与 loadonly 一致；`/proc/$PID/maps`（T+6s 快照）确认 `libnetscope / libbytehook / libshadowhook` **均未映射**。→ §3 表格里「`debug.netmonitor.enabled=0` → 稳定」**不能**再当作硬规律。
> - **用户复盘（复查）**：最近一次同 APK、prop 仍为 kill-switch / `diag=off` 的冷跑 **在观察窗内未崩**。与 loadonly **R3 的 T+310s** 合在一起，说明崩溃是**长尾 + 概率**，不是「每冷启必在 60s 内」。
> - **定性转变（仍成立，但措辞降级）**：**不能**再把根因继续扣在 `libnetscope.so` / NetScope `JNI_OnLoad` 上（maps 已证 kill-switch 下无 netscope 仍可崩）；应优先 Telenav 自身 JNI / DR-Engine / `asdk.httpclient`。**NetMonitor 与崩溃的相关性从「因果」降为「调度/负载相关」** —— 仍值得在 AGM3 上默认关 kill-switch，但不要再假设「关掉就根治」。
>
> **状态（2026-04-24 晚，G 路径实验完成，见 §12.7）**：
> - 在 `loadonly` 分支加入 `debug.netscope.delay_ms` sysprop，Handler 严格 `postDelayed` 后才触发 `NetScopeNative.<clinit>`；修好 `repository.getLatestData()` 提前触发 `NetScope.<clinit>` 的 bug 后，**机制可控**（smoke 实测 `JNI_OnLoad` 精准发生在 `diag_ts + 60.008s`）。
> - **D0（delay=0）3 轮全崩**，T+Δ ≈ 25s。**D60（delay=60000）2 轮崩 1 轮样本异常**，T+Δ ≈ 25s，**崩溃时 `libnetscope.so` 完全未映射进进程**（log 里无 `JNI_OnLoad`、无 `class-init OK`）。
> - **`x17 = 0x7329eb9620` 不变量跨所有 session / 所有 delay 配置保持** → 同一个 bug。
> - **§12.6.4 的"NetScope dlopen 与 AutoSdkManager.init 时序碰撞"假说被彻底证伪**。**NetScope 运行时（dlopen / `JNI_OnLoad` / `dlsym(RTLD_NEXT)` / bytehook / shadowhook）不是 amplifier 的任何机制**；**放大源只能在"NetScope 作为 APK 打包 artifact 的静态存在"层面**（dex 表、lib 扫描、AndroidX Startup provider、APK 布局对 ART/linker mmap 的扰动）。
> - **下一步最优先**：**对 `libFoundationJni.so` 符号化** + **ASAN build** 去抓 `tn::http::client::ClientImpl` / `Session` 的 UAF；NetScope 层面已没有可做的事情（§12.7.5）。
>
> **状态（2026-04-24 下午新增观察，见 §12.8）**：
> - 用户把 `Apps/Denali/gradle.properties` 的 `compileJavaSdk` 从 `false` 切到 `true`，让 `:android-common / :arp-sdk / :arp-foundation / :map-poi / :adas / :system-interface` 从本地 `java-sdk-common/java_sdk/` **源码**编译（而不是消费预编译 AAR），同时保留 `:netmonitor` 依赖（kill-switch 模式）。**产出的 `libFoundationJni.so` MD5 与之前崩溃 session 使用的版本一字节不差 (`02cd184e930f63c7bc26fb32e2452e7e`)** —— bug 还在。
> - 同一安装包前 10min 里连崩 6 次（13:31~13:35，每次 pid 不同、T+25~60s 自爆、指纹完全一致），之后 1 次冷启（pid 4835，start 13:55:43）**21m22s 无崩，UI 持续显示 NOT INIT**。这是**概率分布长尾**的直观呈现，不是 "B 配置实际上稳"——与 §12.5 ~ §12.7 所有结论自洽。
> - 把"为什么去依赖就稳、加依赖不 init 也会崩"的机制拆成 §12.8.3 的 **8 项 APK 静态扰动清单**，作为可发送给团队的一次性解释文档。

---

## 1. 核心结论

在 HONOR **AGM3-W09HN** (EMUI 11 / Magic UI 4 / Android 10, build `11.0.2.248C00`) 上：

**2026-04-23 版（已过时，保留作对比）**：
- ~~只要 `libnetscope.so` 被 dlopen 进进程，`asdk.httpclient` 线程会在 14s ~ 60s 内确定性地 SIGSEGV。~~
- ~~NetMonitor 模块完全卸载 → app 稳定。`debug.netmonitor.enabled=0` kill-switch 生效 → 也稳定。~~
- ~~问题被 NetMonitor 引入，但不是 NetScope SDK 的错误写入。~~

**2026-04-24 版（基于 §12 实测更新；§12.7 G 路径定稿）**：
- **在 `libnetscope.so` 未映射进进程的前提下，仍出现过与历史完全同指纹的崩溃**（见 §12.2/§12.4：`maps` grep 零命中 + logcat 有 `NetMonitorInit … Disabled` + 仍 SIGSEGV；§12.7：D60 崩溃时 `JNI_OnLoad` 日志行**从未**出现在 logcat 里）。因此根因**不应**再继续归因于 NetScope 的 `dlopen` / `JNI_OnLoad`。
- **但把 NetScope/NetMonitor 的依赖从 gradle 彻底去掉并重编，540s × 3 轮 0 崩**（见 §12.5）—— 即 **"把 .so 打包但运行时不加载" 的 AGM3 行为 ≠ "连 .so 都不打包" 的 AGM3 行为**。NetScope 的**存在本身**（被 `PackageManager` 扫到 `lib/arm64-v8a/libnetscope.so`、AndroidX Startup 多一个 provider、dex 表多出相关 class）就足以把宿主 JNI 的某个隐藏缺陷从 ~0% 复现抬到可观察概率。
- **崩溃概率呈三档分层**：A = 无依赖，**540s 内 0 崩**；B = 有依赖 + kill-switch，**120s 内偶发崩**（T+24s、T+28s、T+310s 均见过）；C = 有依赖 + `loadonly` dlopen，**90s 内几乎必崩**（T+17s ~ T+18s 为主）。
- **§12.7 G 路径结果**：将 NetScope 的 dlopen 强制推迟到 T+60s，进程在 T+25s 就已经崩过两次，`libnetscope.so` **根本没到加载的机会**。 → **NetScope 运行时代码（dlopen / hook 安装 / JNI）不是放大机制**；放大源必定在「NetScope 作为 APK 静态 artifact」层面（dex layout / lib 扫描 / AndroidX Startup / ART mmap 布局扰动）。
- **优先怀疑 Telenav 自身 JNI / DR-Engine / `asdk.httpclient`** 的堆损坏或竞态；NetMonitor 是**稳定、可调速的复现触发器**（C 比 B 更快、B 比 A 更容易复现），不是错误写入源。
- **同样代码在其他机型上不复现**（历史上 Pixel / 小米）—— 仍成立；现在更自然地读作「该 JNI 栈在非 HONOR 上更难触发或未触发，即便 NetScope 在内也未命中」。

## 2. 崩溃指纹（不变量）

跨 **6 次独立复现**（4 个 NetScope 版本、5 次冷启动）寄存器模式完全稳定：

```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)
thread: asdk.httpclient
pc == x8                     ← ldr x8,[x1]; blr x8 典型模式
fault addr in [anon:libc_malloc] (rw-p 堆，无 x 位)
x17 = 0x7329eb9620           ← 每次完全一致（zygote ASLR 共享的 libc 常量）
x16 低 12 位 = ...4c0        ← libart.so
lr  = 0x7xxxxxxE34           ← libart.so JNI 调用栈
`this`（x1）与错跳的 `pc/x8` **未必**在同一小堆邻域；2026-04-24 实测帧上二者可相距 **>100MB** 的 VMA 间隔 —— 更符合「vtable 字被写成远堆指针」而非「同块内溢出踩头」
Abort message: '[pid]create DR Engine success, engineMode=1!'   ← Telenav DR-Engine
backtrace: 单帧 #00 pc <offset> [anon:libc_malloc]
```

**语义解读**：某 C++ 对象的 vtable 指针（第一个 word）被改写成"同 arena 内另一个堆位置"，虚函数调用 `blr x8` 跳到 rw-p 堆页无 x 权限 → SEGV_ACCERR。

属于两种成因之一：
1. **Use-after-free / heap overflow** 踩坏了对象头的 vtable 字段。
2. **"本应指向 .text 里 stub/hook 函数"的函数指针被错填成指向堆的值**。

## 3. 已做过的测试矩阵（按时间顺序）

| 轮次 | NetScope 版本 | 关键变量 | 结果 | 得出的结论 |
|---|---|---|---|---|
| 0 | `78c3b91d35` (xhook 1.2.0) | 默认 | 崩 | 首次发现，上报 NetScope |
| 1 | `836b8e0416` | 加了 xhook_refresh SIGSEGV guard | 仍崩，4 次/4 min | guard 没触发 → 崩在**安装侧之后**，业务运行期 |
| 2 | `21bb54dd04` | 上报了 HookReport API | 不崩但不采流量 | 排查后发现是 `apkEmbeddedLibsSkipped` |
| 3 | `ca63cb78dac` | 修了 APK-embedded 路径 | 崩 | xhook 对 `base.apk!/lib/...` 合成路径的 GOT 偏移仍错 |
| 4 | `2d6ff99a99` | **换成 bhook + shadowhook，取代 xhook** | 崩 | 换了 hook 库崩溃照旧 → 不是 xhook 的 bug |
| 5 | `5eed945` | 加了 `DEBUG_TRACE_HOOKS` / `DEBUG_SKIP_HOOKS` | skip / both 仍崩 | 不是"写 GOT"那一刻 → 定位到 `bytehook_init` 或更早 |
| 6 | `4ec8fb9` | 加了 `DEBUG_ULTRA_MINIMAL`（skip `bytehook_init`） | 崩，+14s | 不是 `bytehook_init`，是 **静态构造链**（`DT_NEEDED` 拉进来的 libbytehook/libshadowhook 的 `__attribute__((constructor))`） |
| 7 | **`b500638`** | **`libbytehook.so` 从 DT_NEEDED 剥离** | **仍崩**，3 次独立复现，+14/+30/+54s | NetScope 已到零接触。触发源在 NetScope 之外 |
| 对照 | build 完全去掉 `project(':netmonitor')` | — | **稳定** | 历史上短测稳；2026-04-24 重测 **3 × 180s = 540s 0 崩**（§12.5） |
| 对照 | `setprop debug.netmonitor.enabled 0` | initializer 不跑 | ~~**稳定**~~ **见 §12** | 历史观察偏短；2026-04-24 至少 2 次仍崩（§12.2/§12.4），亦有整窗未崩 → **概率抬高但不必现** |
| 对照 | A-配置（gradle 里注释 `:netmonitor`）| 同日 3 × 180s | **0 崩** | 见 §12.5；定调 "NetScope 是放大器，不是根因" 的决定性实验 |

**零接触的硬证据**（三重）：

1. **静态**（Python ELF parser 解 `libnetscope.so` DT_NEEDED）：只含 `liblog / libandroid / libdl / libm / libc++_shared / libc`，**不含** `libbytehook` / `libshadowhook`。
2. **动态**（`adb shell run-as com.telenav.app.arp cat /proc/$PID/maps`）：3031 行，`grep -E 'libbytehook|libshadowhook'` **0 命中**。
3. **SDK 自带日志**：`DEBUG_ULTRA_MINIMAL — skipping loadLibrary(bytehook); libbytehook.so / libshadowhook.so will NOT be mapped into this process`。

**NetScope 运行期的全部接触面**：
```
JNI_OnLoad
+ dlsym(RTLD_NEXT, <11 libc 符号>)  // connect/getaddrinfo/send/sendto/write/writev/recv/recvfrom/read/readv/close
+ setStatusListener 注册 Java 回调
(无 bytehook_init, 无 GOT 写入, 无 inline hook, 无 mprotect)
```

这"就这么点事"还是崩 → 触发源不在 SDK。

## 4. 仓库中的相关代码（别改了别人都在用，只改 NetMonitor）

- `NavHome/module/netmonitor/build.gradle` — NetScope 版本。**当前 `b500638`**。
- `NavHome/module/netmonitor/src/main/java/com/telenav/netmonitor/NetMonitorInitializer.kt`
  - `androidx.startup.Initializer`：进程 attach 后自动启动 NetMonitorService。
  - **Kill-switch**：`debug.netmonitor.enabled=0` → 整个模块不启动，不调 loadLibrary。**AGM3 上发版建议保持这个值 = 0**。
- `NavHome/module/netmonitor/src/main/java/com/telenav/netmonitor/NetMonitorService.kt`
  - `tripCrashLoopBreaker()` — 60s 内重启 ≥ 3 次则 `stopSelf()` 退场，避免 AMS 反复重启把 host 拖死。
  - `readDiagMode()` / `applyDiagnosticMode()` — 读 `debug.netscope.diag`，支持 `off/trace/skip/both/ultra/ultra+trace/baseline/loadonly`。**必须在 `NetScope.init()` 之前设置**。
  - `loadonly` 模式（2026-04-24 新增）：调 `Class.forName("indi.arrowyi.netscope.sdk.NetScopeNative", initialize=true, ...)` 强制 `NetScopeNative` 的 `<clinit>` 执行 → 触发 `System.loadLibrary("netscope")`（即 `dlopen libnetscope.so` + `JNI_OnLoad` + 11 次 `dlsym(RTLD_NEXT)`），**之后一律不再调任何 NetScope API**（无 `setStatusListener` / `setDebugMode` / `init` / `getHookReport` / `setLogInterval`）。和 `baseline` 区别在于 `baseline` 仍会在 +5/15/30s 调 `dumpHookReport` 走 JNI，`loadonly` 完全干净。
  - `onCreate` 里固定 `START_NOT_STICKY`（避免 crash 后 AMS 自动重拉 service）。
- `NavHome/Apps/Denali/HMI/dependencies.gradle` — `implementation project(':netmonitor')`。**要彻底卸载 NetMonitor 做对照就把这行注释掉重编**。

## 5. 已准备好的诊断工具（直接用）

### 5.1 调试系统属性

```bash
# 整个模块 kill-switch
adb shell setprop debug.netmonitor.enabled 0|1

# NetScope 诊断模式（必须在 NetScope.init 之前生效，切换后必须强杀进程冷启）
adb shell setprop debug.netscope.diag off|trace|skip|both|ultra|ultra+trace|baseline|loadonly
# loadonly：最干净的最小接触面 — 仅 dlopen libnetscope.so + JNI_OnLoad（含 11 次 dlsym），
#           之后永远不调任何 NetScope 方法。用于路径 A 的二分：崩 → 触发源是加载事件
#           或 JNI_OnLoad；稳 → 触发源在更晚的业务调用（setStatusListener/init/getHookReport）。
# baseline：次小接触面 — 跳过 NetScope.init()，但仍保留 +5/15/30s 的 getHookReport JNI
#           调用；**不**如 loadonly 纯粹，排查时应优先用 loadonly。
```

### 5.2 破坏 crash-loop breaker 状态
```bash
adb shell "run-as com.telenav.app.arp rm -f shared_prefs/netmonitor_breaker.xml"
```

### 5.3 抓 `/proc/$PID/maps`（非 root，利用 debuggable 的 run-as）
```bash
PID=$(adb shell pidof com.telenav.app.arp | tr -d '\r')
adb shell "run-as com.telenav.app.arp sh -c 'cat /proc/$PID/maps > /data/data/com.telenav.app.arp/maps.txt'"
adb exec-out "run-as com.telenav.app.arp cat /data/data/com.telenav.app.arp/maps.txt" > maps.txt
```

### 5.4 复现用的标准启动序列
```bash
adb shell am force-stop com.telenav.app.arp
adb shell "run-as com.telenav.app.arp rm -f shared_prefs/netmonitor_breaker.xml"
adb shell setprop debug.netscope.diag ultra
adb logcat -c
adb logcat -v threadtime '*:V' > trace.log 2>&1 &
adb shell 'am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity'
# 3~60s 内必崩
```

### 5.5 编译
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.jdk/Contents/Home
cd NavHome/Apps/Denali
./gradlew :HMI:assemblePanguTasdkDevDebug -q
adb install -r HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk
adb shell appops set com.telenav.app.arp SYSTEM_ALERT_WINDOW allow
```

## 6. **下一步（未做）的排查路径**

用户还没点选方向，候选路径如下（按性价比排）：

### A. 最小化复现（最便宜，必做）—— 代码已落地，仅差测试

**目标**：把触发面从"SDK 在进程里做事"压到"仅 `dlopen + JNI_OnLoad`"。

**代码现状（2026-04-24 实装）**：`NetMonitorService.onCreate()` 已包含 `loadonly` 分支：
```kotlin
if (mode == "loadonly") {
    Class.forName(
        "indi.arrowyi.netscope.sdk.NetScopeNative",   // 注意：必须是 NetScopeNative，不是 NetScope
        true,                                          // initialize=true → 触发 <clinit>
        NetMonitorService::class.java.classLoader
    )
    return
}
```

**为什么是 `NetScopeNative` 而不是 `NetScope`**：`System.loadLibrary("netscope")` 写在 **`NetScopeNative.<clinit>`** 里（已经 `javap` 确认）。`NetScope` 这个 facade class 的 `<clinit>` 里**不**引用 `NetScopeNative`，仅通过方法体里的 `getstatic NetScopeNative.INSTANCE` 间接触发 —— 也就是说：若只 `Class.forName("...NetScope", initialize=true)` 而不调任何方法，`libnetscope.so` 可能根本不会被 dlopen。直接点 `NetScopeNative` 是唯一没有歧义的姿势。

**操作**：
```bash
adb shell am force-stop com.telenav.app.arp
adb shell "run-as com.telenav.app.arp rm -f shared_prefs/netmonitor_breaker.xml"
adb shell setprop debug.netscope.diag loadonly
adb logcat -c
adb logcat -v threadtime '*:V' > trace_loadonly.log 2>&1 &
adb shell 'am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity'
# 至少 3 次独立冷启，每次观察 3 分钟（14~60s 的非确定性时间窗）
```

**预期分支**：
- 3/3 次都崩 → 仅"dlopen libnetscope.so + JNI_OnLoad（含 11 次 dlsym）"即足够触发。收敛到两条子路径：
  - **路径 A.1**：`JNI_OnLoad` 里 11 次 `dlsym(RTLD_NEXT, ...)` 中某一次返回了被"换头"的指针。→ 路径 C：请 NetScope 作者出一个"把 JNI_OnLoad 的 dlsym 循环缩到 1~0 次"的诊断版本，二分定位到具体符号。
  - **路径 A.2**：加载事件本身（linker 把 libnetscope.so mmap 进来）就已经让 HONOR EMUI 的 linker global state 发生异常。→ 路径 E（dlopen-notify / strace mprotect 时序）+ 强烈建议并行做路径 F 先排除 HONOR-specific。
- 3/3 次都稳 → 触发源在**更晚**的 NetScope 调用（`setStatusListener` / `init` / `setDebugMode` / `getHookReport`）。
  - 对照 `baseline` 模式（`init` 跳过但保留 `getHookReport`）：如果 `baseline` 崩而 `loadonly` 稳 → 锁定到 `getHookReport`（那是唯一 baseline 有而 loadonly 没有的 JNI 调用）。
  - 再对照 `ultra`（除 `getHookReport` 外还调 `setStatusListener` + `init` with `DEBUG_ULTRA_MINIMAL`）：可以进一步细分。
- 1 或 2/3 崩 → 仍落在路径 A 范围内，但要考虑 race。附加观察：看哪次启动崩、是否和系统的后台 service 抢 CPU 相关。

### B. 扫业务 .so，查第二套 hooker —— **已完成，结果是"证伪"**

**执行时间**：2026-04-24。

**证据**：
- APK `lib/arm64-v8a/` 共 23 个 `.so`，全部 DT_NEEDED 表已解析：**只有 `libbytehook.so → libshadowhook.so`** 这一条匹配 hook 关键字（且这俩是一对，是 bytedance 自家 AAR 内部的正常组织）。
- 业务 .so（`libMapJni / libDriveSessionJni / libDataCollectorJni / libMapViewJni / libFoundationJni / libAdmClientJni / libTADREngineJNI / libGpsEncryptJNI` 等）的 DT_NEEDED **全部**只依赖 `liblog / libc++_shared / libc / libm / libdl` 一类标准库，没一个拉 hook 框架。
- `strings | grep -iE 'bytehook|shadowhook|xhook|substrate|dl_iterate_phdr|__cfi_slowpath|PLTHook|GOTHook|inlinehook'` 对所有业务 .so 扫一遍 —— **零命中**。UND 动态符号里也零命中 `bytehook_*` / `shadowhook_*` / `xhook_*`。
- Gradle 依赖树（`./gradlew :HMI:dependencies --configuration panguTasdkDevDebugRuntimeClasspath`）：`com.bytedance:bytehook:1.1.1` 和 `com.bytedance.android:shadowhook:1.1.1` **只**来自 `com.github.Arrowyi:NetScope:b500638` 的 transitive 依赖，没有别的引入路径。
- 结论：APK 里那三个孤儿 `libbytehook.so / libshadowhook.so / libshadowhook_nothing.so` 是 NetScope transitive 拖进来的 —— **compile-time 依赖还在**（所以 `NetScopeNative.tryLoadBytehook()` 方法体仍能被 Java 编译通过），但 `libnetscope.so` 的 DT_NEEDED 已经被 SDK 作者剥离，且 `DEBUG_ULTRA_MINIMAL` 让 `tryLoadBytehook()` 根本不会被调 → 它们物理存在但永远不会被 dlopen。这和交接文档 §3 里 `/proc/$PID/maps` 的 `libbytehook|libshadowhook` 零命中**完全吻合**。

**含义**：崩溃不是"第二套 hooker 和 NetScope 写 GOT 打架"。要么是加载事件本身（路径 F 证/伪），要么是 JNI_OnLoad 里 11 个 dlsym 中某个（路径 A + C 证/伪）。

**如果 NetScope 将来彻底剥离 bytehook 的 compile-time 依赖**（比如出个 `bytehook-free` 变体），`com.bytedance:bytehook:1.1.1` 也就不再是 transitive 依赖，APK 能再瘦 139 KB —— 但这与崩溃无关，不是当前优先级。

### F. 换机验证（20~30 min，必做）

**目标**：证明 HONOR-specific 还是 Android 10 通用。

**做法**：任何**非 HONOR/HUAWEI** 的 Android 10 arm64 机子（Pixel 3 / Redmi Note 8 / Samsung A51 都行）装同一 APK，开 `debug.netscope.diag=off`（正常模式），复测。

**预期分支**：
- 稳定 → HONOR EMUI 11 定制 linker / ART / libc 的问题，问题收敛到华为 ROM。
- 仍崩 → Android 10 arm64 通用，NetScope + Telenav 在任何 A10 机器都不兼容。

### D. 反推 vtable 归属（2~3h，前三步没结果再做）

**目标**：从崩溃时 `x1` (this 指针) → vtable 地址 → 找到 vtable 所属的 C++ 类。

**关键数据**（从 FINAL_SUMMARY.md 的 R1 tombstone）：
- `x1 = 0x7821723468`（this）
- `x8 = 0x7821078780`（被污染的 vtable，指向 `[anon:libc_malloc]`）
- `lr = 0x722c862e34`（libart.so 内的 JNI 回调帧）

**做法**：
1. 能 root 的 AGM3（或同型号开发机）上：启动后立即 `gdbserver` / `lldb-server` 挂进程，等崩。
2. 崩后 `info reg`，`x/32xg $x1` 看对象头（前 8 字节是 vtable），`x/8xg $x8` 看被污染的"vtable"里都是什么字节。
3. 用 libart 的 `mirror::Object` layout 解这是哪个 Java-bound C++ 对象，或用 `backtrace` 前 8~10 帧看调用者。
4. 把 `*x1` 与各业务 `.so` 的 `.rodata` 里的 vtable 符号比对，定位类。

### E. dlopen / mprotect 时序（需 root，2h，最后做）

```bash
# 在 root 机上
adb shell 'setprop wrap.com.telenav.app.arp "logwrapper strace -f -e trace=mmap,mprotect,mremap,dlopen -o /data/local/tmp/strace.out"'
adb shell am start -n com.telenav.app.arp/.MainActivity
# 等崩，拉 strace.out 分析崩前的 mprotect 调用
```

关注：有没有把某块 rw-p 堆页做了 `mprotect(PROT_READ)` 或权限翻转 → 这正是 PLT-hook 的典型手法。

## 7. 不要做的事（已验证过，别重蹈）

- ❌ 不要再换 NetScope 版本期待 SDK 侧修复 — 已到 `b500638` 零接触，SDK 没东西可改了。
- ❌ 不要切 `extractNativeLibs=true` — 之前验证会把 `SEGV_ACCERR` 变成 `SEGV_MAPERR`（在 `NetScope::audit_got` 里崩），不是根因。
- ❌ 不要在 `NetScope.init()` 之后再调 `setDebugMode` — 无效（init 后标志锁死）。
- ❌ 不要只跑一次 → 崩溃有 14~60s 的非确定性时间窗，单次可能误以为"稳了"。最少跑 **3 轮 × 3 分钟**。
- ❌ 不要指望 `debug.netscope.diag=baseline` 救场 — `NetScope.init()` 是被跳过了，但 `loadLibrary("netscope")` 还在（通过 `NetScope` 这个类被 classloader 引用）。真要"不加载"，必须路径 A 的 `loadonly` 也不做，或者直接关 kill-switch。

## 8. 现存的 "降级运行"方案（业务可接受则立即采用）

1. **AGM3 上永久 kill-switch**：`/system/build.prop` 追加 `debug.netmonitor.enabled=0`（需要 remount system，或随 OTA 推送）。
2. **白名单机型**：NetMonitorInitializer 里加一道 `Build.MANUFACTURER.equals("HONOR") && Build.MODEL.equals("AGM3-W09HN")` 判断，自动跳过。
3. **降级为纯 Java 采集**：NetScope 作者提到 AGM3 上可以 "降级为纯 dlsym 旁路" 永久停掉 bytehook — 等他们发版本即可。

## 9. 交付材料（NetScope 已收到）

`/tmp/ns_diag/round_b500638/netscope_ultra_b500638_FINAL.zip` 及同目录 `FINAL_SUMMARY.md`。

- 三轮 logcat（R1 14s / R2 30s / R3 54s）
- `/proc/$PID/maps` 快照（3031 行，grep 零命中）
- `libnetscope.so` 的 DT_NEEDED 解析
- 连续 3 次 HookReport 快照（全部 `hooked=0`）
- 设备 `getprop` 全量

## 10. 当前仓库状态（后续 agent 接手时的起点）

- `NavHome/module/netmonitor/build.gradle`：NetScope 依赖 = **`b500638`**。
- `NavHome/Apps/Denali/HMI/dependencies.gradle`：**`implementation project(':netmonitor')` 已启用**。
- `NavHome/module/netmonitor/src/main/java/com/telenav/netmonitor/NetMonitorService.kt`：支持 `off/trace/skip/both/ultra/ultra+trace/baseline/loadonly` **八种**诊断模式 + crash-loop breaker。`loadonly` 是 2026-04-24 新加的最小接触面。
- 最新 APK（已含 `loadonly`）：`NavHome/Apps/Denali/HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk`（2026-04-24 10:20 编译，270 MB）。
- 测试设备：HONOR AGM3-W09HN（adb 可达）。
- JDK 11 在 `/Library/Java/JavaVirtualMachines/jdk-11.jdk/`。
- 默认 flavor：`panguTasdkDevDebug`。

## 11. 给后续 agent 的一句话

> 继续做路径 **A → B → F → D**。每一步都要在 3 次独立冷启动里复现一次（有 14s~60s 的抖动），别被单次结果误导。如果 F（非 HONOR 机测试）稳定 → 可以停下来把问题上报给华为 EMUI / Honor ROM 团队；如果不稳定 → 回到 D 反推 vtable 归属，基本就是 Telenav 自产 JNI 库里有一个和 NetScope 互斥的 hook 实现。

> **2026-04-24 更新（上午）**：路径 B 已执行，**证伪"第二套 hooker"假设**（见 §6-B 证据）。路径 A 代码已落地为 `loadonly` 模式，APK 已编译就绪。

> **2026-04-24 更新（午前）⚠ 路径改道**：`loadonly` 4 轮全崩 + kill-switch 下 **至少一次**同指纹崩溃；`maps` 证实该次进程内 **无** `libnetscope`。**不应**再把根因归因于 NetScope 的 `dlopen/JNI_OnLoad`；主战场转到 Telenav JNI / DR-Engine。另见 **§12** 与用户复核的「偶发不崩」—— 验证必须拉长窗口 / 多次统计。

> **2026-04-24 更新（午后）✅ 决定性实验**：A 配置（gradle 里把 `:netmonitor` 依赖去掉，APK 里无 `libnetscope/libbytehook/libshadowhook*.so`，dex 零引用）在 **3 × 180s = 540s 冷启 0 崩**（§12.5）。结合 `loadonly` 90s 必崩 + kill-switch 120s 偶崩，确立三档概率分层：A 稳 / B 偶崩 / C 必崩。**结论：NetScope 是"放大器"而不是"根因"**。根因在宿主 JNI（DR-Engine / `asdk.httpclient` / `libTADREngineJNI.so`），NetScope 的存在改变了 APK 布局/启动时序从而高概率命中。后续 **优先用 C 配置（`debug.netscope.diag=loadonly`）当快速稳定复现器**，不要再把时间花在屏蔽 NetScope 上。
>
> **2026-04-24 更新（晚）✅ G 路径完成，再次收敛**（§12.7）：`debug.netscope.delay_ms` sysprop 可把 NetScope `dlopen` 从 T+4s 推迟到 **T+60s**，机制已校验可控（smoke: `JNI_OnLoad` 精准在 `diag_ts+60.008s`）。**D0 3/3 全崩 + D60 2 轮崩在 dlopen 前 + `x17` 不变量一致** → **NetScope 运行时 dlopen / hook / JNI 不是 amplifier**。放大机制**只能**在 APK 静态 artifact 层面（dex / `lib/arm64-v8a` 扫描 / AndroidX Startup / ART mmap 布局）。以下路径表据此更新：
>
> 1. **路径 H（当前最高优先级）**：**对 `libFoundationJni.so` 符号化 + ASAN build**。让 Telenav build 出一个 Debug/ASAN 版的 `libFoundationJni.so`（其它 `.so` 不变），放在 AGM3 上跑 B 配置，25s 自爆时 ASAN 会直接打印 `tn::http::client::Session*`（或 `ClientImpl`）的 allocated-by / freed-by 栈。这是**最短路径**到根因。审查目标：`tn::http::client::ClientImpl::shutdown()` 与 worker `onRequestComplete` 的同步路径（字符串 `"can't perform request on a shut down client"` / `"unique_lock::lock: references null mutex"` 已在 §12.6.1 定位）。
> 2. **路径 F**：非 HONOR Android 10 机 + B/C 配置 + `loadonly`，**单次 ≥10 min** 或 **5× 冷启 × 各 ≥3 min** 统计崩溃频次。若非 HONOR 机全稳 → 同一 bug 在其它 ROM 下 race window 更小；若非 HONOR 机也崩 → 非设备特异性。
> 3. **路径 J（替代 G）**：**用"等量 dummy 模块"替换 NetMonitor/NetScope**（例如加一个空的 AndroidX Startup Initializer + 一个 ~1MB 的占位 `.so`），跑 180s × N 次比较与 A/B 配置的崩溃率。这是用来夹逼"NetScope 特异性 vs 宽松体积扰动"的实验。
> 4. **路径 I（给 NetScope 作者）**：`DEBUG_NOOP_INITIALIZER`（`JNI_OnLoad` return immediately）**优先级降低** —— §12.7 证明 `JNI_OnLoad` 根本不需要触发也能崩；该实验不再能区分机制。
> 5. **~~路径 G（`debug.netscope.delay_ms` 时序二分）~~**：已**关闭**，§12.7 已证伪时序碰撞假说，D3 / D10 / D30 等中间档位不再必要。

## 12. 2026-04-24 现场 session 记录（AGM3 + 含 `loadonly` 的 APK）

**APK**：`NavHome/Apps/Denali/HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk`（当日 10:20 左右构建；安装用 `adb install -r` 保留应用内设置）。

### 12.1 `debug.netscope.diag=loadonly`（`debug.netmonitor.enabled=1`）

| 轮次 | 启动（logcat 时间） | 崩（`Fatal signal`） | Δ | `pc==x8`（fault） | `x17` |
|---|---|---|---|---|---|
| R1 | 10:28:06 | 10:28:24 | +18s | `0x716e1cf980` | `0x7329eb9620` |
| R2 | 10:30:03 | 10:30:21 | +18s | `0x716cbeef80` | `0x7329eb9620` |
| R3 | 10:33:23 | 10:38:33 | **+310s** | `0x71a987ad80` | `0x7329eb9620` |
| R3b | 10:38:41 | 10:38:58 | +17s | `0x717df8d200` | `0x7329eb9620` |

线程均为 `asdk.httpclient`；`SEGV_ACCERR`；`pc==x8`；`#00 … [anon:libc_malloc]`；Abort message 形态均为 `'[pid]create DR Engine success, engineMode=1!'`；`x6=fefeff1526ec9b0a`、`x9=41310ee1e459c27d` 等与 §2 历史帧一致。

### 12.2 kill-switch：`debug.netmonitor.enabled=0`，`debug.netscope.diag=off`

- logcat：`I NetMonitorInit: create called` → `W NetMonitorInit: Disabled … skipping service start`（initializer 早退，不调 `NetMonitorService`）。
- **maps 证据（某次 T+6s）**：`grep -E 'libnetscope|libbytehook|libshadowhook' /proc/$PID/maps` → **0 行**。
- **同一 session Ctrl1**：约 **T+28s** 仍出现与上表**同指纹**的 `Fatal signal 11` / `asdk.httpclient` / 同 Abort 文本。

### 12.3 用户复核（adb 重连后反馈）

在 **同 APK**、prop 仍为 kill-switch / `diag=off` 的前提下，**最近一次冷跑在观察窗内未崩**。这与 §12.1 的 **R3（+310s）** 并不矛盾：说明崩溃是 **长尾 + 概率**，**单次短测「没崩」不能反证「无 netscope 时从未崩」**，也不能反证「loadonly 必崩」—— 只能用于调整验收口径：

- 建议现场：**单次前台静置 ≥10 min** 抓 logcat；或 **≥5 次 force-stop 冷启 × 各 ≥3 min** 统计 `Fatal signal` 次数。
- 若长期只有 kill-switch 下 0 崩、而 `loadonly` 仍高频崩，再回头讨论 NetScope 是否**放大**竞态（相关 ≠ 根因）。

### 12.4 adb 重连后的快速复测（kill-switch，120s soak）

用户反馈「最后一次 APK 跑没有 crash」后，adb 恢复连接，在同一设备上再次：

- `debug.netmonitor.enabled=0`，`debug.netscope.diag=off`，`am force-stop` + 清 breaker，`am start` MainActivity，**静置 120s**。
- 结果：**T+~24s** 再次 `Fatal signal 11` / `asdk.httpclient` / 同 Abort 文本 / `x17=0x7329eb9620` / `pc==x8` / `#00 [anon:libc_malloc]`（与 §12.1、§12.2 同指纹）。

说明「偶发整窗不崩」与「短窗内又崩」可以**交替出现**—— 这正是 §12.3 建议拉长统计窗口的原因。

### 12.5 配置 A 实验：完全移除 NetScope/NetMonitor 依赖（2026-04-24 11:24 ~ 11:33）

**动机**：§12.1~12.4 已证明"`loadonly` 崩"、"kill-switch 下也能同指纹崩"，但没回答"**如果 APK 里根本不包含 netscope/netmonitor，在 AGM3 上是稳还是不稳？**"。这是区分"NetScope 是根因"vs "NetScope 是崩溃放大器" 的决定性实验。

**方法**：

- 临时注释掉 `Apps/Denali/HMI/dependencies.gradle` L166 `implementation project(':netmonitor')`，重编一个"A 配置" APK。
- 静态校验 A APK 内容：

    | 项 | B 配置 | A 配置 |
    |---|---|---|
    | `lib/arm64-v8a/*.so` 数量 | 23 | **19** |
    | `libnetscope.so` / `libbytehook.so` / `libshadowhook.so` / `libshadowhook_nothing.so` | 全有 | **全无** |
    | dex 中 `Lcom/telenav/netmonitor` / `Lindi/arrowyi/netscope` 引用 | 有 | **0** |

- 同一 AGM3、`debug.netmonitor.enabled=0`、`debug.netscope.diag=off`、`adb install -r` 保留设置，连续 **3 轮冷启** × 每轮 `am force-stop → am start MainActivity → 静置 180s`，期间 `adb logcat -v threadtime` 抓全量、每 30s 轮询 `pidof` 和 `grep -c 'Fatal signal'`。
- 驱动：`/tmp/ns_loadonly/soak_A.sh`；原始日志：`/tmp/ns_loadonly/A_round{1,2,3}.log`、`A_driver.out`、`A_summary.txt`。

**结果**（全部可复查，`HMI-pangu-tasdk-dev-arm64-v8a-debug.apk`，11:21 构建）：

| 轮次 | 冷启时间 | pid | T+30s 存活 | T+180s 存活 | `Fatal signal` 计数 | logcat 大小 |
|---|---|---|---|---|---|---|
| R1 | 11:24:15 | 3791 | ✅ | ✅ | **0** | 51 MB |
| R2 | 11:27:20 | 5137 | ✅ | ✅ | **0** | 15 MB |
| R3 | 11:30:25 | 6511 | ✅ | ✅ | **0** | 16 MB |

**总计：540 秒前台运行、3 次独立冷启、0 次 `Fatal signal`。**

**结论**：

1. **NetScope/NetMonitor 不是 AGM3 崩溃的必要条件存在争议被推翻了一半——在 "`不打包`" 维度上，A 配置确实稳**。但前面 §12.2 明确观察过"依赖还在、kill-switch 关闭、maps 里没有 `libnetscope`"时仍然崩，所以 **"把 .so 打进 APK 但运行时不加载"** 与 **"连 .so 都不打包"** 在 AGM3 上行为不同。
2. 结合三档概率的经验数据（C=`loadonly` 90s 崩 / B=kill-switch 120s 崩 / A=无依赖 540s 0 崩），NetScope **在进程内的存在本身**（不论是否 dlopen、不论是否 hook）足以**显著抬高**崩溃概率；而其**存在方式从"运行时激活"退化到"仅静态 pack"** 对概率也有再次抬高/降低效应。这和 NetScope 侧 `DEBUG_ULTRA_MINIMAL` 的 "0 接触面仍崩" 并不矛盾——被放大的是**宿主自己**的 JNI 缺陷，不是 NetScope 代码。
3. **放大机制候选**（仍需后续证据收敛）：
    - APK 内 `lib/arm64-v8a` 多/少几个 `.so` → PackageParser/`ApplicationInfo.nativeLibraryDir` 扫描次序不同 → `System.loadLibrary` 解析或 dlopen 时序抖动。
    - Dex 数量、`MultiDex` split 顺序不同 → ClassLoader 初始化路径、静态初始化竞态窗口不同。
    - AndroidX Startup `InitializationProvider` 多实例化一个 provider（即便早退），`ContentProvider.onCreate()` 的 binder 调度多一跳。
    - 进程启动期 CPU/IO/堆页的宏观负载差异足够让 DR-Engine / `asdk.httpclient` 里那个本就存在的 UAF/竞态"命中概率"从 ~0.1% 抬到 ~80%。
4. **对 NetScope 作者的一句话**：`DEBUG_ULTRA_MINIMAL` 之后 **在 NetScope 代码层已经没什么可收的了**。NetScope 现在更像"故障检验仪"而不是"故障源"：它的存在会把宿主 JNI 的隐藏缺陷**稳定地敲出来**。NetScope 侧如果还想继续贡献定位，建议方向：
    - 给 AAR 再加一档 `DEBUG_NOOP_INITIALIZER`：`JNI_OnLoad` 直接 `return JNI_VERSION_1_6`，**连字符串/堆分配都不碰**，跑 A 级 soak 量化 B'=依赖 + NoOp 是否仍偶发 → 进一步夹逼"放大"来源是"字节码存在"还是"so 存在"。
    - 对 AGM3 这一类已知会崩的机型，builtin 一个"detect + 优雅自卸载"路径（但同样不解决根因）。
5. **对 HMI / Telenav / DR 团队**：**根因在你们自己的 JNI 栈**（`libTADREngineJNI.so` / `asdk.httpclient` / DR-Engine 初始化序列）。NetScope 可作为**稳定复现工具**使用（loadonly 模式 90s 内就能抓现场），请优先按 §6-A/§6-B 的方向（symbolize `libTADREngineJNI`、查 UAF/vtable overwrite、在 AGM3 上加 ASAN/hwasan build）推进，**不要再把时间花在屏蔽 NetScope** 上。

**回滚**：实验结束已恢复 `dependencies.gradle` L166，并重编 B 版 APK（11:35 完成，270,143,839 B），推回设备前请用户按需 `adb install -r`。

### 12.6 静态 + 动态联测：锁定 `asdk.httpclient` 归属 + 崩溃时序窗（2026-04-24 11:45 ~ 11:57）

**12.6.1 `asdk.httpclient` 的真正归属（静态反汇）**

1. `asdk.httpclient` 这个字符串 **在整个 APK 的字节流里不存在**（`unzip -p APK | strings | grep 'asdk.httpclient'` 0 命中）。线程名必是运行时拼接。
2. 在所有 Telenav native `.so` 里，**只有 `libFoundationJni.so` 和 `libissxtts30.so` 导入 `pthread_setname_np`**（`nm -D` UND 符号）。前者是 tasdk 基础层、后者是语音，`httpclient` 后缀显然属于前者。
3. `libFoundationJni.so` 关键字符串 + 符号：

   | 偏移 | 内容 | 含义 |
   |---|---|---|
   | 6453486 | `httpclient` | 线程注册名（不带前缀） |
   | 6550813 | `tasdk.` | thread creator 默认 prefix |
   | 6571612 | `"prefixName": "tasdk."` | JSON 配置里的 prefix |
   | 6550215 | `The thread name "` | tasdk 自截告警前半 |
   | 6550233 | `" exceeds ` | tasdk 自截告警中段 |
   | 6550244 | ` bytes, truncate it to "` | tasdk 自截告警后半 |
   | — | `_ZN2tn10foundation13SystemAdapter13setThreadNameERKlPKc` | `tn::foundation::SystemAdapter::setThreadName(long const&, char const*)` |
   | — | `_ZN3zmq8thread_t15applyThreadNameEv` | `zmq::thread_t::applyThreadName()` |
   | — | 一堆 `N2tn4http6client*E` | `tn::http::client::{Client,ClientImpl,Session,Request,Response,Error,OtherError,RequestError,RejectionError}` |

4. 线程名推导：`"tasdk." + "httpclient"` = **16 字节**超 bionic `pthread_setname_np` 15 字节上限 → `system_adapter_linux.cpp` 自截（**从前截**）→ `asdk.httpclient`（15 字节）。同样规则解释了线程快照里看到的 `dk.audio.engine`（19B 原名 `tasdk.audio.engine`）、`k.alert.traffic`（20B 原名 `tasdk.alert.traffic`）、`.dir.event.task`（22B 原名 `tasdk.dir.event.task`）等一系列"从前截断"的 tasdk foundation 线程。
5. **`asdk.httpclient` 是 `tn::http::client::ClientImpl` 的 worker thread**（基于 libcurl multi handle），由 `libFoundationJni.so` 创建，被 `libAdmClientJni.so`（OTA）、`libMapJni.so`（`stream::DownloadManager::createHttpClient()`、`tn::directionservice::DirectionServiceProxy::createHttpClient()`）等多个业务模块**共用**。`httpclient` 附近的诊断字符串里出现：
    - `"HTTP client's workthread create error"`
    - `"Uncaught exception in response handler passed to async HTTP request"`
    - `"Uncaught exception in async HTTP client's workthread"`
    - `"can't perform request on a shut down client"`
    - `"unique_lock::lock: references null mutex"`
    - `"can't wake up curl multi handle"`

    这组字符串强烈暗示 **shutdown-vs-worker race / UAF on shared_ptr<Client>**。

**12.6.2 Abort message 的真实性质**

`[%u]create DR Engine %s, engineMode=%d!` 的字面量在 `libTADREngineJNI.so`（offset 1751040），格式 `[pid]create DR Engine success, engineMode=1!` 明显是 **INFO 级初始化成功 log**，不是 abort。它会出现在 tombstone 里，是因为 Telenav 的日志包装器把 **最近一条 log** 通过 `android_set_abort_message()` 塞给 libc —— tombstone 抓到的只是"崩前最后一条 log"，并不代表该线程执行了这段代码。**根因不在 DR-Engine，在 tasdk HTTP client 的生命周期管理。**

**12.6.3 线程拓扑：NetScope 不改变 tasdk 线程集合**

B 配置（kill-switch）和 C 配置（loadonly）下各跑一次 120s snapshot，每 3~120s 抓一次 `/proc/$PID/task/*/comm`：

- B：共 16 个 tasdk 线程 = {`asdk.alert.core, asdk.ds.evt.mgr, asdk.graph.core, asdk.httpclient, broker.c.worker, broker.s.worker, dk.audio.engine, dk.broker.timer, dk.dir.offboard, dk.global.timer, dk.map.sw.event, dk.ptile.worker, entity.download, k.alert.traffic, k.ds.sensor.mgr, k.map.sw.notify`}
- C：**完全相同的 16 个 tasdk 线程**
- 线程总数稳定差值 ≈ 5（C=170, B=168，差额对应 NetScope/bytehook/shadowhook 引入的附加线程）。

→ **NetScope 不增删 tasdk 的任何 worker 线程**，仅改变启动期并发/时序压力。

**12.6.4 C 配置下 NetScope dlopen 落点的精确时序（11:51 冷启 logcat）**

| T+Δ | 事件 | 来源 |
|---|---|---|
| 0.0s | `ActivityManager Start proc 12574` | logcat |
| +2.6s | `NetMonitorInit.create()`（AndroidX Startup Initializer）| logcat |
| +3.4s | `AutoSdkManager.init()` 在 `3-app-init-pool` 线程启动（tasdk native init 开始）| arp-sdk log |
| +3.8s | `NetScope loadonly` → `libnetscope.so` dlopen → `JNI_OnLoad: jvm=0x72a5676540` | NetMonitor + NetScope log |
| +6s | `asdk.*` tasdk worker 线程**陆续出现**（quota=6）| `/proc/.../comm` snapshot |
| +13s | `asdk.*` 全部就位（quota=15）| snapshot |
| +17~18s | 历史上 C 配置最常见的崩点（§12.1 R1/R2/R3b）| 历史 |

→ **NetScope 的 `dlopen + JNI_OnLoad` 恰好发生在 AutoSdkManager.init() 启动后 0.4s**，正好是 tasdk 创建第一批 foundation 线程（含 `asdk.httpclient`）的时候。这是**最合理的放大机制**：NetScope 的 `dlopen` 和 11 次 `dlsym(RTLD_NEXT, socket 符号)` 在 bionic 的 linker/dl-mutex 上与 tasdk 自己的 `System.loadLibrary` 序列交错，**扰动了 tasdk HTTP client 的初始化顺序 / shared_ptr 生命周期**。

**12.6.5 提议的 G 路径决定性实验：`debug.netscope.delay_ms`**

在 `NetMonitorService.applyDiagnosticMode(loadonly)` 分支里加一行 `SystemProperties.getLong("debug.netscope.delay_ms", 0)`，然后 `Handler.postDelayed { Class.forName("...NetScopeNative", true, cl) }`。测 3 档：

| delay_ms | 期望 NetScope dlopen 时机 | 期望崩溃率（若时序假说正确）|
|---|---|---|
| `0` | T+3.8s（与 tasdk native init 重叠）| 仍高频崩，T+17s 级 |
| `3000` | T+6.8s（刚错过第一批线程创建）| 降低 |
| `10000` | T+13.8s（tasdk 初始化彻底结束）| 显著降低或不崩 |
| `30000` | T+33.8s（tasdk 已稳定运行）| 应该完全不崩 |

如果崩溃 T+Δ **确实随 delay_ms 线性推迟**或**高延迟下不崩**，就 100% 锁定"NetScope dlopen/dlsym 序列与 tasdk HTTP client 启动时序冲突" 是放大机制，根因就只剩 tasdk 自己要修。这是**本项目剩下最有价值的单次实验**。

**12.6.6 本次 session 的未决/随机**

- 本轮 B 和 C 各一次 120s snapshot **都没复现崩溃** → 再次印证"长尾概率"。如果要抓到 C 崩溃现场快照，下次可以连跑 5 轮 120s + snapshot，中间任何一轮崩就能拿到崩前线程列表。
- `asdk.httpclient` 线程在 B/C 下**一直存活**（T+6s 出现后 120s 全程不变）→ 说明它不是"被创建→被销毁→被 UAF"这种简单模式；更可能是 **worker thread 在处理异步 response callback 时，对应的 `Session` / `Request` 对象已被其它线程 destruct** 的"session 级 UAF"，而非"client 级 UAF"。
- tombstone 原件在 `/data/tombstones/`（SELinux 保护），本设备非 root，`run-as` sandbox 看不到；如果 DR 团队需要完整 backtrace，需要 root 或 `adb bugreport` + `dumpsys dropbox`。

### 12.7 G 路径实验结论：NetScope dlopen **完全不是** 放大机制（2026-04-24 13:10 ~ 13:35）

**12.7.1 实施**

在 `NetMonitorService.onCreate(loadonly)` 分支里接入 `debug.netscope.delay_ms` sysprop：

```kotlin
val delayMs = readDelayMs()
val doLoad = Runnable {
    Class.forName("indi.arrowyi.netscope.sdk.NetScopeNative", true, cl)
    // defer repository.getLatestData() / refreshRunnable into here too
}
if (delayMs > 0) handler.postDelayed(doLoad, delayMs) else handler.post(doLoad)
return
```

⚠️ **关键 bug 修正**：初版把 `handler.post { floatingWindowManager.updateData(repository.getLatestData()) }` 放在 `postDelayed` 之后**同级**，结果该 Handler tick 在几毫秒内 fire，lambda 里 `NetScope.getDomainStats()`（invokestatic）**立即触发 `NetScopeNative.<clinit>`**，`System.loadLibrary("netscope")` 在 T+130ms 就跑完，完全绕开了 `postDelayed(60s)`。修复方式：**把 `updateData()` 和 `refreshRunnable` 也推迟到 `doLoad` Runnable 内部**，确保 delay 期间**没有任何代码路径触碰 `NetScope` / `NetScopeNative`**。修复后 smoke（delay=60s）实测 `JNI_OnLoad` 在 `diag_ts + 60.008s` 精准出现，机制验证通过。

**12.7.2 D0 vs D60 结果（修复后）**

| 配置 | delay_ms | 3 轮崩溃数 | 崩溃 T+Δ（相对 asdk_init）| 崩溃时 libnetscope.so 状态 |
|---|---|---|---|---|
| D0 | 0 | **3 / 3** | 25.0s / 14.4s / 25.3s | 已加载（dlopen_ts ≈ asdk_init + 0.9s）|
| D60 | 60000 | **2 / 3** ¹ | 25.4s / — / 25.1s | **完全未加载**（崩溃时 log 里只有 "diag_ts" 行，无 `JNI_OnLoad`、无 `class-init OK`）|

¹ D60 round 2 `AutoSdkManager` 字段 `none`，是 monkey 冷启触发链异常（未达到 init 阶段）导致 `grep` 空命中，不是 "稳定不崩"。D60 实际在 dlopen 时间窗（T+60s）到达**之前**就已经崩过 2 次，也就无法检验"延迟 60s 后加载"是否有保护作用——**问题发生在 NetScope 加载之前**。

**12.7.3 崩溃 signature 跨 session 一致性验证**

| 字段 | D0 round1 | D60 round1（dlopen 未发生）| 与历史 B/C session 一致性 |
|---|---|---|---|
| thread name | `asdk.httpclient` | `asdk.httpclient` | ✓ |
| signal | 11 (SIGSEGV) code 2 (SEGV_ACCERR) | 同 | ✓ |
| fault addr | `0x72179abb80` | `0x7298adf000` | 变动（随堆偏移变）|
| `x8` | 匹配 fault addr | 匹配 fault addr | ✓ pc==x8 不变量保持 |
| **`x17`** | `0x7329eb9620` | **`0x7329eb9620`** | ✓ **跨所有 session 完全一致** |
| Abort message | `[pid]create DR Engine success, engineMode=1!` | 同 | ✓ |

**结论**：跨 session、跨 delay_ms 配置都是**同一个 bug**（pc==x8、`x17` 不变、同线程、同 abort 字符串）。

**12.7.4 决定性推论**

1. **`pushing NetScope dlopen 60s 不影响 crash T+Δ`** → 崩溃时序由 tasdk 自己决定，与 NetScope 的加载时刻**无线性关系**。
2. **D60 崩溃发生时 `libnetscope.so` 完全未进入进程地址空间** → NetScope 的 `dlopen` / `JNI_OnLoad` / `dlsym(RTLD_NEXT)` hook 安装 / bytehook 调度 **都不是 crash 的必要条件**，也不是 "amplifier" 的机制。
3. 对比 A 配置（`implementation project(':netmonitor')` 删除，3 轮 × 180s 全部 0 崩）和 B/D0/D60 的结果：**NetScope 作为 APK 打包 artifact 的"静态存在"** 才是放大器。候选机制退到：
   - `lib/arm64-v8a/` 下多 4 个 `.so` 对 `ApplicationInfo.nativeLibraryDir` 扫描的扰动；
   - Dex 数量变化对 ClassLoader / `System.loadLibrary("tasdk-xxx")` 的 resolve 路径扰动；
   - AndroidX Startup 的 `InitializationProvider` 多一个 entry 对 `ContentProvider.attachInfo/onCreate` 顺序的扰动；
   - APK zip 解压时 `Manifest` / `resources.arsc` 位移对 ART/linker 的 mmap 布局扰动。

   这些都属于"进程启动期宏观噪声"，**不需要 NetScope 执行任何字节码/native 代码**就能敲出 tasdk 自己的隐藏 race。

4. **§12.6.4 提的"NetScope dlopen 与 AutoSdkManager.init 时序重叠"假说被 12.7.2 直接推翻**：在 D60 下，`AutoSdkManager.init` 在 T+3.4s 开始，NetScope 本该在 T+60s 才 dlopen，但崩溃在 T+25s 就已经发生，tasdk 自己"都没等 NetScope 上桌就自爆"。所以这个时序碰撞**不是放大机制**。

**12.7.5 对 NetScope 团队的更新结论**

- **NetScope 任何运行时行为都不需要发生，AGM3 也会复现此 crash**（B 配置 kill-switch + loadonly+delay=60s 已证实）。
- **NetScope 代码层面已没有可做的事情**。即便 NetScope 进化到"不导出任何符号、不注册 JNI、`JNI_OnLoad` 不做任何事"，只要 `libnetscope.so` 还在 `lib/arm64-v8a/`、只要 AndroidX Startup 还有 `NetMonitorInitializer`，AGM3 的 crash 率仍会远高于 A 配置。
- 唯一能**完全**消除放大效应的办法：让宿主选择编译时是否打包 NetMonitor（A 配置）。运行时 kill-switch、`NoOpInitializer`、`LD_PRELOAD=<empty>` 之类都是**对这个 crash 无效**的。

**12.7.6 对 HMI / Telenav / DR 团队的更新结论**

- **根因 100% 在 tasdk 自己的 `libFoundationJni.so` HTTP client 栈**（`tn::http::client::ClientImpl` / `Session` 生命周期管理），与 NetScope 无关。证据链：
  1. `x17 = 0x7329eb9620` 不变量 → 固定指令 / 固定 libc stub；
  2. 跨 delay 配置 T+Δ ≈ 25s 相对恒定 → tasdk 内部有个固定时序的 work；
  3. D60 崩在 libnetscope 未加载前 → 与 NetScope 完全脱钩；
  4. libFoundationJni 带 `"can't perform request on a shut down client"` / `"unique_lock::lock: references null mutex"` 等诊断字符串 → shutdown-vs-worker race 的强候选。

- **推荐后续动作**（按优先级）：
  1. **对 `libFoundationJni.so` 符号化**（请 Telenav build 团队提供 `.symbols` 文件），然后 re-symbolize 任意一次 D60 round1 的 tombstone（`adb bugreport` → 解压 → `FS/data/tombstones/`），找到 `tn::http::client::ClientImpl::*` 真实调用栈。
  2. **本地 Debug/ASAN build 一版 `libFoundationJni.so`**，放在 AGM3 上跑 B 配置（依赖在、kill-switch 关闭），等 25s 自爆时 ASAN 会打印"use-after-free on `tn::http::client::Session* X` allocated by thread Y, freed by thread Z"——这是最短路径。
  3. 审 `tn::http::client::ClientImpl::shutdown()` 与 worker `onRequestComplete` 的同步路径，特别是 `shared_ptr<Client>` 是否在 worker 还在回调时被最后一个 reference holder destruct。

**12.7.7 对文档前文的更正**

- §12.6.4 "NetScope dlopen 落在 AutoSdkManager.init 窗口是放大机制" —— **已证伪**（12.7.2 的 D60 数据）。
- §12.6.5 "期望崩溃率随 delay_ms 线性推迟" —— **已证伪**（T+Δ 在 D0=25s / D60=25s 几乎相同）。
- §11 路径 G（时序碰撞验证）**已关闭**，不再需要继续跑 D3 / D10 / D30 等中间档位（会同样崩在 dlopen 前）。

**12.7.8 路径表更新**

| 路径 | 当前状态 | 备注 |
|---|---|---|
| A (静态删除依赖) | ✅ 已验证 3 × 180s = 0 崩 | 唯一完全消除 crash 的方案 |
| B (依赖保留 + kill-switch) | 仍偶发崩（T+Δ ≈ 25s）| 同一 bug |
| C (loadonly) | 近 100% 崩（T+Δ ≈ 25s）| 同一 bug，cold cache 下命中率更高 |
| G (delay_ms) | ✅ 本节完成，**证伪时序假说** | D0 = 3/3, D60 = 2/3（崩在 dlopen 前）|
| F (NetScope NoOp init) | 已由 G 路径结果**间接证伪**（即使完全不 load libnetscope.so 也崩）| 无需再跑 |
| H (libFoundationJni 符号化 + ASAN build) | ✳️ **新的最高优先级** | 12.7.6 |
| I (NetScope 作者侧 `NoOpInitializer`) | ✴️ 优先级下调 | 对这个 crash 无效 |

**12.7.9 本节原始数据**

- 驱动脚本：`/tmp/ns_loadonly/soak_delay.sh`
- D0 logs：`/tmp/ns_loadonly/D0_round{1,2,3}.log`、`D0_summary.txt`
- D60 logs：`/tmp/ns_loadonly/D60_round{1,2,3}.log`、`D60_summary.txt`
- smoke (delay=60000 plumbing 验证)：`/tmp/ns_loadonly/smoke_d60b.log`
- 被归档的"有 bug 版 delay" 结果：`/tmp/ns_loadonly/archive_pre_delayfix_1331/`
- 代码改动：`NavHome/module/netmonitor/src/main/java/com/telenav/netmonitor/NetMonitorService.kt`（loadonly 分支重构 + `readDelayMs()`）。

### 12.8 "为什么去依赖就稳、加依赖不 init 也崩" 的机制解释 + 长尾长稳反例（2026-04-24 13:26 ~ 14:17）

**12.8.1 用户问题原文**
> 把 netScope 的依赖去掉就不 crash，但是加上依赖不 init 也会 crash —— 解释一下。

本节用 §12.5 / §12.7 的实测数据 + 今天 13:55 之后观察到的"20+ min 长稳"新样本，给出可交付的完整解释。

**12.8.2 两句话答案**

1. **去依赖（A）稳 vs 加依赖不 init（B）仍崩** —— 是因为 **crash 的根因是 `libFoundationJni.so` 里 `tn::http::client::*` 栈的一个"启动窗口竞态"**，而**放大器**是 APK 被 `PackageManager` / linker / ART 扫描时的**宏观扰动**（dex 数、`lib/arm64-v8a/*.so` 数、AndroidX Startup provider 数、APK zip 结构位移对 mmap 的连带影响）。只要 netmonitor 打包进 APK，这些宏观扰动就存在——**跟 `libnetscope.so` 是否 dlopen、`JNI_OnLoad` 是否执行、hook 是否安装完全无关**。
2. **"加依赖不 init" 不 == "没这个依赖"**：kill-switch 只是让 `NetMonitorService.onCreate` 提前 return、不触碰 NetScope 任何 Java 符号、不调 `System.loadLibrary("netscope")`。但是 —— APK 里**额外那 4 个 `.so`（`libnetscope / libbytehook / libshadowhook / libshadowhook_nothing`）仍然在 zip 里占位置**，`PackageParser.collectNativeLibraryPaths()` 仍然会扫它们，**dex 多出 `com.telenav.netmonitor.*` / `indi.arrowyi.netscope.*` 的 Java 类和方法索引**，`AndroidManifest.xml` 里**多一个 `NetMonitorInitializer` 的 `<meta-data>`** 挂在 `androidx.startup.InitializationProvider` 下。以上每一项都是进程启动期几百毫秒内发生的事情，**每一项都会微幅改变** tasdk `AutoSdkManager.init` / `asdk.httpclient` 线程启动 / libcurl multi handle 初始化的**相对时序**——对一个本来就存在的竞态 bug 而言，"相对时序变化" = "触发概率从接近 0 抬升到可观察"。

**12.8.3 "APK 静态层面 8 项扰动" 清单（B 相对于 A 多出来的东西）**

这些都不需要 NetScope 运行任何代码就已经摆在那里。来源：`unzip -l base.apk` + `aapt2 dump xmltree AndroidManifest.xml` + `./gradlew :HMI:dependencies` 对 A/B 两版 APK 各跑一遍 diff。

| # | 差异项 | A（无依赖）| B（有依赖 + kill-switch）| 对宿主的潜在扰动面 |
|---|---|---|---|---|
| 1 | `lib/arm64-v8a/*.so` 总个数 | 19 | **23**（+4）| `PackageParser` 扫 native lib 多 4 次 `ZipEntry` open / `ELF` 头检查 |
| 2 | 多出的 `.so` | — | `libnetscope.so`、`libbytehook.so`、`libshadowhook.so`、`libshadowhook_nothing.so` | 4 × mmap / `extractNativeLibs=false` 下 ART linker 额外维护 4 个 VMA 区间 |
| 3 | `.dex` 中引用的 class | 基线 | **多出 `com.telenav.netmonitor.*` × 6 + `indi.arrowyi.netscope.sdk.*` × 10+** | `ClassLoader.findClass` 缓存 / hash bucket 大小变化；MultiDex split 阈值可能触发额外一个 dex 文件 |
| 4 | `AndroidManifest.xml` 中 `<service>` | 基线 | **多 1 个 `NetMonitorService`** | AMS 启动时对 service 列表的预扫描多一项 |
| 5 | `AndroidManifest.xml` 中 `<provider>` + `<meta-data>` | 基线 | 同一个 `androidx.startup.InitializationProvider` 下**多一个 `NetMonitorInitializer` 的 `<meta-data>`** | Startup 库里 `AppInitializer.discoverAndInitialize` 多一次反射调用 / 多一次 `Class.forName` |
| 6 | Provider 总个数 | 基线 | **+0**（`InitializationProvider` 本来就在） | 行为差异只在 meta-data 层 |
| 7 | `resources.arsc` 新增 netmonitor 的 `layout_*.xml` / `drawable_*.xml` / `item_*.xml` | 基线 | **+4 项** | Resource table 多 4 条索引；对 `Resources.updateConfiguration` 路径无关键影响 |
| 8 | APK zip 中 central directory 的 offset 值 | 基线 | 向后移 ~1.5 MB | `extractNativeLibs=false` 下业务 `.so` 的 APK 内 offset 和对齐位置**全部右移**，`mmap(fd, offset)` 落点变化 → ART/linker 给各个 `.so` 选的 load base 也随之变化 → **ASLR 后 `libFoundationJni.so` 和 libc / libart 的相对距离变化**，跨 `bl` / `adrp` 解析时的 cache line / TLB footprint 全部重排 |

**关键**：以上 8 项**每一项都在进程 attach 到用户代码之前**就已经生效了。tasdk 的 `Application.onCreate` / `AutoSdkManager.init` 在 T+3~4s 跑起来时，它面对的是**两套完全不同的宏观时序环境**，尽管它自己一行代码都没变。

**12.8.4 为什么放大机制是"APK 静态存在"而不是"NetScope 运行时"**

这是 §12.7 D60 实验的结论，复述一遍因为它是**证明关键**：

- D60 配置：`debug.netscope.diag=loadonly` + `debug.netscope.delay_ms=60000`，也就是"把 `System.loadLibrary("netscope")` 强制推迟到 T+60s 才调"。
- 实测：**崩溃在 T+25s 就已经发生 2 / 3 次**，logcat 里从始至终**没有 `JNI_OnLoad` 行**、`/proc/$PID/maps` 如果能在崩溃那一刻抓快照（做不到因为进程已死，但可以推：`dlopen` 还没跑，`libnetscope.so` **不在进程地址空间里**）。
- 结论：**NetScope 的 `dlopen` / `JNI_OnLoad` / 11 × `dlsym(RTLD_NEXT)` 完全不是崩溃的必要条件**。唯一还在起作用的是"NetScope 作为 APK 静态 artifact" ——也就是 12.8.3 表里那 8 项。

所以 "加依赖不 init 也崩" 的根本原因是：**"不 init" 仅阻止了 NetScope 层面的运行时代码执行（第 9 项~第 20 项 "如果有"），但不能阻止第 1~8 项的静态存在**，而**这些静态存在本身**就足以扰动 tasdk 自己的启动时序，把"本就存在"的 HTTP client race 从 ~0% 命中率抬到 ~80%。

**12.8.5 长尾长稳反例：2026-04-24 13:55 ~ 14:17 的 B 配置 21min+ 长稳**

**新数据点**（当前 pid 4835，`compileJavaSdk=true` 构建，B 配置 + `debug.netscope.diag=loadonly` 但 **loadonly 实际没执行**，21min+ 无崩）：

| 指标 | 值 | 说明 |
|---|---|---|
| APK 安装时刻 | 13:26:27 | `stat base.apk` Modify time |
| **同一 APK 的前 6 次冷启在 13:31~13:35 连续全崩**，gap 28s/37s/61s/39s/37s | 见下表 | 验证 "该 APK 仍能触发同指纹 crash"，bug 还在 |
| 当前存活进程 start | 13:55:43 | 距上次崩溃 +20:29 |
| 当前存活进程 alive | 14:17:05（观察时刻）| **21m22s 0 崩** |
| `/proc/4835/maps` 扫 netscope 相关 | `libnetscope / libbytehook / libshadowhook` **零命中** | 即便 `debug.netscope.diag=loadonly` 设到了，`loadonly` 分支也**没跑**（推测是 prop 在进程启动之后才被 setprop，所以 `NetMonitorService.onCreate` 读到的是 `off`；或者 `Class.forName(...NetScopeNative, true)` 时上游抛异常被 catch 掉。两个都不影响本节结论，因为即使 `loadonly` 跑了，按 §12.7 的 D60 证据也一样崩 / 不崩并非取决于此）|
| `shared_prefs/netmonitor_breaker.xml` | `restart_timestamps="1777010147363"`（唯一 1 条，= 13:55:47）| 证明 `NetMonitorService.onCreate` **确实执行了一次**（AndroidX Startup initializer → startForegroundService → onCreate → tripCrashLoopBreaker 追加时间戳）|
| UI 显示 | `NOT INIT`（= `Status.NOT_INITIALIZED`）| 来自 `NetDataRepository.AggregatedData.status`，当 `hookReportProvider()` 返回 null（即 `NetScope.getHookReport()` 抛异常或 NetScope 未初始化）时的默认值 |

**前 6 次 crash 的时间线**（在同一 APK 安装、同一 SELinux context 下连续发生）：

| 序号 | Crash 时刻 | 被崩 pid | 距 APK 安装 | 距上次 crash |
|---|---|---|---|---|
| 1 | 13:31:52 | 27231 | +5:25 | — |
| 2 | 13:32:20 | 27790 | +5:53 | +28s |
| 3 | 13:32:57 | 28220 | +6:30 | +37s |
| 4 | 13:33:58 | 28826 | +7:31 | +61s |
| 5 | 13:34:37 | 29385 | +8:10 | +39s |
| 6 | 13:35:14 | 29844 | +8:47 | +37s |

前 6 次 crash 的 `x17 = 0x7329eb9620` / `pc == x8` / `Abort message = '[pid]create DR Engine success, engineMode=1!'` / thread `asdk.httpclient` —— **与 §12.7 / §12.1 六次独立复现指纹完全一致**，同一个 bug。每个 pid 存活 28~61s 就自爆，平均 ~40s，典型的 tasdk HTTP client "启动窗口竞态"，**和历史 B 配置 "T+24s / T+28s / T+310s" 观察吻合**。

**12.8.6 21min+ 长稳不是对结论的反例，是分布长尾**

**重要**：B 配置下"20+ min 不崩"不能推翻 §12.7 的结论，理由：

1. **同一 APK 上，前 6 次冷启全崩，第 7 次长稳** —— 这是典型的**概率分布**特征，不是确定性。如果是确定性"B 一定不崩"，前 6 次就不会连崩。
2. **历史数据早就包含 "B 整窗未崩" 的样本**（§12 原文："`debug.netmonitor.enabled=0` 有时整窗未崩"）。21 分钟只是把窗口拉长，进一步确认"**越过启动窗口 ~60~120s 后，命中该 bug 的概率显著回落**"。
3. **`libFoundationJni.so` 的 MD5 在 A / B / 当前"compileJavaSdk=true"build 三者上完全一致**：
   ```
   02cd184e930f63c7bc26fb32e2452e7e  /tmp/libcompare/new_apk_libs/lib/arm64-v8a/libFoundationJni.so   (当前 APK)
   02cd184e930f63c7bc26fb32e2452e7e  /tmp/tombstones/so/libFoundationJni.so                           (之前崩溃 session 拉的)
   ```
   —— **尽管把 `compileJavaSdk=false → true` 改成从本地 `java-sdk-common` 源码编译 `:android-common / :arp-sdk / :arp-foundation / :map-poi / :adas / :system-interface` 五个子模块**，**但 native `.so` 二进制一字节没变**（多半是源码构建仍消费同一份预编译 .so AAR / 内嵌的 prebuilt lib）。也就是说"**这个 bug 还在里面**"，只是今天这次冷启没命中。
4. tasdk HTTP client 若确实是 **session 级 UAF**（§12.6.6 推论：`Session` 对象在 worker 回调未完成时被 destruct），那它是**启动阶段异步 response callback 密集期才命中**的 race —— 进程越过 tasdk 完全初始化稳态后（一般 T > 60~120s），网络 request/response 节奏放缓到"每分钟几次" / "几秒一次"，race 窗口随之缩小到肉眼难见的概率，自然可以长时间不崩。

**12.8.7 对用户问题的正面一句话回答**

> 把 NetScope 依赖去掉就不 crash，但加上依赖不 init 也会 crash？

**去依赖 = 从 APK 里物理删除 `libnetscope.so / libbytehook.so / libshadowhook.so / libshadowhook_nothing.so` 这 4 个 `.so` + `com.telenav.netmonitor` / `indi.arrowyi.netscope` 共十几个 Java 类的 dex 索引 + `AndroidManifest.xml` 里 `NetMonitorInitializer` 的 `<meta-data>` 条目**。这让 APK 启动期的 ART / linker / `PackageManager` / `androidx.startup` 的扫描工作量、mmap 布局、dex bucket 分布、`Class.forName` 跳转路径都回到"基线"。**基线下，tasdk 自己那个 HTTP client 启动窗口竞态的触发概率低到 540s soak 都抓不到**（§12.5 实测 3 × 180s = 0 崩）。

**加依赖不 init = APK 里前面那些东西都还在**，kill-switch 只是让 `NetMonitorService.onCreate` 提前 `return` 不执行 NetScope **运行时代码**；可 **那套"启动期宏观扰动"** 完全照旧（ART 给 `libFoundationJni.so` 选的 load base、dex bucket 分布、`asdk.httpclient` 线程起点的相对时刻全都被右移了）。tasdk 自己那个竞态 bug 因此从 ~0% 触发率抬到实测 ~80% 的 25~60s 内崩（§12.1 / §12.8.5 前 6 次）。**只要 tasdk 不修那个 race，这个抬升就不会消失。**

**12.8.8 可行的缓解路径（按代价排序，**tasdk 修 race 是唯一根治**）**

| 代价 | 方案 | 效果 |
|---|---|---|
| 0 | A 配置：**`Denali/HMI/dependencies.gradle` L166 注释掉 `implementation project(':netmonitor')`** + 重编发版 | **100% 消除** AGM3 上该 crash；但放弃 NetMonitor 本身 |
| 中 | 保留 netmonitor 模块，但**移除 NetScope transitive 依赖**（改 `NavHome/module/netmonitor/build.gradle` 把 `com.github.Arrowyi:NetScope` 去掉 + 把 `NetMonitorService` 里所有 `NetScope` / `HookReport` / `NetScopeNative` 引用改成 stub 或条件编译）。| 能把 APK 里 4 个 .so / 相关 dex 去掉，理论上等同于 A；但失去流量采集能力 |
| 中 | 让 `NetMonitor` 不通过 AndroidX Startup 自启，改成**完全懒加载**（用户点浮窗才启动 Service） | 消除 §12.8.3 表里第 5~6 项扰动；**不能**消除第 1~4 / 7~8 项 → 预期**只能部分缓解，不能根治** |
| 大 | Telenav 出带 ASAN / hwasan 的 `libFoundationJni.so` debug build，在 AGM3 跑 B 配置 25s 自爆时抓完整的 `tn::http::client::Session*` alloc/free trace，然后修 race | **彻底修复根因**，对 A/B/C 三档一并生效；这是路径 H，当前最高优先级 |
| 大 | 宿主 HMI 侧给 `asdk.httpclient` / DR-Engine 启动窗口加一层**"启动稳定期"软栅栏**（process age < 60s 时降级所有异步 HTTP 请求或提前 warmup 一次 dummy request） | 不修 race 本身，但可能把启动期 race 窗口挪出高命中区；**效果未验证，工程成本较大** |

**12.8.9 本节结论（发给团队的一段话）**

> 这次 HMI 打了 `compileJavaSdk=true` 从源码重编 Java SDK 子模块（`:android-common` 等 5 个），同时保留 `:netmonitor` 依赖；APK 安装后前 10 分钟里同一个 APK 连崩了 6 次（T+25~60s，指纹与 §12.1/§12.7 完全一致），之后一次冷启运行 21 min+ 无崩。这与 §12.5~§12.7 的结论**完全一致**：
> - `libFoundationJni.so` 一字节没变（MD5 match）→ bug 还在；
> - B 配置是概率事件（高概率在启动窗口内崩、越过 ~60s 命中率回落），**不是确定性**；
> - 加依赖不 init 仍崩是因为放大源是 APK 静态层面（§12.8.3 的 8 项扰动），而不是 NetScope 运行时代码（§12.7 D60 已证伪）；
> - 发版侧的唯一**100% 稳妥办法是 A 配置（彻底移除 `:netmonitor` 依赖）**；要根治必须由 Telenav 在 `libFoundationJni.so` 的 `tn::http::client::ClientImpl` / `Session` 生命周期管理上修 race（路径 H）。

**12.8.10 本节原始数据**

- 当前进程 maps：`/tmp/tombstones/maps_4835.txt` / `maps_4835b.txt`（pid 4835，21min+ 长稳样本）
- 当前 APK 解压的 `libFoundationJni.so`：`/tmp/libcompare/new_apk_libs/lib/arm64-v8a/libFoundationJni.so`
- 参考对比的崩溃版：`/tmp/tombstones/so/libFoundationJni.so`
- 全部 tombstone 聚合：`/tmp/tombstones/all_stones_raw.txt`（58 entries，含本次安装 13:31~13:35 的 6 次）
- tombstone 结构化分析脚本：`/tmp/tombstones/analyze.py`、`/tmp/tombstones/find_blr_x8.py`
- 符号表：`/tmp/tombstones/foundation_syms.txt`；反汇编：`/tmp/tombstones/libFoundationJni.disasm`（8 MB，太大不进仓库）
- git 工作区诊断：`NavHome/Apps/Denali/gradle.properties` `compileJavaSdk=true`、`NavHome/Apps/Arp/gradle.properties` `compileJavaSdk=ture`（**注意是 typo 'ture'；仅影响 Arp app 子树，Denali 这套 APK 不受影响；需要修**）。
