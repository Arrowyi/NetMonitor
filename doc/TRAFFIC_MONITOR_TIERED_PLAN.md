# 流量监控分层方案（C++ 层 crash 不可规避时的替代路径）

> ## ⚠ 2026-04-24 晚 PIVOT（读下面 Tier 1–4 正文前必看）
>
> 本文档原 Tier 1–4 的叙事（纯 Java / Telenav JNI / NetScope 瘦身 / VpnService）在当日晚间被用户 pivot 成一套**更务实的 3 层划分**，并把对 NetScope 的诉求正式化为一份独立文档。
>
> **新分层（请以此为后续实施依据）**：
>
> | 新层次 | 负责方 | 原 Tier 对应关系 | 状态 |
> |-------|-------|-----------------|------|
> | **Layer A** 整体流量统计（`TrafficStats` + `NetworkStatsManager`）| HMI 自己 | = 原 Tier 1 的 `TotalTrafficSource` 子集 | 可立即落地，无外部依赖 |
> | **Layer B** Java per-domain 分域统计（AOP）| **NetScope**（出 Java-only 变体 + AOP design doc）+ HMI（接埋点）| 介于原 Tier 1 的 Java 部分和原 Tier 3 之间；**NetScope 转型为 AOP 流量库** | 阻塞于 NetScope R1/R2 交付 |
> | **Layer C** native per-domain 分域统计（`HttpStatsJni`）| Telenav | = 原 Tier 2 `ClientStatsRegistry` 完全沿用 | 长期推动，不阻塞 Layer A/B 发版 |
>
> **关键调整**：
> 1. 原 **Tier 3**（NetScope 瘦身）的 §3.4 一段话模板**已升级成正式需求稿** → 见 [`NETSCOPE_AOP_REQUEST.md`](./NETSCOPE_AOP_REQUEST.md)。本文档 §Tier 3 下面的文字仅作**历史存档**，**请勿再把 §3.4 模板发出去**，已被 `NETSCOPE_AOP_REQUEST.md` 取代。
> 2. 原 **Tier 4**（VpnService）仍然不推荐上生产，地位不变。
> 3. 原 **Tier 1 / Tier 2** 的代码草案**依然有效**：下面 §Tier 1.3 的 `TotalTrafficSource` / `JavaDomainStatsRegistry` / `OkHttpMonitorInterceptor` / `HttpUrlConnectionMonitor` 继续用 —— 但叙事语境换成 "**Layer A 的实现代码** + **Layer B 的 fallback**（NetScope R1 做不到 Java-only 变体时启用，整份 NetScope 依赖扔掉，自力更生）"。原 §Tier 2 的 `ClientStatsRegistry` 设计未变动，就是 Layer C。
>
> **Chery 8155 第二块硬数据**（2026-04-24 晚补充）：
> - NetScope **静态剔除**（本地 `NetScopeStub.kt` 替身，gradle 注释 `com.github.Arrowyi:NetScope:b500638`）：Chery 8155 上 **3 × 180s = 540s 冷启 soak，0 崩**。
> - 同机型 NetScope **静态在** + kill-switch：**7 crash / 180s**（N=1，对照样本；反向对照 N=3 尚未跑，作为下一轮 followup）。
> - `libFoundationJni.so` MD5 `02cd184e930f63c7bc26fb32e2452e7e`（与 AGM3 历史崩溃版一字节不差），证明 bug 仍在 so 里、只是没被触发。
> - 跨 AGM3 / Chery 8155 两个独立设备的一致结论：**NetScope 的静态存在是 race 触发放大器**（`ASDK_HTTPCLIENT_CRASH_HANDOFF §12.5 / §12.8` 的结论跨设备复现）。
>
> **接手 agent 请先读** `NETSCOPE_AOP_REQUEST.md`（最新的对外正式需求），再回到本文档查 Layer A 的代码草案（§Tier 1.3）。

---

> **背景**：`ASDK_HTTPCLIENT_CRASH_HANDOFF.md §12` 已证明 `libFoundationJni.so` 里 `tn::http::client::*` 有一个启动窗口期的 session-级 race；只要 `:netmonitor` 作为 APK artifact 存在就会静态放大该 race 的触发概率（§12.8.3 的 8 项扰动），即使 NetScope 在运行时完全不 dlopen 也一样。
>
> 本方案给出**不依赖 NetScope / libbytehook / libshadowhook 的流量监控替代路径**，分四层，按代价从低到高：
> - **Tier 1**：纯 Java + Android 系统 API（立即可落，0 崩溃风险）
> - **Tier 2**：请 Telenav 在 `libFoundationJni.so` 内原生暴露 per-domain 统计接口（最干净的根治路径）
> - **Tier 3**：NetScope 侧改造以**降低**（而非消除）静态放大器 — ⚠ 已被 `NETSCOPE_AOP_REQUEST.md` 替代，见上方 PIVOT 注记
> - **Tier 4**：`VpnService` 本地隧道（debug-only，代价大）
>
> 若只做其中一条，**推荐 Tier 1 打底 + Tier 2 长期推动**。Tier 3 只在 Tier 2 无法推动且产品强依赖 per-domain 时做保底。Tier 4 不推荐上生产。

---

## 0. 当前应用 HTTP 栈拓扑（做方案前必须对齐的事实）

| 流量源 | 协议栈 | Java 层可见？ | Android `TrafficStats` 可见？ |
|---|---|---|---|
| 地图瓦片（`stream::DownloadManager::createHttpClient()`）| `libFoundationJni.so` → libcurl | ❌ | ✅（计入 UID 总量）|
| Direction 路由（`tn::directionservice::DirectionServiceProxy::createHttpClient()`）| `libFoundationJni.so` → libcurl | ❌ | ✅ |
| OTA / AdmClient（`libAdmClientJni.so`）| 内部走 `libFoundationJni` 的 http client | ❌ | ✅ |
| 语音 TTS（`libissxtts30.so`）| 内部 http | ❌ | ✅ |
| Alexa 客户端（`alexa-client/build.gradle` 的 okhttp3 3.9.1）| OkHttp（Java）| ✅ | ✅ |
| activation / login 验证码（`GetSecurityCodeBy.java` 的 `HttpURLConnection`）| `HttpURLConnection`（Java）| ✅ | ✅ |
| EV Trip Planner（`evtripplanner` 的 Retrofit + okhttp）| OkHttp（Java）| ✅ | ✅ |
| Google StreetView（`GoogleStreetView` okhttp 3.10.0）| OkHttp（Java）| ✅ | ✅ |

**经验比例**（需要真实流量验证，但按 tasdk 架构通常是这个量级）：
- tasdk native（地图 + 路由 + OTA + TTS）占 **70 ~ 90 %**
- Java 业务（Alexa / activation / evtripplanner / streetview）占 **10 ~ 30 %**

**结论**：纯 Java AOP 只能拿到 10~30% 的流量，70%+ 没有 domain 可言，只能作为"其他（native-unattributed）"桶展示。如果产品要求"地图瓦片每个 CDN 域名分别计量"，**必须**走 Tier 2。

---

## Tier 1：纯 Java + Android 系统 API（立即落地）

### 1.1 覆盖能力

| 维度 | 来源 | 精度 |
|---|---|---|
| app 生命周期内总 tx / rx（当前进程累计）| `TrafficStats.getUidTxBytes(uid)` / `getUidRxBytes(uid)` | **100% 覆盖**，精确到字节 |
| app 历史流量（小时 / 日 / 月窗口，WiFi vs cellular 分开）| `NetworkStatsManager.querySummaryForUid()` | **100% 覆盖**，系统维护数据，最多保留 ~90 天 |
| 实时速率（tx/s, rx/s）| 差分 `TrafficStats` 采样 | 精确，约 1s 粒度 |
| Java HTTP per-domain（Alexa / activation / EV / StreetView）| OkHttp `EventListener` + `HttpURLConnection` 包装 | 精确，需要在每个 `OkHttpClient` 创建点注册 |
| Java HTTP per-request 耗时 / 响应码 | OkHttp `EventListener` 回调时间戳 | 精确 |
| **native HTTP per-domain（tasdk）**| ❌ 无 | **只能作为 "其他" 桶 = 总量 − Java 各域之和** |

### 1.2 模块结构（`:netmonitor` 重构）

彻底拿掉 `NetScope` 依赖和 `NetScopeNative` / `HookReport` 相关全部代码；保留 `FloatingWindowManager` + `NetMonitorService` + `NetDataRepository` + `DomainStatsAdapter` 的 UI 骨架。新的数据源分两层：

```
com.telenav.netmonitor
├─ NetMonitorInitializer.kt   // AndroidX Startup（不变）
├─ NetMonitorService.kt        // 前台服务 + 定时刷新（简化，去 loadonly/baseline 分支）
├─ NetMonitorConfig.kt         // UI 参数（不变）
├─ DomainTrafficStats.kt       // data class（不变）
├─ stats/
│   ├─ TotalTrafficSource.kt   // ← 新增：TrafficStats + NetworkStatsManager 聚合
│   ├─ JavaDomainStatsRegistry.kt // ← 新增：并发 map<domain, counter>
│   ├─ OkHttpMonitorInterceptor.kt // ← 新增：给业务侧 OkHttpClient.Builder().eventListener(...)
│   └─ HttpUrlConnectionMonitor.kt // ← 新增：包装 URLConnection.openConnection
├─ NetDataRepository.kt        // 合并 TotalTrafficSource + JavaDomainStatsRegistry，给 UI 用
└─ view/
    ├─ FloatingWindowManager.kt
    ├─ FloatingWindowView.kt
    ├─ DomainStatsAdapter.kt   // 展示：Total / Java 已识别域 / Native-Unattributed
    └─ BubbleView.kt
```

### 1.3 关键代码草案

#### `stats/TotalTrafficSource.kt`

```kotlin
package com.telenav.netmonitor.stats

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Process

/**
 * Covers ALL app traffic (Java + native tasdk) via two kernel-maintained counters:
 *   - [TrafficStats.getUidTxBytes] / getUidRxBytes: process-lifetime accumulator, cheap.
 *   - [NetworkStatsManager.querySummaryForUid]: windowed historical, needs PACKAGE_USAGE_STATS.
 */
class TotalTrafficSource(private val ctx: Context) {

    private val uid = Process.myUid()

    /** Lifetime tx bytes since device boot for this UID. Lightweight. */
    fun currentTotalTx(): Long = TrafficStats.getUidTxBytes(uid)
    fun currentTotalRx(): Long = TrafficStats.getUidRxBytes(uid)

    /**
     * Historical summary for a time window. Android 6.0+, needs PACKAGE_USAGE_STATS
     * (declare in manifest + user grants via Settings → Apps → Special access → Usage access).
     *
     * Returns Pair<txBytes, rxBytes> for [startMs, endMs], split by network type if needed.
     */
    fun historicalSummary(
        startMs: Long,
        endMs: Long,
        networkType: Int = ConnectivityManager.TYPE_MOBILE,
    ): Pair<Long, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE)
                as? NetworkStatsManager ?: return null
        return try {
            nsm.querySummaryForUid(networkType, /*subscriberId*/null, startMs, endMs, uid)
                .let { it.txBytes to it.rxBytes }
        } catch (_: SecurityException) {
            null    // PACKAGE_USAGE_STATS not granted; fall back to TrafficStats.
        }
    }
}
```

#### `stats/JavaDomainStatsRegistry.kt`

```kotlin
package com.telenav.netmonitor.stats

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Process-wide singleton. Any Java-layer HTTP layer can feed per-domain counters. */
object JavaDomainStatsRegistry {

    private data class Counter(
        val tx: AtomicLong = AtomicLong(0),
        val rx: AtomicLong = AtomicLong(0),
        val req: AtomicLong = AtomicLong(0),
        val lastSeenMs: AtomicLong = AtomicLong(0),
    )

    private val map = ConcurrentHashMap<String, Counter>()

    fun recordRequest(domain: String) {
        get(domain).req.incrementAndGet()
    }

    fun addTx(domain: String, bytes: Long) {
        get(domain).apply { tx.addAndGet(bytes); lastSeenMs.set(System.currentTimeMillis()) }
    }

    fun addRx(domain: String, bytes: Long) {
        get(domain).apply { rx.addAndGet(bytes); lastSeenMs.set(System.currentTimeMillis()) }
    }

    private fun get(domain: String) = map.getOrPut(domain) { Counter() }

    fun snapshot(): List<com.telenav.netmonitor.DomainTrafficStats> =
        map.entries.map { (d, c) ->
            com.telenav.netmonitor.DomainTrafficStats(d, c.tx.get(), c.rx.get())
        }

    /** Sum of all Java-tracked domains; used to derive native-unattributed bucket. */
    fun totalTrackedTx(): Long = map.values.sumOf { it.tx.get() }
    fun totalTrackedRx(): Long = map.values.sumOf { it.rx.get() }
}
```

#### `stats/OkHttpMonitorInterceptor.kt`

```kotlin
package com.telenav.netmonitor.stats

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * Drop-in EventListener that feeds JavaDomainStatsRegistry.
 * Usage from caller:
 *   OkHttpClient.Builder()
 *       .eventListener(OkHttpMonitorInterceptor())
 *       .build()
 */
class OkHttpMonitorInterceptor : EventListener() {

    override fun callStart(call: Call) {
        val host = call.request().url.host
        JavaDomainStatsRegistry.recordRequest(host)
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        // Rough: serialize the headers to estimate size. OkHttp doesn't expose byte count
        // for headers, but we can approximate with request.headers.byteCount().
        val bytes = request.headers.byteCount()
        JavaDomainStatsRegistry.addTx(call.request().url.host, bytes)
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        JavaDomainStatsRegistry.addTx(call.request().url.host, byteCount)
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        JavaDomainStatsRegistry.addRx(call.request().url.host, response.headers.byteCount())
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        JavaDomainStatsRegistry.addRx(call.request().url.host, byteCount)
    }
}
```

**调用侧接入**：所有业务模块在构造 `OkHttpClient` 时挂一行。示例（alexa-client）：

```diff
 val client = OkHttpClient.Builder()
     .connectTimeout(30, TimeUnit.SECONDS)
+    .eventListener(com.telenav.netmonitor.stats.OkHttpMonitorInterceptor())
     .build()
```

覆盖所有出现 `OkHttpClient()`/`OkHttpClient.Builder()` 的文件（目前 5 个：`alexa-client/AlexaClient.java`、`GetSecurityCodeBy.java`、`Rainier/cloudtesting/HttpHelper.java`、`StreetViewParser.java`、`TaSdkComponentInitializerHelper.java`）。

可以用一个 `lint.gradle` 规则强制新加的 OkHttpClient 构造必须挂 EventListener（低成本预防回归）。

#### `stats/HttpUrlConnectionMonitor.kt`（给 `HttpURLConnection` 用户）

```kotlin
package com.telenav.netmonitor.stats

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Helper: wrap conn.inputStream / outputStream so byte counts are fed to registry. */
object HttpUrlConnectionMonitor {
    fun wrap(url: URL): HttpURLConnection = WrappedConnection(url.openConnection() as HttpURLConnection)

    private class WrappedConnection(private val delegate: HttpURLConnection) :
        HttpURLConnection(delegate.url) {
        private val host = delegate.url.host
        init { JavaDomainStatsRegistry.recordRequest(host) }
        // ... 委派所有 HttpURLConnection 方法到 delegate ...
        override fun getInputStream(): InputStream = CountingInputStream(delegate.inputStream, host)
        override fun getOutputStream(): OutputStream = CountingOutputStream(delegate.outputStream, host)
        override fun disconnect() = delegate.disconnect()
        override fun usingProxy() = delegate.usingProxy()
        override fun connect() = delegate.connect()
        // ... 其他方法省略
    }

    private class CountingInputStream(private val d: InputStream, private val host: String) : InputStream() {
        override fun read(): Int = d.read().also { if (it >= 0) JavaDomainStatsRegistry.addRx(host, 1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            d.read(b, off, len).also { if (it > 0) JavaDomainStatsRegistry.addRx(host, it.toLong()) }
        override fun close() = d.close()
    }
    private class CountingOutputStream(private val d: OutputStream, private val host: String) : OutputStream() {
        override fun write(b: Int) { d.write(b); JavaDomainStatsRegistry.addTx(host, 1) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            d.write(b, off, len); JavaDomainStatsRegistry.addTx(host, len.toLong())
        }
        override fun close() = d.close()
    }
}
```

调用点：把所有 `URL.openConnection()` / `URL().openConnection()` 改成 `HttpUrlConnectionMonitor.wrap(url)`。

#### `NetDataRepository.kt`（简化重写，删除 NetScope 引用）

```kotlin
package com.telenav.netmonitor

import android.content.Context
import com.telenav.netmonitor.stats.JavaDomainStatsRegistry
import com.telenav.netmonitor.stats.TotalTrafficSource

class NetDataRepository(private val ctx: Context) {

    private val totalSource = TotalTrafficSource(ctx)

    data class AggregatedData(
        val totalTx: Long,
        val totalRx: Long,
        /** Per-domain stats the Java layer managed to attribute. */
        val javaDomains: List<DomainTrafficStats>,
        /** totalTx - sum(javaDomains.tx). Always >= 0. */
        val nativeUnattributedTx: Long,
        val nativeUnattributedRx: Long,
    ) {
        val domainCount get() = javaDomains.size
    }

    fun getLatestData(): AggregatedData {
        val totalTx = totalSource.currentTotalTx()
        val totalRx = totalSource.currentTotalRx()
        val domains = JavaDomainStatsRegistry.snapshot().sortedByDescending { it.totalBytes }
        val attrTx = JavaDomainStatsRegistry.totalTrackedTx()
        val attrRx = JavaDomainStatsRegistry.totalTrackedRx()
        return AggregatedData(
            totalTx = totalTx, totalRx = totalRx,
            javaDomains = domains,
            nativeUnattributedTx = (totalTx - attrTx).coerceAtLeast(0),
            nativeUnattributedRx = (totalRx - attrRx).coerceAtLeast(0),
        )
    }
}
```

#### `NetMonitorService.kt`（简化）

```kotlin
class NetMonitorService : Service() {
    private lateinit var repository: NetDataRepository
    private lateinit var floating: FloatingWindowManager
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForeground(0x4E4D, buildNotification())
        repository = NetDataRepository(this)
        floating = FloatingWindowManager(this)
        floating.show()
        handler.post(refresh)
    }

    private val refresh = object : Runnable {
        override fun run() {
            floating.updateData(repository.getLatestData())
            handler.postDelayed(this, NetMonitorConfig.refreshIntervalMs)
        }
    }
    // ... onDestroy 去掉 NetScope.setStatusListener(null)
}
```

### 1.4 `build.gradle` 依赖瘦身

```diff
 dependencies {
     implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])
     implementation 'androidx.startup:startup-runtime:1.0.0'
     implementation libraries.recyclerView
     implementation libraries.kotlinStdlibJdk8
-    implementation 'com.github.Arrowyi:NetScope:b500638'
+    compileOnly 'com.squareup.okhttp3:okhttp:3.10.0'   // 只在编译期引用，运行时由宿主提供
     testImplementation libraries.junit
     testImplementation libraries.mockitoCore
     coreLibraryDesugaring libraries.coreLibraryDesugarTools
 }
```

### 1.5 Tier 1 对 `ASDK_HTTPCLIENT_CRASH_HANDOFF §12.8.3` 8 项静态扰动的清除度

| # | 扰动项 | A 配置 | Tier 1 后 |
|---|---|---|---|
| 1 | `lib/arm64-v8a/*.so` 数 | 19 | **19**（无 .so 新增）|
| 2 | 额外 `.so` | — | — |
| 3 | dex 中 Java 类净增量 | 基线 | +~8 个（`TotalTrafficSource`、`JavaDomainStatsRegistry`、`OkHttpMonitorInterceptor`、`HttpUrlConnectionMonitor`、`NetDataRepository`、`NetMonitorService`、`NetMonitorInitializer` 等；不到 `:netmonitor`+NetScope 的 1/3 且**无 Kotlin coroutine / ConcurrentHashMap hash bucket 抖动之外**的 native 符号）|
| 4 | `<service>` | 基线 | +1 `NetMonitorService`（无法去掉）|
| 5 | `androidx.startup` meta-data | 基线 | +1 `NetMonitorInitializer`（无法去掉）|
| 6 | provider 数 | 基线 | +0 |
| 7 | resources.arsc | 基线 | +4 条（与原 :netmonitor 一致）|
| 8 | APK central dir offset | 基线 | 右移 ~30 KB（vs 原 `:netmonitor+NetScope` 右移 ~1.5 MB）|

**预期效果**：和 A 配置（彻底删依赖）**非常接近**，静态扰动面仅剩"+1 service / +1 startup entry / +8 个 dex class / 30 KB APK offset"，实测需要跑 3 × 180s soak 确认。如果仍偶发崩 → 再评估是否要走 A 配置（完全不打包）+ 把流量监控作为单独 debug APK 提供。

### 1.6 Tier 1 已知限制

1. **native tasdk 流量 per-domain 完全不可见**，只能进 "其他" 桶展示。
2. `HttpURLConnection` 包装需要调用方主动走 `HttpUrlConnectionMonitor.wrap(url)`；对第三方库用 `URL.openConnection()` 我们改不到（如果这类库的代码不在我们仓里，就漏掉）。
3. OkHttp `EventListener` 的 `requestHeadersEnd` 拿到的 header 字节数是 Java 层序列化估算值，和 wire 上真正发出去的字节可能差 ≤ 5%（chunked encoding、TLS 开销等不计）。总量对齐到 `TrafficStats` 时会有差距，UI 展示上用"其他 = 总量 − 已识别域"公式自动吸收差异即可。
4. `TrafficStats.getUidTxBytes/RxBytes` 在 **Android 10 + aarch64 设备上** 偶尔返回 `-1`（HONOR EMUI 已验过工作正常，但宽容代码写一行 `coerceAtLeast(0L)`）。
5. `NetworkStatsManager` 需要用户手动授予"使用情况访问权限"，否则只能退化到 `TrafficStats`。

### 1.7 工作量与交付周期

| 任务 | 人日 |
|---|---|
| 重构 `:netmonitor`（去 NetScope、加 TotalTrafficSource / JavaDomainStatsRegistry / NetDataRepository 重写）| 1 |
| 在 5 个 OkHttpClient 构造点接入 EventListener | 0.5 |
| 在 `HttpURLConnection` 用户点（目前主要是 `GetSecurityCodeBy.java` + `StethoConnectivityFactory.java` 曾经的 pattern）用 wrap 替换 | 0.5 |
| UI 改造：展示 Total / Java per-domain / Native-Unattributed 三栏 | 0.5 |
| AGM3 验证 3×180s soak + 常规回归 | 0.5 |
| **合计** | **~3 人日** |

---

## Tier 2：Telenav 在 `libFoundationJni.so` 原生暴露 per-domain 接口（推荐长期）

### 2.1 为什么这是最干净的根治路径

- tasdk 的 `tn::http::client::ClientImpl` **本来就在内部记录**每个 request 的 URL / bytes / 响应时间（诊断字符串里的 `"HTTP client's workthread create error"` 等表明有完整的 audit trail），只是没有通过 JNI 暴露出来。
- 加一个纯 read-only 的 getter 接口**不需要任何 hook**、**不需要改 wire path**、**零崩溃引入风险**。
- 顺带把当前这个 session-级 race 让 Telenav 的人**正好**在加接口的同一次改动里把 `shared_ptr<Session>` 生命周期审一遍 —— 一次改动解决两个问题。

### 2.2 API 建议（对 Telenav 团队的 design doc）

```cpp
// 新增头文件：tn/http/client/Stats.h

namespace tn::http::client {

struct DomainStatEntry {
    std::string host;          // e.g. "api.telenav.com"
    uint64_t request_count;
    uint64_t tx_bytes;
    uint64_t rx_bytes;
    uint64_t tx_bytes_failed;  // for retries / aborts
    uint64_t last_seen_epoch_ms;
};

class ClientStatsRegistry {
public:
    // Thread-safe snapshot. Impl: internal std::mutex + std::unordered_map<string, atomic<...>>
    // Called from any thread (typically JNI caller thread).
    static std::vector<DomainStatEntry> snapshot();
    
    // Reset all counters. Useful for UI "clear" button.
    static void reset();

    // Optional: per-client filtering for modules that have multiple clients
    // (e.g. OTA / map tile / direction run on separate Client instances).
    static std::vector<DomainStatEntry> snapshotForClientName(const std::string& name);
};

} // namespace
```

```cpp
// 在 ClientImpl::doRequest() 里插一行，请求完成时插一行：

void ClientImpl::onRequestStart(const Request& req) {
    ClientStatsRegistry::recordStart(req.url().host());
}

void ClientImpl::onRequestEnd(const Request& req, const Response& rsp) {
    ClientStatsRegistry::recordEnd(req.url().host(),
                                   req.totalBytesSent(),
                                   rsp.totalBytesReceived());
}
```

```cpp
// JNI 桥：tn/http/client/jni/StatsJni.cpp

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_telenav_app_android_jni_HttpStatsJni_snapshot(JNIEnv* env, jclass) {
    auto stats = ClientStatsRegistry::snapshot();
    auto cls = env->FindClass("com/telenav/app/android/jni/HttpStatsEntry");
    // ... build String[][] 或 new HttpStatsEntry[] ...
}
```

### 2.3 HMI 侧改动

```kotlin
// com.telenav.app.android.jni.HttpStatsJni
object HttpStatsJni {
    external fun snapshot(): Array<HttpStatsEntry>
    external fun reset()
}

data class HttpStatsEntry(
    val host: String, val requestCount: Long,
    val txBytes: Long, val rxBytes: Long,
    val lastSeenEpochMs: Long
)
```

`:netmonitor` 的 `NetDataRepository` 在 Tier 1 基础上再多一个数据源：

```kotlin
class NetDataRepository(private val ctx: Context) {
    fun getLatestData(): AggregatedData {
        val totalTx = totalSource.currentTotalTx()
        val totalRx = totalSource.currentTotalRx()
        val javaDomains = JavaDomainStatsRegistry.snapshot()
        val nativeDomains = try {
            HttpStatsJni.snapshot().map {
                DomainTrafficStats("[native] ${it.host}", it.txBytes, it.rxBytes)
            }
        } catch (t: UnsatisfiedLinkError) {
            // tasdk 还没出带 StatsJni 的版本，保持 Tier 1 行为
            emptyList()
        }
        // ...
    }
}
```

### 2.4 工作量估算（Telenav 侧）

| 任务 | 人日 |
|---|---|
| `ClientStatsRegistry` 类实现（`std::mutex` + `std::unordered_map<string, atomic...>`）| 1 |
| 在 `ClientImpl` 2 个 hook 点（onRequestStart / onRequestEnd）插入记录调用 | 0.5 |
| JNI 桥 + Java 数据类 | 0.5 |
| 单元测试（`ClientStatsRegistry` 并发读写、overflow）| 0.5 |
| 集成测试（在 AGM3 上 soak）| 0.5 |
| **合计（Telenav 侧）**| **~3 人日** |

HMI 侧在 Tier 1 基础上再加 0.5 人日接入 `HttpStatsJni`。

### 2.5 对 AGM3 crash 的连带影响

加这个接口时 Telenav 会被迫**重新 code review** `tn::http::client::ClientImpl::shutdown()` 与 worker `onResponseComplete` 的同步路径。那正好是 `§12.7.6` 怀疑的 race 位置。**极大概率在同一个 PR 里就把那个 UAF 修了。** 这比 Tier 3 的 NetScope 改动更治本。

---

## Tier 3：NetScope 侧瘦身以降低（不消除）静态放大器

> ### ⚠ 本节已被 `NETSCOPE_AOP_REQUEST.md` 替代（2026-04-24 晚 PIVOT）
>
> - §3.4 的"发给 NetScope 作者的一段话模板"**已失效**，不要再直接发出。
> - 最新的正式需求（R1 Java-only 变体、R2 AOP design doc、R3 manifest 规范）见 [`NETSCOPE_AOP_REQUEST.md`](./NETSCOPE_AOP_REQUEST.md)。
> - 本节以下内容**作为历史存档保留**，用于理解"为什么我方最终要求 NetScope 剥 hook .so"的推理过程。

**适用场景**：Tier 2 推不动、产品又强依赖 native per-domain、同时 Tier 1 给的"其他"桶在 UI 上不可接受。

**先决条件**：必须接受"降低概率但不消除"，并且要实测 soak 量化效果。

### 3.1 NetScope 作者侧可做的动作（按收益排序）

| # | 动作 | 对 §12.8.3 扰动项的影响 | 代价 |
|---|---|---|---|
| 1 | **剥离 bytehook / shadowhook compile-time 依赖**：当前 `com.bytedance:bytehook:1.1.1` 是 transitive 拖进来的，若 NetScope 把 "fallback to bytehook" 的分支用 `build variants` 或 `compileOnly` 隔离，**APK 里 3 个孤儿 `.so` 可消失**（`libbytehook.so` 139 KB、`libshadowhook.so` 79 KB、`libshadowhook_nothing.so` 1 KB）| 扰动项 1 由 +4 降至 +1；扰动项 2 由 +4 个 `.so` 降至 +1（只剩 `libnetscope.so`）；扰动项 8 由 +1.5 MB 降至 ~130 KB | 1 人日（NetScope 作者侧）|
| 2 | **出 `libnetscope-stub.so` 变体**：`JNI_OnLoad` 直接 `return JNI_VERSION_1_6`，不做任何 `dlsym(RTLD_NEXT)`，不注册任何 JNI 方法，不初始化 bytehook。SDK Java 层的 `NetScopeNative` / `NetScope` facade 在运行时检测到 stub 变体就降级到"无数据"模式（与当前 AGM3 上崩完拉不到数据行为一致）。| 扰动项 2 中的 `libnetscope.so` 体积从 121 KB 降至 ~10 KB；`.so` 数仍是 +1 但内容几乎空；扰动项 3 dex 层无变化 | 1 人日 |
| 3 | **合并 NetScope 的多个 Java 类成 1 个**：目前 SDK 面向业务暴露 10+ 个 class（`NetScope`、`NetScopeNative`、`HookReport`、`Status`、`DomainStat` 等），可以压缩到 2~3 个，并用 `@Keep` 合理组织。| 扰动项 3 从 +10 class 降至 +3 class | 0.5 人日 |
| 4 | **移除 NetScope 自带的 AndroidX Startup initializer**（如果有）：当前 `:netmonitor` 里是**宿主**声明的 `NetMonitorInitializer`，但请 NetScope 作者**不要**再在 `netscope-sdk` 的 `AndroidManifest.xml` 里主动声明 provider。宿主决定是否通过 Startup 自启。| 扰动项 5 由 **宿主自己**来控制（当前已如此，只是给 NetScope 作者一个 "不要自作主张" 的规范） | 0 人日（当前已如此）|
| 5 | **把 `NetScope` / `NetScopeNative` 改成 `@Suppress("ObjectPropertyName")` 的**懒加载**单例**：仅在宿主调用某个 NetScope API 时才触发 `System.loadLibrary("netscope")`；宿主可以通过 kill-switch 根本不调 → 同等于"不 dlopen"。这个与 §12.7 已经无冲突，只是强化了"可懒加载"保证。| 对扰动项无改变，但让宿主**更放心**打 kill-switch | 0.5 人日 |

### 3.2 HMI 侧配合动作

- 重新发版时在 gradle 把 `com.bytedance:bytehook` 和 `com.bytedance.android:shadowhook` 用 `exclude group:'com.bytedance'` 强制剔除（前提是 NetScope 作者出了不依赖 bytehook 的变体）。
- 若选择 `libnetscope-stub.so`：gradle 依赖从 `com.github.Arrowyi:NetScope:b500638` 换成假设的 `com.github.Arrowyi:NetScope:stub-X.Y.Z`。

### 3.3 预期效果（未实测）

| 配置 | 扰动量级 | 崩溃概率（启动窗口）预期 | 需要实测验证 |
|---|---|---|---|
| A（完全不打包）| 0 | ~ 0% | 已证（§12.5）|
| Tier 3 全套 | +1 stub `.so` ~10 KB + ~3 class + ~30 KB offset | ~ 5~20%？ | **必须实测**，不能仅靠推理 |
| 当前 B | +4 `.so` ~1.5 MB + ~16 class | ~ 60~80% | 已证（§12.8.5）|

**风险**：即便 Tier 3 做到"几乎不打包"，只要那 1 个 stub `.so` 存在 + `NetMonitorInitializer` meta-data 存在，静态放大器理论上仍会比 A 配置差一点。是否能压低到产品可接受（< 5%）完全取决于 AGM3 上的实测。

### 3.4 发给 NetScope 作者的一段话模板

> 我们已经通过 delay=60s 的 `loadonly` 实验证伪了"NetScope dlopen / JNI_OnLoad / dlsym(RTLD_NEXT) / bytehook 任何运行时行为"是 AGM3 crash 的放大器（见 `ASDK_HTTPCLIENT_CRASH_HANDOFF §12.7`）。真正的放大源是 NetScope 作为 APK artifact 的静态存在（多 4 个 `.so`、多 10+ 个 Java 类、APK central directory offset 右移 ~1.5 MB），这些会扰动 ART/linker 的 mmap 布局和启动期时序，把宿主 tasdk 自己的 `libFoundationJni.so` 的一个启动窗口 race（已在独立路径 §12.7.6 中确认）从 ~0% 触发率抬到 ~80%。NetScope 代码层已到极限，我们不再希望你继续排查 hook 行为；但能帮我们做的一件事是**把 APK 静态足迹压到最小**：(1) 剥离 bytehook/shadowhook 的 compile-time 依赖；(2) 出一个 `libnetscope-stub.so`（JNI_OnLoad 空实现）变体让我们发版时作为 noop 占位。我们会自己在 AGM3 上做 A/B soak 验证是否把概率降到可接受水平。如果不可行也不打紧，我们会退到纯 Java 层的监控方案（Tier 1）。

---

## Tier 4：`VpnService` 本地隧道（仅 debug build）

### 4.1 原理

`android.net.VpnService` 允许应用注册一个本地 VPN 接口，所有进程的外发包都会先路由到这个 fd。自己读 fd、解 IP 头、取目的 IP/DNS-resolved-hostname 做 per-domain 计数，再把包透传给真实网关即可。

### 4.2 为什么不推荐上生产

- 弹 VPN 权限授权 dialog（用户第一次启动时），**体验差**。
- Android 状态栏常驻 VPN 图标。
- 若用户配置了企业 VPN 或 WireGuard，**会被抢占**。
- 需要在用户空间做全部 TCP/UDP 包的转发，**CPU 开销** ~5% 起步；在 HMI 这种实时性要求高的场景不合适。
- IPv6 / QUIC / TLS 1.3 SNI 拿 host 需要额外解 ClientHello；MLS / ECH 时代不可靠。

### 4.3 可用性

- 只在 dev/QA build 开启 `<uses-permission android:name="android.permission.BIND_VPN_SERVICE"/>`，跑压测抓全量 per-domain。
- 生产 build 通过 `productFlavors` 把 VPN service 剔除。

### 4.4 工作量

- 最小可用版本 ~5 人日（fd 读 / IP 解析 / per-flow session table / socket-protect / 重连）。
- 不推荐，列在这里只作为兜底方案。

---

## 最终建议路线图

> **⚠ 2026-04-24 晚 PIVOT 后此节已重写，旧 "阶段 1 / 2 / 3 / N" 叙事作废**，替换如下（以 Layer A/B/C 语义为准）：
>
> ```
> 阶段 1（可立即动手）：Layer A 落地（HMI 自力更生）
>  └─ 新增 TotalTrafficSource（TrafficStats + NetworkStatsManager），不依赖 NetScope
>  └─ HMI 侧 :netmonitor 保留 UI 骨架，数据源改为 Layer A
>  └─ 发版前跑 Chery 8155 + AGM3 各 3×180s soak
>  └─ 产品展示：Total（按 Layer A）+ "Native/Java 未分域" 两栏
>
> 阶段 2（阻塞于 NetScope 交付）：Layer B 落地（NetScope + HMI 合作）
>  └─ 发出 NETSCOPE_AOP_REQUEST.md 给 NetScope 作者，等待 R1/R2 响应
>  └─ R1（Java-only SDK 变体）回来后：HMI 把 stub 替身换回真实 NetScope 依赖
>  └─ R2（AOP design doc）回来后：HMI 按清单接入 5 个 OkHttpClient 埋点
>  └─ 产品升级到：Total + Java per-domain + Native 未归属 三栏
>  └─ 若 NetScope R1/R2 都推不动 → 退到 §Tier 1.3 代码草案自力更生（Layer B fallback）
>
> 阶段 3（长期并行，不阻塞）：Layer C 协同 Telenav
>  └─ 发出 §Tier 2.2 的 ClientStatsRegistry API design doc 给 Telenav
>  └─ Telenav 出带 HttpStatsJni 的 libFoundationJni build
>  └─ HMI 侧 :netmonitor 接 HttpStatsJni，产品升级到 "Java + native 都按域"
>  └─ 期望副作用：Telenav 在 code review 时顺手修掉 session 级 UAF 根因
>
> 阶段 N（永不）：原 Tier 4 VpnService
>  └─ 除非做 QA 压测 / 抓包需要
> ```
>
> **和旧版"阶段 1/2/3"对照**：
> - 旧 "阶段 1 Tier 1 落地" = 新 "阶段 1 Layer A 落地"（去掉"5 个 OkHttp 点接入 EventListener"，那部分下沉到 Layer B）
> - 旧 "阶段 2 Tier 2 协同 Telenav" = 新 "阶段 3 Layer C"
> - 旧 "阶段 3 Tier 3 NetScope 瘦身（只在失败时启用）" = 新 "阶段 2 Layer B"（地位由备选升级为主线）
> - 旧 "阶段 N Tier 4" = 不变

---

### 旧版路线图（历史存档，以上 PIVOT 后版本为准）

```
阶段 1（本周）：Tier 1 落地
 └─ :netmonitor 重构，去 NetScope，纯 Java + TrafficStats + NetworkStatsManager
 └─ 5 个 OkHttpClient 点接入 EventListener
 └─ HMI 出版本，AGM3 soak 3×180s 验证
 └─ 产品上：Total + Java per-domain + "其他(native)" 三栏展示

阶段 2（Tier 1 验证通过后并行推动）：Tier 2 协同 Telenav
 └─ 发出 ClientStatsRegistry API design doc 给 Telenav 研发
 └─ Telenav 出带 HttpStatsJni 的 libFoundationJni debug build
 └─ HMI 侧 :netmonitor 接 HttpStatsJni，产品升级到"Java 按域 + native 按域 + 总量"
 └─ 期望副作用：Telenav 在 code review 时顺手修掉 session 级 UAF 根因

阶段 3（只在 Tier 1 仍偶发崩 + Tier 2 短期推不动 时启用）：Tier 3 NetScope 瘦身
 └─ 提需求给 NetScope 作者（3.4 的模板）
 └─ 拿到 stub.so 变体后在 AGM3 上 A/B soak

阶段 N（永不）：Tier 4 VpnService
 └─ 除非做 QA 压测 / 抓包需要
```

## 与已有文档的对应

| 结论 | 证据章节 |
|---|---|
| 彻底删 `:netmonitor` 依赖 → AGM3 稳定 | `ASDK_HTTPCLIENT_CRASH_HANDOFF §12.5`（A 配置 3×180s=0 崩）|
| NetScope 运行时行为与 crash 解耦 | `§12.7`（D60 实验）|
| 静态放大器具体来源 | `§12.8.3`（8 项 APK 扰动清单）|
| 长尾 21+min 非反例 | `§12.8.5-6`（6 次连崩后概率长尾）|

## 未解决问题

- Tier 1 重构后 APK 静态扰动面是否小到 AGM3 soak 不崩？**必须实测**。若仍偶发，考虑把 `:netmonitor` 走 Debug-only APK 插件方案（发版时完全不打包，只在 QA 设备 `adb install` 独立 debug apk + 通过 ContentProvider 访问）。
- Tier 2 的 `HttpStatsJni` 需要 Telenav build 配合，当前 release schedule 是否来得及？
- `NetworkStatsManager` 的"使用情况访问权限"是否可以在产品首启引导里一次性申请到？HMI 是否接受弹这个授权？
