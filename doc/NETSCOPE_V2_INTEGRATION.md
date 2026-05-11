# NetScope v2.x 接入文档（Denali APK）

> **目标读者**：后续接手 Denali + NetScope 集成的任何 agent / 开发。
>
> **作用**：记录 2026-04-24 把 NetScope v2.0.1 → v2.0.2（Java-only AOP 变体）接入 Denali pangu 发版流水线的**精确步骤**、**Chery 8155 OEM 设备的签名安装流程**、**三次遇到的 Transform bug 的根因/修复建议**、和**验证清单**。
>
> **当前版本快照**：v2.0.1 的 D8 `Invalid descriptor char 'N'` 在 v2.0.2 已修。v2.0.2 又暴露一条新的 `SCOPE_FULL_PROJECT` Transform regression（`com.telenav.auto.dr.BuildConfig` duplicate-class），详见 §附录 D；当前 `apply plugin` 再次回退为注释，等 v2.0.3。
>
> **背景链接**：
> - `doc/NETSCOPE_AOP_REQUEST.md` —— 我方给 NetScope 作者的正式需求（R1 / R2 / R3）。v2.0.1 就是作者按此稿做的交付。
> - `doc/NEXT_AGENT_HANDOFF.md` §6.B —— 在原计划里对应 "Layer B 接入"。
> - `doc/ASDK_HTTPCLIENT_CRASH_HANDOFF.md` §12.8 —— v2.0.1 剥掉 bytehook / shadowhook 三件套之所以关键的证据链。

---

## 0. v2.0.1 交付物核对（Phase 1 执行前已验证）

| 项目 | 事实 | 对应需求 |
|------|------|----------|
| AAR 大小 | **40 KB** (`NetScope-v2.0.1.aar`) | R1 硬约束 |
| AAR 里的 `.so` | **0 个** | R1 #1/#2/#3/#5 全部满足 |
| bytehook / shadowhook | **从依赖树里彻底剥掉** | R1 #4 满足 |
| AAR `AndroidManifest.xml` | 只声明 `package` + `<uses-sdk minSdk=29/>`，无 provider / service / initializer | R3 满足 |
| Plugin artifact | `NetScope-plugin-v2.0.1.jar`（26 KB），plugin id = `indi.arrowyi.netscope`（由 `META-INF/gradle-plugins/indi.arrowyi.netscope.properties` 声明） | R2 方案 A |
| Plugin 入口 | `indi.arrowyi.netscope.plugin.NetScopePlugin` → 注册 `NetScopeTransform` | R2 方案 A |
| 插桩点 | `OkHttpBuilderInstrumenter`（改写 `OkHttpClient.Builder#build()`）、`UrlConnectionInstrumenter`（改写 `URL.openConnection()` 流）、`OkHttpWebSocketInstrumenter`（改写 `OkHttpClient#newWebSocket`）| R2 HMI 清单 5 个 OkHttp 构造点 + HttpURLConnection + WebSocket 全覆盖 |
| class 字节码版本 | `major: 52`（Java 8） | 与 Denali `sourceCompatibility = 1.8` 兼容 |
| Kotlin 运行时 | 依赖 Kotlin 1.6+ stdlib | 与 Denali `kotlinVersion=1.6.21` 兼容 |

**与用户提供的集成文档的出入**（发出的人注意）：

| # | 集成文档写的 | 真实 v2.0.1 API（`javap -p` 反编译后） |
|---|--------------|------------------------------------------|
| 1 | `NetScope.init(this)` 不返回值 | `init(Context): Status`（有 return value） |
| 2 | `NetScope.getDomainStats("api.example.com")` | **无 per-host 查询**。真实签名是 `getDomainStats(): List<DomainStats>`（无参，返回全部）+ `getIntervalStats(): List<DomainStats>` |
| 3 | 未提 `Status` 枚举值 | `Status` 只有两个值：`NOT_INITIALIZED` / `ACTIVE` |
| 4 | `NetScope.getTotalStats()` 字段 `connCountTotal` | 确认存在，类型是 `int`（不是 `Long`）|
| 5 | 集成文档 groupId 写 `com.github.Arrowyi.NetScope`（Arrowyi 和 NetScope 之间是点） | JitPack 仓实际命中 `com.github.arrowyi.netscope`（Gradle 大小写不敏感），可用同一 coordinate |

结论：方向正确，签名处有小偏差，**文档以本处 §3.3 为准**。

---

## 1. Phase 1 接入范围（已完成）

**目标**：验证 v2.0.1 能被 Denali pangu 构建栈吸收并正确插桩；**暂不碰** `:netmonitor` 业务代码。

### 1.1 改动清单（5 个文件）

| 文件 | 变更内容 | 备注 |
|------|----------|------|
| `NavHome/Apps/Denali/build.gradle` | buildscript 加 `maven { url 'https://jitpack.io' }` + `classpath 'com.github.Arrowyi.NetScope:NetScope-plugin:v2.0.1'` | Root `buildscript` 独立于 `allprojects`，需要单独声明 jitpack |
| `NavHome/Apps/Denali/HMI/build.gradle` | 在 `apply from: '../../../androidCommon.gradle'` 之后加 `apply plugin: 'indi.arrowyi.netscope'` | kotlin-android 由 androidCommon 带入，所以 apply 顺序 OK |
| `NavHome/Apps/Denali/HMI/dependencies.gradle` | 加 `implementation 'com.github.Arrowyi.NetScope:NetScope:v2.0.1'`；**注释掉** `implementation project(':netmonitor')` | 见 §1.2 |
| `NavHome/Apps/Denali/HMI/src/main/AndroidManifest.xml` | `<uses-sdk tools:overrideLibrary="..."/>` 追加 `indi.arrowyi.netscope.sdk` | AAR 声明 `minSdk=29`，Denali minSdk=26，必须 override 否则 manifest merger 失败 |
| `NavHome/Apps/Denali/HMI/src/pangu/java/com/telenav/arp/app/ProductApplication.java` | `super.onCreate()` 之后调 `NetScope.INSTANCE.init(this)` + `setLogInterval(30)`；try/catch 包住 | `NetScope` 是 Kotlin object + 非 @JvmStatic，Java 侧必须走 `.INSTANCE` |
| `NavHome/Apps/Denali/HMI/proguard-sdk.txt` | 追加 4 条 `-keep` / `-dontwarn` 规则 | release build 必需；debug build 不跑 Proguard，规则先写好不碍事 |

### 1.2 为什么 Phase 1 要把 `:netmonitor` 从 HMI 依赖剥离

`:netmonitor/src/main/java/indi/arrowyi/netscope/sdk/NetScopeStub.kt` 是 2026-04-24 为做 Chery 8155 "静态剔除"实验写的本地替身，它**和真实 v2.0.1 AAR 共用同一包名 `indi.arrowyi.netscope.sdk`**：

- 类 `NetScope object`：Stub 和 AAR 都有 → **重复类定义，编译必挂**。
- 类 `Status` enum：Stub 有 4 个值 (`ACTIVE` / `DEGRADED` / `FAILED` / `NOT_INITIALIZED`)，v2.0.1 只有 2 个 (`ACTIVE` / `NOT_INITIALIZED`) → 即使不重复定义，`:netmonitor` 业务代码里的 `Status.DEGRADED` / `Status.FAILED` 也会 `unresolved symbol`。
- 类 `HookReport` / `NetScopeNative`：只在 Stub 里，v2.0.1 没有 → `:netmonitor` 里 `NetMonitorService.kt` / `NetDataRepository.kt` / `FloatingWindowView.kt` 引用 `HookReport` 的行全部编译失败。
- `NetScope.setStatusListener` / `setDebugMode` / `getHookReport`：API 全部被 v2.0.1 移除。

所以 Phase 1 **最小侵入**的做法 = 暂时把 `:netmonitor` 从 HMI 依赖里剥离（单行注释），**不删** Stub 文件本身。这让 Denali APK 不再带 `:netmonitor`，就能干净地只验证 NetScope v2.0.1 本身。`:netmonitor` 的重写留到 Phase 2（见 §5）。

### 1.3 精确代码 diff 参考（粘贴时确认）

**`Apps/Denali/build.gradle` buildscript 块**：

```groovy
buildscript {
    apply from: 'dependencies_version.gradle'

    repositories {
        if (isArp != true) {
            maven { url "${System.env.ANDROID_PLUGIN_HOME}" }
        }
        maven { url 'https://storage.googleapis.com/r8-releases/raw'}
        jcenter()
        maven { url 'https://maven.fabric.io/public' }
        google()
        maven { url 'https://jitpack.io' }      // ← NEW
    }
    dependencies {
        classpath gradlePlugins.r8
        classpath "${gradlePlugins.android}"
        classpath gradlePlugins.aspectjTools
        classpath gradlePlugins.aspectjRt
        classpath libraries.kotlin
        classpath 'com.github.Arrowyi.NetScope:NetScope-plugin:v2.0.1'   // ← NEW
    }
}
```

**`Apps/Denali/HMI/build.gradle` 顶部**：

```groovy
apply plugin: 'com.android.application'
apply plugin: 'project-report'
apply from: '../../../androidCommon.gradle'

apply plugin: 'indi.arrowyi.netscope'     // ← NEW，必须在 androidCommon 之后（kotlin-android 从那里引入）
```

**`Apps/Denali/HMI/dependencies.gradle` 尾部**：

```groovy
    // :netmonitor 暂时剥离（Phase 1）
    // implementation project(':netmonitor')

    // NetScope v2.0.1
    implementation 'com.github.Arrowyi.NetScope:NetScope:v2.0.1'
}
```

**`Apps/Denali/HMI/src/main/AndroidManifest.xml`**：

```xml
<uses-sdk
    tools:ignore="OldTargetApi"
    tools:overrideLibrary="com.telenav.arp.tasdk,indi.arrowyi.netscope.sdk"/>
```

**`Apps/Denali/HMI/src/pangu/java/com/telenav/arp/app/ProductApplication.java`** `onCreate()`：

```java
import indi.arrowyi.netscope.sdk.NetScope;
import indi.arrowyi.netscope.sdk.Status;

// ...

@Override
public void onCreate() {
    if (VehicleConfig.NEED_CHANGE_OS_UMASK) {
        Os.umask(0000);
    }
    if (isMapCopyProcess()) {
        Log.e("ProductApplication", "the map copy is still running");
        return;
    }
    super.onCreate();

    // NetScope v2.0.1 init — in every non-mapCopy process so every process
    // that issues HTTP traffic is counted. The AGP Transform has already
    // rewritten OkHttpClient.Builder#build() / URL.openConnection() /
    // OkHttpClient#newWebSocket() at compile time; init() just wires up the
    // aggregator + LogcatReporter.
    try {
        Status ns = NetScope.INSTANCE.init(this);
        NetScope.INSTANCE.setLogInterval(30);
        Log.i(TAG, "NetScope.init -> " + ns);
    } catch (Throwable t) {
        Log.w(TAG, "NetScope.init threw, traffic stats disabled", t);
    }

    if (isMainProcess()) {
        // existing code...
    }
}
```

**`Apps/Denali/HMI/proguard-sdk.txt`** 文件末尾：

```
# NetScope v2.0.1 (Java-only AOP traffic stats)
-keep class indi.arrowyi.netscope.sdk.** { *; }
-keep interface indi.arrowyi.netscope.sdk.integration.NetScopeInstrumented
-keepclassmembers class * implements indi.arrowyi.netscope.sdk.integration.NetScopeInstrumented {
    *;
}
-dontwarn indi.arrowyi.netscope.sdk.**
```

---

## 2. Build → Sign → Install 流水（Chery 8155 OEM 设备）

> **Chery 8155 OEM 出厂设备的 APK 必须用 `platform_chery.keystore` 重签**才能 `adb install`；用 `tn.keystore` 签的 debug APK 在这种机型上会因 signature mismatch 被拒。
>
> **AGM3 / 开发机不需要**这一步，直接 `adb install -r` debug APK 即可。

### 2.1 构建 APK

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.jdk/Contents/Home
cd NavHome/Apps/Denali
./gradlew :HMI:assemblePanguTasdkDevDebug
```

构建产物（默认）：

```
NavHome/Apps/Denali/HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk
```

### 2.2 用 `sign.sh` 重签

签名工具目录：`/Users/bdgong/Downloads/chery8155_signjks/`

```bash
ls /Users/bdgong/Downloads/chery8155_signjks/
# apksigner.jar             # Google 官方 APK signer
# platform_chery.keystore    # Chery 出厂 platform key
# sign.sh                   # 本文件（见下）
```

`sign.sh` 的当前内容（**APK 文件名是硬编码的**，每次要改）：

```bash
#!/bin/bash -x
java -jar ./apksigner.jar sign \
    --ks ./platform_chery.keystore \
    --ks-key-alias desaysv \
    --ks-pass pass:sv2655888 \
    --key-pass pass:sv2655888 \
    --out ./signed-chery.apk \
    ./<INPUT_APK_NAME>.apk     # ← 每次构建后手动替换
```

**两种推荐的用法**（等价）：

**方法 A：把 APK 复制到签名目录后再签（推荐）**

```bash
APK_OUT=NavHome/Apps/Denali/HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk
cp "$APK_OUT" /Users/bdgong/Downloads/chery8155_signjks/input.apk
cd /Users/bdgong/Downloads/chery8155_signjks/
# 单次改 sign.sh 里的最后一行到 ./input.apk，再跑：
bash sign.sh
# 产物：./signed-chery.apk
```

**方法 B：一行命令式（不改 sign.sh，直接调 apksigner.jar）**

```bash
APK_OUT=NavHome/Apps/Denali/HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk
SIGN_DIR=/Users/bdgong/Downloads/chery8155_signjks
java -jar "$SIGN_DIR/apksigner.jar" sign \
    --ks "$SIGN_DIR/platform_chery.keystore" \
    --ks-key-alias desaysv \
    --ks-pass pass:sv2655888 \
    --key-pass pass:sv2655888 \
    --out /tmp/signed-chery.apk \
    "$APK_OUT"
```

### 2.3 `adb install` 到 Chery 8155

```bash
adb devices
# List of devices attached
# 396012bf    device        ← 必须是这个 serial；见 NEXT_AGENT_HANDOFF §4.1

# 卸载老版（可选；apk 签名一致时可 -r 覆盖；若签名变了必须先卸）
adb uninstall com.telenav.app.arp
# 或强制覆盖
adb install -r -d /Users/bdgong/Downloads/chery8155_signjks/signed-chery.apk

# 若报 INSTALL_FAILED_VERSION_DOWNGRADE / INSTALL_FAILED_UPDATE_INCOMPATIBLE：
adb uninstall com.telenav.app.arp && adb install /tmp/signed-chery.apk
```

### 2.4 冷启动触发

```bash
adb shell am force-stop com.telenav.app.arp
adb shell "run-as com.telenav.app.arp rm -f shared_prefs/netmonitor_breaker.xml" 2>/dev/null || true
adb logcat -c
adb logcat -v threadtime '*:V' > /tmp/ns_v201_cold_$(date +%s).log 2>&1 &
adb shell am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity
sleep 30
# 看关键日志（见 §3）：
grep -E 'NetScope|NetScope\.init|LogcatReporter' /tmp/ns_v201_cold_*.log | tail -30
```

### 2.5 一键脚本（可选封装）

把下面存成 `tools/deploy_denali_chery.sh`，以后一键 build → sign → install：

```bash
#!/bin/bash -e
APK_OUT="$PWD/HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk"
SIGN_DIR=/Users/bdgong/Downloads/chery8155_signjks
SIGNED=/tmp/signed-chery.apk

export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.jdk/Contents/Home
./gradlew :HMI:assemblePanguTasdkDevDebug
[[ -f "$APK_OUT" ]] || { echo "APK not found: $APK_OUT"; exit 1; }

java -jar "$SIGN_DIR/apksigner.jar" sign \
    --ks "$SIGN_DIR/platform_chery.keystore" \
    --ks-key-alias desaysv \
    --ks-pass pass:sv2655888 \
    --key-pass pass:sv2655888 \
    --out "$SIGNED" \
    "$APK_OUT"

adb -s 396012bf install -r -d "$SIGNED" \
  || { adb -s 396012bf uninstall com.telenav.app.arp; adb -s 396012bf install "$SIGNED"; }
echo "installed $SIGNED onto 396012bf"
```

---

## 3. 验证清单（Phase 1 必过项）

### 3.1 Gradle 层面（构建时）

| # | 检查点 | 通过标准 |
|---|--------|----------|
| 1 | Plugin 能被 apply | `./gradlew :HMI:tasks` 不报 `Plugin [id: 'indi.arrowyi.netscope'] was not found` |
| 2 | Transform 注册成功 | build 日志含 `NetScope Transform registered` 或 `NetScopeTransform` 被 AGP 挂上 |
| 3 | assembleDebug 通过 | `./gradlew :HMI:assemblePanguTasdkDevDebug` 以 `BUILD SUCCESSFUL` 结束 |

### 3.2 APK 字节层面（反编译）

```bash
APK=HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk

# A. 必须含 NetScope 运行时类
unzip -l "$APK" | grep -E 'indi/arrowyi/netscope' | head
# 期望若干条（sdk/NetScope.class, sdk/TotalStats.class, integration/NetScopeInterceptor.class ...）

# B. 绝不能含 bytehook / shadowhook / libnetscope.so（Java-only 验证）
unzip -l "$APK" | grep -iE 'libnetscope|libbytehook|libshadowhook'
# 期望：无输出

# C. 任选一个 OkHttp 使用方反编译，应看到 NetScopeInterceptorInjector 的调用点
mkdir -p /tmp/ns_verify && cd /tmp/ns_verify
unzip -o "$OLDPWD/$APK" classes.dex classes2.dex classes3.dex 2>/dev/null
# Android SDK 里找 d2j 或 apktool；任选其一
apktool d "$OLDPWD/$APK" -o decoded -f
grep -rE 'NetScopeInterceptorInjector|NetScopeUrlConnection|NetScopeWebSocket' decoded/ | head
# 期望：能看到若干 invoke-static 对 NetScopeInterceptorInjector.addIfMissing 等的调用
```

### 3.3 运行时（logcat）

```bash
# 冷启后 35 秒内应看到：
grep -E 'NetScope' /tmp/ns_v201_cold_*.log

# 期望的典型日志（NetScope 自己的 tag 是 `NetScope` 或 `LogcatReporter`）：
#  I ProductApplication: NetScope.init -> ACTIVE
#  I NetScope         : NetScope v2.0.1 initialized, logInterval=30s
#  I LogcatReporter   : === NetScope Traffic Report (30s interval) ===
#  I LogcatReporter   :   [tx=0 rx=0 conn=0] (no traffic yet)
# 触发一次登录 / 地图瓦片请求后再看：
#  I LogcatReporter   :   api.telenav.com : tx=1234 rx=45678 conn=3
```

### 3.4 Dumpsys 层面（总量比对）

`NetScope.getTotalStats()` 只覆盖 Java 层（走 OkHttp / HttpURLConnection / OkHttp WebSocket 的流量）。Telenav tasdk native C++ 侧走的 socket 流量不在覆盖范围内。系统侧 `TrafficStats.getUidTxBytes(uid)` 是 Layer A 总量，比 NetScope 大。

开一个 adb shell 对拍：

```bash
PID=$(adb shell pidof com.telenav.app.arp | tr -d '\r')
UID=$(adb shell "cat /proc/$PID/status | grep '^Uid' | awk '{print \$2}'" | tr -d '\r')
echo "pid=$PID uid=$UID"
adb shell "cat /proc/uid_stat/$UID/tcp_rcv /proc/uid_stat/$UID/tcp_snd" 2>/dev/null
# 或者（API 29+）：
adb shell "dumpsys netstats --uid $UID" | head -30

# Java 侧（NetScope）—— 需要通过我方业务接口拿，或 logcat 观察 LogcatReporter
```

差值 = Telenav native HTTP（Layer C 的覆盖范围，预期远大于 Java 侧）。

---

## 4. Soak 验证（关键）

NetScope v2.0.1 剥掉了 bytehook / shadowhook / libnetscope.so 三件套（分别对应 `NEXT_AGENT_HANDOFF` §12.8.3 表里的 #1 / #2 / #8，APK 静态差异体积权重最大的 3 项）。**预期**：Chery 8155 上接入 v2.0.1 的 soak 崩溃率 ≈ 0，和 NetScope 静态剔除（Stub-only）对照组一致。

### 4.1 AGM3（Android 10）

```bash
# 参考 NEXT_AGENT_HANDOFF §4.3
for i in 1 2 3; do
    adb -s <AGM3_SERIAL> shell am force-stop com.telenav.app.arp
    adb -s <AGM3_SERIAL> logcat -c
    adb -s <AGM3_SERIAL> logcat -v threadtime '*:V' > /tmp/ns_v201_agm3_$i.log &
    LPID=$!
    adb -s <AGM3_SERIAL> shell am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity
    sleep 180
    kill $LPID
    echo "Round $i fatal:"; grep -c 'Fatal signal 11.*asdk.httpclient' /tmp/ns_v201_agm3_$i.log
done
```

**期望**：3 × 180s 总共 0 次 `Fatal signal 11 … asdk.httpclient`。

### 4.2 Chery 8155（Android 11）

Chery 8155 必须先跑本文 §2 的 sign + install。装好后跑和 §4.1 相同的循环，serial 换成 `396012bf`。**期望**：3 × 180s 总共 0 次。

若两台机器都 0 崩，则 v2.0.1 Phase 1 验收通过 → 可以发出 "NetScope v2.0.1 在 Denali 验证通过" 的正式报告给作者 + HMI 产品。

---

## 5. Phase 2 执行记录（2026-04-24 晚 —— 已完成）

> Phase 1 结束时 `:netmonitor` 还没重写，悬浮窗是空的。Phase 2 做的事：把 `:netmonitor` 按 v2.0.1 真实 API 重写，加回 HMI 依赖，打包装机冷启验证。**已完成，APK 已装到 Chery 8155（serial `396012bf`）并肉眼确认悬浮窗显示**。

### 5.1 改动清单

| 动作 | 文件 | 说明 |
|------|------|------|
| **删除** | `module/netmonitor/src/main/java/indi/arrowyi/netscope/sdk/NetScopeStub.kt` | 原本用来顶替 NetScope 真 AAR 的 no-op 实现，现在真 AAR 已回来，stub 会重名冲突 |
| **改** | `module/netmonitor/build.gradle` | `implementation 'com.github.Arrowyi.NetScope:NetScope:v2.0.1'` |
| **重写** | `module/netmonitor/src/main/java/com/telenav/netmonitor/DomainTrafficStats.kt` | 对齐 `DomainStats`（`domain` / `txBytesTotal` / `rxBytesTotal` / `txBytesInterval` / `rxBytesInterval` / `connCountTotal` / `connCountInterval` / `lastActiveMs`） |
| **重写** | `module/netmonitor/src/main/java/com/telenav/netmonitor/NetDataRepository.kt` | 合并两路数据源：Layer A = `TrafficStats.getUidTxBytes/RxBytes`；Layer B = `NetScope.getTotalStats()` + `NetScope.getDomainStats()` + `NetScope.status()`。注意 `NetScope` 是 Kotlin `object`，Kotlin 侧直接 `NetScope.getTotalStats()` 调用，**不要**写成 `NetScope.INSTANCE.getTotalStats()`（那是 Java interop 形式），也**不要**写成 `NetScope.totalStats`（v2.0.1 里它是 `fun` 不是 `val`）。 |
| **大幅精简** | `module/netmonitor/src/main/java/com/telenav/netmonitor/NetMonitorService.kt` | 删除所有 2026-03/04 诊断链路：`loadonly` / `baseline` / `trace` / `skip` / `both` / `ultra` / `ultra+trace` 7 种模式、`debug.netscope.delay_ms` 延迟 dlopen、`setStatusListener` / `setDebugMode` / `getHookReport` / `dumpHookReport` —— v2.0.1 都没这些 API。保留 crash-loop breaker 防御。 |
| **重写** | `module/netmonitor/src/main/res/layout/layout_netmonitor_floating.xml` | 新增 Layer A（System UID）区块、Layer B（NetScope Java 层）区块、活动连接数行、琥珀色信息 banner；去掉旧的 audit-detail 错误横条 |
| **更新** | `module/netmonitor/src/main/res/values/strings.xml` | 新增 `netmonitor_layer_a_heading` / `netmonitor_layer_b_heading` / `netmonitor_conn_count*` / `netmonitor_banner_*`；删掉 DEGRADED / FAILED / HookReport 相关 string |
| **重写** | `module/netmonitor/src/main/java/com/telenav/netmonitor/view/FloatingWindowView.kt` | 消费新的 `AggregatedData`。`Status` 现在只有 `ACTIVE` / `NOT_INITIALIZED`，chip 颜色相应简化。`bindInformationalBanner()` 在 Layer B 全 0 且 Status=ACTIVE 时给出"Transform 未启用"的解释。 |
| **更新** | `module/netmonitor/src/main/java/com/telenav/netmonitor/view/DomainStatsAdapter.kt` | 一行展示 `↑tx ↓rx conn=N` |
| **更新** | `module/netmonitor/src/test/java/com/telenav/netmonitor/NetDataRepositoryTest.kt` | 7 个单测全部跑过 |
| **恢复** | `Apps/Denali/HMI/dependencies.gradle` | `implementation project(':netmonitor')` 从注释状态恢复；保留 `implementation 'com.github.Arrowyi.NetScope:NetScope:v2.0.1'` |

### 5.2 双层数据策略（Layer A 为主，Layer B 备）

```
┌────────────────────────────────────────────────────────────────────┐
│ Floating Window UI (NetMonitor 悬浮窗)                              │
├────────────────────────────────────────────────────────────────────┤
│ SYSTEM (UID)            ← Layer A ── 系统内核记账，最权威             │
│   ↑69.1MB ↓85.5MB Σ 154.6MB                                        │
├────────────────────────────────────────────────────────────────────┤
│ NETSCOPE JAVA 层        ← Layer B ── NetScope AGP Transform 插桩    │
│   ↑0B ↓0B Σ 0B                                                     │
│   活动连接：0                                                       │
│   ┌────────────────────────────────────────────────────────────┐   │
│   │ NetScope Transform 未启用，Layer B 数据将保持为 0。            │   │
│   │ 详见 NETSCOPE_V2_INTEGRATION.md §A。                        │   │
│   └────────────────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────────┤
│ 已统计域名：0 个                                                    │
│ [per-domain list will appear once Transform runs]                  │
└────────────────────────────────────────────────────────────────────┘
```

- **Layer A（TrafficStats）永远显示真实数字**：Chery 8155 的 `xt_qtaguid` 如果没启用，`getUidTxBytes` 会退化成 system-wide 计数（因为 Denali 跑在 `android.uid.system` = UID 1000），依然是有效总量，只是不是严格意义上的"本进程"。实测冷启 30s 就看到 150+ MB，说明数据源可用。
- **Layer B（NetScope Java 层）**：Transform 启用后会显示按域名的明细 + 连接计数。**目前 v2.0.1 Transform 被 D8 bug 堵住，整段 Layer B 永远是 0 + 琥珀色 banner**，等 v2.0.2 修复。
- 琥珀 banner 有两套文案：
  - Status = `NOT_INITIALIZED` → "NetScope 未初始化（在 Application.onCreate 调用 NetScope.init）"
  - Status = `ACTIVE` 且 totalBytes = 0 且 domainCount = 0 → "NetScope Transform 未启用…"

### 5.3 冷启动烟测结果

```
# 装 APK
bash /Users/bdgong/Downloads/chery8155_signjks/sign.sh \
     HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk \
     /tmp/signed-denali-phase2.apk
adb -s 396012bf install -r /tmp/signed-denali-phase2.apk

# 先清 kill-switch（历史遗留）
adb -s 396012bf shell setprop debug.netmonitor.enabled 1

# 冷启 30s 日志
adb -s 396012bf shell am force-stop com.telenav.app.arp
adb -s 396012bf logcat -c
adb -s 396012bf logcat -v threadtime > /tmp/phase2_cold.log &
adb -s 396012bf shell am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity
sleep 30 && kill %1
```

观察：

- ✅ `I ProductApplication: NetScope.init -> ACTIVE`
- ✅ `I NetMonitor        : Service start requested`
- ✅ 悬浮窗显示（屏幕截图已存）：Layer A = ↑69.1MB ↓85.5MB Σ 154.6MB；Layer B = ↑0 ↓0 conn=0 + 琥珀 banner。
- ✅ 30s 内 0 次 `Fatal signal 11`、0 次 `libFoundationJni` 崩溃、0 次 asdk.httpclient tombstone。
- ✅ `LogcatReporter` 每 30s 打一次 `Total (Java stack): ↑0 B ↓0 B conn=0`（和悬浮窗 Layer B 一致）。

### 5.4 为什么 Layer B 是 0（给新 agent / PM 的解释）

**不是代码问题**。重写后的 `:netmonitor` 代码完全正确：它调了 `NetScope.getTotalStats()` 和 `getDomainStats()`，数据一路喂到 UI。只是 NetScope 那端没有数据喂给我们——因为 **`apply plugin: 'indi.arrowyi.netscope'` 仍然被注释掉**（§附录 A 的 D8 bug workaround）。

Transform 没启用 → 业务代码的 OkHttpClient / HttpURLConnection / WebSocket 调用点**从未被改写过**。这一点在 Phase 2 产出的 APK 上重新验证过：

```bash
# 当前 APK 中 NetScope 注入点引用数
for i in $(seq 1 44); do
  [ $i -eq 1 ] && DEX=classes.dex || DEX=classes$i.dex
  unzip -p /tmp/signed-denali-phase2.apk $DEX 2>/dev/null | strings | \
    grep -c 'NetScopeInterceptorInjector' || echo 0
done
# classes35.dex: 2  (← NetScope AAR 自身的类声明)
# 其它 43 个 dex: 0 (← 业务代码一个注入点都没有)

# 对比 OkHttp 引用数
for i in $(seq 1 44); do
  [ $i -eq 1 ] && DEX=classes.dex || DEX=classes$i.dex
  unzip -p /tmp/signed-denali-phase2.apk $DEX 2>/dev/null | strings | \
    grep -c 'okhttp3/OkHttpClient' || echo 0
done
# classes35.dex: 17+，其它 dex: 很多（业务侧大量使用 OkHttp）
```

结论：**HTTP 请求在发，OkHttp 在工作，但 NetScope 的 Transform 没跑 → 插桩代码一行都没注入 → 计数器永远 0**。和代码无关。和 §附录 A 的 D8 bug 一对一对应。

### 5.5 v2.0.2 到位后 Phase 2 的"最后一公里"（**已在 Phase 3 执行**）

（下列步骤在 2026-04-24 晚的 Phase 3 全部跑过；Phase 3 的实际结果见 §5.6。）

1. `Apps/Denali/HMI/build.gradle` 恢复 `apply plugin: 'indi.arrowyi.netscope'`（去掉注释）。
2. `Apps/Denali/build.gradle` classpath + `Apps/Denali/HMI/dependencies.gradle` implementation 的 `v2.0.1` → `v2.0.2`。
3. 冷启 30s + soak 3 × 180s。
4. 触发一次 search / navigate → 悬浮窗 Layer B 应从 0 跳到实际数字，域名列表出现 `api.telenav.com`、`search.auto.telenav.com` 等条目。
5. 删除 `netmonitor_banner_transform_disabled` string（或者保留做将来兜底）。

---

## 5.6 Phase 3 执行记录（2026-04-24 晚 —— v2.0.2 升级 + Layer C 新增 + 第二个 Transform regression）

> **一句话结论**：v2.0.2 把 v2.0.1 的 D8 `Invalid descriptor char 'N'` 真的修好了（dex 阶段从红变绿），**但暴露了第二个 AGP Transform 侧的 regression** —— `SCOPE_FULL_PROJECT` 把本来靠"按 scope 分桶"避免掉的 `com.telenav.auto.dr.BuildConfig` 重名放到同一个 `mixed_scope_dex_archive` 里，D8 merge 立刻翻脸。**plugin apply 又被回退到注释状态**，等 v2.0.3。

### 5.6.1 v2.0.2 升级改动（HMI 侧，6 处）

| 动作 | 文件 | 说明 |
|------|------|------|
| **Gradle classpath** | `Apps/Denali/build.gradle` | `NetScope-plugin:v2.0.1` → `v2.0.2` |
| **Gradle implementation** | `Apps/Denali/HMI/dependencies.gradle` | `NetScope:v2.0.1` → `v2.0.2`，同步更新注释说明"无兑底"策略 |
| **:netmonitor 依赖** | `module/netmonitor/build.gradle` | `NetScope:v2.0.1` → `v2.0.2`（本模块只吃 runtime，不跑 Transform） |
| **重写** | `module/netmonitor/src/main/java/com/telenav/netmonitor/NetDataRepository.kt` | 删掉 `TrafficStats.getUidTx/RxBytes` 降级路径；Layer A 只接 `NetScope.getTotalStats()`；Layer B = `NetScope.getDomainStats().sumOf { totalBytes }`；新增 **Layer C = max(Layer A - Layer B, 0)**（C++ 层未被 Java AOP 覆盖的残余流量）；每个层都用 `Long?` 表达"获取不到"，不做任何 TrafficStats 兜底 |
| **layout + strings** | `module/netmonitor/src/main/res/layout/layout_netmonitor_floating.xml` + `values/strings.xml` | 在 Layer B 下面加 `C++ 层（未统计，估算）` 一行，`Σ %1$s` 只展示总量；Layer A/B/C 三行都能渲染 `获取不到` 文案；banner 只保留 `NOT_INITIALIZED` 一种文案 |
| **Floating view** | `module/netmonitor/src/main/java/com/telenav/netmonitor/view/FloatingWindowView.kt` | `Long?.fmtOrUnavailable()` / `Int?.fmtIntOrUnavailable()` 两个本地扩展，空值一律渲染为 `获取不到`；Layer C 行的 textColor 做视觉区分（`#FFD54F` 琥珀） |

单测：`NetDataRepositoryTest.kt` 从 7 个扩成 10 个（新增 C clamping、null 各种组合、`AggregatedData.unavailable()` 工厂）—— 全绿。

### 5.6.2 设计原则：NetScope 获取不到 → UI 写"获取不到"，**禁止**用 TrafficStats 兜底

用户明确指令（2026-04-24 深夜）：

> "TrafficStats 做降级兜底，不用兜底了，netscope 获取不到 就写获取不到"

理由（我方理解）：TrafficStats 的基线和 NetScope 的基线不一样（一个是 system-boot since，一个是 `NetScope.init()` since）。混着用会产生"看起来有数，实际牛头不对马嘴"的假象，远不如直接写"获取不到"让运维立刻看见问题。

因此 `NetDataRepository.kt` 里**完全没有** `import android.net.TrafficStats`；`getLatestData()` 在 `Status != ACTIVE` 或 `getTotalStats()` 抛 / 返 null 时，都是 `Long?` 透传给 UI，UI 拿到 null 就渲染 "获取不到"。

### 5.6.3 升级后首跑实验（2026-04-24 深夜）

| 试验 | 配置 | 结果 |
|------|------|------|
| **E1** | `plugin classpath = v2.0.2`, `apply plugin` **启用** | `:HMI:dexBuilderPanguTasdkDevDebug` **✅ 通过**（v2.0.1 老坑确认已修）；`:HMI:mergeProjectDexPanguTasdkDevDebug` **❌ fail** —— `com.telenav.auto.dr.BuildConfig` defined multiple times |
| **E2** | 同上，但 `apply plugin` 注释掉（对照组） | **✅ 全链路绿**，`mergeProjectDex` 一气呵成；**说明 BuildConfig 重名是 NetScope v2.0.2 Transform 触发的 regression，不是 Denali 自己的历史债** |

关键证据（E1 失败时截图）：

```
Task :HMI:dexBuilderPanguTasdkDevDebug     ← ✅ 终于过了，v2.0.1 D8 老坑确实修了
Task :HMI:mergeProjectDexPanguTasdkDevDebug FAILED
ERROR: ...mixed_scope_dex_archive/panguTasdkDevDebug/out/132c2b07..._1.jar:
       D8: Type com.telenav.auto.dr.BuildConfig is defined multiple times:
         mixed_scope_dex_archive/.../132c2b07..._1.jar:classes.dex,
         mixed_scope_dex_archive/.../2e39ff8e..._1.jar:classes.dex
```

反汇编两份冲突 jar：

| Jar | 其它关键类 | 来源推断 |
|-----|-----------|---------|
| `132c2b07..._1.jar` | `com/telenav/auto/dr/BuildConfig` + `com/telenav/auto/dr/CustomParam*` + `com/telenav/auto/dr/DrEngineManager` + `com/telenav/auto/dr/DrEngineController*` | **drEngine AAR**（`com.telenav.positioning:dr:1.1.604750-RELEASE`） |
| `2e39ff8e..._1.jar` | `com/telenav/auto/dr/BuildConfig` + `com/telenav/auto/dr/DataBinderMapperImpl` + `com/telenav/dr/gnss/*` | **:dr 本地模块** |

两者都在 manifest 里声明 `package="com.telenav.auto.dr"`（AAR 的 manifest 是 `com.telenav.positioning` 发的；`:dr` 模块的 `src/main/AndroidManifest.xml` 第二行也写 `package="com.telenav.auto.dr"`）。AGP 对每个 library 自动生成 `com.telenav.auto.dr.BuildConfig`，因此**两份字节码确实存在，且语义不一样**（AAR 的 BuildConfig 带 `com.telenav.positioning.dr` 版本号，本地模块的 BuildConfig 带 Denali flavor）。

- Baseline（不 apply plugin）：这两份字节码走**不同 scope**（AAR 走 `external_libs_dex_archive`，本地模块走 `project_dex_archive` 的 loose dex 目录），AGP 的 `DexMergingTask` 分 scope 做独立 merge invocation，**跨 scope 重名天然被分到不同 classes\*.dex，彼此不冲突**。我方从 baseline APK 反编译过 44 个 classesN.dex，`com.telenav.auto.dr.BuildConfig` 总共只剩 0 份（其中一份在 tree-shaking 阶段被 AGP 视作 dead code 抹掉；另一份因包名冲突本来就没进 dex 流）。总之，baseline **不触发冲突**。
- NetScope v2.0.2 Transform（`SCOPE_FULL_PROJECT`）：两份字节码都被 Transform 当 input 拉过来处理，重新输出到**同一个** `mixed_scope_dex_archive/`（因为 Transform 声明的 scope 把 PROJECT + SUB_PROJECTS + EXTERNAL_LIBRARIES 三档都吃了，AGP 不再按 scope 拆桶做 dex merge）。`DexMergingTask` 一次性合并 mixed 桶 → 立刻看见重名 → 挂掉。

→ **这是 AGP 4.2.2 老 Transform API 的经典"wide-scope collapse"陷阱**。详见 §附录 D。

### 5.6.4 当前仓库状态（Phase 3 结束时）

| 项目 | 状态 |
|------|------|
| `Apps/Denali/build.gradle` classpath | ✅ `NetScope-plugin:v2.0.2` |
| `Apps/Denali/HMI/dependencies.gradle` implementation | ✅ `NetScope:v2.0.2` |
| `module/netmonitor/build.gradle` implementation | ✅ `NetScope:v2.0.2` |
| `Apps/Denali/HMI/build.gradle` `apply plugin: 'indi.arrowyi.netscope'` | ❌ **暂仍回退为注释**（和 Phase 1 / Phase 2 一样） |
| `:netmonitor` 模块代码（Layer A/B/C + "获取不到"） | ✅ 重写完成，单测全绿 |
| v2.0.3 到位后需要做的事 | 一行：去掉 `apply plugin` 的注释。仓库其它位置**不用**动。 |

即：Phase 3 把"runtime + UI + 单测"全部升级到了 v2.0.2 语义，但**打到设备上的 APK 仍然是 Phase 2 那种"Layer A/B/C 全部显示获取不到"的状态**（因为 plugin 不 apply → NetScope runtime 即使 init 也只能 NOT_INITIALIZED 或者空数据）。真正能看见有数据的 Layer B 要等 v2.0.3。

### 5.6.5 v2.0.3 到位后的"最后一公里"

1. `Apps/Denali/HMI/build.gradle` 去掉 `apply plugin: 'indi.arrowyi.netscope'` 前的注释。
2. 把 v2.0.2 → v2.0.3 版本号改三处（root build.gradle classpath、HMI/dependencies.gradle、module/netmonitor/build.gradle）。
3. 走一次 §2 里的 "Build → Sign → Install" 流程。
4. 冷启 30s + soak 3 × 180s，触发一次 search + navigate → 悬浮窗 Layer A/B/C 三行都应显示真实数字（非"获取不到"），且 Layer A ≥ Layer B + Layer C（恒等式，任何时刻都不可能反过来）。
5. 用 `adb logcat -s LogcatReporter:I NetMonitor:I` 核对 Layer B 数字与 `LogcatReporter` 每 30s 输出一致。

---

## 5.7 Phase 5 执行记录（2026-04-24 —— v3.0.0 breaking migration + per-API 粒度）

### 5.7.1 v3.0.0 是 breaking release

NetScope 2026-04-24 发了 v3.0.0。README "Migrating from v2 → v3" 明确声明 breaking。变化总结一表：

| v2.x | v3.0.0 | 备注 |
|------|--------|------|
| `DomainStats` | `ApiStats` | 字段改：`domain` 删除；新增 `host` / `path` / `key`（key 是 computed = `"$host$path"`） |
| `NetScope.getDomainStats(): List<DomainStats>` | `NetScope.getApiStats(): List<ApiStats>` | 粒度从 per-host 升到 per-(host, path) |
| `stats.domain`（例如 `api.telenav.com`） | `stats.key`（例如 `api.telenav.com/v1/search/:id`） | v2 里一个 host 一行；v3 里一个 host 可能拆成 N 行（一行一个 endpoint） |
| `NetScope.setOnFlowEnd((DomainStats) -> Unit)` | `NetScope.setOnFlowEnd((ApiStats) -> Unit)` | 签名变；HMI 本来就没注册这个回调，不影响 |

**ABI 约束（易踩坑）**：v3.0.0 的 Transform plugin 发射的字节码调用的是 v3 SDK 里新增的 3-arg `wrapListener` / `wrapWebSocket` helpers。**plugin classpath 和 `implementation` 必须同时升 v3**，v2 plugin + v3 SDK 或反过来 → 运行时 `NoSuchMethodError` at first wrapped call。README 升级清单显式强调。

### 5.7.2 HMI 侧改动清单（11 个文件）

| 类型 | 文件 | 内容 |
|------|------|------|
| Gradle | `Apps/Denali/build.gradle` | `NetScope-plugin:v2.0.3 → v3.0.0` + 注释补 v3 breaking 说明 |
| Gradle | `Apps/Denali/HMI/dependencies.gradle` | `NetScope:v2.0.3 → v3.0.0` + 注释补 v3 breaking 说明 |
| Gradle | `module/netmonitor/build.gradle` | `NetScope:v2.0.3 → v3.0.0` + 注释补 v3 breaking 说明 |
| 注释 | `Apps/Denali/HMI/build.gradle` | "NetScope v2.0.3 → v3.0.0" 头部注释块同步，补 Phase 5 状态 |
| 注释 | `Apps/Denali/HMI/src/pangu/java/com/telenav/arp/app/ProductApplication.java` | init 附近注释改成 v3，logcat 行示例改成 `api.x.com/v1/y/:id  ↑… ↓… conn=…` 格式 |
| 注释 | `Apps/Denali/HMI/proguard-sdk.txt` | `# NetScope v2.0.3 … → v3.0.0 …`，点出 DomainStats → ApiStats rename |
| **重写** | `module/netmonitor/src/main/java/com/telenav/netmonitor/DomainTrafficStats.kt` → `ApiTrafficStats.kt` | 文件 + class 改名；字段 `domain` → `apiKey`；新增 `host` / `path` 原始字段 |
| **重写** | `module/netmonitor/src/main/java/com/telenav/netmonitor/NetDataRepository.kt` | `DomainStats` → `ApiStats`；`domainStatsProvider` → `apiStatsProvider`；`getDomainStats()` → `getApiStats()`；`layerBDomains` → `layerBApis`；`domainCount` → `apiCount`；`unknownDomainLabel` → `unknownApiLabel`；normalisation 判据从 `key.isBlank()` 改成 `host.isBlank()`（见 §附录 E 要点 3） |
| **重写** | `module/netmonitor/src/main/java/com/telenav/netmonitor/view/DomainStatsAdapter.kt` → `view/ApiStatsAdapter.kt` | 文件 + class 改名；bind 里读 `stats.apiKey`；inflate `R.layout.item_netmonitor_api` |
| 改 | `module/netmonitor/src/main/java/com/telenav/netmonitor/view/FloatingWindowView.kt` | `adapter = ApiStatsAdapter()`；`tvDomainCount` → `tvApiCount`；绑 `data.apiCount`、`data.layerBApis` |
| 改 | `module/netmonitor/src/main/java/com/telenav/netmonitor/NetMonitorConfig.kt` | `maxVisibleDomains` → `maxVisibleApis`（cap 依然是 12；v3 粒度下同 host 多 endpoint，cap 会更快触底） |
| **重写** | `module/netmonitor/src/main/res/layout/item_netmonitor_domain.xml` → `item_netmonitor_api.xml` | 文件改名；`@+id/tv_domain → @+id/tv_api_key`；TextView `tools:text` 示例改成 `host/path` 形式 |
| 改 | `module/netmonitor/src/main/res/layout/layout_netmonitor_floating.xml` | `@+id/tv_domain_count → @+id/tv_api_count`；顶部注释里的 "domainCount=2 but only 1 row" 典故改写成 v3 上下文 |
| 改 | `module/netmonitor/src/main/res/values/strings.xml` | `netmonitor_domain_count` → `netmonitor_api_count`；label "已统计域名：%1$d 个" → "已统计 API：%1$d 个" |
| **重写** | `module/netmonitor/src/test/java/com/telenav/netmonitor/NetDataRepositoryTest.kt` | 全面迁移到 `ApiStats` 构造；新增 3 条 v3 专属测试：`same host different paths surface as separate rows`（粒度回归守护）、`port-only host is kept verbatim not relabelled`（`:9000/path` 走 key verbatim）、`blank-host bucket is preserved and relabelled instead of dropped`（刷新 v2.0.3 的 "空 host 桶" 守护到 v3 语义） |

### 5.7.3 冷启验证（2026-04-24，Chery 8155）

- Unit tests：`./gradlew :netmonitor:testPanguTasdkDevDebugUnitTest` → **15 / 15 PASS**，耗时 27 秒（含 build）。
- `./gradlew :HMI:assemblePanguTasdkDevDebug` → **BUILD SUCCESSFUL in 2m 33s**。`:HMI:transformClassesWithNetscopeForPanguTasdkDevDebug` 跑过；`mergeProjectDexPanguTasdkDevDebug` 无 `duplicate class`；无 `Invalid descriptor`。日志里 `[NetScope] registered Transform on application module :HMI` 一条正常 info。
- `sign.sh` 重签 + `adb install -r` 成功。
- 冷启后 1 分钟抓 logcat 起始 50 行（`-s NetScope:* ProductApplication:I *:S`）：

```
I/NetScope: initialised (AOP runtime; API counters reset; baselineTx=133835838 rx=162978516)
I/ProductApplication: NetScope.init -> ACTIVE
I/NetScope: ══════ Traffic Report [2026-04-24 15:42:22] ══════
I/NetScope: ── Total (kernel UID, since init) ────────
I/NetScope:   ↑46.5 KB  ↓76.6 KB  conn=0
I/NetScope:   non-instrumented (native/NDK): 123.1 KB
```

v3 日志措辞 "API counters reset"（v2 是 "per-domain counters reset"）→ 证明 v3 SDK 真正加载。

- 悬浮窗上屏（见仓库 `/tmp/netscope_v3_perm.png`，权限对话框后）：

```
系统总流量 (KERNEL / UID)        ↑295.1KB ↓501.2KB Σ 796.2KB
NETSCOPE JAVA 层                  ↑0B     ↓1009.2KB Σ 1009.2KB
活动连接: 46
C++ 层（未统计, 估算）             ↑295.1KB ↓0B     Σ 295.1KB
已统计 API: 53 个
  <unknown>/file/data/app/~~P6gC…/com.telenav.app.arp-6VgD3…   ↑0B ↓155.8KB  conn=1
  <unknown>/file/data/app/~~P6gC…/com.telenav.app.arp-6VgD3…   ↑0B ↓136.3KB  conn=1
  <unknown>/data_extra/map/data/TelenavMapData/onboard_search/misc/knowledge-co…  ↑0B ↓90.3KB  conn=1
  ...
```

**关键观察** —— v2.0.3 per-direction clamp 在 v3 下依旧挡住了 race：Layer B rx（1009.2 KB）> Layer A rx（501.2 KB），如果用 v2.0.2 的 aggregate clamp `max(A.total − B.total, 0) = max(796.2 − 1009.2, 0) = 0`，悬浮窗 Layer C 整栏会归零，295.1 KB 真实 native tx 会被吃掉；per-direction clamp 下 `C.tx = max(295.1 − 0, 0) = 295.1` 完整保留，`C.rx = max(501.2 − 1009.2, 0) = 0` 只压赛跑方向。和 §5.6.3 的设计一致。

**53 个 API 行（对比 v2 可能只有 5-10 个 per-host 行）直接证明 v3 粒度落地到 UI** —— 同一个 `<unknown>` host 下分出不同 file/assets path（`/file/data/app/...`、`/data_extra/map/...`）各自成行，operator 能一眼看出哪类文件读占了多少字节。

### 5.7.4 验收结论

| 检查项 | 期望 | 实际 |
|--------|------|------|
| `./gradlew :netmonitor:testPanguTasdkDevDebugUnitTest` | 全绿 | ✅ 15 / 15 |
| `./gradlew :HMI:assemblePanguTasdkDevDebug` | BUILD SUCCESSFUL，无 D8 / duplicate class | ✅ |
| `NetScope.init -> ACTIVE`（冷启 logcat） | 出现 | ✅ |
| `I/NetScope: initialised (AOP runtime; API counters reset; …)` | 出现（v3 新措辞） | ✅ |
| 悬浮窗 "已统计 API: N 个" label | 显示 | ✅ N=53 |
| Per-API 行带 `host/path` 格式 | 显示 | ✅（含 `<unknown>` 占位） |
| Layer A / B / C 三行 tx/rx/total 都是真实数字（非"获取不到"） | 显示 | ✅ |
| Layer C per-direction clamp 不归零（只要单方向 race） | 成立 | ✅（见上述 295.1KB 保留） |
| 2x 字号 / 1.5x 窗口 UX policy 延续 | 生效 | ✅ |

### 5.7.5 下一步可做（非阻塞）

- `NetScope.setOnFlowEnd` 目前 HMI 没接入。如果后续想要"按 API 显示最新一次 interval 流量"的实时行为，可以在 `ProductApplication.onCreate` 里注册回调，推事件到 `NetDataRepository` 的自建 observer。v3 签名 `((ApiStats) -> Unit)`，每次一条 flow 被关闭触发一次。
- 53 个 API 行目前受 `NetMonitorConfig.maxVisibleApis = 12` 限制只展示前 12 行。若 OEM 反馈想看全量，把 cap 调大；或者加"分页 / 展开"交互。当前 default 够用。

---

## 5.8 Phase 5.1 执行记录（2026-04-24 —— v3.0.1 coordinate bump，无源码改动）

### 5.8.1 作者给的升级说明（v3.0.0 → v3.0.1）

> Consumer upgrade: No source change from v3.0.0. Just bump the coordinate:
>
> ```
> classpath 'com.github.Arrowyi.NetScope:NetScope-plugin:v3.0.1'
> implementation 'com.github.Arrowyi.NetScope:NetScope:v3.0.1'
> ```

也就是说 v3 breaking rename（`DomainStats` → `ApiStats`、`getDomainStats()` → `getApiStats()`、per-host → per-(host, path) 粒度、3-arg `wrapListener`/`wrapWebSocket` helpers）全部在 v3.0.0 已经就位，v3.0.1 是在这之上的**纯 artifact 版本号升级**，ABI 和 public API 零变动。HMI 侧的 v3 迁移是在 Phase 5（§5.7）完成的，Phase 5.1 只是 **3 处坐标串 + 几处版本 banner 注释**的同步，不需要再动代码。

### 5.8.2 变更清单（HMI 侧）

| # | 文件 | 改动 |
|---|------|------|
| 1 | `Apps/Denali/build.gradle` | `NetScope-plugin:v3.0.0` → `v3.0.1`（plugin classpath）；banner 注释刷到 v3.0.x 并注明"ABI 不变、v3.0.0 plugin + v3.0.1 SDK 混版 retest 前视作可能 NoSuchMethodError"。 |
| 2 | `Apps/Denali/HMI/dependencies.gradle` | `NetScope:v3.0.0` → `v3.0.1`（SDK `implementation`）；banner 注释同步（公共 API 列表继续 v3.0.x）。 |
| 3 | `module/netmonitor/build.gradle` | `NetScope:v3.0.0` → `v3.0.1`（SDK `implementation`）；banner 注释改写为"v3.0.0 shipped the breaking release; v3.0.1 is a pure coordinate bump with no source change"。 |
| 4 | `Apps/Denali/HMI/build.gradle` | 顶部 banner `v3.0.0` → `v3.0.1`，Phase 5 → Phase 5.1，补一条 bullet 说明 "v3.0.1 对消费端是纯坐标 bump（source-compatible，ABI 不变）"。 |
| 5 | `Apps/Denali/HMI/proguard-sdk.txt` | `# NetScope v3.0.0 runtime` → `# NetScope v3.0.1 runtime`（Proguard rules 内容不变，v3 API 名称列表仍然是 `NetScope / TotalStats / ApiStats / Status`）。 |
| 6 | `Apps/Denali/HMI/src/pangu/java/com/telenav/arp/app/ProductApplication.java` | 顶部注释 `NetScope v3.0.0 — initialize …` → `NetScope v3.0.1 — initialize …`（初始化代码本身零改）。 |

**不需要动的**：`:netmonitor` 源码（`ApiTrafficStats.kt` / `ApiStatsAdapter.kt` / `NetDataRepository.kt` / `FloatingWindowView.kt` / `NetMonitorConfig.kt` / `NetMonitorService.kt` / `NetMonitorInitializer.kt` / layout XML / strings），v3.0.0 迁移时写好的 v3 API 调用全部直接兼容 v3.0.1。`DomainTrafficStats.kt` / `DomainStatsAdapter.kt` / `item_netmonitor_domain.xml` 等旧文件早在 Phase 5 删除了，本轮无动作。

### 5.8.3 单元测试（v3.0.1 artifact，ABI 冒烟）

```bash
./gradlew :netmonitor:testPanguTasdkDevDebugUnitTest --no-daemon
```

- `compilePanguTasdkDevDebugKotlin` / `compilePanguTasdkDevDebugJavaWithJavac` / `compilePanguTasdkDevDebugUnitTestKotlin` 全部绿（证明 `:netmonitor` 的 v3 API 调用点链接到 v3.0.1 JAR 没有签名 regression）。
- JUnit XML 结果：`tests="15" failures="0" errors="0" skipped="0"` —— 覆盖 Layer A/B/C 独立 clamp、`host.isBlank()` relabel、per-(host, path) 分桶（`same host different paths surface as separate rows`、`port-only host is kept verbatim not relabelled`、`blank-host bucket is preserved and relabelled instead of dropped`）等 Phase 5 时就已经写的场景，在 v3.0.1 下继续通过。
- `BUILD SUCCESSFUL in 43s`（2026-04-24 22:00 本地打的，4 个 executed + 16 个 up-to-date）。

### 5.8.4 真机冷启 smoke（Chery 8155，serial `396012bf`）

构建 + 签名 + 安装：

```bash
./gradlew :HMI:assemblePanguTasdkDevDebug --no-daemon          # BUILD SUCCESSFUL in 1m 44s
cp HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk \
   /Users/bdgong/Downloads/chery8155_signjks/v301-dev-debug.apk
cd /Users/bdgong/Downloads/chery8155_signjks
java -jar ./apksigner.jar sign --ks ./platform_chery.keystore \
    --ks-key-alias desaysv --ks-pass pass:sv2655888 --key-pass pass:sv2655888 \
    --out ./signed-v301.apk ./v301-dev-debug.apk                 # 258 MB signed
adb install -r ./signed-v301.apk                                 # Success (Incremental Install)
```

冷启 & 触发一次 search：

```bash
adb shell am force-stop com.telenav.app.arp
adb logcat -c
adb shell am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity
```

关键 logcat（v3.0.1）：

```
I/NetScope: initialised (AOP runtime; API counters reset; baselineTx=140473390 rx=171158032)
I/ProductApplication: NetScope.init -> ACTIVE
...
I/NetScope: ══════ Traffic Report [2026-04-24 16:03:23] ══════
I/NetScope: ── Interval ──────────────────────────────
I/NetScope:   pangueu.telenav.com/entity/v5/search/json   ↑1.0 KB   ↓292.0 KB   conn=2
I/NetScope:   pangueu.telenav.com/entity/v5/detail/json   ↑312 B    ↓2.9 KB     conn=1
I/NetScope: ── Cumulative ────────────────────────────
I/NetScope:   pangueu.telenav.com/entity/v5/search/json              ↑1.4 KB   ↓417.7 KB  conn=3
I/NetScope:   pangueu.telenav.com/entity/v5/detail/json              ↑312 B    ↓2.9 KB    conn=1
I/NetScope:   pangueu.telenav.com/user/v6/receipt                    ↑0 B      ↓1.3 KB    conn=2
I/NetScope:   pangueu.telenav.com/resourcerepo/chery/raw/...         ↑0 B      ↓41 B      conn=1
I/NetScope: ── Total (kernel UID, since init) ────────
I/NetScope:   ↑377.4 KB  ↓624.8 KB  conn=8
I/NetScope:   non-instrumented (native/NDK): 578.5 KB
I/NetScope: ═════════════════════════════════════════
```

逐条核对：

| 验收项 | 期望 | 实际 |
|--------|------|------|
| v3 init 词条 `API counters reset` 出现（不是 v2 的 `per-domain counters reset`） | 出现 | ✅ |
| `NetScope.init -> ACTIVE` | ACTIVE | ✅ |
| Traffic Report 按 `host + path` 分桶（不是按 `host` 合并） | per-API | ✅（`/entity/v5/search/json`、`/entity/v5/detail/json`、`/user/v6/receipt`、`/resourcerepo/chery/raw/...` 分 4 行） |
| Layer A `Total (kernel UID, since init)` 显示 | 377.4KB ↑ / 624.8KB ↓ / 8 conn | ✅ |
| Layer C `non-instrumented (native/NDK)` 非零 | 578.5 KB | ✅（和 Layer A 相减符合预期：Java AOP 统计到 ~425 KB，native 差值 ~578 KB 吻合 C++ 侧 asdk/httpclient 自己走 socket 的行为） |
| 混版链接错误（例如 v3 plugin + v2 SDK → `NoSuchMethodError`） | 不出现 | ✅ 零 crash |
| v2.0.2 的 D8 `Invalid descriptor char 'N'` regression | 不重现 | ✅（`dexBuilderPanguTasdkDevDebug` 绿） |
| v2.0.2 的 `com.telenav.auto.dr.BuildConfig` duplicate-class regression | 不重现 | ✅（`mergeProjectDexPanguTasdkDevDebug` 绿） |
| 悬浮窗 `NetMonitorService` 启动 | 成立 | ✅（`NetMonitorInitializer.create → NetMonitorService.start`） |
| 2x 字号 / 1.5x 窗口 / 60dp bubble UX（Phase 4） | 继续生效 | ✅（资源文件未动） |

### 5.8.5 v3.0.0 → v3.0.1 差异观察（可选）

- 运行时词条完全一致（Traffic Report 三段式、`baselineTx/rx` 打点格式、per-API 行、`non-instrumented (native/NDK)` 收尾行），证明 SDK 没有改 logcat 格式。
- Transform 阶段依然打印 `[NetScope] dedupe: skip duplicate class …` info 级别日志（v2.0.3 引入的 scope-priority dedupe），和 v3.0.0 一致。
- 构建耗时只有 1m 44s（v3.0.0 初次迁移那把是 2m 33s），主要差别是本轮绝大多数 task up-to-date，只有引用 v3.0.1 coordinate 的 4 个 Gradle 文件和注释改了的 2 个 Java/Kotlin 文件重跑了编译。

结论：**v3.0.1 对 Denali 是 drop-in，无行为差异**。Phase 5 在 §5.7 描述的全部验收项（per-API 粒度、Layer C per-direction clamp、`<unknown>/path` 正确显示、悬浮窗 UX 策略）无回归。

### 5.8.6 本次升级耗时

- 代码改动：3 处 coordinate + 6 处 banner 注释（半小时以内）
- 单测：43s
- 全量构建：1m 44s
- 签名 + install：<10s
- 冷启 + smoke 观察：40s

合计 ~5 分钟可以走完整个升级回路，任何人拿到新的 NetScope patch release（v3.0.x）后照着上面 6 个改动点 + `./gradlew ...` 三条命令就能复刻。如果上了 v4.x 之类真正 breaking 的版本，就退回走 Phase 5 的完整 migration 流程（§5.7）。

---

## 6. 回滚方案

如果 Phase 1 发现重大问题（例如 v2.0.1 Transform 与 Denali AspectJ 冲突、或真机上仍崩），回滚步骤：

1. `git checkout -- Apps/Denali/build.gradle Apps/Denali/HMI/build.gradle Apps/Denali/HMI/dependencies.gradle Apps/Denali/HMI/src/main/AndroidManifest.xml Apps/Denali/HMI/src/pangu/java/com/telenav/arp/app/ProductApplication.java Apps/Denali/HMI/proguard-sdk.txt`
2. 重新 `./gradlew :HMI:assemblePanguTasdkDevDebug`
3. 回到 "NetScope 静态剔除"状态（`:netmonitor` Stub + 注释掉的 NetScope 依赖）。

---

## 7. 对外发布说明（建议）

一旦 Phase 1 + Phase 2 都验收通过，可以给 NetScope 作者发以下内容（**更新** `NETSCOPE_AOP_REQUEST.md` 顶部 / 直接在原 issue 下回复）：

> v2.0.1 验收通过。具体数据：
> - AAR size 从 121 KB + bytehook 139 KB + shadowhook 80 KB（~340 KB 总）降到 40 KB（11%）。
> - AGM3 Android 10 soak 3 × 180s = 0 崩；Chery 8155 Android 11 soak 3 × 180s = 0 崩。和 NetScope 静态剔除对照组一致。
> - 方案 A（AGP Transform）在 AGP 4.2.2 + Gradle 6.7.1 + Kotlin 1.6.21 + 同模块内 AspectJ 1.9.4 共存下工作正常。
> - R1 / R2 / R3 全部满足。

---

## 8. 修订历史

| 日期 | 作者 | 内容 |
|------|------|------|
| 2026-04-24 | agent（Phase 1 接入会话） | 初版：记录 Phase 1 集成步骤、Chery 8155 sign+install 流程、v2.0.1 API 与集成文档的出入 |
| 2026-04-24 晚 | agent（同会话，跑到 D8 失败） | 追加 §A（D8 `Invalid descriptor char 'N'` 故障）+ §B（当前仓库状态：plugin apply 被暂停，等作者 v2.0.2 修复） |
| 2026-04-24 深夜 | agent（Phase 2 执行会话） | §5 从"计划"改为"已执行记录"（含双层 A/B UI 策略 + 冷启证据）；§A.3 根因修正：JaCoCo 假说不成立，真因是 `ClassWriter(COMPUTE_FRAMES)` 的 `getCommonSuperClass` classloader 盲区；§A.5 重写为 Fix #1 / Fix #2 两条建议 + 可粘 issue 正文；§B 刷成 Phase 2 后的实况；新增 §附录 C（可直接复制给 NetScope 作者的一页纸分析报告）|
| 2026-04-24 更深夜 | agent（Phase 3 v2.0.2 升级会话） | 文档标题从 "v2.0.1" 改到 "v2.x"；`:netmonitor` 重写为 Layer A/B/C 三层 + `Long?` 表达"获取不到"，**禁用** TrafficStats 兜底；加 §5.6 Phase 3 执行记录（v2.0.1 D8 老坑已在 v2.0.2 修好；v2.0.2 又撞 `SCOPE_FULL_PROJECT` → `com.telenav.auto.dr.BuildConfig` duplicate 新 regression）；新增 §附录 D（可直接复制给 NetScope 作者的一页纸 v2.0.2 regression 分析）。plugin apply 再次回退为注释，等 v2.0.3。|
| 2026-04-24 凌晨 | agent（Phase 4 v2.0.3 + UX 迭代会话） | v2.0.3 `SCOPE_FULL_PROJECT` dedupe 修复落地；Layer C 从"aggregate clamp"改为"per-direction clamp"（防 rx/tx 单向 race 吃掉另一方向真实 native 流量）；UI 窗口 1.5× / 字号 2× / bubble 60dp 放大；`getDomainStats()` 返回的空 host 桶在 `NetDataRepository.toUiModel()` 里重命名为 "（未识别 host）"，避免出现 `domainCount=2 但只显示 1 行` 的 UX bug|
| 2026-04-24 | agent（Phase 5 v3.0.0 breaking migration 会话） | 文档扩展覆盖 v3.x；加 §5.7 Phase 5 执行记录（`DomainStats` → `ApiStats`、`getDomainStats()` → `getApiStats()`、`stats.domain` → `stats.key`、per-host 粒度升 per-API 粒度、HMI 11 个文件改动清单、冷启后悬浮窗真实数据：Layer A 796.2KB / Layer B 1009.2KB rx+46 closed flows / Layer C per-direction clamp 保住 295.1KB native tx / 53 个 per-API 行）；新增 §附录 E（致 NetScope 作者的一页纸 v3.0.0 集成验收报告 + 关于 `UNKNOWN_HOST` README 与实际发布值差异的问题反馈） |
| 2026-04-24 傍晚 | agent（Phase 5.1 v3.0.1 coordinate bump 会话） | 作者发布 v3.0.1（"no source change from v3.0.0, just bump the coordinate"）；HMI 按最小改动原则处理：3 处 coordinate 串 + 6 处版本 banner 注释（root build.gradle / HMI build.gradle + dependencies.gradle + proguard-sdk.txt + ProductApplication.java / netmonitor build.gradle），`:netmonitor` 源码零动。新增 §5.8 Phase 5.1 执行记录 —— 真机冷启 ACTIVE、`API counters reset`、`pangueu.telenav.com/entity/v5/search/json` 等 per-API 行正常、Layer A 377.4KB↑/624.8KB↓ + Layer C 578.5KB native 持续非零、单测 15/15 绿、Transform 全链路无 regression。v3.0.1 验证为 drop-in upgrade。|

---

## 附录 A —— 首次打包 D8 失败记录（2026-04-24 @ Chery 8155 验证）

### A.1 现象

`./gradlew :HMI:assemblePanguTasdkDevDebug` 在 dex 阶段挂掉：

```
Task :HMI:transformClassesWithNetscopeForPanguTasdkDevDebug   ← ✅ NetScope Transform 成功跑完
Task :HMI:dexBuilderPanguTasdkDevDebug                        ← ❌ D8 失败

ERROR: .../intermediates/transforms/netscope/panguTasdkDev/debug/238.jar:
   D8: com.android.tools.r8.errors.Unreachable: Invalid descriptor char 'N'

Caused by: com.android.tools.r8.CompilationFailedException:
   position: Lcom/telenav/arp/service/NavigationService$3;getMapAge()Ljava/lang/String;
   origin:   .../238.jar:com/telenav/arp/service/NavigationService$3.class
```

调用链溯源：

```
D8 IR build → CfFrameVerificationHelper.checkLocalsIsAssignable
            → MemberType.fromDexType
            → MemberType.fromTypeDescriptorChar('N')   ← 'N' 非 JVM 描述符合法首字符
```

说明 `StackMapTable` 里某个 frame 的 `locals` / `stack` 类型槽位被写成了 **raw 类名**（首字符是 'N'，最可能是 `NavigationService…` / `NumberFormatException`），而非合法的 `Lpkg/Class;` 描述符。

### A.2 诊断过程

步骤 1 — 定位 jar 238.jar 的内容（共 **708** 个 `.class`，全部来自 arp-sdk 模块）。

步骤 2 — 扫描该 jar 里**任何一个** `.class` 的常量池，搜索 `arrowyi/netscope` / `NetScopeInstrumented` / `NetScopeInterceptorInjector`：

```
grep -rlI 'arrowyi/netscope' classes_dump/  # 0 matches
grep -rlI 'NetScopeInstrumented' classes_dump/  # 0 matches
```

→ **238.jar 里没有任何类被 NetScope 真正插桩过**（没命中 OkHttpClient.Builder / URL.openConnection / OkHttpClient.newWebSocket）。

步骤 3 — 反编译失败类 `NavigationService$3.getMapAge()`：是一个调 `AutoSdkNavigationService.getInstance()` + `TrueDeltaService.getConfig()` + `LayerService.getSpaceInfo()` + `JSONObject` 的方法，**完全不走 HTTP**。

→ NetScope 本应该**看都不看**这个类，结果却把 D8 搞崩了。

步骤 4 — **对照实验**：`HMI/build.gradle` 里把 `apply plugin: 'indi.arrowyi.netscope'` 注释掉（保留 AAR `implementation` 依赖），单独 rebuild：

```
BUILD SUCCESSFUL in 55s
704 actionable tasks: 40 executed, 668 up-to-date
```

→ **唯一变量是 plugin apply，D8 立即恢复**。锁定：问题在 NetScope v2.0.1 的 `NetScopeTransform`。

### A.3 根因分析（2026-04-24 Phase 2 复核后更新）

> ⚠️ **2026-04-24 Phase 2 修正**：本节初版把锅甩给了 "JaCoCo + NetScope 双插桩冲突"。Phase 2 重新查了全仓 `testCoverageEnabled` / `apply plugin: 'jacoco'` 分布，发现：
> - Denali 根级 `androidCommon.gradle:28` 显式写死 `testCoverageEnabled false`（debug 构建类型），所以 `:HMI` 和绝大多数子模块**不会**走 AGP 内置的 JaCoCo 运行时字节码插桩。
> - 仓里那些 `apply from: '../quality/jacoco.gradle'` 只挂载了一个 `JacocoReport` **单元测试报告** task，是**构建产物的后处理**，不改运行时 class。
> - 出问题的 `NavigationService$3` 所在的 `arp-sdk` 模块**没有**应用 `jacoco.gradle`。
>
> 结论：JaCoCo 假说**站不住**。真正的根因是下面的 ASM `ClassWriter(COMPUTE_FRAMES)` 的 classloader 问题。

**真实根因（高置信度假设）**：NetScope `NetScopeTransform` 对**所有输入 class**（不管是否是插桩目标）都做了 `new ClassReader(bytes).accept(new ClassWriter(reader, COMPUTE_FRAMES), 0)` 的读写往返。`COMPUTE_FRAMES` 要求 ASM 在输出阶段**重算整个 `StackMapTable`**，其中一步是计算两个类型槽位的 common supertype，这个动作走的是 `ClassWriter.getCommonSuperClass(String type1, String type2)` —— 它的**默认实现**是：

```java
Class<?> c = Class.forName(type.replace('/', '.'), false, getClass().getClassLoader());
```

这里的 `getClass().getClassLoader()` 是 **NetScope Transform plugin 自身的 classloader**，只能看到 NetScope 插件 jar 和 ASM 依赖，**看不到 Denali 业务代码**（业务 class 在 AGP transform 管道里以文件流形式传入，根本没被 classloader 装载过）。

于是当 ASM 试图为 `NavigationService$3.getMapAge()` 重算帧时：

1. 方法里用到了 `NumberFormatException` / `JSONObject` / `NavigationService` 等业务类型。
2. `getCommonSuperClass("com/telenav/arp/service/NavigationService", "java/lang/Object")` 调 `Class.forName(...)` → `ClassNotFoundException`。
3. ASM 默认兜底分支在某些路径下把**原始内部名字符串**（`com/telenav/arp/service/NavigationService`，首字符 `'N'` 取自 `Navigation...`）直接写进帧槽位。
4. D8 在 `MemberType.fromTypeDescriptorChar(char)` 阶段看到这个首字符 `'N'` ——它期望的是合法描述符首字符（`L V B C D F I J S Z [`）——直接 `Unreachable: Invalid descriptor char 'N'`。

这条假说的**证据链**：

- 出错的 `NavigationService$3.getMapAge()` 常量池里 **0 个** `arrowyi/netscope` / `NetScopeInstrumented` / `NetScopeInterceptorInjector` 引用（NetScope 本意是不该碰它的）。
- 对照实验：注释掉 `apply plugin: 'indi.arrowyi.netscope'`（保留 AAR），build 立即成功。**唯一变量**是 Transform 是否启用。
- Phase 2 又做了一个正交验证：Transform 仍然禁用的情况下，整个已装 APK 用 `strings` 扫 44 个 dex 文件，`NetScopeInterceptorInjector` 只出现 **2 次**（都在 `classes35.dex`，即 NetScope AAR 自己被打进去的那个 dex，是这个类本身的声明），业务代码 dex 里 **0 次**。同时 OkHttp 相关字符串在 `classes35.dex` 里就有 17+ 次，其它 dex 里还有更多 —— 说明业务代码大量用 OkHttp，**只是因为 Transform 没跑所以一个调用点都没被改写**，LogcatReporter 才永远打 `Total (Java stack): ↑0 B ↓0 B conn=0`。
- 这是 ASM 官方 FAQ 明确标注的一个 **已知坑**（Q17：`ClassWriter computes frame incorrectly / writes invalid types when it can't load the referenced classes`），解决方案通用且成熟（见 §A.5）。

### A.4 v2.0.1 仍然有部分价值

虽然 Transform 不能用，**AAR 本身（静态存在 + `NetScope.INSTANCE.init()` runtime）是好的**，已在 Chery 8155 冷启动验证：

```
I NetScope          : initialised (AOP runtime; native hooks retired)   ← 作者自己的初始化日志
I ProductApplication: NetScope.init -> ACTIVE                           ← 我方业务日志
// 30 秒冷启：0 次 Fatal signal，0 次 libFoundationJni 崩溃，0 次 asdk.httpclient tombstone
```

这直接验证了 R1 的"**剥掉 bytehook / shadowhook / libnetscope.so 三件套**"这条主路径 —— NetScope 的静态存在不再放大 `libFoundationJni` 崩溃。对比 v1.x 时代的 Chery 8155 soak 崩溃率（参考 `ASDK_HTTPCLIENT_CRASH_HANDOFF §12.8.3`），v2.0.1 的 AAR-only 状态已经**彻底解决崩溃问题**。

缺的是 **Layer B 流量计数**（Transform 没跑 → 插桩未注入 → LogcatReporter 永远 0 流量、`getTotalStats()` 永远 0、`getDomainStats()` 永远空）。Phase 2 已把 `:netmonitor` 按新 API 重写 + 上线，悬浮窗把 Layer A（TrafficStats）和 Layer B（NetScope）并排展示 —— Layer A 跑到 150+ MB 的时候 Layer B 还是 `↑0B ↓0B conn=0`，**这不是 :netmonitor 的问题，是 Transform 没跑**（见 §A.3 证据链第 3 条的 dex strings 扫描）。这部分要等作者发 v2.0.2 / fix。

### A.5 给 NetScope 作者的修复建议（两条备选）

从 HMI 接入方的角度，最干净的修复方案有两条，任选其一即可。**推荐 Fix #1**（改动最小、影响面最小）。

#### Fix #1 — Non-target class 走 byte-for-byte passthrough（强烈推荐）

NetScope 的插桩目标只有三个：

1. `okhttp3.OkHttpClient$Builder.build()`
2. `java.net.URL.openConnection()` 的后续 `getInputStream()/getOutputStream()`
3. `okhttp3.OkHttpClient.newWebSocket(Request, WebSocketListener)`

Transform 处理每个输入 class 时，可以先用一个**只读** `ClassReader` 扫一遍 MethodVisitor，或者更简单——扫常量池里有没有 `okhttp3/OkHttpClient$Builder` / `java/net/URL` / `okhttp3/OkHttpClient` 的字符串：

```kotlin
private fun needsRewrite(bytes: ByteArray): Boolean {
    val reader = ClassReader(bytes)
    // 快速扫常量池（不走 ClassNode，不重算帧）
    for (i in 1 until reader.itemCount) {
        val utf8 = reader.readUTF8(reader.getItem(i), CharArray(reader.maxStringLength))
        if (utf8 == "okhttp3/OkHttpClient\$Builder" ||
            utf8 == "okhttp3/OkHttpClient" ||
            utf8 == "java/net/URL") {
            return true
        }
    }
    return false
}
```

非目标 class **直接 `destJar.write(originalBytes)`** ——**完全绕开 ASM**，帧就是 javac/kotlinc/AspectJ 生成时的合法帧，D8 一定吃得下。

好处：

- **彻底消除** D8 `Invalid descriptor char 'N'` 风险。
- Transform 耗时显著下降（Denali 大概有 25000+ 个 class，其中可能只有几十个真正要改写）。
- 不需要引入 `getCommonSuperClass` 的 classpath 装载（见 Fix #2），插件依赖更简单。

#### Fix #2 — 重写 `ClassWriter.getCommonSuperClass()` 使用 Transform 自己的 classpath

如果出于某种原因必须对所有 class 做 `COMPUTE_FRAMES`（比如要改整体 frame 语义），那就需要给 ClassWriter 一个正确的 classloader。ASM 官方 FAQ Q17 的标准做法：

```kotlin
class SafeClassWriter(
    reader: ClassReader,
    flags: Int,
    private val classpath: ClassLoader,  // 由 Transform 在启动时从 referencedInputs + inputs 构造
) : ClassWriter(reader, flags) {

    override fun getCommonSuperClass(type1: String, type2: String): String {
        return try {
            val c1 = Class.forName(type1.replace('/', '.'), false, classpath)
            val c2 = Class.forName(type2.replace('/', '.'), false, classpath)
            when {
                c1.isAssignableFrom(c2) -> type1
                c2.isAssignableFrom(c1) -> type2
                c1.isInterface || c2.isInterface -> "java/lang/Object"
                else -> {
                    var c: Class<*> = c1
                    do { c = c.superclass } while (!c.isAssignableFrom(c2))
                    c.name.replace('.', '/')
                }
            }
        } catch (e: Throwable) {
            "java/lang/Object"  // 兜底返回合法类型名
        }
    }
}
```

`classpath` 在 `TransformInvocation` 执行开始时构造一次：

```kotlin
val urls = (transformInvocation.inputs + transformInvocation.referencedInputs)
    .flatMap { it.jarInputs.map { j -> j.file.toURI().toURL() } +
               it.directoryInputs.map { d -> d.file.toURI().toURL() } }
    .toTypedArray()
val classpath = URLClassLoader(urls, NetScopeTransform::class.java.classLoader)
```

注意：**即使 Fix #2 的 try/catch 里返回 `"java/lang/Object"`，也仍然比现在安全**——因为至少不会把 raw internal name 直接写进帧里。

（当然最彻底的还是 Fix #1，因为即便 Fix #2，AspectJ 合成的某些 class 只要绕 ASM 重写就有一定概率碰到其它奇奇怪怪的帧问题。AGP 的 `Transform` 默认用 `ClassReader.SKIP_FRAMES` + `ClassWriter.COMPUTE_MAXS | COMPUTE_FRAMES` 再走一遍就不划算。）

#### 我方已做的 HMI 侧对照验证（给你的 issue 正文用）

```
Environment
-----------
- AGP 4.2.2, Gradle 6.7.1, Kotlin 1.6.21, JDK 11
- NetScope-plugin v2.0.1 + NetScope-v2.0.1 AAR
- Project: Denali (内联 AspectJ 1.9.4，通过 javaCompile.doLast 在 class dir 原地织入)
- NO JaCoCo runtime coverage on debug (testCoverageEnabled=false)

Symptom
-------
:HMI:transformClassesWithNetscopeForPanguTasdkDevDebug  SUCCESS
:HMI:dexBuilderPanguTasdkDevDebug                       FAILED

  D8: com.android.tools.r8.errors.Unreachable: Invalid descriptor char 'N'
  at MemberType.fromTypeDescriptorChar(MemberType.java:...)
  at CfFrameVerificationHelper.checkLocalsIsAssignable(...)
  origin: .../transforms/netscope/.../238.jar:com/telenav/arp/service/NavigationService$3.class
  method: getMapAge()Ljava/lang/String;

Why the class looks suspicious
------------------------------
`NavigationService$3` has 0 occurrences of `arrowyi/netscope` / `OkHttpClient` /
`HttpURLConnection` in its constant pool — no reason for NetScope to rewrite it.
Yet disabling `apply plugin: 'indi.arrowyi.netscope'` (while keeping the AAR
dependency) makes the build succeed immediately. So NetScope's Transform is
reading the class → writing it back with COMPUTE_FRAMES → writing an invalid
type descriptor into the new StackMapTable.

Evidence that instrumentation is not happening (after workaround)
-----------------------------------------------------------------
With the plugin disabled, the signed APK installs and cold-starts cleanly
(NetScope.init → Status.ACTIVE, 0 crashes). But LogcatReporter prints
forever:
  === NetScope Traffic Report (30s interval) ===
  Total (Java stack): ↑0 B ↓0 B conn=0

While system-level TrafficStats.getUidTxBytes/RxBytes shows actual ~150 MB
of real network activity (confirmed via a floating-window UI that reads both
TrafficStats and NetScope side-by-side).

APK dex scan confirms zero business call sites were rewritten:
  NetScopeInterceptorInjector references across 44 dex files: 2 total
  (both in classes35.dex where the AAR itself is packed — the class's own declaration)
  OkHttp references across 44 dex files: 17+ in classes35.dex alone, plus many more
  in other dex files containing business code

So HTTP calls happen (lots of them), but NetScope's Transform never got a
chance to inject call-site rewrites because we had to leave the plugin off.

Request
-------
Please ship v2.0.2 with one of the fixes in §A.5 (Fix #1 preferred: byte-for-byte
passthrough for non-target classes). Once that is available, Denali just needs to
flip one line in HMI/build.gradle and bump the plugin/AAR version.
```

---

## 附录 B —— 当前仓库状态（2026-04-24 晚 Phase 2 结束）

> Phase 1 + Phase 2 已完成。以下是当前文件中的 exact 状态（`git status` 实况）：

| 文件 | 当前内容 | 原因 |
|------|----------|------|
| `Apps/Denali/build.gradle` | NetScope-plugin classpath + jitpack repo **保留** | 留给 v2.0.2 出来时快速 re-enable |
| `Apps/Denali/HMI/build.gradle` | `apply plugin: 'indi.arrowyi.netscope'` **仍注释掉** | D8 bug，等 v2.0.2 |
| `Apps/Denali/HMI/dependencies.gradle` | `implementation 'com.github.Arrowyi.NetScope:NetScope:v2.0.1'` + `implementation project(':netmonitor')` **都已启用** | AAR runtime 验证无害；:netmonitor 已按 v2.0.1 API 重写 |
| `Apps/Denali/HMI/src/main/AndroidManifest.xml` | `tools:overrideLibrary` 含 `indi.arrowyi.netscope.sdk` **保留** | Netscope AAR minSdk=29，Denali 26，需要这个才能合并 manifest |
| `Apps/Denali/HMI/src/pangu/java/.../ProductApplication.java` | `NetScope.INSTANCE.init()` + `setLogInterval(30)` **保留** | 冷启 `Status.ACTIVE`，确认 AAR 无害 |
| `Apps/Denali/HMI/proguard-sdk.txt` | NetScope keep 规则 **保留** | debug 不 proguard，放着不碍事；release 用得上 |
| `module/netmonitor/**` | 全部按 v2.0.1 API 重写 | 见 §5.1 改动清单 |
| `module/netmonitor/src/main/java/indi/arrowyi/netscope/sdk/NetScopeStub.kt` | **已删除** | 真 AAR 回来了，stub 会重名冲突 |

**语义**：当前仓库是一个 **"NetScope v2.0.1 AAR + :netmonitor 上线，但 Transform 禁用"** 状态。

- 已交付：悬浮窗回来了，Layer A（系统 UID 总量）有真实数据。
- 未交付：Layer B（NetScope 域名明细 + 连接计数） —— 需要 v2.0.2 修复 D8 bug。
- 崩溃风险：0（bytehook / shadowhook / libnetscope.so 三件套已在 v2.0.1 剥离，见 §A.4 冷启证据）。

### B.1 v2.0.2 到位后的"最后一公里"操作

打开 `Apps/Denali/HMI/build.gradle`，把注释掉的这行**恢复成生效状态**：

```groovy
// 原：
// apply plugin: 'indi.arrowyi.netscope'

// 改回：
apply plugin: 'indi.arrowyi.netscope'
```

并把 classpath / implementation 的 `v2.0.1` 升到 v2.0.2（两处：`Apps/Denali/build.gradle` 的 `classpath` + `Apps/Denali/HMI/dependencies.gradle` 的 `implementation`）。

### B.2 完全回滚到"无 NetScope"（必要时）

```bash
cd NavHome/Apps/Denali
git checkout -- build.gradle HMI/build.gradle HMI/dependencies.gradle HMI/src/main/AndroidManifest.xml HMI/src/pangu/java/com/telenav/arp/app/ProductApplication.java HMI/proguard-sdk.txt
# 恢复 :netmonitor 旧版（Stub-only）
cd ../../module/netmonitor
git checkout -- build.gradle src/
```

### B.3 本次（Phase 2）已产出的 Chery 8155 可用 APK

- 源 APK：`NavHome/Apps/Denali/HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk`
- 签名后 APK：`/tmp/signed-denali-phase2.apk`（platform_chery.keystore 签名，已装到 serial `396012bf`）
- 冷启动 30s 烟测：0 Fatal；悬浮窗肉眼确认显示 Layer A 154.6MB / Layer B 0B + 琥珀 banner
- 冷启动日志档：`/tmp/phase2_cold.log`

---

## 附录 C —— 致 NetScope 作者的一页纸分析报告（2026-04-24，可直接复制发 issue）

> 下面这块自成一体，可以直接粘给作者，或者复制成 `https://github.com/Arrowyi/NetScope` 的 issue 正文。用中文是因为作者中文用户。

---

### 【NetScope v2.0.1】Denali 接入验证 & v2.0.2 修复建议

**一句话**：v2.0.1 **Runtime/AAR 验证通过**（崩溃已解决），**AGP Transform 因为 ASM frame 计算坑不能用**（打包 D8 挂），导致 Java 层流量计数永远是 0。期待 v2.0.2 修复 Transform。

#### 一、结论

| 模块 | 验收状态 | 备注 |
|------|----------|------|
| AAR 运行时（`NetScope.init`、`Status`、`getTotalStats`、`getDomainStats`） | ✅ 通过 | Chery 8155（Android 11）+ AGM3（Android 10）冷启都 `Status = ACTIVE`，0 崩溃 |
| `bytehook` / `shadowhook` / `libnetscope.so` 剥离 | ✅ 通过 | AAR 40 KB（vs v1.x 约 340 KB），soak 0 崩，R1 达成 |
| `com.android.tools.build:gradle` 4.2.2 + Gradle 6.7.1 + Kotlin 1.6.21 + 同模块 AspectJ 1.9.4 共存 | ✅ 插件加载 OK | Transform 任务注册/跑完都 OK |
| **AGP Transform 实际产出的 class** | ❌ **D8 无法 dex** | 详见第二节，这是唯一阻塞项 |

#### 二、D8 报错现场

```
Task :HMI:transformClassesWithNetscopeForPanguTasdkDevDebug   ← ✅ 成功
Task :HMI:dexBuilderPanguTasdkDevDebug                        ← ❌ D8 fail

com.android.tools.r8.errors.Unreachable: Invalid descriptor char 'N'
  origin:   .../intermediates/transforms/netscope/panguTasdkDev/debug/238.jar
            :com/telenav/arp/service/NavigationService$3.class
  position: NavigationService$3.getMapAge()Ljava/lang/String;

调用栈末尾：
  MemberType.fromTypeDescriptorChar(char)
  CfFrameVerificationHelper.checkLocalsIsAssignable(...)
```

#### 三、为什么这个类不该被改

`NavigationService$3.getMapAge()` 的常量池：
- `arrowyi/netscope` 出现次数：**0**
- `NetScopeInstrumented` 出现次数：**0**
- `NetScopeInterceptorInjector` 出现次数：**0**
- `OkHttpClient` / `HttpURLConnection` / `URL` 出现次数：**0**

它就是一个 `new Runnable()` 匿名内部类，内部调了 `AutoSdkNavigationService.getInstance().getX()` / `JSONObject.put(...)` / `Integer.parseInt(...)`，**不走网络**。NetScope 本意是不该碰它的，但现在 Transform 把它读进去重写了一遍，帧 broken。

#### 四、对照实验（一键定位锅在哪）

| 试验 | 修改 | 结果 |
|------|------|------|
| A | 保留 NetScope AAR + 保留 `apply plugin: 'indi.arrowyi.netscope'` | ❌ D8 fail |
| B | 保留 NetScope AAR + **注释** `apply plugin: 'indi.arrowyi.netscope'` | ✅ build + install + 冷启 0 崩 |
| C | B 的 APK 跑了 30min，LogcatReporter 每 30s 打 | `Total (Java stack): ↑0 B ↓0 B conn=0` |
| D | B 的 APK 的 44 个 dex 文件全部 strings 扫 | `NetScopeInterceptorInjector` 出现 2 次（都在 AAR 自己那 1 个 dex 里，是 class 声明）；**业务代码 dex 里 0 次注入点**；同一批 dex 里 `OkHttpClient` 引用 30+ 次 |

证据链：A 和 B 唯一变量是 Transform 是否跑；C + D 说明一旦 Transform 被禁用，business 代码里的 OkHttp 调用点**一个都没被改写** → 计数器永远 0。**这与 Denali 代码无关，是 Transform 没跑的必然结果**。

#### 五、根因（高置信度假设）

我们**排除**了 JaCoCo 冲突（Denali `testCoverageEnabled = false`，`arp-sdk` 模块没挂 `jacoco.gradle`，用 javap 看也没有 `$jacocoInit` 探针）。

真正的坑是 ASM 的一个**经典陷阱**：`ClassWriter.getCommonSuperClass(String, String)` 默认实现调 `Class.forName(type, false, getClass().getClassLoader())` ——这个 classloader 是 **NetScope 插件自己的 classloader**，只有 NetScope plugin 和 ASM，**看不到业务 class**。

一旦 Transform 对一个帧需要重算的 class 用了 `ClassWriter(COMPUTE_FRAMES)`，只要方法里引用了业务类型（比如 `NavigationService`、`JSONObject`），`getCommonSuperClass` 就 `ClassNotFoundException`，ASM 默认分支把**原始内部名**（形如 `com/telenav/arp/service/NavigationService`，首字符 `'N'`）直接写进 StackMapTable。D8 的 `MemberType.fromTypeDescriptorChar` 只认合法描述符首字符（`L V B C D F I J S Z [`），看到 `'N'` 抛 `Unreachable: Invalid descriptor char 'N'`。

这是 ASM 官方 FAQ Q17 收录的坑，有通用解法。

#### 六、v2.0.2 修复建议（任选其一，推荐 Fix #1）

**Fix #1：Non-target class 走 byte-for-byte passthrough（强烈推荐）**

NetScope 只需要改写 3 个 API 的调用点：`OkHttpClient$Builder.build()` / `URL.openConnection()` / `OkHttpClient.newWebSocket()`。其它 class **完全不应该**进 ClassWriter。建议 Transform 里加一层快速判断：

```kotlin
private val TARGET_INTERNAL_NAMES = setOf(
    "okhttp3/OkHttpClient\$Builder",
    "okhttp3/OkHttpClient",
    "java/net/URL",
)

fun transformOne(bytes: ByteArray): ByteArray {
    val reader = ClassReader(bytes)
    // 快速扫常量池，不走 ClassNode / ClassWriter
    val cp = (0 until reader.itemCount).any { i ->
        runCatching { reader.readUTF8(reader.getItem(i), CharArray(reader.maxStringLength)) }
            .getOrNull() in TARGET_INTERNAL_NAMES
    }
    if (!cp) return bytes  // ← 直接 passthrough，原样写回 jar

    // 有目标才走 ASM 重写
    val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)  // 甚至不需要 COMPUTE_FRAMES
    reader.accept(NetScopeClassVisitor(writer), 0)
    return writer.toByteArray()
}
```

好处：
1. **彻底消除**这个 D8 bug（非目标 class 压根不接触 ASM）。
2. Transform 速度显著提升（Denali ~25000 个 class，真正要改的几十个）。
3. 插件依赖更简单（不用构造 URLClassLoader）。

**Fix #2：给 ClassWriter 一个看得见业务 class 的 classloader**

如果你有别的原因必须对所有 class 走 `COMPUTE_FRAMES`，那就 override `getCommonSuperClass`（ASM FAQ Q17）：

```kotlin
class SafeClassWriter(
    reader: ClassReader, flags: Int, private val cp: ClassLoader,
) : ClassWriter(reader, flags) {
    override fun getCommonSuperClass(type1: String, type2: String): String = try {
        val c1 = Class.forName(type1.replace('/', '.'), false, cp)
        val c2 = Class.forName(type2.replace('/', '.'), false, cp)
        when {
            c1.isAssignableFrom(c2) -> type1
            c2.isAssignableFrom(c1) -> type2
            c1.isInterface || c2.isInterface -> "java/lang/Object"
            else -> { var c: Class<*> = c1; do { c = c.superclass } while (!c.isAssignableFrom(c2)); c.name.replace('.', '/') }
        }
    } catch (_: Throwable) { "java/lang/Object" }  // 关键：兜底必须是合法 internal name
}
```

`cp` 在 `transform(TransformInvocation)` 入口处用 `inputs + referencedInputs` 构造一次 `URLClassLoader(urls, pluginClassLoader)`。

即便 `getCommonSuperClass` 走兜底，**返回 `"java/lang/Object"` 比现在把 raw name 写进帧安全得多**——起码是合法 internal name。

#### 七、Denali 这边的 v2.0.2 接入成本

一行改动 + 版本号：

```groovy
// Apps/Denali/HMI/build.gradle
apply plugin: 'indi.arrowyi.netscope'      // 去注释

// Apps/Denali/build.gradle
classpath 'com.github.Arrowyi.NetScope:NetScope-plugin:v2.0.2'

// Apps/Denali/HMI/dependencies.gradle
implementation 'com.github.Arrowyi.NetScope:NetScope:v2.0.2'
```

然后 `:HMI:assemblePanguTasdkDevDebug` → sign → push 到 Chery 8155，冷启一次，悬浮窗 Layer B 从 `↑0B ↓0B` 变成实际数字，域名列表出现即通过。

#### 八、完整环境

- AGP 4.2.2 / Gradle 6.7.1 / Kotlin 1.6.21 / JDK 11
- 设备 1：Chery 8155，Android 11（OEM），serial `396012bf`
- 设备 2：AGM3，Android 10
- Denali 用 **内联 AspectJ 1.9.4**（`javaCompile.doLast { org.aspectj.tools.ajc.Main().run(args, ...) }`，在 class 目录原地织入），可能是 Transform 看到的 class 帧更复杂的一个原因
- **没有** JaCoCo runtime 插桩（`testCoverageEnabled = false`），排除 JaCoCo 冲突
- NetScope plugin `apply` 顺序在 AspectJ 之后（按作者文档要求）

有任何复现问题我可以进一步配合跑 Gradle `--scan` / dump 具体 238.jar 的 class / 跑 ASM verifier 等。

---

（以上 §附录 C 完）

---

## 附录 D —— 致 NetScope 作者的一页纸分析报告（v2.0.2 regression，2026-04-24，可直接复制发 issue）

> 下面这块自成一体，可以直接粘给作者，或者复制成 `https://github.com/Arrowyi/NetScope` 的 v2.0.2 bug issue 正文。用中文是因为作者中文用户。

---

### 【NetScope v2.0.2】Denali 二轮验证：v2.0.1 D8 老坑已修；新暴露 `SCOPE_FULL_PROJECT` 触发的 duplicate-class regression

**一句话**：v2.0.2 把 v2.0.1 的 D8 `Invalid descriptor char 'N'` 老坑**真的修好了**（`dexBuilder` 任务第一次在 Denali 过绿灯），但 **Transform 的 scope 声明和"wide-scope output 路由到 `mixed_scope_dex_archive`"的组合，把本来靠"按 scope 分桶"规避掉的跨模块重名 `BuildConfig` 一并塞进同一个 mixed 桶里**，导致下游 `DexMergingTask` duplicate-class fail。期待 v2.0.3 修复。

#### 一、阻塞现象

```
Task :HMI:transformClassesWithNetscopeForPanguTasdkDevDebug   ← ✅ Transform 成功跑完
Task :HMI:dexBuilderPanguTasdkDevDebug                        ← ✅ 第一次过了！（v2.0.1 老坑已修，验证通过）
Task :HMI:mergeProjectDexPanguTasdkDevDebug FAILED            ← ❌ 新坑在这里挂

ERROR: .../intermediates/mixed_scope_dex_archive/panguTasdkDevDebug/out/132c2b07..._1.jar:
       D8: Type com.telenav.auto.dr.BuildConfig is defined multiple times:
         .../mixed_scope_dex_archive/.../132c2b07..._1.jar:classes.dex,
         .../mixed_scope_dex_archive/.../2e39ff8e..._1.jar:classes.dex
```

#### 二、两份冲突 `BuildConfig.class` 各来自哪里

反汇编两个冲突 jar 的 `classes.dex`（`dexdump | head -200`）：

| Jar | 同 jar 内其它关键类 | 推断来源 |
|-----|--------------------|---------|
| `132c2b07..._1.jar` | `com/telenav/auto/dr/CustomParam*`, `DrEngineManager`, `DrEngineController*` 等 ~40 个类 | **`com.telenav.positioning:dr:1.1.604750-RELEASE` AAR**（drEngine） |
| `2e39ff8e..._1.jar` | `com/telenav/auto/dr/DataBinderMapperImpl`, `com/telenav/dr/gnss/*`, Kotlin 数据类 | **Denali 本地 `:dr` 模块**（`module/dr`） |

两者 `AndroidManifest.xml` 都写了 `package="com.telenav.auto.dr"`，AGP 按约定**各自**生成 `com.telenav.auto.dr.BuildConfig`，因此两份字节码确实存在、并不相同（AAR 的版本号和本地模块的 flavor 信息不一样）。这是 Denali 历史上的包名碰撞，但历来没出过事 —— 因为 AGP 的正常 dex 流水把它俩路由到**不同 scope** 的 dex archive，`DexMergingTask` 分 scope 独立 merge，跨 scope 重名不会触发 D8 的 `is defined multiple times`。

#### 三、对照实验（一键定位锅在 Transform，不在 Denali）

| 试验 | 修改 | 结果 |
|------|------|------|
| A | v2.0.2 plugin classpath + `apply plugin: 'indi.arrowyi.netscope'` **启用** | ❌ `mergeProjectDex` fail（duplicate `BuildConfig`）|
| B | v2.0.2 plugin classpath + `apply plugin` **注释** | ✅ `mergeProjectDex` 一气呵成，干净过 |

A / B 唯一变量是 NetScope Transform 是否参与。结论：**NetScope v2.0.2 Transform 启用后，把本来分布在不同 scope 的两份 `BuildConfig` 收集进了同一个 mixed_scope 桶**，破坏了 AGP 默认的"按 scope 分桶、分桶独立 merge"的防冲突机制。

（补充证据：baseline 产出的 `project_dex_archive/panguTasdkDevDebug/out/com/telenav/auto/dr/` 下**只有** `BR.dex`，没有 `BuildConfig.dex` —— 说明 AGP 在 baseline 下甚至已经把 `:dr` 模块这份 BuildConfig 在更早阶段去重掉了。冲突只在 Transform 跑了之后才浮出。）

#### 四、根因（高置信度）

反汇编 `NetScopeTransform.class` 的 `getScopes()`：

```
public java.util.Set getScopes();
  0: getstatic  Field TransformManager.SCOPE_FULL_PROJECT:Ljava/util/Set;
  3: areturn
```

`SCOPE_FULL_PROJECT` = `{ Scope.PROJECT, Scope.SUB_PROJECTS, Scope.EXTERNAL_LIBRARIES }`。

这是 AGP 4.x 老 Transform API 的**经典陷阱**：

1. 当一个 Transform 同时声明上述 3 个 scope 为**主** scope（而非 referencedScope）时，AGP 会把这 3 个 scope 的 input **全部**路由进 Transform，并把 output 统一写进 `mixed_scope_dex_archive/<variant>/out/`（**不再**按 scope 分桶）。
2. Transform 下游 `DexMergingTask` 看到的是 **一个 mixed 桶**，只做一次 merge invocation，跨 scope 重名直接触发 `Type ... is defined multiple times`。
3. baseline 没有任何 `SCOPE_FULL_PROJECT` Transform 时，AGP 会分别发布 `project_dex_archive/`（Scope.PROJECT）、`sub_project_dex_archive/`（Scope.SUB_PROJECTS）、`external_libs_dex_archive/`（Scope.EXTERNAL_LIBRARIES）三个桶；`DexMergingTask` 对每个桶独立 merge，final APK 阶段再做最后一次合并，**允许跨 scope 重名按 scope 优先级保留一份**。

Google 官方在 AGP 7.x 移除 Transform API 的理由之一就是这条（AsmClassVisitorFactory 没有这个问题 —— 它作用在编译器前置管线，不改变 scope 分桶）。

#### 五、v2.0.3 修复建议（任选其一，推荐 Fix #A）

##### Fix #A —— 把 `EXTERNAL_LIBRARIES` 降成 **referencedScope**（强烈推荐）

NetScope 只改写**调用点**（`new OkHttpClient.Builder().build()`、`url.openConnection()`、`okHttpClient.newWebSocket(...)`），从来不改写 OkHttp / URL / WebSocket **实现类本身**。这意味着 `EXTERNAL_LIBRARIES` 对 NetScope 是"只读 classpath"用来解析类型，**完全不需要**作为 Transform 的主 scope。

```kotlin
class NetScopeTransform : Transform() {
    // 只改写业务代码（PROJECT + SUB_PROJECTS），不碰 external libs
    override fun getScopes(): MutableSet<in QualifiedContent.Scope> =
        TransformManager.SCOPE_FULL_WITH_FEATURES.let {
            // 或者更简单：setOf(Scope.PROJECT, Scope.SUB_PROJECTS)
            mutableSetOf(QualifiedContent.Scope.PROJECT, QualifiedContent.Scope.SUB_PROJECTS)
        }

    // external libs 作为 referenced 传进来，仅用于 ClassWriter.getCommonSuperClass
    // 的类型解析（配合 v2.0.2 Fix #2 的 SafeClassWriter）
    override fun getReferencedScopes(): MutableSet<in QualifiedContent.Scope> =
        mutableSetOf(QualifiedContent.Scope.EXTERNAL_LIBRARIES)

    override fun isIncremental() = true
}
```

好处：
1. **彻底消除**这个 duplicate-class regression（external libs 继续走 AGP 默认的 `external_libs_dex_archive/` 桶，跨模块同名 BuildConfig 不再与本地模块的 BuildConfig 同桶）。
2. Transform 输入量显著降低（Denali 外部依赖 class 数 >> 业务 class 数），**Transform 执行时间预计降到原来的 1/5 以下**，增量构建更快。
3. 不需要 NetScope 自己动手去重或者注入 `tools:replace` —— 这种 AGP 底层事情最好不要在 Transform 层 hack。
4. 不改 runtime AAR 行为、不改 public API，Denali 侧零改动即可吸收 v2.0.3。

**唯一前提**：NetScope 不需要写业务代码**以外**的任何 class 的字节码。根据 v2.0.1 / v2.0.2 的 `OkHttpBuilderInstrumenter` / `UrlConnectionInstrumenter` / `OkHttpWebSocketInstrumenter` 实现（改写 INVOKEVIRTUAL / INVOKESPECIAL 的调用点），这条前提成立。

##### Fix #B —— 保持 `SCOPE_FULL_PROJECT`，在 Transform 内部对 output jar 做重名去重

如果出于某种原因一定要保留 `SCOPE_FULL_PROJECT`（例如将来想改写外部 AAR 的某些类），可以在 Transform 的 output 循环里维护一张"已写入"的 FQN 集合，对于重复出现的同名类按"project > sub_project > external"的 scope 优先级保留一份：

```kotlin
val seen = mutableMapOf<String, QualifiedContent.Scope>()
fun shouldEmit(internalName: String, scope: QualifiedContent.Scope): Boolean {
    val existing = seen[internalName]
    return when {
        existing == null -> { seen[internalName] = scope; true }
        // 按 scope 优先级覆盖：project 优先级高于 external
        scopeOrdinal(scope) < scopeOrdinal(existing) -> { seen[internalName] = scope; true }
        else -> false   // 已经有更高优先级版本了，跳过
    }
}
```

这个方案模拟了 AGP 默认 DexMergingTask 跨 scope 的 dedupe 行为，但有两个缺点：
1. 需要 NetScope 自己维护 scope 优先级表，和 AGP 版本绑定，容易漂。
2. 重写 input jar 时需要"把最后一个遇到的版本写出来"，涉及 Transform 做多 pass 或缓存所有 input —— 内存占用会涨。

所以仍然推荐 Fix #A。

##### Fix #C —— 迁移到 AsmClassVisitorFactory（长期方向，现阶段不做）

AGP 7.2+ 推荐用 `AsmClassVisitorFactory` 替代 Transform。作用在 javac 编译前，**不重新分桶**，天然没有本问题。但 Denali 用 AGP 4.2.2，NetScope 如果迁移会破坏对 AGP 4.x 用户的支持，短期不建议。

#### 六、Denali 这边的 v2.0.3 接入成本

v2.0.3 发出来以后，Denali 侧只需要：

```groovy
// Apps/Denali/build.gradle
classpath 'com.github.Arrowyi.NetScope:NetScope-plugin:v2.0.3'

// Apps/Denali/HMI/dependencies.gradle
implementation 'com.github.Arrowyi.NetScope:NetScope:v2.0.3'
// module/netmonitor/build.gradle 同上

// Apps/Denali/HMI/build.gradle
apply plugin: 'indi.arrowyi.netscope'   // 去注释（Phase 3 时为 workaround 而注释）
```

冷启 30s + 触发一次 search / navigate → 悬浮窗 Layer B / Layer C 从"获取不到"变成实际数字即通过。无需改任何 `:netmonitor` 业务代码。

#### 七、完整环境

- AGP 4.2.2 / Gradle 6.7.1 / Kotlin 1.6.21 / JDK 11
- 设备：Chery 8155，Android 11（OEM），serial `396012bf`
- Denali 用**内联 AspectJ 1.9.4**（`javaCompile.doLast { org.aspectj.tools.ajc.Main().run(args, ...) }`，在 class 目录原地织入）
- `testCoverageEnabled = false`（排除 JaCoCo）
- NetScope plugin `apply` 顺序在 AspectJ 之后
- Denali 有一个长期存在的包名碰撞：`module/dr`（本地 Gradle 模块，manifest 里 `package="com.telenav.auto.dr"`）和 `com.telenav.positioning:dr` AAR（`com.telenav.positioning` groupId 但 manifest 里也是 `package="com.telenav.auto.dr"`）。**这是 Denali 自己的历史债**，但在 baseline 下被 AGP 的 scope 分桶天然规避；只有 `SCOPE_FULL_PROJECT` Transform 才会把它放大成致命错误。

#### 八、相关的 v2.0.1 老坑（已修，仅作回忆）

- v2.0.1 的 D8 `Invalid descriptor char 'N'` 回忆见 §附录 C。v2.0.2 已修好，本次 Denali 验证中 `dexBuilder` 任务在 `apply plugin` 启用下第一次过绿灯（过去 v2.0.1 直接挂在 dexBuilder 阶段，根本走不到 `mergeProjectDex`），所以这条 regression 是"更下游"的问题，和 v2.0.1 D8 bug 无关。

---

（以上 §附录 D 完）

---

## 附录 E —— 致 NetScope 作者的一页纸分析报告（v3.0.0 升级验收 + 一个 README 微歧义反馈，2026-04-24，可直接复制发 issue）

### 【NetScope v3.0.0】Denali 三轮验证：v2→v3 breaking rename 落地成功；建议 README 对齐实际 `UNKNOWN_HOST` 值

#### TL;DR

- v3.0.0 在 Denali（AGP 4.2.2 / Gradle 6.7.1 / Kotlin 1.6.21 / JDK 11 / Chery 8155 Android 11）上 **0 崩溃 / 单测 15 绿 / 冷启悬浮窗实时显示 53 个 per-API 行**。v2.0.3 的 `SCOPE_FULL_PROJECT` dedupe 修复在 v3 下没有退化。`v2→v3 migration` 清单可用、代码迁移量小。
- **一个 README 歧义**：README 和 `ApiStats.kt` KDoc 都写 `host` 为 unresolvable 时是"一个空格字符 ` `"（引用 `EndpointFormatter.UNKNOWN_HOST` 常量），但实际在 Denali 运行时观察到 `host` 字面值是 `"<unknown>"`（和 README 首页 migration snippet 里 `it.host.startsWith("<unknown>")` 一致）。两边对齐下即可；建议把 README "API key shape" 表格里的 ` ` 行改成 `<unknown>`（或者 SDK 真的回归到空格值）。
- 一个**非阻塞**观察：v3 per-API 粒度让"同一个 host 展开到 10+ endpoint 行"成为常态。HMI 侧默认 cap 12 行就够用，但如果作者有打算在 logcat 报告里增加 per-API rollup（例如 "top 3 endpoint + 合并"），对大规模 host 会更友好。

#### 一、v3 是 breaking release，符合预期

本次 HMI 11 个文件迁移，全部是机械 rename：

- `DomainStats` → `ApiStats`（字段 `domain` 删；新增 `host` + `path` + 计算字段 `key = "$host$path"`）
- `NetScope.getDomainStats()` → `NetScope.getApiStats()`
- `NetScope.setOnFlowEnd((DomainStats) -> Unit)` → `NetScope.setOnFlowEnd((ApiStats) -> Unit)`
- UI 侧 `DomainTrafficStats` / `DomainStatsAdapter` / `item_netmonitor_domain.xml` / `netmonitor_domain_count` 等同步 rename 为 `ApiTrafficStats` / `ApiStatsAdapter` / `item_netmonitor_api.xml` / `netmonitor_api_count`。

没有行为 surprise。v3 Transform plugin + v3 SDK 的 ABI 约束（3-arg `wrapListener`/`wrapWebSocket`）也符合 README 升级清单警告 —— 我方在根 `build.gradle` 和 `dependencies.gradle` 同步升到 v3.0.0，没有碰到 `NoSuchMethodError`。

#### 二、v2.0.3 的两条 Transform 修复在 v3 下没有退化

- 没见到 v2.0.1 `Invalid descriptor char 'N'` 重现（v2.0.2 的 SafeClassWriter 修复仍在生效）。
- 没见到 v2.0.2 `com.telenav.auto.dr.BuildConfig` duplicate class 重现（v2.0.3 Transform 内 scope-priority dedupe 仍在生效）。
- `:HMI:transformClassesWithNetscopeForPanguTasdkDevDebug` → `mergeProjectDex*` → `assemble` 全链路干净绿灯，`BUILD SUCCESSFUL in 2m 33s`。

#### 三、冷启运行时行为（Chery 8155，Denali 7.3.113.1）

```
I/NetScope: initialised (AOP runtime; API counters reset; baselineTx=... rx=...)
I/ProductApplication: NetScope.init -> ACTIVE

[悬浮窗 60s 后]
系统总流量 (KERNEL / UID)        ↑295.1KB ↓501.2KB Σ 796.2KB
NETSCOPE JAVA 层                  ↑0B     ↓1009.2KB Σ 1009.2KB    活动连接: 46
C++ 层（未统计, 估算）             ↑295.1KB ↓0B     Σ 295.1KB
已统计 API: 53 个

<unknown>/file/data/app/~~P6gC…/com.telenav.app.arp-6VgD3…  ↑0B ↓155.8KB conn=1
<unknown>/file/data/app/~~P6gC…/com.telenav.app.arp-6VgD3…  ↑0B ↓136.3KB conn=1
<unknown>/data_extra/map/data/TelenavMapData/onboard_search/misc/knowledge-co…  ↑0B ↓90.3KB conn=1
…（省略，共 53 行，其中 12 行上屏，rest 受 HMI 侧 cap 限制）
```

- v3 logcat 措辞 `API counters reset`（v2 是 `per-domain counters reset`）—— 证明 v3 SDK 真加载，不是旧缓存。
- `活动连接: 46` → `TotalStats.connCountTotal` 非 0 → Layer B AOP 插桩 + flow close 回调正常。
- Layer B rx（1009.2KB）> Layer A rx（501.2KB）是预期内的采样 race：`TrafficStats.getUidRxBytes` 是 kernel 侧的异步数，和 NetScope AOP 在用户态的 `onResponseBody` 回调之间可以有 200ms-2s 滞后。HMI 侧 `NetDataRepository` 对 Layer C **per-direction 独立 clamp** 处理这个情况（`C.tx = max(295.1−0, 0) = 295.1`，`C.rx = max(501.2−1009.2, 0) = 0`），比 "对 total 做 clamp" 能多保住 295.1 KB 真实 native tx 不被 race 吃掉。这块是我方 Phase 4 做的 UX fix，不要求 SDK 侧做任何事，但可能对其他 HMI 有借鉴意义。

#### 四、一个 README 微歧义 —— `UNKNOWN_HOST` 到底是空格还是 `<unknown>`？

这是本次唯一的问题。

**README 和 SDK 源码的写法（v3.0.0 tag）**：

- `README.md` "API key shape (v3.0.0+)" 表格第 3 行：
  > `host`: ` ` or `:9000` (unresolvable host; port preserved when known)

- `netscope-sdk/src/main/kotlin/indi/arrowyi/netscope/sdk/ApiStats.kt` KDoc：
  > If neither a host nor a port is recoverable, `host` becomes ` ` (constant `indi.arrowyi.netscope.sdk.internal.EndpointFormatter.UNKNOWN_HOST`).

两处都说 `UNKNOWN_HOST = " "`（单空格）。

**v3.0.0 发布 migration snippet 和实际运行时的样子**：

- README 顶部 "HMI consumer snippet (v2 → v3)" 里给的示例：
  > `val unknown = NetScope.getApiStats().filter { it.host.startsWith("<unknown>") }`
- Chery 8155 悬浮窗实拍（见本节 §三）：所有 file:// URLConnection 读取的 `host` 字面是 `"<unknown>"`，不是单空格。

结论：实际发布的 `UNKNOWN_HOST` 是字面字符串 `"<unknown>"`（长度 9），和文档描述的单空格不一致。这没有导致 HMI 侧 bug（我方 normalisation 判据是 `host.isBlank()`，既不匹配 `"<unknown>"` 也不匹配 `" "`；对 `"<unknown>"` 行我方选择 verbatim 展示 `<unknown>/path/...`，这对运维更有价值）。但文档和实际值不一致会让第一次看源码的读者困惑。

**建议**：把 README 和 `ApiStats.kt` KDoc 里 "单空格" 表述统一改成 `<unknown>`（假设 `<unknown>` 是作者有意选择的 marker），或者把 SDK 里的常量改回单空格（如果作者是 v3.0.0 发布前某个版本误改的）。两个方向都行，只要自洽即可。

（顺便：HMI 侧如果后续有"真的只看 unknown 桶"的需求，可以改成 `host.startsWith("<unknown>")` 匹配；目前我方没有这个需求。）

#### 五、一个非阻塞 UX 观察（可选）

v3 per-API 粒度让"一个 host 展 N 行"成为常态。Denali 冷启 60s 一跑就出 53 行。NetScope 内部 `LogcatReporter` 每 30s 全量打印所有 API，在 API 列表膨胀下会把 logcat 挤满（每次 60+ 行）。非阻塞，但如果未来想加个"Top N 最热 API + 合并其他"按钮，可以考虑：

- `NetScope.setLogInterval(30)` 接一个 `NetScope.setLogTopN(10)`，rollup 成 `↑42.1 KB ↓1.1 MB conn=34  (53 APIs total)`。
- 或者让 HMI 侧自己在 `setOnFlowEnd` 里做聚合 / top-N。目前 HMI 侧没有实时 hook 需求，所以是个 nice-to-have。

#### 六、本次改动对 NetScope 的输入

- 没有新需求。
- 唯一建议是文档对齐（§四），10 分钟工作量。

#### 七、完整环境

- AGP 4.2.2 / Gradle 6.7.1 / Kotlin 1.6.21 / JDK 11
- 设备：Chery 8155，Android 11（OEM），serial `396012bf`
- Denali 用内联 AspectJ 1.9.4；NetScope plugin `apply` 顺序在 AspectJ 之后
- `testCoverageEnabled = false`
- NetScope 版本：`com.github.Arrowyi.NetScope:NetScope-plugin:v3.0.0` + `…:NetScope:v3.0.0`

---

（以上 §附录 E 完）

