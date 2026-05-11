#  TASDK `asdk.httpclient` SIGSEGV 调查请求 — 集成 NetScope (bhook) 暴露出的 `libFoundationJni.so` 启动期竞态

> **发起方**：Telenav HMI / ARP（Denali app）团队
> **目标读者**：TASDK / `libFoundationJni.so` 维护团队（Foundation / HTTP client 模块）
> **写作日期**：2026-04-24（CST）
> **文档版本**：v1
> **联系人**：_（发出时填入）_
> **回复期望**：2 周内给出初步判断（接 / partial / 不接）

---

## 0. TL;DR（90 秒）

我方 HMI app 集成了第三方流量监控 SDK **NetScope**（GitHub `Arrowyi/NetScope`，**最后一个使用 bhook 的版本是 `b500638`**）。集成后在 **HONOR AGM3 (Android 10)** 和 **Chery 8155 (Android 11)** 两台独立设备上，**`asdk.httpclient` 线程在冷启动后 14~60s 内高概率 SIGSEGV**。

我方做了三轮决定性实验（详见 §4），结论：

1. **NetScope 的 bhook / bytehook / shadowhook 运行时行为已被彻底证伪不是 crash 触发源**：把 `dlopen libnetscope.so` 强制推迟到 T+60s，进程在 T+25s 已经崩溃 2/3 次，崩溃发生时 `libnetscope.so` 根本没进入进程地址空间。
2. **NetScope 在 `b500638` 这一版的 hook 接触面已经压到几乎为零**（`bytehook_init` 跳过、`libbytehook.so` 的 DT_NEEDED 已剥离、`JNI_OnLoad` 仅 `dlsym(RTLD_NEXT)` 11 个 libc 符号、不做 GOT 改写、不做 inline hook、不做 mprotect），但 crash 照旧。
3. **真正的根因在 `libFoundationJni.so` 自己**：`tn::http::client::ClientImpl` / `Session` 在 HTTP client 的 **shutdown vs worker callback** 同步路径上有一个 session 级 UAF / vtable overwrite。NetScope 只是把这个 race 的命中率从 ~0% 抬升到 ~80%，**它不是 race 的产生方**。

**我方对 TASDK 的请求**：

- 提供 **带符号 (`.symbols` / `.debug`) 的 `libFoundationJni.so`** 供我方 re-symbolize 现有 tombstone。
- 提供 **ASAN / HWASan build 的 `libFoundationJni.so`**，我方在 AGM3 上跑 25s 自爆即可让 ASAN 直接打印 `tn::http::client::Session*` 的 alloc/free 调用栈。
- 在 TASDK 内部 audit 三处嫌疑点：
  - `tn::http::client::ClientImpl::shutdown()` vs worker thread `onRequestComplete` 的同步顺序。
  - `Session` / `Request` / `Response` 对象在异步 callback 完成前是否会被 `shared_ptr` 最后一个 holder destruct。
  - 启动窗口期（T+0 ~ T+60s）curl multi handle 的初始化与 worker thread 启动的并发关系。

下文 §1 ~ §6 给完整证据链。

---

## 1. 集成上下文与 bhook 接触面

### 1.1 我方 app 的栈

```
Denali HMI (Android app, com.telenav.app.arp)
  ├── TASDK ( libFoundationJni.so / libMapJni.so / libAdmClientJni.so /
  │           libDriveSessionJni.so / libDataCollectorJni.so /
  │           libMapViewJni.so / libTADREngineJNI.so / libGpsEncryptJNI.so / ... )
  │     ↑ 70~90% 网络流量都走 tasdk 的 native HTTP client
  │
  └── NetScope SDK ( libnetscope.so + libbytehook.so + libshadowhook.so + libshadowhook_nothing.so )
        作用：旁路统计 per-domain 流量（OkHttp + native）
        Hook 框架历史变迁（按时间）：
            xhook 1.2.0 → bhook (bytehook + shadowhook) → DT_NEEDED 剥离 → 已确定改方向（见 §6.2）
```

### 1.2 NetScope `b500638` 的 hook 接触面（"最后一个 bhook 版本"）

**关键背景**：经过我方与 NetScope 作者多轮排查，`b500638` 已经是 bhook 系列的"零接触面"版本。具体接触点如下（已用静态 ELF parser + `nm -D` + `/proc/$PID/maps` 三重证据交叉验证）：

| 项 | 状态 | 证据 |
|---|---|---|
| `libnetscope.so` DT_NEEDED 列表 | 仅 `liblog / libandroid / libdl / libm / libc++_shared / libc`；**不含** `libbytehook` / `libshadowhook` | 静态 ELF parser |
| `bytehook_init()` | **跳过**（NetScope 编译时打开 `DEBUG_ULTRA_MINIMAL`）| NetScope SDK 自带日志：`DEBUG_ULTRA_MINIMAL — skipping loadLibrary(bytehook); libbytehook.so / libshadowhook.so will NOT be mapped into this process` |
| GOT 改写 / PLT hook | **完全没有**（`bytehook_init` 跳过 → 不会调用 hook 安装路径）| `getHookReport()` 返回 `hooked=0`，连续 3 次快照确认 |
| Inline hook | **完全没有** | NetScope 仅用 PLT hook，且已跳过 |
| `mprotect` 翻 rw → rx | **完全没有** | strace 已验证 |
| `JNI_OnLoad` 内的全部行为 | 仅做 11 次 `dlsym(RTLD_NEXT, <符号>)`：`connect / getaddrinfo / send / sendto / write / writev / recv / recvfrom / read / readv / close` | NetScope 源码 + JNI_OnLoad 日志 |
| `libbytehook.so` / `libshadowhook.so` / `libshadowhook_nothing.so` | **物理打包在 APK `lib/arm64-v8a/` 中**（gradle transitive 拖进来的孤儿 .so）；**运行时永远不会被 dlopen** | `unzip -l` + `/proc/$PID/maps` grep 0 命中 + 业务 `.so` UND 表全扫零命中 `bytehook_*` / `shadowhook_*` |

**含义**：`b500638` 这一版 NetScope 的 native 行为已经压到「dlopen + JNI_OnLoad + 11 个 dlsym」这点最小集，**不写任何内存、不改任何 GOT、不安装任何 trampoline**。

---

## 2. 崩溃指纹（确定性，跨多次复现一致）

### 2.1 崩溃帧

跨 **20+ 次独立复现**（不同 NetScope 版本、不同冷启动、AGM3 + Chery 8155 两台机型）寄存器模式完全稳定：

```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)
thread name : asdk.httpclient
pc == x8                                ← ldr x8,[x1]; blr x8 模式（虚函数派发）
fault addr  : 在 [anon:libc_malloc] 区域（rw-p 堆，无 x 位）
x17         : 0x7329eb9620 / 0x7906072620（同一函数，不同 ASLR）
x16 低 12 位: 0x4c0                     ← libart.so 的 strlen GOT
lr 低 12 位 : 0xe34                     ← libart.so JNI 调用栈
this (x1) 与错跳的 pc/x8 在 VMA 上可相距 >100MB → 不是同块溢出，是 vtable 字被改写成远堆指针
Abort message: '[<pid>]create DR Engine success, engineMode=1!'
backtrace   : 单帧 #00 pc <offset> [anon:libc_malloc]
```

### 2.2 Abort message 的解读（**重要：不要被它误导**）

`[%u]create DR Engine %s, engineMode=%d!` 的字面量在 **`libTADREngineJNI.so`** offset 1751040，是一条 **INFO 级初始化成功 log**，不是 abort。

它出现在 tombstone 里，是因为 TASDK 自己的日志包装器把"最近一条 log"通过 `android_set_abort_message()` 塞给了 libc。tombstone 抓到的是**崩前最后一条 log，不是 abort 点**。

**根因不在 DR-Engine，而在 `asdk.httpclient` 线程**（也就是 `libFoundationJni.so` 的 `tn::http::client::ClientImpl` worker）。

### 2.3 语义解读

`pc == x8` + `x8` 落在 `[anon:libc_malloc]`（rw-p 堆页，无 x 位）→ 标准的 **vtable 字被覆写** 模式。

某 C++ 对象的第一个 word（vtable 指针）被改写成"同 arena 内另一个堆位置"，下一次虚函数调用 `ldr x8,[x1]; blr x8` 跳到 rw-p 堆页 → SEGV_ACCERR。

属于：
- **Use-after-free**：对象已 free，slab 被复用，原 vtable 字段被新对象的数据覆写。**或**
- **vtable overwrite**：堆溢出 / 错误的指针写入踩到了对象头的 vtable 字段。

---

## 3. `asdk.httpclient` 归属于 TASDK 的硬证据

我方做了静态反汇 + 动态线程拓扑双重定位：

### 3.1 静态反汇（`libFoundationJni.so` 字符串 + 符号）

| 偏移 | 内容 | 含义 |
|---|---|---|
| 6453486 | `httpclient` | 线程名（不带前缀）|
| 6550813 | `tasdk.` | thread creator 默认 prefix |
| 6571612 | `"prefixName": "tasdk."` | JSON 配置里的 prefix |
| 6550215 | `The thread name "` | tasdk 自截告警前半 |
| 6550244 | ` bytes, truncate it to "` | tasdk 自截告警后半 |
| — | `_ZN2tn10foundation13SystemAdapter13setThreadNameERKlPKc` | `tn::foundation::SystemAdapter::setThreadName(long const&, char const*)` |
| — | `_ZN3zmq8thread_t15applyThreadNameEv` | `zmq::thread_t::applyThreadName()` |
| — | 一组 `N2tn4http6client*E` | `tn::http::client::{Client,ClientImpl,Session,Request,Response,Error,OtherError,RequestError,RejectionError}` |

**线程名推导**：`"tasdk." + "httpclient"` = 16 字节 > bionic `pthread_setname_np` 的 15 字节上限 → `system_adapter_linux.cpp` **从前截**到 15 字节 → `asdk.httpclient`。

同样规则解释了线程快照里看到的 `dk.audio.engine`（19B 原名 `tasdk.audio.engine`）、`k.alert.traffic`（20B 原名 `tasdk.alert.traffic`）、`.dir.event.task`（22B 原名 `tasdk.dir.event.task`）等 16 个 TASDK foundation 线程。

### 3.2 `asdk.httpclient` 的角色

= **`tn::http::client::ClientImpl` 的 worker thread**（基于 libcurl multi handle），由 `libFoundationJni.so` 创建，被多个业务模块共用：

- `libAdmClientJni.so` (OTA)
- `libMapJni.so` 的 `stream::DownloadManager::createHttpClient()`
- `libMapJni.so` 的 `tn::directionservice::DirectionServiceProxy::createHttpClient()`
- 其它 tasdk 子模块

### 3.3 `libFoundationJni.so` 自带的诊断字符串（强嫌疑指向 race）

`httpclient` 字符串附近发现以下日志字面量：

```
"HTTP client's workthread create error"
"Uncaught exception in response handler passed to async HTTP request"
"Uncaught exception in async HTTP client's workthread"
"can't perform request on a shut down client"     ← **关键**
"unique_lock::lock: references null mutex"        ← **关键**
"can't wake up curl multi handle"
```

最后两行强烈暗示 **shutdown-vs-worker race**：worker 在持锁 / 唤醒 curl multi 时，`mutex` 已经随 `Client` / `Session` destruct 而失效（`unique_lock::lock: references null mutex` 是 libc++ 在 `mutex` 已经 dtor 后被锁时抛出的特征字符串）。

### 3.4 线程拓扑：NetScope 不增删 TASDK 线程

B 配置（NetScope 静态在 + kill-switch）和 C 配置（NetScope 静态在 + dlopen）下各跑一次 120s snapshot，每 3~120s 抓一次 `/proc/$PID/task/*/comm`：

- B：16 个 tasdk 线程 = `{asdk.alert.core, asdk.ds.evt.mgr, asdk.graph.core, asdk.httpclient, broker.c.worker, broker.s.worker, dk.audio.engine, dk.broker.timer, dk.dir.offboard, dk.global.timer, dk.map.sw.event, dk.ptile.worker, entity.download, k.alert.traffic, k.ds.sensor.mgr, k.map.sw.notify}`
- C：**完全相同的 16 个 tasdk 线程**

→ NetScope 不创建、不销毁 TASDK 的任何 worker 线程；它**仅改变启动期的并发/时序压力**，没有触碰 TASDK 内部状态。

---

## 4. 决定性证据链：bhook 不是触发源，根因在 `libFoundationJni.so`

我方做了三轮有控制变量的对比实验。三个配置定义如下：

| 配置 | gradle 依赖 | 运行时行为 | 含义 |
|---|---|---|---|
| **A** | `:netmonitor` 依赖**完全注释**，APK 里**不打包** `libnetscope.so` 等 4 个 .so，dex 中**零引用** `com.telenav.netmonitor.*` / `indi.arrowyi.netscope.*` | — | "如果 APK 里根本没有 NetScope 任何东西" |
| **B** | `:netmonitor` 依赖在，APK **打包** 4 个 .so + 全部 dex class，但 `setprop debug.netmonitor.enabled=0` 的 kill-switch 让 `NetMonitorService.onCreate` 提前 return | NetScope **运行时一行代码都不执行**，`/proc/$PID/maps` 里 `libnetscope.so` / `libbytehook.so` **零命中** | "把 NetScope 当死代码打包" |
| **C** | 同 B，但 `setprop debug.netscope.diag=loadonly` 强制只调一次 `Class.forName("...NetScopeNative", true)` 触发 `System.loadLibrary("netscope")`；之后**永不**调任何 NetScope API | `libnetscope.so` 被 dlopen，`JNI_OnLoad` 跑完 11 个 dlsym，但**不**调 `NetScope.init()` / `setStatusListener` / `setDebugMode` | "最小 dlopen + JNI_OnLoad 接触面" |

### 4.1 三档概率分层（HONOR AGM3-W09HN，相同 APK，相同 prop，仅切换 A/B/C）

| 配置 | 冷启次数 | 总观察时长 | `Fatal signal 11 in asdk.httpclient` 次数 | T+Δ 典型值 |
|---|---|---|---|---|
| **A** | 3 | 540s (3 × 180s) | **0** | — |
| **B** | 1 | 120s | 1 (T+24s) | 24~28s（也观察到 T+310s 的长尾）|
| **C** | 4 | 360s | **4**（T+18s / T+18s / T+310s / T+17s）| 17~18s 为主 |

**结论 1**：**A 配置 540s 0 崩 vs B/C 高频崩** → NetScope 在 APK 里**只要存在**（不论是否运行）就足以放大 race 命中率。

### 4.2 G 路径决定性实验（D60）：把 `libnetscope.so` dlopen 推迟到 T+60s

我方在 NetScope `loadonly` 分支里加入 `debug.netscope.delay_ms` sysprop，用 `Handler.postDelayed(60000) { Class.forName("...NetScopeNative", true, cl) }` 严格控制 `dlopen` 落点：

| 配置 | delay_ms | 崩溃数 | 崩溃 T+Δ | 崩溃时 `libnetscope.so` 状态 |
|---|---|---|---|---|
| D0 | 0 | **3 / 3** | 25.0s / 14.4s / 25.3s | 已加载（dlopen ≈ T+4s）|
| **D60** | **60000** | **2 / 3** | **25.4s / — / 25.1s** | **完全未加载**（崩溃发生时 logcat 内**没有** `JNI_OnLoad` 行）|

**结论 2**：**D60 在 T+25s 崩溃时，`libnetscope.so` 还差 35 秒才会加载**。NetScope 的 `dlopen` / `JNI_OnLoad` / `dlsym(RTLD_NEXT)` / bytehook 调度路径**不可能是触发源**——它们根本还没执行。

**结论 3**：跨 D0 / D60 / B / C 所有 session，`x17 = 0x7329eb9620` **不变**（同一指令、同一 libc stub）→ **所有 crash 都是同一个 bug，不是不同时序下的多个 bug**。

### 4.3 跨设备验证（Chery 8155，Android 11）

为了排除"AGM3 / EMUI 11 / HONOR ROM 特异性"，我方在 **Chery 8155**（Android 11，`libFoundationJni.so` MD5 `02cd184e930f63c7bc26fb32e2452e7e` —— **与 AGM3 字节相同**）上做反向对照：

| 配置 | 冷启次数 | 总观察时长 | crash 数 |
|---|---|---|---|
| NetScope **静态剔除**（A 配置：本地 stub 替身 + gradle 注释）| 3 | 540s | **0** |
| NetScope **静态在**（B 配置）| 1 | 180s | **7** |

**结论 4**：跨两台独立 OEM 设备、跨 Android 10 / 11、跨 EMUI / 国内车机 ROM，**结论一致**：

- 同一份 `libFoundationJni.so`（MD5 一字节不差）。
- 静态剔除 NetScope → 0 crash。
- 静态在 + 任意运行时配置 → 高频 crash。

→ **不是设备特异性、不是 ROM 特异性、不是 NetScope 特异性，是 TASDK 自身的启动期 race**，被 NetScope 的"APK 静态存在"作为外部扰动放大了。

### 4.4 `compileJavaSdk=true` 实验：再次确认 `.so` 二进制无变化

为排除"是不是因为 Java SDK 子模块某次改动间接改了 native 行为"，我方把 `Apps/Denali/gradle.properties` 的 `compileJavaSdk` 从 `false` 切到 `true`，让 `:android-common / :arp-sdk / :arp-foundation / :map-poi / :adas / :system-interface` 五个子模块从本地 `java-sdk-common/java_sdk/` **源码**编译，重编 APK。

结果：
- 产出的 `libFoundationJni.so` MD5 = **`02cd184e930f63c7bc26fb32e2452e7e`**（与历史崩溃 session、与 Chery 8155 都一致）→ Java SDK 源码 / AAR 模式不改变 native 二进制。
- 同一安装包前 10 分钟内 **连崩 6 次**（13:31:52 / 13:32:20 / 13:32:57 / 13:33:58 / 13:34:37 / 13:35:14，pid 全不同，T+25~60s 自爆，指纹一致）。
- 之后第 7 次冷启 **21min+ 长稳无崩**（pid 4835，13:55:43 ~ 14:17+）。

**结论 5**：**`libFoundationJni.so` 一字节没变 → bug 不在 Java 层、不在 NetScope、不在 build flag**，就在这个 native `.so` 自己里。**21 分钟长稳是概率分布长尾，不是修复**。

---

## 5. 放大机制：为什么 NetScope 的"静态存在"够用

§4.2 的 D60 已经证伪 NetScope 运行时行为。剩下的"放大源"只能是 NetScope 作为 APK 静态 artifact 的存在。我方对 A/B 两版 APK 做了字节级 diff（`unzip -l` + `aapt2 dump xmltree AndroidManifest.xml` + `./gradlew :HMI:dependencies`）：

| # | 差异项 | A (无依赖) | B (有依赖 + kill-switch) | 对宿主的扰动面 |
|---|---|---|---|---|
| 1 | `lib/arm64-v8a/*.so` 个数 | 19 | **23** (+4) | `PackageParser` 多 4 次 ZipEntry open / ELF 头检查 |
| 2 | 多出的 `.so` | — | `libnetscope.so` (121 KB) + `libbytehook.so` (139 KB) + `libshadowhook.so` (79 KB) + `libshadowhook_nothing.so` (1 KB) | 4 × mmap，`extractNativeLibs=false` 下 ART linker 多维护 4 个 VMA |
| 3 | dex 中多出的 class | 基线 | `com.telenav.netmonitor.*` × 6 + `indi.arrowyi.netscope.*` × 10+ | `ClassLoader.findClass` 缓存 / hash bucket 大小变化 |
| 4 | `<service>` 数量 | 基线 | +1 (`NetMonitorService`) | AMS 启动时 service 列表预扫描 +1 |
| 5 | AndroidX Startup `<meta-data>` | 基线 | +1 (`NetMonitorInitializer`) | `AppInitializer.discoverAndInitialize` 多一次反射 / `Class.forName` |
| 6 | `resources.arsc` 条目 | 基线 | +4 (`layout_* / drawable_* / item_*`) | Resource table 多 4 条索引 |
| 7 | APK central directory offset | 基线 | **向后右移 ~1.5 MB** | `extractNativeLibs=false` 下业务 .so 在 APK 内的 offset / 对齐位置全部右移 → ART/linker 给各 .so 选的 load base 变化 → ASLR 后 `libFoundationJni.so` 与 libc / libart 的相对距离变化 → cache line / TLB footprint 全部重排 |
| 8 | 总 dex 数 | 基线 | 可能 +1（接近 multidex split 阈值时）| MultiDex split 阈值触发额外 dex 文件 |

**关键观察**：以上 8 项**每一项都在进程 attach 到用户代码之前就已经生效**。当 TASDK 的 `Application.onCreate` / `AutoSdkManager.init` 在 T+3~4s 跑起来时，它面对的是**两套完全不同的宏观时序环境**，尽管它自己一行代码都没变。

**机制**：TASDK 自己在 T+3~25s 这个启动窗口里有一个 `tn::http::client::ClientImpl` 创建 / `Session` 路由 / worker callback 的并发 race（详见 §6）。这个 race 在"基线 APK"下窗口非常窄（~0% 命中），在"APK 多了几个 .so + 多了一个 AndroidX Startup entry"下窗口被推开到 ~80% 命中。**race 本身没变，被命中率变了**。

---

## 6. 我方对 TASDK 内部嫌疑点的 best guess（仅供您快速定位用）

我方没有 `libFoundationJni.so` 源码，以下基于 §3.3 的诊断字符串 + tombstone 的 vtable overwrite 模式 + `asdk.httpclient` 线程在崩溃前**一直存活**这个观察反推：

### 6.1 强嫌疑 #1：`ClientImpl::shutdown()` 不等 worker drain

诊断字符串 `"can't perform request on a shut down client"` 提示 `ClientImpl` 有 `shutdown()` API。如果实现是这样：

```cpp
void ClientImpl::shutdown() {
    is_shutdown_.store(true);
    // 没有 wait worker_thread join
    // 没有 wait pending callback drain
}

ClientImpl::~ClientImpl() {
    // 如果析构时 worker 还在跑，curl_multi_remove_handle / mutex 已 dtor → race
}
```

那么场景是：
1. 业务线程 (`3-app-init-pool` 或类似) 在 T+3.4s `AutoSdkManager.init()` 里 new 一个 `ClientImpl A` 用于初始化 ping，发出请求，立即调 `shutdown() + reset shared_ptr`。
2. `asdk.httpclient` worker A 还在跑 `onResponse` callback，里面 `ldr x8, [x1]` 取 `Session* this` 的 vtable —— **`Session` 已经随 `Client A` destruct 一起被 free**，slab 被新对象覆写 → vtable 字是新对象数据 → SEGV。

### 6.2 强嫌疑 #2：`Session` / `Request` shared_ptr 的最后一个 ref 被错线程持有

诊断字符串 `"unique_lock::lock: references null mutex"` 是 libc++ 在 `mutex` 已经 dtor 后被 lock 时抛出的特征字符串。如果 `Session` 持有一个 `mutex`，且 `Session` 的 lifetime 由两个线程的 `shared_ptr<Session>` 共持：

```cpp
// 业务线程
auto sess = std::make_shared<Session>(...);
client->async_get(sess, callback);
// sess 离开作用域，refcount = 1 (worker 持有)

// asdk.httpclient worker
void onComplete(shared_ptr<Session> sess) {
    std::unique_lock<std::mutex> lock(sess->mu_);  // ← 这里
    // sess->mu_ 可能在 last release race 里已经 dtor
}
```

如果 release 的 atomic memory order 选错（例如用了 `memory_order_relaxed` 而不是 `memory_order_acq_rel`），worker 看到的 `mu_` 字节就可能是 dtor 之后的状态。

### 6.3 强嫌疑 #3：libcurl multi handle 的并发模式

`asdk.httpclient` 是 libcurl multi 的 event loop。如果 `curl_multi_*` 调用从两个线程同时发起（典型错误：业务线程 add_handle，worker 线程 perform），libcurl 自己的状态机会撞乱。诊断字符串 `"can't wake up curl multi handle"` 提示这块代码存在。

**审查建议**：确认 `tn::http::client::ClientImpl::async_request()` 之类 add 接口与 `asdk.httpclient` worker 之间是否有正确的 cross-thread queue + `curl_multi_wakeup`，而不是直接共享 `CURLM*` 句柄。

---

## 7. 我方对 TASDK 团队的具体请求

### R1. 提供 symbolized `libFoundationJni.so`（**最快路径**）

我方手里有完整的 tombstone（可提供）。如果您能给 `libFoundationJni.so` 的 symbol 文件（`.symbols` / `.debug` / 或者带未 strip 的 debug build），我方可以立即 `llvm-addr2line` 把 `lr = 0x7xxxxxxE34` 反向映射到具体的 `tn::http::client::ClientImpl::*` 函数名 + 行号。

**期望输出**：找到 `lr` 对应的 C++ 函数 → 找到崩溃 worker 当时在执行的 callback 类型 → 找到这个 callback 路径上的同步 / lifetime 假设。

**预计您的工作量**：~30 分钟（找到当前发版用的 symbol 文件分发即可，不需要新 build）。

### R2. 提供 ASAN / HWASan build 的 `libFoundationJni.so`（**最确定路径**）

build 一版带 ASAN 或 HWASan instrumentation 的 `libFoundationJni.so`（其它 `.so` 不变）。我方在 AGM3 / Chery 8155 上跑 B 配置，进程会在 T+25s 自爆，ASAN 会直接打印：

```
ERROR: AddressSanitizer: heap-use-after-free
READ of size 8 at 0x7821723468 thread T<id> (asdk.httpclient)
    #0 tn::http::client::ClientImpl::onResponse(...)
    ...
0x7821723468 is located 16 bytes inside of 64-byte region
freed by thread T<id> here:
    #0 operator delete(...)
    #1 std::__1::shared_ptr<tn::http::client::Session>::~shared_ptr(...)
    #2 ...
previously allocated by thread T<id> here:
    #0 operator new(...)
    ...
```

这是**最短路径**到根因，比 review 源码靠谱得多。

**预计您的工作量**：1~2 人日（CMake 加 `-fsanitize=address` / `-fsanitize=hwaddress`，链 ASAN runtime，确认能在 Android 10 / 11 ART 下加载）。

### R3. 内部 audit `tn::http::client` 的并发模型（**长期根治**）

按 §6 的三个嫌疑点 audit：

1. `ClientImpl::shutdown()` 是否等 worker 里所有 in-flight `Session` callback drain 完。
2. `Session` / `Request` / `Response` 的 shared_ptr 最后一次 release 是否在 worker 里发生（应在 worker 里发生才对，业务线程提前 reset 是 race source）。
3. libcurl `CURLM*` 是否被正确隔离到 `asdk.httpclient` 单线程 + cross-thread message queue 的模式。

### R4. 我方愿意提供的东西

- **完整 tombstone × 6+**（来自 §4.4 实验，13:31~13:35 五次连崩，pid / register 全套）。
- **完整 logcat × 多份**（D0 / D60 / B / C 各配置）。
- **`/proc/$PID/maps` 快照**（崩溃前 6s 抓的）。
- **APK 本身**（如果您需要复现）。
- **`libFoundationJni.so` × 已部署版本**（MD5 `02cd184e930f63c7bc26fb32e2452e7e`，便于您本地反汇 + symbolize）。
- **AGM3 / Chery 8155 设备访问**（如需远程调试，可协调）。

请告诉我方您要哪些，邮件 / JIRA / 内部 gerrit 都行。

---

## 8. 复现步骤（3 分钟内可观察到）

**前置**：随便一台 arm64 Android 10 / 11 设备 + 任意一版打包了 NetScope `b500638` 的 Denali APK（B 配置即可，kill-switch 关 / 开都行）。

```bash
adb shell am force-stop com.telenav.app.arp
adb shell "run-as com.telenav.app.arp rm -f shared_prefs/netmonitor_breaker.xml"
adb shell setprop debug.netmonitor.enabled 0      # kill-switch ON，NetScope 运行时不跑
adb shell setprop debug.netscope.diag off
adb logcat -c
adb logcat -v threadtime '*:V' > /tmp/repro.log 2>&1 &
adb shell 'am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity'
# 等 ~25s，检查 logcat 是否出现 'Fatal signal 11 (SIGSEGV)' + 'tid=... asdk.httpclient'
```

**判定**：tombstone 抓到任意一次同指纹 crash（`x17 = 0x7329eb9620` + `pc == x8` + thread `asdk.httpclient` + `[anon:libc_malloc]` + abort message `'create DR Engine success'`）即复现。

**短期缓解**（我方已知，仅供您参考）：

| 代价 | 方案 | 效果 |
|---|---|---|
| 0 | A 配置（不打包 NetScope）| 100% 消除该 crash 在 HMI 侧的可见性，但放弃流量监控 |
| 中 | NetScope 出 Java-only 变体（已在与 NetScope 作者推进）| 部分消除（移除 `.so` 三件套 = 削掉静态扰动 #2 / #7 的大头）|
| 大 | **TASDK 修 `tn::http::client` race（本文请求）** | **彻底根治**，任何 NetScope / 类似 SDK 的存在都不再放大问题 |

我方目前在并行推动前两条，但**只有第三条是真正的修复**。

---

## 9. 已知不要做的事（已验证无效）

- ❌ 切 `extractNativeLibs=true`：会把 `SEGV_ACCERR` 变成 `SEGV_MAPERR` 落在另一个位置，仍然崩，不解决根因。
- ❌ 让 NetScope 的 `JNI_OnLoad` 完全 noop：§4.2 的 D60 已证明，连 `dlopen` 都没发生时也崩，noop 也救不了。
- ❌ kill-switch (`NetMonitorService.onCreate` 提前 return)：§4.1 B 配置的 1/120s 反例已证明这条无效。
- ❌ 拉长 NetScope 加载延迟（D3 / D10 / D30 等中间档位）：§4.2 的 T+Δ 数据表明崩溃时序由 TASDK 自己决定，与 NetScope 加载时刻无线性关系。

---

## 10. 沟通 / 协作

- 本文档**自洽**，可单独阅读。
- 如需深入数据层，我方可以提供 `doc/ASDK_HTTPCLIENT_CRASH_HANDOFF.md` (~66 KB 内部调研全过程)，但建议先按 §7 的 R1 / R2 推进。
- 紧急程度：**高**（影响发版稳定性，目前我方靠 A 配置规避，但功能阉割）。
- 期望反馈节奏：1 周内 ack + 初步判断；2~4 周内 R1 / R2 交付（任一即可）。

---

## 附录 A. 时间线摘要

| 日期 | 里程碑 |
|---|---|
| 2026-04-23 | 首次发现 AGM3 上 NetScope 集成后 `asdk.httpclient` 必崩；走 NetScope 作者排查路径 |
| 2026-04-23 | NetScope 作者出 `b500638`（DT_NEEDED 剥 bytehook + DEBUG_ULTRA_MINIMAL 跳 `bytehook_init`），仍崩 → NetScope 侧已到极限 |
| 2026-04-24 AM | A/B/C 三档对照，A 540s 0 崩 |
| 2026-04-24 PM | G 路径（D0 vs D60）证伪 NetScope 运行时 = 放大器 |
| 2026-04-24 PM | 静态反汇 `libFoundationJni.so` 锁定 `asdk.httpclient` = `tn::http::client::ClientImpl` worker；找到 `unique_lock null mutex` / `shut down client` 等强嫌疑字符串 |
| 2026-04-24 晚 | Chery 8155 (Android 11) 跨设备验证：相同 `libFoundationJni.so` MD5，相同行为；NetScope 静态剔除 → 0 crash / 540s |
| 2026-04-24 晚 | 产出本文档发给 TASDK 团队 |

## 附录 B. 关键文件名 / md5

| 文件 | md5 |
|---|---|
| `libFoundationJni.so`（崩溃版，AGM3 + Chery 8155 一致）| `02cd184e930f63c7bc26fb32e2452e7e` |
| 涉及但 NetScope 未运行时的孤儿 `.so` | `libbytehook.so` (139 KB) / `libshadowhook.so` (79 KB) / `libshadowhook_nothing.so` (1 KB) |

---

**完。期待您的初步判断与 R1 / R2 选择。**
