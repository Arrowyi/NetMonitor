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

NetMonitor cannot, by itself, see how much data your various subsystems are uploading or downloading. If you want that section of the overlay populated, implement `NetworkUsageSource` and inject it once your upstream services are ready:

```java
public class MyUsageSource implements NetworkUsageSource {
    @Override
    public void subscribe(Function1<? super List<SubsystemUsage>, Unit> listener) {
        // call listener.invoke(currentList) periodically with your stats
    }

    @Override
    public void unsubscribe() { /* cancel your timers */ }
}

// later, once upstream is ready:
NetMonitor.setNetworkUsageSource(new MyUsageSource());
```

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
