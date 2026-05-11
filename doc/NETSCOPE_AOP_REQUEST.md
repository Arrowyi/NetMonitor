# NetScope AOP 流量统计方案需求文档

> **目标读者**：NetScope 作者（GitHub `Arrowyi/NetScope`，当前版本 `b500638`）。
>
> **本文档的用途**：代替 `TRAFFIC_MONITOR_TIERED_PLAN.md §Tier 3.4` 的一段话模板，作为我方（Telenav HMI / ARP 团队）对 NetScope 的**正式需求**。
>
> **写作时间**：2026-04-24 晚。
>
> **前置阅读**（如作者想核对结论，可按顺序参考我方内部文档；但本需求稿是自洽的，读不到内部文档也不影响理解）：
> - `doc/ASDK_HTTPCLIENT_CRASH_HANDOFF.md` §12.5 / §12.7 / §12.8（crash 根因收敛证据链）
> - `doc/TRAFFIC_MONITOR_TIERED_PLAN.md`（分层方案原稿）

---

## 0. TL;DR（给作者的 90 秒摘要）

我方在 HONOR AGM3 (Android 10) 和 Chery 8155 (Android 11) 两台独立设备上做了对照实验，结论：

1. **bug 根因在宿主自己的 `libFoundationJni.so` 里**（Telenav tasdk 的 HTTP client 有一个启动窗口期的 session 级竞态），**和你的代码完全无关**。
2. **NetScope 运行时行为已被彻底证伪**：把 `System.loadLibrary("netscope")` 推迟到 T+60s，进程在 T+25s 就崩了 2/3 次，`libnetscope.so` 根本没加载过。
3. **NetScope 的"静态存在"（APK 打包产物里多的 4 个 `.so` + ~16 个 Java class + AndroidX Startup meta-data + central directory offset 右移 ~1.5 MB）在无意间放大了宿主的那个竞态**。Chery 8155 上把 NetScope 从 APK 里物理拔掉（仅剩 Java stub 替身），**从 7 crash / 180s 降到 0 crash / 540s**（N=3 × 180s）。

**我方对 NetScope 后续方向的判断**（也是本文档的正式请求）：

- **停止**对 native inline hook 方向的任何投入（bytehook / shadowhook / xhook 都与我方 crash 无关，也不再是我方产品需要的能力）。
- **转向 Java/Android 层 AOP 流量统计方案**（覆盖 OkHttp / `HttpURLConnection`），无需 native hook。
- 为了让我方能继续使用 NetScope 发版，需要 NetScope **出一个 "Java-only" 变体，不含任何 hook 相关 .so**（详见 §5 R1）。

---

## 1. 背景：已经排除的方向

### 1.1 NetScope 运行时 ≠ 放大器（§12.7 D60 实验）

| 实验 | 变量 | 结果 |
|------|------|------|
| D0 | NetScope 正常 dlopen（T+4s 左右）| 3/3 次 ~25s 内 SIGSEGV |
| D60 | `setprop debug.netscope.delay_ms 60000`，把 dlopen 推迟到 T+60s | 3 次里 2 次在 **T+25s 崩溃**，**`libnetscope.so` 根本没加载**（logcat 里看不到 `JNI_OnLoad` 行）|

D60 的结论是**决定性**的：**NetScope 的 `dlopen` / `JNI_OnLoad` / `dlsym(RTLD_NEXT)` / `bytehook_init` / GOT 改写 全都不是放大机制**。我方已停止在 NetScope 运行时代码路径上投入排查。你之前那几版换 hook 库 (xhook → bytehook → shadowhook) 的工作没有方向性错误，但**对当前的 bug 无助**。

### 1.2 你的代码没有写错 GOT，没有破坏 vtable

过去怀疑的"hook 改 GOT 改错"→ 已排除（§6-B 用 Python ELF parser 扫 DT_NEEDED + run-as 读 `/proc/maps` 做了三重证据）。你的代码层面已经没有可追查的 bug。

---

## 2. 背景：已经锁定的真正方向（不是你能修的）

`libFoundationJni.so`（Telenav tasdk 自己的一个 native so，MD5 `02cd184e930f63c7bc26fb32e2452e7e`）里 `tn::http::client::ClientImpl` / `Session` 有一个 session 级竞态 —— worker thread 的 response callback 完成之前 `Session` 对象被 destruct，vtable 字段被覆写成堆指针，下一次 `blr x8` 就跳到无 x 位的数据页，SEGV。

触发概率取决于**进程启动期的宏观时序**。APK 里任何扰动它 mmap 布局 / dex bucket / AndroidX Startup 扫描时序的东西，都会把这个 race 的命中率从接近 0% 抬到可观察水平。

**Telenav 会在另一条线上修这个 race**（由我方推动），跟你无关。

---

## 3. 背景：为什么 NetScope 的"静态存在"是放大器

我方对 A（不打包 `:netmonitor`）和 B（打包但运行时 kill-switch）两种 APK 做了字节级 diff（`unzip -l` + `aapt2 dump` + gradle 依赖树），差异清单如下：

| # | 差异项 | A 配置 | B 配置 |
|---|--------|--------|--------|
| 1 | `lib/arm64-v8a/*.so` 个数 | 19 | 23（**+4**）|
| 2 | 额外的 `.so` | — | `libnetscope.so` (121 KB) + `libbytehook.so` (139 KB) + `libshadowhook.so` (79 KB) + `libshadowhook_nothing.so` (1 KB) |
| 3 | dex 中多出的 Java class | 基线 | `com.telenav.netmonitor.*` × 6 + `indi.arrowyi.netscope.*` × 10+ |
| 4 | `<service>` 数量 | 基线 | +1 (`NetMonitorService`) |
| 5 | AndroidX Startup `<meta-data>` | 基线 | +1 (`NetMonitorInitializer`) |
| 6 | Provider 个数 | 基线 | +0（`InitializationProvider` 本来就在）|
| 7 | `resources.arsc` 条目 | 基线 | +4 (`layout_* / drawable_* / item_*`) |
| 8 | APK central directory offset | 基线 | **向后右移 ~1.5 MB** |

**这 8 项里面，体积权重最大的是 #1 / #2 / #8（全都由 .so 贡献），次大的是 #3（dex class）**。

我方的实验数据证明：在两个独立机型上，**只要把 B 变成 A（把这些差异全部归零），启动期 race 的命中率就从 ~80% 降到 540s 内抓不到**。

---

## 4. 我方新的产品方向：两层流量统计

我方决定把 NetMonitor 流量统计产品重构为如下分层（**这取代了原 `TRAFFIC_MONITOR_TIERED_PLAN` 里 Tier 1/2/3 的叙述方式**）：

```
┌───────────────────────────────────────────────────────────────┐
│ Layer A  整体流量统计                                          │
│   负责方: HMI 自己                                             │
│   数据源: TrafficStats + NetworkStatsManager                   │
│   精度:   UID 级字节精确、WiFi/蜂窝分开、无 domain             │
│   覆盖率: 100%（Java + native 全进来）                         │
│   依赖:   无（不依赖 NetScope、不依赖 Telenav 改动）           │
└───────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────┐
│ Layer B  Java 层 per-domain 分域统计          ★ 需要 NetScope  │
│   负责方: NetScope（SDK）+ HMI（5 个业务侧埋点）               │
│   数据源: OkHttp EventListener / HttpURLConnection wrapper     │
│   精度:   per-host tx / rx / request_count / latency           │
│   覆盖:   所有走 OkHttp 或 HttpURLConnection 的 Java 层 HTTP   │
│          (~10~30% 流量)                                        │
│   不覆盖: tasdk native HTTP（~70~90%，走 Layer C）             │
└───────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────┐
│ Layer C  native per-domain 分域统计            （Telenav 长期）│
│   负责方: Telenav（`libFoundationJni.so` 加 JNI 接口）         │
│   状态:   与本文档并行推动，不阻塞 Layer A/B 发版              │
└───────────────────────────────────────────────────────────────┘
```

UI 展示策略：`总量 (Layer A)` + `Java 分域 (Layer B)` + `Native 未归属 = A − sum(B)`。等 Layer C 到位再追加第 4 栏。

---

## 5. 对 NetScope 的正式需求

### R1. 出一个 "Java-only" SDK 变体（**强约束**，Layer B 发版的阻塞项）

**硬要求**：

1. **不含** `libbytehook.so`（compile-time 就剥掉，不只是 runtime 不加载）。
2. **不含** `libshadowhook.so`。
3. **不含** `libshadowhook_nothing.so`。
4. **不含** `com.bytedance:bytehook` 和 `com.bytedance.android:shadowhook` 这两个 transitive 依赖（包括任何 `implementation` / `api` / `compileOnly`，全部剥干净，让 gradle 依赖树里彻底不再出现它们）。

**理想要求**（如果能做到，优先）：

5. **不含** `libnetscope.so`。`NetScope` / `NetScopeNative` 等 Java facade 保留，但内部所有依赖 native 的调用（`external fun` 签名）改成 Java 实现或直接返回默认值。

**退而求其次**（R1.5 不可行时的下限）：

6. 出一个 **几乎空的 stub `libnetscope.so`**：`JNI_OnLoad` 只 `return JNI_VERSION_1_6`，**不做任何 `dlsym(RTLD_NEXT)` / 不注册任何 JNI 方法 / 不初始化 bytehook / 不碰 GOT**。体积目标 **≤ 10 KB**（当前版本 121 KB，目标压到 ~8%）。

**Gradle 依赖形态建议**：

```gradle
// 旧（我方当前使用）：
implementation 'com.github.Arrowyi:NetScope:b500638'

// 新（期望）：
implementation 'com.github.Arrowyi:NetScope:X.Y.Z-java-only'
// 或者通过 flavor：
implementation('com.github.Arrowyi:NetScope:X.Y.Z') {
    exclude group: 'com.bytedance'
    exclude group: 'com.bytedance.android'
}
```

任选其一都行，只要我方能通过 gradle 表达"拿 NetScope 但不拿 bytehook/shadowhook 任何产物"即可。

**为什么 R1 是强约束**：见 §3 表格，#1 / #2 / #8 这三项静态扰动（体积权重最大）的 75%+ 都来自 hook 三件套。剥掉三件套能把 #2 从 ~340 KB 降到 ~121 KB（甚至 0 KB），#8 从 ~1.5 MB 降到 ~130 KB（甚至 ~30 KB）。我方两台独立设备的实测数据表明这是让 Layer B 能发版的最低门槛。

---

### R2. AOP 方案 design doc（希望你先出方案，我方 review）

Layer B 的具体 AOP 切入方式有两种典型备选，选一个或混合：

| 方案 | 原理 | 对 HMI 入侵度 | 对你的复杂度 | 工具链风险 |
|------|------|-------|--------|-------|
| **A：AGP plugin 字节码插桩** | 在 Transform / AGP task 里扫所有 `OkHttpClient.Builder()` / `new URL().openConnection()` 调用点，自动注入 EventListener 或 wrapper | **零代码改动** | 高（ASM/javassist 编织）| 中（AGP 版本适配）|
| **B：显式 builder wrap API** | 暴露 `NetScope.wrap(builder) / NetScope.wrap(url)` 公开 API，HMI 侧手动在每个构造点调用一行 | HMI 侧改 5 个业务文件 | 低 | 低 |

**我方倾向**：方案选择**交给你**，我方只列优劣给你参考。任何一种我方都可以接。实际上**方案 B 有可能更快上线**，所以**建议你先出方案 B 的 API 签名 + JavaDoc，然后再评估是否加方案 A**。

**希望的交付物**：

- 公开 API 头签名（Kotlin + Java 兼容）+ JavaDoc。
- 如果采用方案 B 或方案 A 覆盖不全，**请给我方一张"HMI 侧需要埋点"的清单**：哪些文件、哪些方法要加什么调用。我方会单独一个 PR 接入。
- 我方已知的 5 个 OkHttpClient 构造点（可直接作为 R2 design doc 的锚点）：
  - `alexa-client/AlexaClient.java`
  - `NavHome/Apps/Arp/HMI/.../login/GetSecurityCodeBy.java`
  - `NavHome/Apps/Rainier/tool/cloudtesting/.../HttpHelper.java`
  - `NavHome/module/GoogleStreetView/.../StreetViewParser.java`
  - `NavHome/Apps/Rainier/HMI/.../navigation/TaSdkComponentInitializerHelper.java`
- 我方还有零散的 `HttpURLConnection` 用户，典型是 `GetSecurityCodeBy.java` 里直接 `url.openConnection() as HttpURLConnection`；如果你的方案 A 能覆盖，我方就不用手工改；如果只有方案 B，我方按你 API 挨个 wrap。

**期望的统计数据接口（粗略）**：

```kotlin
// NetScope 暴露给宿主的只读 snapshot API（方向意向，不是硬约束）
data class DomainStat(
    val host: String,
    val tx: Long, val rx: Long,
    val requestCount: Long,
    val avgLatencyMs: Long,
    val lastSeenEpochMs: Long,
)

object NetScope {
    fun snapshot(): List<DomainStat>
    fun reset()

    // 如果需要让宿主实时订阅（可选）
    fun setSnapshotListener(listener: (List<DomainStat>) -> Unit)
}
```

上述只是我方的需求方向，**具体 API 形状由你决定**，我方 review 时只看两条：(a) 线程安全（`snapshot()` 可从 UI thread 或后台 thread 调用）；(b) 不阻塞调用线程（内部用 atomic / ConcurrentHashMap）。

---

### R3. 不要在 NetScope AAR 的 `AndroidManifest.xml` 里声明 provider 或 initializer

- 由宿主 HMI 决定**是否**通过 AndroidX Startup 自启、什么时候启动。
- 当前 NetScope 的 AAR（`b500638`）我们核对过**没有**主动声明 provider（是宿主 `:netmonitor` 自己声明的 `NetMonitorInitializer`），所以这一条实际上是**现状规范化**，写在这里避免未来版本再加。

---

## 6. 我方时间线

| 节点 | 动作 | 负责方 | 前置依赖 |
|------|------|--------|----------|
| 立即 | HMI 侧用本地 stub 替身（`NetScopeStub.kt`）临时发版，保证 Chery 8155 / AGM3 稳定，Layer A 先落地 | HMI | 无 |
| 立即 | 发出本文档给你 | HMI | 无 |
| T+? | R1 交付（Java-only 变体，含或不含 stub `.so`）| NetScope | 本文档达成共识 |
| T+? | HMI 切换回真实 NetScope 依赖（用新变体），再跑一次 3×180s 验证稳定性 | HMI | R1 |
| T+? | R2 交付（AOP design doc，API 签名）| NetScope | 本文档达成共识 |
| T+? | HMI 按 R2 清单接入 5 个业务点 + 更新 NetMonitor UI | HMI | R2 |
| T+? | Layer B 上线验证 | HMI | R1 + R2 |
| 长期并行 | Layer C（Telenav 侧 `HttpStatsJni`）推动 | HMI + Telenav | 不阻塞 |

T+? 由你评估。我方急的是 R1（Chery 8155 发版用），R2 可以稍晚一点。

---

## 7. 如果你有困难（可以直接拒绝的 fallback）

| 情况 | 我方的后备方案 |
|------|-------------------|
| R1.5（不含 `libnetscope.so`）做不到 | 接受 R1.6 的 stub 版本（≤ 10 KB）。告诉我方即可 |
| 连 stub `.so` 都做不到（Java 层有 `external fun` 而 ART 不能接受无 native symbol 的 AAR）| **我方退到纯 HMI Java 实现**（OkHttp EventListener + URL wrapper），整体不依赖 NetScope。已有完整代码草案在 `TRAFFIC_MONITOR_TIERED_PLAN §Tier 1.3`。告诉我方即可，我方走这条路，不再消费 NetScope SDK |
| R2 暂时没空出方案 | 我方先用方案 B（显式 wrap）做打底，等你 AGP plugin 出来再升级 |
| 全都做不到 / 没时间 | 我方完全自力更生，NetScope 走向 "archive" 状态。这不是我方希望的结果，但我方理解你的精力有限 |

**我方的底线**：无论你选什么，**请明确告知**。我方不需要你继续追 AGM3 crash，**那已经不是 NetScope 的问题**。

---

## 8. 背景证据索引

如果你想亲自核对结论（不推荐，因为需要我方仓库的 tombstone / maps 数据），可按以下顺序读我方内部文档：

| 文档 | 相关章节 | 用途 |
|------|----------|------|
| `doc/ASDK_HTTPCLIENT_CRASH_HANDOFF.md` | §12.5 | A 配置 3×180s=0 崩（证 "不打包 NetScope 则稳"）|
| | §12.7 D60 实验 | 证 "NetScope 运行时 ≠ 放大器"（关键）|
| | §12.7.6 | tasdk 自己的 `ClientImpl` / `Session` race 收敛 |
| | §12.8.3 | 8 项 APK 静态扰动清单（关键）|
| | §12.8.5-8 | "加依赖不 init 仍崩" 的机制拆解 |
| `doc/TRAFFIC_MONITOR_TIERED_PLAN.md` | §Tier 1.3 | 纯 Java 实现代码草案（即本文档 §7 的 fallback）|

Chery 8155 上的新数据（2026-04-24 晚）**尚未**写入上述 `ASDK_HTTPCLIENT_CRASH_HANDOFF`，将在下一次更新里补一个 §12.9 节。关键数字：**NetScope 静态剔除 + 3 × 180s = 0 崩**（同机型 NetScope 静态在时对照组 7 crash / 180s）。

---

## 9. 联系方式

- 发起方：Telenav HMI / ARP 团队
- 本次联络人：_（由发出时填入）_
- 邮件组 / 群：_（由发出时填入）_
- 回复截止：无硬截止，但我方希望在 **2 周内** 收到你的初步判断（接 / 接 partial / 不接）。

---

## 10. 构建栈兼容性 fact sheet（Denali APK 场景）

> **发出本节的上下文**：你在 2026-04-24 反馈想做 **Gradle ASM 字节码插桩方案**（即本文档 §5 R2 表里的"方案 A：AGP plugin 字节码插桩"）。为了你评估工具链兼容风险、不做无效投入，我方把 Denali APK 这一个目标产物涉及到的所有版本号一次性列清楚。
>
> **作用域约束（重要）**：本节数据**仅覆盖 Denali 一个 app 的构建栈**。仓库里另外还有 `Apps/Arp`（AGP 2.2.x 老古董）、`Apps/Rainier`（AGP 2.3.x）、`Apps/RSI`（AGP 7.1.x）三套独立子树，它们**不在** NetScope 需要兼容的目标范围内。**你的 AGP plugin 只需要能在 AGP 4.2.2 上加载成功即可**。

### 10.1 关键版本号一览

| 维度 | 版本 | 来源文件 | 备注 |
|------|------|----------|------|
| **Android Gradle Plugin (AGP)** | **`4.2.2`** | `Apps/Denali/gradle.properties`（`gradlePluginVersion=4.2.2`）+ `Apps/Denali/dependencies_version.gradle`（`androidGradlePlugin: "${project.gradlePluginVersion}"`）| 唯一需要你适配的 AGP 版本。**不需要考虑 AGP 7.x / 8.x**。|
| **Gradle Wrapper** | **`6.7.1`** | `Apps/Denali/gradle/wrapper/gradle-wrapper.properties`（`distributionUrl=.../gradle-6.7.1-all.zip`）| AGP 4.2.2 官方最低要求是 Gradle 6.7.1，我方就是跑在这个最低版本上。|
| **R8** | **`3.3.75`**（**当前禁用**）| `Apps/Denali/dependencies_version.gradle`（`r8: '3.3.75'`）+ `Apps/Denali/gradle.properties`（`android.enableR8=false`）| **我方实际走 Proguard 路径**，不走 R8。见 §10.4。|
| **Kotlin** | **`1.6.21`** | `Apps/Denali/dependencies_version.gradle`（`kotlinVersion: '1.6.21'`）| 你 plugin 本身编译时目标 Kotlin 需 ≤ 1.6，避免触发 Kotlin metadata 不兼容。|
| **Kotlin Coroutines** | `1.6.4` | 同上（`kotlinx_coroutines_android: "1.6.4"`）| 仅作信息，你的 plugin 不需要依赖它。|
| **Java source / target** | **`1.8`**（bytecode 52.0）| `NavHome/androidCommon.gradle`（`sourceCompatibility = JavaVersion.VERSION_1_8` / `targetCompatibility = JavaVersion.VERSION_1_8`）| 你插桩后生成的字节码**必须 ≤ 52.0**，否则 D8/dexer 在 Android 26+ 上会失败。|
| **Core Library Desugaring** | 启用，`1.0.9` | `androidCommon.gradle`（`coreLibraryDesugaringEnabled true` + `desugar_jdk_libs:1.0.9`）| 你插桩生成的代码**可以**用 `java.util.stream` / `java.time`，会由 desugar 降级。但保守起见别用，保持 Java 8 原生 API 最稳。|
| **compileSdk / targetSdk** | `29`（Android 10）| `Apps/Denali/gradle.properties`（`myCompileSdkVersion=29`）+ `Apps/Denali/dependencies_version.gradle`（`targetSdk: 29`）| Android 10 SDK API 限制你能调的 framework API。|
| **minSdk** | **`26`**（Android 8.0）| `NavHome/androidCommon.gradle`（`minSdkVersion 26`）| 你插桩生成的代码可以用 API 26+ 的所有东西，`invoke-custom` / `invoke-polymorphic` 也可用（Android 26+ ART 已支持）。|
| **buildToolsVersion** | `30.0.2` | `Apps/Denali/gradle.properties`（`buildTool=30.0.2`）| D8 / aapt2 版本锁定。|
| **JVM for Gradle** | **JDK 11**（我方开发机实际用）；Gradle 6.7.1 也兼容 JDK 8 | `Apps/Denali/gradle.properties`（`org.gradle.jvmargs=-Xmx4608m`）+ 开发机环境 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.jdk/...` | 你 plugin 本身的编译目标 **建议 JDK 8**（对齐 AGP 4.2.x 的最低要求，让 Gradle 8 JDK 运行环境也能加载）。|
| **AndroidX / Jetifier** | 启用 / 启用 | `Apps/Denali/gradle.properties`（`android.useAndroidX=true` + `android.enableJetifier=true`）| 你插桩注入的 imports **用 `androidx.*`，不要用 `android.support.*`**。|
| **Jetifier blacklist** | `kotlin-android-extensions-1.6.21.jar` | 同上（`android.jetifier.blacklist=...`）| 仅信息。|
| **Multidex** | 启用，`androidx.multidex:2.0.0` | `dependencies_version.gradle`（`multidexVersion: '2.0.0'`）| 你 plugin 注入的类数量不受限，会被 multidex 自动分桶。|
| **Native ABI splits** | **仅 `arm64-v8a`** | `NavHome/androidCommon.gradle`（`splits.abi.include 'arm64-v8a'`）| 与你 Java-only AGP plugin 无关；仅说明我方发版只有 arm64-v8a 一种 ABI。|
| **AspectJ（已在用）** | `1.9.4`（aspectjTools + aspectjRt）| `dependencies_version.gradle`（`aspectjGradlePlugin: '1.9.4'`）| **关键**：见 §10.3 —— 你不是 Transform 链上唯一的一环。|
| **compileJavaSdk** | `true` | `Apps/Denali/gradle.properties`（`compileJavaSdk=true`）| java-sdk-common 的 5 个子模块会以**源码**形式被一起编译进 Denali APK。你 Transform 的输入 class 量比单纯 Denali 自己的多几倍，需要做好性能预期。|

### 10.2 决定性约束：你能用哪个 AGP 扩展点

AGP 版本决定了 plugin 写法。**AGP 4.2.2 的字节码插桩只有一条路**：

| 扩展点 | 引入版本 | 在我方可用？ |
|--------|----------|---------------|
| `com.android.build.api.transform.Transform`（Transform API）| AGP 1.5 ~ AGP 7.x（AGP 7.0 开始标 deprecated，AGP 8.0 移除）| **✅ 唯一可用**。AGP 4.2.2 上 stable，没有 deprecation warning。|
| `AsmClassVisitorFactory` + `Artifacts` API（`registerForMultipleArtifacts`）| AGP 7.0+ | **❌ 不可用**。AGP 4.2.2 里这些类根本不存在。|
| `Instrumentation.transformClassesWith(...)` | AGP 7.2+ | **❌ 不可用**。|

**对你的行动含义**：
- **请按 Transform API 写**。签名是 `abstract class Transform` → override `getName()` / `getInputTypes()` / `getScopes()` / `isIncremental()` / `transform(TransformInvocation)`。
- 注册入口是 `project.extensions.getByType(AppExtension).registerTransform(myTransform)` 或 `com.android.build.gradle.LibraryExtension`。
- 你以后想做的 AGP 7+ 版本可以**同时并存**（用 `com.android.tools.build:gradle-api` 的不同版本做 reflective 检测），但对我方**只发一个 Transform 版本即可**。

### 10.3 Transform 链顺序 —— 你不是链上唯一一环

`Apps/Denali/build.gradle` 的 buildscript 已经通过 `aspectjGradlePlugin` 注册了一个 AspectJ Transform。含义：

```
原始 class (从 javac + kotlinc 出来)
        │
        ▼
[AspectJ Transform]         ← 已经在用，由 HMI 业务代码的 @Aspect 注解驱动
        │
        ▼
[你的 NetScope Transform]   ← 期望你插在这里，看到的 input 已经被 AspectJ 织过
        │
        ▼
[DexGuard / Proguard ...]   ← 仅 release buildType
        │
        ▼
[D8 → dex]
```

**对你 Transform 的要求**：
- `getInputTypes()` 建议返回 `TransformManager.CONTENT_CLASS`（标准 bytecode input）。
- `getScopes()` 建议返回 `TransformManager.SCOPE_FULL_PROJECT`（你要扫 app + 所有 library）。
- 必须处理 AspectJ 合成出来的方法 / 类（名字通常带 `ajc$` 前缀）。不要改它们。

### 10.4 R8 禁用 + 走 Proguard

我方 `android.enableR8=false`（全局禁用）。实际混淆链是：**Proguard**（不是 R8）。

Denali pangu flavor 的 proguard 文件清单：
- `proguard-android.txt`（SDK 自带）
- `proguard-sdk.txt`（我方自有）
- `proguard-chery.txt`（pangu / Chery 定制）

**对你的要求**：
- NetScope AAR 的 `consumer-rules.pro` 必须声明 `-keep` 你插桩后生成的合成类（如 `NetScope$$AspectEventListener`）和所有 runtime reflect 访问的 API。
- 请给我方一份 `proguard-rules.pro` 建议清单，我方合并到 `proguard-sdk.txt`。

**注意**：debug buildType 的 `minifyEnabled false`，所以**我方当前 AGM3 / Chery 8155 soak 测试用的 APK（pangu-tasdk-dev-debug）没跑 Proguard**。你的插桩测试建议**也同时跑一次 release buildType**（`minifyEnabled true`），保证混淆后仍然工作。

### 10.5 Plugin 发布形态建议

我方的 `buildscript.repositories` 已经配置：
- `google()` / `mavenCentral()` / `jcenter()`（legacy）
- `maven { url 'https://jitpack.io' }`
- `maven { url 'http://tar1.telenav.com/repository' }`（内部）

**推荐**：
- **JitPack**（和你的 SDK 发布渠道一致）：我方添加 `classpath 'com.github.Arrowyi.NetScope-plugin:X.Y.Z'`，你在 github 上维护 tag 即可。
- **Maven Central**：更正式，但你需要申请 groupId，周期长。

**不推荐**：
- Gradle Portal（`plugins { id "..." }` DSL）：我方 buildscript 还是老式 `classpath + apply plugin: 'xxx'`，Portal 样式会改动我方配置。

### 10.6 Plugin 自身编译时的建议

如果你决定做 AGP plugin，**plugin 工程本身**（不是被插桩的 target）推荐这么配：

```kotlin
// NetScope-plugin/build.gradle.kts
plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "1.6.21"  // 对齐我方 Kotlin
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmTarget = "1.8"
}

dependencies {
    compileOnly("com.android.tools.build:gradle:4.2.2")      // Transform API
    compileOnly("com.android.tools.build:gradle-api:4.2.2")
    implementation("org.ow2.asm:asm:9.2")                    // 或更新，都能在 Gradle 6.7.1 上加载
    implementation("org.ow2.asm:asm-commons:9.2")
    implementation("org.ow2.asm:asm-tree:9.2")
}
```

**必须避开**的几个坑：
- 别用 Gradle 7+ 才有的 API（`ConfigurableFileCollection.convention` 的某些重载、`Property<T>.convention` 链式写法）—— 我方 6.7.1 上会 NoSuchMethodError。
- 别用 AGP 7+ 的 `AndroidComponentsExtension`、`Variant` API —— 4.2.2 上不存在。
- 别把 plugin 自身编译成 Java 11 字节码（class version 55.0）—— Gradle 6.7.1 + JDK 8 运行时加载会失败。保持 52.0 (Java 8)。

### 10.7 我方的 sanity 验收门槛

在你交付 plugin 后，我方会跑以下 6 项校验：

| # | 校验 | 通过标准 |
|---|------|---------|
| 1 | Plugin 能在 Gradle 6.7.1 + AGP 4.2.2 上 `apply` 成功 | `./gradlew tasks` 不报 `ClassNotFoundException` / `NoSuchMethodError` |
| 2 | Transform 不破坏现有 AspectJ 织入 | HMI 已有的 `@Aspect` 功能（如 Denali 的 trace 注入）运行正常 |
| 3 | 生成的字节码 D8 能成功 dex | `./gradlew assemblePanguTasdkDevDebug` 成功 |
| 4 | 生成的字节码能在 Android 11 (Chery 8155) 上加载运行 | 不出 `VerifyError` / `NoClassDefFoundError` |
| 5 | OkHttp / HttpURLConnection 流量能被正确统计 | 手写一条 curl 请求到已知 host，snapshot 里能看到 |
| 6 | release buildType + Proguard 后仍然工作 | `./gradlew assemblePanguTasdkDevRelease` 后 APK 装到设备上，流量统计照常 |

---

## 附：为什么我方判定 "native inline hook 这条路线对 HMI 已失去吸引力"

- NetScope 的 native hook 能抓所有 Java + native 流量并做 per-domain，这**本来**是它相对于 Android `TrafficStats` 的唯一差异化优势。
- 但从我方在两台车载设备（AGM3 / Chery 8155）上的实测，**只要 `libbytehook.so` / `libshadowhook.so` / `libshadowhook_nothing.so` 三件套打进 APK，就会放大宿主 `libFoundationJni.so` 里的 race**。把"放大器风险"和"native per-domain 精度"放在天平两端，我方选择放弃后者（通过 Layer C 的 Telenav JNI 接口补）。
- 这是**我方这个宿主的具体场景**的权衡，不是对 NetScope 技术方向的否定。对别的宿主（没有 tasdk / 或者 tasdk 的 race 已修复），NetScope 的 native hook 方案仍然有价值。我方不会在社区里给你"带差评"。

如果你将来做了 Layer B 之外的新方向（比如 eBPF / 内核 VPN），我方乐意跟进评估。
