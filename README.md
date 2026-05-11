# NetMonitor

[![](https://jitpack.io/v/Arrowyi/NetMonitor.svg)](https://jitpack.io/#Arrowyi/NetMonitor)

An Android library that monitors a process's network traffic and renders a draggable, collapsible floating overlay with per-API, per-socket, and per-subsystem breakdowns.

The integration surface intentionally hides all underlying SDK details (NetScope and friends) — host apps only see `NetMonitor` and a small set of optional extension points.

## Requirements

- Android: `minSdk 26`, runtime features active on API 29+
- AGP: 4.2.x or newer
- Kotlin: 1.6.x or newer

## Integration

### 1. Root `build.gradle`

```groovy
buildscript {
    repositories {
        maven { url 'https://jitpack.io' }
    }
    dependencies {
        // Bytecode transform plugin required for the in-process API traffic
        // aggregation. Must be on the classpath of the host application.
        classpath 'com.github.Arrowyi.NetScope:NetScope-plugin:v3.2.8'
    }
}

allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

### 2. Application module `build.gradle`

```groovy
apply plugin: 'com.android.application'
apply plugin: 'indi.arrowyi.netscope'

dependencies {
    implementation 'com.github.Arrowyi:NetMonitor:v1.0.0'
}
```

### 3. `Application.onCreate()`

NetMonitor needs to install a small piece of itself before native libraries load,
so it splits initialization into two stages around `super.onCreate()`:

```java
public class MyApp extends Application {
    @Override
    public void onCreate() {
        NetMonitor.preInit(this);   // stage 1 — before super.onCreate()
        super.onCreate();
        NetMonitor.init(this);      // stage 2 — after super.onCreate()
    }
}
```

That's it for the minimum integration. The floating window will appear shortly after the app process starts.

### 4. Optional: provide a subsystem-level usage source

NetMonitor can see kernel totals (Layer A), per-API Java traffic (Layer B), and per-endpoint socket traffic (Layer D) on its own. What it **cannot** see is how much of that traffic each of your application's logical subsystems (navigation, telemetry, OTA, voice, …) is responsible for — only the host knows that. The "NetworkUsage" panel in the floating overlay (Layer C) is populated exclusively through the `NetworkUsageSource` SPI.

#### 4.1 SPI shape

```kotlin
interface NetworkUsageSource {
    fun subscribe(listener: (List<SubsystemUsage>) -> Unit)
    fun unsubscribe()
}

data class SubsystemUsage(
    val subsystem: String,    // human-readable label shown in the overlay row
    val uploadBytes: Long,    // cumulative tx for this subsystem since process start
    val downloadBytes: Long,  // cumulative rx for this subsystem since process start
)
```

NetMonitor expects **cumulative** counters, not deltas — the same monotonically-increasing values you would report to a metrics pipeline. It re-sorts the list by total bytes descending before rendering and caps the visible rows at `NetMonitorConfig.maxVisibleNetworkUsage` (default 8).

#### 4.2 Push cadence

Push a fresh snapshot whenever your numbers change, or on a fixed timer if your subsystems aggregate internally. A push every 1–2 s is plenty; the overlay itself only redraws every `NetMonitorConfig.refreshIntervalMs` (default 2 s).

The listener callback is invoked from your thread — NetMonitor stores the snapshot into a `@Volatile` field, so any thread is safe. You do **not** need to marshal to the main thread.

#### 4.3 Kotlin example

```kotlin
class MyUsageSource(private val stats: SubsystemStatsRegistry) : NetworkUsageSource {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MyUsageSource").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null

    override fun subscribe(listener: (List<SubsystemUsage>) -> Unit) {
        task?.cancel(false)
        task = executor.scheduleWithFixedDelay({
            val snapshot = stats.snapshot().map { (name, s) ->
                SubsystemUsage(name, s.tx, s.rx)
            }
            listener(snapshot)
        }, 0, 1, TimeUnit.SECONDS)
    }

    override fun unsubscribe() {
        task?.cancel(false)
        task = null
    }
}
```

#### 4.4 Java example

```java
public class MyUsageSource implements NetworkUsageSource {
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MyUsageSource");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> task;

    @Override
    public void subscribe(Function1<? super List<SubsystemUsage>, Unit> listener) {
        if (task != null) task.cancel(false);
        task = exec.scheduleWithFixedDelay(() -> {
            List<SubsystemUsage> snapshot = buildSnapshot();   // your aggregator
            listener.invoke(snapshot);
            return null;                                       // Function1<...,Unit>
        }, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void unsubscribe() {
        if (task != null) { task.cancel(false); task = null; }
    }
}
```

#### 4.5 Wiring it in

```kotlin
// You can call this at any point in the process lifetime — before NetMonitor.init,
// after it, or even much later once your upstream services finish booting.
// NetMonitor subscribes lazily, so a source installed after the overlay is
// already showing will start populating the panel on the next refresh tick.
NetMonitor.setNetworkUsageSource(MyUsageSource(stats))
```

Notes:
- `subscribe(...)` is invoked **exactly once** for the lifetime of the process — the first time NetMonitor sees a non-null source. Setting a different instance later, or setting `null` afterwards, does **not** trigger another `subscribe` or an `unsubscribe`. Install one source and reuse it; route any feature-flag toggling inside your own implementation by simply not pushing.
- An empty list is a valid push and clears the panel.
- If you never call `setNetworkUsageSource`, the NetworkUsage panel is simply hidden.
- `unsubscribe()` is invoked by NetMonitor on its own service teardown (process exit, kill switch, crash-loop breaker), so your timers / executors should be released there.

### 5. Optional: redirect logging

```kotlin
NetMonitor.setLogger(object : NetMonitorLog {
    override fun i(sub: String, msg: String) { /* … */ }
    override fun w(sub: String, msg: String, t: Throwable?) { /* … */ }
    override fun e(sub: String, msg: String, t: Throwable?) { /* … */ }
    override fun d(sub: String, msg: String) { /* … */ }
})
```

### 6. Optional: UI tuning

`NetMonitorConfig` exposes a handful of static knobs (refresh interval, list caps, expanded-window sizing). Set them once at startup, before `NetMonitor.init`.

## Kill switch

```
adb shell setprop debug.netmonitor.enabled 0
```

The floating window won't appear on the next process start. Set back to `1` (or unset) to re-enable.

## License

Apache-2.0 — see [LICENSE](LICENSE).
