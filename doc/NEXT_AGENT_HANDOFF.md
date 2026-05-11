# 给下一任 Agent 的接手说明

> **目的**：把本项目 2 次会话（2026-04-23 + 2026-04-24）的调研成果**一次性索引化**，让下一任 agent 5 分钟之内能判断"此刻手里该做什么"。
>
> 本文件**只是 index**，不重复细节。细节分别在：
> - `doc/ASDK_HTTPCLIENT_CRASH_HANDOFF.md` (~66 KB)：HONOR AGM3 `asdk.httpclient` SIGSEGV 调研全过程，§1–§12.8。
> - `doc/TRAFFIC_MONITOR_TIERED_PLAN.md` (~30 KB+)：流量监控方案设计。**2026-04-24 晚已 pivot**，顶部的 PIVOT 注记 + `NETSCOPE_AOP_REQUEST.md` 是最新版；正文 Tier 1–4 仍然保留但叙事语境换成 Layer A/B/C。
> - `doc/NETSCOPE_AOP_REQUEST.md` (~9 KB，**新增**)：给 NetScope 作者的正式需求稿（R1 Java-only 变体、R2 AOP design doc、R3 manifest 规范）。**此文档替代**了 `TRAFFIC_MONITOR_TIERED_PLAN §Tier 3.4` 的一段话模板，是当前对外唯一出口。
>
> **本文件最后一次更新**：2026-04-24 晚（CST）。

---

## 0. 如果你只有 5 分钟

读这四段就够：
1. **结论**：本项目**不在追查 NetScope bug 了**，NetScope 代码层已到极限。下一步分三条：**(a) Layer A 整体流量统计**（`TrafficStats` + `NetworkStatsManager`，HMI 自己做，立即可动手）；**(b) Layer B Java per-domain 分域统计**（阻塞于 NetScope 交付 R1 Java-only 变体 + R2 AOP design doc）；**(c) Layer C native per-domain**（长期推动 Telenav，不阻塞发版）。
2. **为什么是这个结论**：跨 **AGM3 (Android 10) 和 Chery 8155 (Android 11) 两台独立设备**的对照实验一致收敛 —— `§12.7 D60` 证伪 NetScope 运行时 = 放大器；`§12.8.3` 锁定放大器 = APK 静态存在的 8 项扰动；**Chery 8155 反向独立验证**：NetScope 静态剔除 → 3×180s=0 崩 vs 静态在 → 7 crash/180s。根因 = `tn::http::client::*` session-级 UAF（Telenav `libFoundationJni.so` 自己的 race，与 NetScope 无关）。
3. **当前用户正在等的决策**：见本文件 §5 "Open Decisions"（**2026-04-24 晚 pivot 后已更新**）。在用户点之前**不要**擅自动手改源码。
4. **如果用户叫你"继续"**：默认理解为"开干 Layer A"（`TrafficStats` / `NetworkStatsManager` 基线实现，保留当前 `NetScopeStub.kt` 替身），从 §6.A 的清单开始。**不要**重新开启原 Tier 1 里"5 个 OkHttpClient 点接入 EventListener"那条分支 —— 那已经是 Layer B 的工作，阻塞于 NetScope R1/R2。

---

## 1. 项目目标（两位一体）

| 目标 | 状态 | 主文档 |
|---|---|---|
| 搞清楚 AGM3 `asdk.httpclient` SIGSEGV 的根因 | **已基本定位**：`libFoundationJni.so` 里 `tn::http::client::ClientImpl / Session` 生命周期 race；放大器是 APK 静态层面，不是 NetScope 运行时 | `ASDK_HTTPCLIENT_CRASH_HANDOFF` §1 / §12.6 / §12.7 / §12.8 |
| 给应用做**流量消耗监控** | **方案已设计**，Tier 1 可立即落地（~3 人日），Tier 2 最干净但要 Telenav 配合 | `TRAFFIC_MONITOR_TIERED_PLAN` 全文 |

两个目标的**耦合点**：`:netmonitor` 模块既是流量监控的载体，也是 crash 放大器的源头。最终两个目标会合并成一件事：**重写 `:netmonitor` 成纯 Java 实现**，顺便把放大器几乎削平。

---

## 2. 时间线（会话级）

| 日期 | 里程碑 |
|---|---|
| 2026-04-23 | 首次 agent session；发现 `loadonly` 必崩、NetScope 侧 `DEBUG_ULTRA_MINIMAL` 仍崩；把问题定性为"HMI/Telenav 栈内"。 |
| 2026-04-24 AM | 路径 B（`.so` 扫描证伪第二套 hooker）、§12.1–§12.5（A 配置 3×180s=0 崩）、§12.6（锁定 `asdk.httpclient` 归属到 `libFoundationJni.so`）。 |
| 2026-04-24 PM | **G 路径决定性实验**：`debug.netscope.delay_ms=60000` 下 2/3 崩在 dlopen 前 → 证伪"NetScope 运行时 = 放大器"。 |
| 2026-04-24 下午后段 | 用户把 `compileJavaSdk` 切到 `true` 本地重编，同 APK 连崩 6 次后 1 次冷启 21min+ 稳定（概率长尾），`libFoundationJni.so` MD5 仍与历史崩溃版一致 → §12.8。 |
| 2026-04-24 晚 | 用户提"C++ 规避不了就放弃 native 分域"需求 → 产出 `TRAFFIC_MONITOR_TIERED_PLAN.md`（4 层方案）。 |
| 2026-04-24 晚（新增）| **Chery 8155 (Android 11) 跨设备验证**：用 `NetScopeStub.kt` 替身把 NetScope 从 APK 里物理拔掉，3×180s 冷启 soak **0 崩**（同机型 NetScope 静态在对照组：N=1 观察 7 crash / 180s）。`libFoundationJni.so` MD5 保持 `02cd184e930f63c7bc26fb32e2452e7e`，与 AGM3 历史崩溃版一字节不差。跨设备复现 §12.5 / §12.8 的结论。|
| 2026-04-24 晚（新增）| 用户 **pivot 产品方向**：NetScope 转型为 Java 层 AOP 流量统计库，**去 native hook 化**。产出 `NETSCOPE_AOP_REQUEST.md`（正式需求稿，R1/R2/R3），同时在 `TRAFFIC_MONITOR_TIERED_PLAN.md` 顶部加 PIVOT 注记，重划分 Layer A / B / C。|
| （下一步）| 等用户：(i) 点发 `NETSCOPE_AOP_REQUEST.md` 给 NetScope 作者；(ii) 点 Layer A 是否开写；(iii) 点反向对照实验（Chery 8155 上 N=3 跑 NetScope 静态在的对照组，把 N=1 样本堵死为 N=3）。|

---

## 3. 代码现状（动手前必须知道的）

### 3.1 `:netmonitor` 模块当前文件清单

```
NavHome/module/netmonitor/
├─ build.gradle                             ← 还在依赖 'com.github.Arrowyi:NetScope:b500638'，Tier 1 需删
├─ src/main/AndroidManifest.xml             ← 声明了 NetMonitorService + NetMonitorInitializer（Startup provider meta-data）
└─ src/main/java/com/telenav/netmonitor/
    ├─ NetMonitorInitializer.kt            ← AndroidX Startup，带 debug.netmonitor.enabled kill-switch
    ├─ NetMonitorService.kt                ← 前台服务 + loadonly/baseline/off/trace/skip/... 诊断分支（Tier 1 要大幅简化）
    ├─ NetMonitorConfig.kt                 ← UI 参数（保留）
    ├─ NetDataRepository.kt                ← 当前从 NetScope.getDomainStats()/getHookReport() 拿数据（Tier 1 要重写成 Java/Android 系统 API）
    ├─ DomainTrafficStats.kt               ← data class（保留）
    ├─ FloatingWindowManager.kt            ← 浮窗（保留）
    └─ view/
        ├─ BubbleView.kt
        ├─ DomainStatsAdapter.kt
        ├─ FloatingWindowView.kt
        └─ FormatUtils.kt
```

### 3.2 被诊断过程加进来的"实验代码"（不是生产代码）

这些是 G 路径 / loadonly / baseline / ultra 等诊断模式的支架，**Tier 1 动工时要一起删掉**：

- `NetMonitorService.kt` 里的 `readDiagMode()` / `readDelayMs()` / `applyDiagnosticMode()` / `dumpHookReport()`
- `loadonly` / `baseline` / `trace` / `skip` / `both` / `ultra` / `ultra+trace` 六种分支 → Tier 1 全部删除
- `onHookStatusChanged(report: HookReport)` → 跟随 NetScope 依赖一起删

### 3.3 用户工作区 uncommitted 改动（别擅自提交）

```
 M NavHome/Apps/Arp/gradle.properties          ← compileJavaSdk=ture（typo, 应为 true；但属于用户意图，问完再改）
 M NavHome/Apps/Denali/HMI/dependencies.gradle ← +implementation project(':netmonitor')
 M NavHome/Apps/Denali/build.gradle
 M NavHome/Apps/Denali/gradle.properties       ← compileJavaSdk=true（正确）+ 加了公司代理配置
 M NavHome/Apps/Denali/settings.gradle         ← compileJavaSdk=true 分支里 include :android-common 等
 M NavHome/module/netmonitor/build.gradle      ← NetScope 依赖行被注释掉（Chery 8155 实验用），Layer A/B 发版策略定下来前不要 revert
?? NavHome/module/netmonitor/src/main/java/indi/arrowyi/netscope/sdk/NetScopeStub.kt  ← 本地 Stub 替身（给 :netmonitor 业务代码提供 NetScope API 签名的 inert 实现）。R1 交付后可删
?? NavHome/module/netmonitor/doc/NETSCOPE_AOP_REQUEST.md  ← 对 NetScope 作者的正式需求稿
?? NavHome/Apps/Denali/HMI/libs/*.aar          ← 新拉下来的预编译 aar，大概是为本地源码编译兜底用
?? NavHome/Apps/Denali/.claude/ / docs/superpowers/  ← agent 自己的东西
?? 各 module 的 .gradle/                       ← gradle 构建缓存，应进 .gitignore
```

**不要 git commit**，除非用户明确要求。

**关于 `NetScopeStub.kt` 的背景**：为了在 Chery 8155 上做"NetScope 静态剔除"实验，我方在 `netmonitor/build.gradle` 里把 `implementation 'com.github.Arrowyi:NetScope:b500638'` 注释掉，导致 `NetMonitorService.kt` / `NetDataRepository.kt` / `FloatingWindowView.kt` 里引用的 `NetScope`/`HookReport`/`NetScopeNative`/`Status`/`DomainStat` 全部失去符号。为了最小侵入地让 `:netmonitor` 仍能编译，我方在 `indi/arrowyi/netscope/sdk/NetScopeStub.kt` 提供了一份完整的 no-op 实现（同包名 + 同签名 + 所有方法返回默认值）。这是**临时过渡手段**，待 NetScope R1（Java-only 变体）交付后应删除该 Stub 文件 + 把 `build.gradle` 里注释改成新版本号。

### 3.4 关键第三方依赖的实际状况

| 依赖 | 引入方 | 运行时是否真的被用 | 备注 |
|---|---|---|---|
| `com.github.Arrowyi:NetScope:b500638` | `netmonitor/build.gradle:9`，**当前已注释**（Chery 8155 实验），由 `NetScopeStub.kt` 本地替身填补符号 | **完全未加载**（stub 模式下 `System.loadLibrary` 不会被调用）| 等 NetScope R1 交付后换成 Java-only 变体版本号，重新打开 |
| `com.bytedance:bytehook:1.1.1` + `shadowhook:1.1.1` | NetScope 的 transitive | **从来没被 dlopen**（§6-B 已证），三个 `.so` 是孤儿 | 当前实验下 APK 里**已经完全不含**（跟随 NetScope 依赖注释一起消失）；R1 交付后希望 NetScope 作者把 transitive 也剥掉 |
| `com.squareup.okhttp3:okhttp:3.x` | CheryPlatform / alexa-client / GoogleStreetView / telenav-sdk-base | 真的在用，是 Layer B EventListener 的挂载点 | NetScope R2（AOP design doc）交付后再埋点 |
| `com.squareup.retrofit2:retrofit:2.0.2` | evtripplanner / ArpHMI | 真的在用 | Retrofit 底层走 OkHttp，EventListener 统一覆盖 |
| `tasdk` / `libFoundationJni.so` etc. | `panguCompile files('libs/telenav-android-configuration-cherylts2-*.aar')` 或 `compileJavaSdk=true` 时从 `../../../../java-sdk-common/java_sdk` 源码 | 真的在用，70~90% 流量都走它 | 是 crash 的根因所在；Layer C 要加 API |

---

## 4. 测试与复现环境

### 4.1 硬件 + 系统

**主测设备（§12.1–§12.8 全部数据来自这台）**：
- **设备**：HONOR AGM3-W09HN
- **ROM**：EMUI 11 / Magic UI 4，系统 build `11.0.2.248C00`
- **Android**：10 (SDK 29)，arm64-v8a

**跨设备验证设备（2026-04-24 晚新增）**：
- **设备**：Chery 8155（OEM 车机），`adb` serial `396012bf`
- **Android**：11 (SDK 30)，arm64-v8a
- **`libFoundationJni.so` MD5**：`02cd184e930f63c7bc26fb32e2452e7e`（与 AGM3 同一字节）
- **已做的实验**：
  - NetScope 静态剔除（Stub 替身）+ kill-switch=0：**3 × 180s = 540s，0 崩**。
  - NetScope 静态在 + kill-switch=0：**N=1 观察 7 crash / 180s**（反向对照 N=3 尚未跑，列为 §5 的 Open Decision #3）。
- **其他 Android 10 arm64 机型**：路径 F 尚未跑，**建议优先级下降**（Chery 8155 已经给出跨机型确认，路径 F 的边际收益变小）。

### 4.2 构建 & 安装

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.jdk/Contents/Home
cd NavHome/Apps/Denali
./gradlew :HMI:assemblePanguTasdkDevDebug -q
adb install -r HMI/build/outputs/apk/panguTasdkDev/debug/HMI-pangu-tasdk-dev-arm64-v8a-debug.apk
adb shell appops set com.telenav.app.arp SYSTEM_ALERT_WINDOW allow  # 浮窗权限
```

当前工作区是 `compileJavaSdk=true` 状态，从本地 `../../../../java-sdk-common/java_sdk/` 源码编译 `:android-common / :arp-sdk / :arp-foundation / :map-poi / :adas / :system-interface` 五个 Java SDK 子模块。已验证：**产出的 `libFoundationJni.so` MD5 与历史崩溃 session 使用的一致**（`02cd184e930f63c7bc26fb32e2452e7e`），换句话说切换这个 flag**不影响 native bug**。

### 4.3 关键实验操作（copy-paste ready）

**冷启前彻底重置**：

```bash
adb shell am force-stop com.telenav.app.arp
adb shell "run-as com.telenav.app.arp rm -f shared_prefs/netmonitor_breaker.xml"
adb shell setprop debug.netmonitor.enabled 1        # 或 0（kill-switch）
adb shell setprop debug.netscope.diag loadonly      # 或 off / baseline / trace / ultra / skip / both
adb shell setprop debug.netscope.delay_ms 0         # 或 60000 等
adb logcat -c
adb logcat -v threadtime '*:V' > /tmp/ns_cold_$(date +%s).log 2>&1 &
adb shell 'am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity'
```

**进程信息**：

```bash
PID=$(adb shell pidof com.telenav.app.arp | tr -d '\r')
adb shell "run-as com.telenav.app.arp cat /proc/$PID/maps" > /tmp/maps_$PID.txt   # 非 root 拿 maps
adb shell "run-as com.telenav.app.arp ls -la shared_prefs/"                        # 看 breaker.xml
adb shell getprop | grep -E 'debug\.(net|ns)'                                      # 确认 props
```

**抓 tombstone**：

```bash
adb shell "dumpsys dropbox --print | grep -i SYSTEM_TOMBSTONE | tail -20"   # 看最近的 tombstone 列表
# 非 root 设备拿完整 tombstone 的最简单办法：
adb bugreport /tmp/bugreport_$(date +%s).zip
# 解压后 FS/data/tombstones/ 里有完整的原始 tombstone
```

**快速诊断脚本**（已存在）：

```bash
/tmp/tombstones/analyze.py       # 把 all_stones_raw.txt 里所有 asdk.httpclient crash 的 x8/x16/lr/pc/时间戳表格化
/tmp/tombstones/find_blr_x8.py   # 扫所有 .so 找 'blr x8' 字节码落在 offset 低 12 位 = 0xe30 的候选
/tmp/ns_loadonly/soak_A.sh       # A 配置 soak 驱动（3×180s）
/tmp/ns_loadonly/soak_delay.sh   # G 路径 D0/D60 驱动
```

### 4.4 Tombstone 特征指纹（核对是否是同一个 bug 用）

任意一次新 crash，只要这些都对上，就是**已知的同一个 bug**，不用重新分析：

| 字段 | 期望值 |
|---|---|
| thread name | `asdk.httpclient` |
| signal | 11 (SIGSEGV) / code 2 (SEGV_ACCERR) |
| `pc == x8` | ✓（不变量）|
| `x17` | `0x7329eb9620` 或 `0x7906072620`（同一函数，不同 ASLR）|
| `x16` 低 12 位 | `0x4c0`（`libFoundationJni.so` 的 `strlen` GOT entry）|
| `lr` 低 12 位 | `0xe34` |
| Abort message | `[<pid>]create DR Engine success, engineMode=1!`（**信息级日志被捕获，不是真 abort 点**，见 `HANDOFF §12.6.2`）|
| `pc` 所在区域 | `[anon:libc_malloc]`（堆，说明跳到了数据 → vtable 污染 / UAF）|

### 4.5 历史原始数据位置

- tombstone 合集：`/tmp/tombstones/all_stones_raw.txt`（58 条，包含本次安装的 6 次）
- 每个 `.so` 的反汇编：`/tmp/tombstones/libFoundationJni.disasm`（8MB，太大不入库）
- `.so` 文件抓取：`/tmp/tombstones/so/*.so`（从崩溃版 APK 抓的）
- G 路径所有 log：`/tmp/ns_loadonly/D0_*.log` / `D60_*.log` / `A_*.log`
- 当前 APK 的 `libFoundationJni.so`：`/tmp/libcompare/new_apk_libs/lib/arm64-v8a/libFoundationJni.so`（MD5 同上）
- Maps 快照：`/tmp/tombstones/maps_4835.txt` / `maps_4835b.txt`（pid 4835 = 当前 21min+ 长稳进程）

---

## 5. Open Decisions（**用户未点之前不要动手**）

**2026-04-24 晚 pivot 后已重写**。接手时先把 §5.1 / §5.2 / §5.3 的状态核一下（这是目前阻塞主线的三件事），再看 §5.4 / §5.5 的杂项。

### 5.1 `NETSCOPE_AOP_REQUEST.md` 发还是不发？

- 该文档已写好（`doc/NETSCOPE_AOP_REQUEST.md`）。
- **用户尚未点发** —— 接手 agent 默认**不发**，除非用户明确说"发给 NetScope 作者 / 发到 GitHub issue / 发到邮件"。
- 发送前的填空：文档 §9 "联系方式" 有两个 `_（由发出时填入）_` 占位符（联络人 + 邮件组 / 群）。发前问用户。
- **发之前可以做的自检**（如果用户问你"发之前我该确认什么"）：
  1. 让用户确认 `§5 R1/R2/R3` 是否准确表达意图。
  2. 让用户确认"底线"（§7 表格里"若你做不到 …"那几条）可接受。
  3. 让用户决定：是用 GitHub issue 还是邮件私发。

### 5.2 Layer A 是否现在就开写？

- Layer A = 只用 `TrafficStats` + `NetworkStatsManager`，HMI 自力更生、**不依赖 NetScope 任何交付**。
- 对应原 `TRAFFIC_MONITOR_TIERED_PLAN §Tier 1.3` 里的 `TotalTrafficSource` + 部分简化的 `NetDataRepository` + `NetMonitorService`。**不要**做原 Tier 1 里"5 个 OkHttpClient 点接入 EventListener" —— 那是 Layer B，阻塞于 NetScope R2。
- 如果用户说"继续 / 开写 / 实现" → 默认理解为 Layer A 开工，见 §6.A。
- 如果用户说"等 NetScope" → 保留当前状态（`NetScopeStub.kt` 替身 + `build.gradle` 注释），什么都别改。

### 5.3 Chery 8155 反向对照 N=3 是否现在跑？

- 当前 Chery 8155 上**只有**"NetScope 静态剔除 → 0 crash / 540s (N=3)" 这一侧 N=3 的数据。
- 反向侧"NetScope 静态在 → 崩" 只有 **N=1 (7 crash / 180s)** 这一个观察样本。
- **严格讲还没堵死"今天 Chery 8155 状态特别好"这个另类解释**，虽然跨 AGM3 对照已经从侧面支持了结论。
- **建议**但不强制：做 3×180s 反向对照（`build.gradle` revert 注释 + 删 Stub + 重编 + 重签 + 重装 + soak）。工作量 ~15 min。
- 如果发 `NETSCOPE_AOP_REQUEST.md` 之前用户想要"再硬一点的证据"，就跑 5.3。否则可以跳过（当前 AGM3 侧的 N=3 + 跨设备 N=3 已经足够说服 NetScope 作者）。

### 5.4 `NetworkStatsManager` 的 `PACKAGE_USAGE_STATS` 权限

（沿用前版决策，pivot 后不变）

- 接受 → Layer A UI 上可以有"近 24h / 7d 历史流量"视图
- 不接受 → Layer A 只保留"当前进程生命周期累计 + 实时速率"，功能缩水

### 5.5 `Apps/Arp/gradle.properties` 的 `compileJavaSdk=ture` typo

（沿用前版决策，pivot 后不变）

- 若是意图的 `true` → 改成 `true`
- 若用户**故意**让 Arp 子树继续走 AAR 预编译 → 不改（当前 Denali 这套 APK 不受影响）

### 5.6 Telenav Layer C 的 design doc 何时发？

- 原前版 Decision #2 改名沿用。Layer C = 原 Tier 2。
- 文件名建议：`doc/TELENAV_CLIENTSTATS_API_REQUEST.md`（尚未创建）。
- 内容 ≈ `TRAFFIC_MONITOR_TIERED_PLAN §Tier 2.2 + §2.3 + §2.4 + §2.5` 的合并 + 一页 C++ team 友好的 header。
- **优先级低于 §5.1 `NETSCOPE_AOP_REQUEST.md`** —— 因为 Layer C 不阻塞发版，可以并行等。

---

## 6. 行动清单（用户点了之后按此动手）

**2026-04-24 晚 pivot 后整章重写**，按 Layer A / B / C + NetScope 沟通四条独立线分段。

### 6.A Layer A 实施（如果用户说"开写 / 继续"，默认走这条）

按 `TRAFFIC_MONITOR_TIERED_PLAN §Tier 1.2–1.4` 的子集走，**但不要**做原 Tier 1 第 5 步（接入 EventListener）—— 那是 Layer B，阻塞于 NetScope R2。

1. **保持当前 gradle 状态**：`netmonitor/build.gradle` 里 `implementation 'com.github.Arrowyi:NetScope:b500638'` 仍然注释，`NetScopeStub.kt` 仍保留（等 R1 交付后再替换）。
2. **新增 1 个类**（其他 3 个类是 Layer B 的，**暂时不要加**）：
   - `src/main/java/com/telenav/netmonitor/stats/TotalTrafficSource.kt`（完整代码见 `TRAFFIC_MONITOR_TIERED_PLAN §Tier 1.3`）
3. **重写** `NetDataRepository.kt`：
   - 删除 Stub 里对 `NetScope.getDomainStats()` / `getHookReport()` 的调用（都是 no-op，删了也不影响）。
   - 数据源改为 `TotalTrafficSource`；`AggregatedData` 只填 `totalTx` / `totalRx`；`javaDomains` 暂返回 `emptyList()`；`nativeUnattributedTx/Rx` 先等于 `totalTx/Rx`（反正 Java 侧还没统计）。
4. **简化** `NetMonitorService.kt`：删除 `readDiagMode` / `readDelayMs` / `applyDiagnosticMode` / `dumpHookReport` / 7 种诊断分支。保留 kill-switch（`debug.netmonitor.enabled=0`）和 crash-loop breaker（`shared_prefs/netmonitor_breaker.xml`）。**注释任何引用 `HookReport` / `Status` 的代码**，但先不动 Stub 文件本身。
5. **更新 UI**（`DomainStatsAdapter.kt` / `FloatingWindowView.kt`）：只展示"总量 + '已识别 Java 域数 0 / Native 未分域 = 总量'" 两栏；Java per-domain 那栏先显示 "等待 NetScope AOP 上线"。
6. **soak 验证**：AGM3 + Chery 8155 各跑 3×180s，目标 0 次 `Fatal signal 11` 在 `asdk.httpclient` 线程。
7. 如果 soak 仍偶发崩 → **不是 Layer A 的失败**，可能是 `:netmonitor` 自己的 `+1 service / +1 startup entry / +~30 KB offset` 还是太多。兜底方案：
   - **方案 α**：做一个 `panguProduction` flavor，production build 里完全不 include `:netmonitor`（等同 A 配置，0 崩），只在 `dev` 里打包。
   - **方案 β**：把 `:netmonitor` 做成独立 `com.telenav.netmonitor.companion` APK，和主 app 用 ContentProvider 通信，主 app 里完全无 `:netmonitor` 痕迹。

### 6.B Layer B 接入（如果用户说"NetScope 新版到了"，阻塞于 R1 + R2）

**前置条件**：NetScope 作者已交付 R1（Java-only 变体）+ R2（AOP design doc）。

1. **切换依赖**：`netmonitor/build.gradle` 里把注释行改为新版本号（形如 `com.github.Arrowyi:NetScope:X.Y.Z-java-only`）。
2. **删除 Stub**：`rm src/main/java/indi/arrowyi/netscope/sdk/NetScopeStub.kt`（真实 NetScope 会覆盖这些符号，留着会导致重复符号编译错误）。
3. **按 R2 清单接入 EventListener**（具体方法由 NetScope 的 AOP design doc 决定）：
   - 如果是方案 A（AGP 字节码插桩）：零代码改动，验证插桩生效即可。
   - 如果是方案 B（显式 wrap）：在 5 处业务侧 `OkHttpClient.Builder()` 点加一行 `NetScope.wrap(...)`：
     - `alexa-client/AlexaClient.java`
     - `Apps/Arp/HMI/.../login/GetSecurityCodeBy.java`
     - `Apps/Rainier/tool/cloudtesting/.../HttpHelper.java`
     - `module/GoogleStreetView/.../StreetViewParser.java`
     - `Apps/Rainier/HMI/.../navigation/TaSdkComponentInitializerHelper.java`
   - 如果是混合方案：按 R2 清单的指示。
4. **新增 `JavaDomainStatsRegistry.kt` + 相关代码**（如果 NetScope R2 不自带"只读 snapshot API"而是要 HMI 自己维护 registry）：按 `TRAFFIC_MONITOR_TIERED_PLAN §Tier 1.3` 原代码照抄。
5. **更新 UI**：`DomainStatsAdapter.kt` / `FloatingWindowView.kt` 加第二栏"Java per-domain"，第三栏"Native 未归属 = 总量 − Java 分域之和"。
6. **soak 验证**：AGM3 + Chery 8155 各跑 3×180s；这次**关键**要验"新 NetScope Java-only 变体的 APK 静态足迹是否真的只剩原 §12.8.3 表的 #3~#7（即 #1 / #2 / #8 已归零）"。同时验 crash 触发率与 Layer A 基线一致（0 or 偶发）。

### 6.C Layer C 对接（如果用户说"Telenav HttpStatsJni 到了"，长期，不阻塞）

1. 当 Telenav 交付带 `HttpStatsJni` 的 `libFoundationJni.so` debug/release 版。
2. `NetDataRepository.kt` 增加第三个数据源（代码见 `TRAFFIC_MONITOR_TIERED_PLAN §Tier 2.3`）。
3. UI 加第四栏 `[native] per-domain`，第三栏 "Native 未归属" 随之缩小到 0。
4. **副作用**：Telenav 在加 API 的同一次 code review 里有极大概率顺手修掉 `tn::http::client::ClientImpl::shutdown()` 与 worker 的同步 race。修了 race 之后，**Layer B 阻塞于 NetScope R1 的硬约束也可以放松**（因为 race 没了，静态放大器也不再重要）。

### 6.D 发出 `NETSCOPE_AOP_REQUEST.md`（如果用户说"发 NetScope"）

1. 检查 `doc/NETSCOPE_AOP_REQUEST.md` §9 的两个 `_（由发出时填入）_` 占位符，让用户填。
2. 问用户发送渠道：GitHub issue on `Arrowyi/NetScope`、邮件私发、企业微信 / 钉钉？
3. 渠道确定后：
   - GitHub issue：把 §0 TL;DR + §5 正式需求 + §7 fallback 贴到一个 issue。正文引用我方仓内 `doc/ASDK_HTTPCLIENT_CRASH_HANDOFF.md` 时要带上**内部仓路径说明**（作者读不到）。
   - 邮件：全文附件即可。
4. 发出后记录到 `doc/NETSCOPE_AOP_REQUEST.md` §9 或顶部，注明"已于 YYYY-MM-DD 发出到 <渠道>"。

### 6.E 发出 Layer C 的 Telenav 需求（如果用户说"发 Telenav"）

1. 创建 `doc/TELENAV_CLIENTSTATS_API_REQUEST.md`，内容 ≈ `TRAFFIC_MONITOR_TIERED_PLAN §Tier 2.2 + §2.3 + §2.4 + §2.5` 的合并 + 一段 contact/priority header。
2. 附上 `ASDK_HTTPCLIENT_CRASH_HANDOFF §12.6.1 / §12.6.2 / §12.7.6` 的摘要作为背景。
3. 强调"加这个 API 的同时顺手 review `ClientImpl::shutdown()` vs worker 的同步路径，极可能正好把 AGM3 UAF 一起修了"。
4. 递交方式由用户决定（邮件 / JIRA ticket / Telenav 内部 gerrit）。

### 6.F 跑 Chery 8155 反向对照 N=3（如果用户说"堵死 N=1 样本"）

1. `git checkout -- NavHome/module/netmonitor/build.gradle` 把 NetScope 依赖注释 revert。
2. 暂时 `git mv NetScopeStub.kt NetScopeStub.kt.bak`（避免与真实 NetScope 符号冲突）。
3. 重编：`./gradlew :HMI:assemblePanguTasdkDevDebug -q`。
4. 签名：`/Users/bdgong/Downloads/chery8155_signjks/sign.sh`（替换 apk 名字）。
5. `adb install -r -d` 到 Chery 8155。
6. 按 §4.3 的"冷启前彻底重置"脚本跑 3 轮 × 180s soak，props 保持 `debug.netmonitor.enabled=0`。
7. 记录结果，补到 `ASDK_HTTPCLIENT_CRASH_HANDOFF.md §12.9`（暂未创建，创建时顺带把 Chery 8155 的所有数据合并进去）。
8. **完成后 revert 回 pivot 状态**：`git checkout -- build.gradle` 再次把依赖注释，`mv NetScopeStub.kt.bak NetScopeStub.kt`。否则 APK 仍会崩，影响后续工作。

---

## 7. 常见踩坑点（上一任 agent 已经吃过亏的）

1. **「20 分钟没崩就是稳」的错觉**：这是概率长尾。必须连续 3~5 个独立冷启 × 180s soak 才能说"稳"。参见 `HANDOFF §12.8.5` 的"6 次连崩后 21 min 不崩"例证。
2. **`cmd dropbox --print` 不能用**（当前设备报 `Unknown command`），要用 `dumpsys dropbox --print`。
3. **非 root 读 `/proc/$PID/maps` 要 `run-as`**：`adb shell "run-as com.telenav.app.arp cat /proc/$PID/maps"`。
4. **换设备 / 换安装 = ASLR 全变**：任何 `lr` / `x8` 等绝对地址做 base 减法前，**必须**用当前 `/proc/$PID/maps` 重算 base，不能复用旧 session 的数字。
5. **`extractNativeLibs=false` 下 `.so` 直接从 APK mmap**：不是传统的 `/data/app/.../lib/...` 路径，符号化时要用 `llvm-addr2line --obj=... <addr-relative-to-so-start>`，不是 `<addr-absolute>`。
6. **tombstone Abort message 是"最后一条 log"，不是 abort 点**：`[pid]create DR Engine success` 看起来像 DR 自爆，实际上那只是信息级 log 被 `android_set_abort_message()` 顺手捕获了（§12.6.2）。
7. **prop 是进程启动时读一次**：`setprop debug.netscope.diag loadonly` 后必须 `force-stop` + `am start` 才生效；已经在跑的进程读不到新值。
8. **`netmonitor_breaker.xml` 会让服务跳过初始化**：实验前务必 `rm -f shared_prefs/netmonitor_breaker.xml`，否则可能误以为稳，其实是 NetMonitor 自己提前 return 了。
9. **AndroidX Startup 的 provider meta-data 是 merge 的**：`:netmonitor` AAR 里用 `tools:node="merge"` 往宿主 manifest 里加 `<meta-data>`。彻底拔除要直接改 `:netmonitor/src/main/AndroidManifest.xml` 而不是宿主 manifest。
10. **Gradle 的 `compileJavaSdk=true` 切换不影响 `.so`**：只切 Java 字节码层；`libFoundationJni.so` 这些 native 库是从同一份 prebuilt 来的。MD5 已确认一致。
11. **`NetScopeStub.kt` 和真 NetScope 依赖不能并存**：一旦 `netmonitor/build.gradle` 里打开 `com.github.Arrowyi:NetScope:...`（或任何变体），**必须**先删 / 暂存 `src/main/java/indi/arrowyi/netscope/sdk/NetScopeStub.kt`，否则会因重复类定义编译失败。Layer B 启动（§6.B）和反向对照实验（§6.F）都要注意。
12. **Chery 8155 vs AGM3 的 ASLR / maps / md5 不共享**：任何"我要复现一个 crash"的脚本，设备换了就不能复用上次的 tombstone 偏移，**必须**重新 `adb pull base.apk` 验 md5 + 重新 `/proc/$PID/maps` 算 base。同一台设备在重装 APK 后 ASLR 也会重算，maps 要重抓。

---

## 8. 如何跟用户沟通

观察到的习惯（从前两次会话提取）：

- 用户喜欢**先要答案，再追问过程**。给答案时前 3 行要有结论；证据链放后面。
- 用户接受**不确定性**，只要你把不确定点显式标出。例如"这次 21 min 不崩，我们 unsure 是不是改动真起作用，因为样本量只有 1"——这种说法用户欣赏，装懂反而会被挑刺。
- 用户会**手动改 APK / setprop / 物理操作设备**；你应当信任用户说的"现在 APK 是 X 状态"，再用 `adb` 验证，而不是直接照着理论推断。
- 用户给的**"20 分钟没崩"** 这类陈述，**不等于**"稳了"。要当作单点观察数据，主动和历史分布对比。
- 用户会提**看似无关的细节**（比如"顺便提一下 compileJavaSdk 改了"），但那些细节往往**改变全部基础假设**。听到任何环境/配置变化都要立刻核实哈希、maps、prop 等硬证据。
- 用户**允许改 NetScope**，但 NetScope 是第三方 SDK（GitHub `Arrowyi/NetScope`），改动要通过作者 / fork。
- 用户**不要求你立刻出代码**，会先讨论方案再决定实施时机。不要一上来就写 patch。

---

## 9. 文件导航地图

```
NavHome/module/netmonitor/doc/
├─ ASDK_HTTPCLIENT_CRASH_HANDOFF.md    ← Crash 调研主文档，必读
│   §1    核心结论（当前版本 + 历史版本对照）
│   §2-5  环境 / 复现 / 编译
│   §6    路径表（A/B/C/D/E/F/G/H/I/J 每条当前状态）
│   §7-11 NetScope 互锁排查细节（历史，快速略读）
│   §12   最新调研主章
│     §12.1-§12.4  loadonly 实测 & kill-switch 仍偶崩
│     §12.5        A 配置 3×180s=0 崩（关键证据）
│     §12.6        锁定 asdk.httpclient 归属到 libFoundationJni
│     §12.7        G 路径 D60 证伪 NetScope 运行时 = 放大器（关键实验）
│     §12.8        ★ 8 项 APK 静态扰动清单 + 21min+ 长稳反例解释
│     §12.9        ✦ 尚未创建：Chery 8155 跨设备复现（2026-04-24 晚，**下一任 agent 可合并进来**）
│
├─ TRAFFIC_MONITOR_TIERED_PLAN.md       ← 流量监控方案设计
│   文件顶部 "⚠ 2026-04-24 晚 PIVOT" ← **先读这段**
│   §0     当前应用 HTTP 栈拓扑（Java vs native 可见度）
│   §Tier 1  [原稿] 纯 Java + Android 系统 API，~3 人日，含完整代码草案
│            → Layer A 和 Layer B 的 fallback 都复用这里的代码
│   §Tier 2  [原稿] Telenav 原生 ClientStatsRegistry API 设计
│            → 直接等同于 Layer C，内容未变
│   §Tier 3  [作废] NetScope 瘦身方案 + 一段话模板
│            → 已被 NETSCOPE_AOP_REQUEST.md 替代，§3.4 模板**不要再发**
│   §Tier 4  [未变] VpnService（不推荐）
│   §最终建议路线图  ← 新版（Layer A/B/C）+ 旧版存档
│
├─ NETSCOPE_AOP_REQUEST.md              ← ★ 2026-04-24 晚新增，对 NetScope 的正式需求
│   §0   TL;DR（给作者 90 秒摘要）
│   §1-3 已排除的方向 / 已锁定的根因 / 为什么是静态放大器
│   §4   新产品方向：Layer A/B/C
│   §5   **正式需求 R1（Java-only 变体）/ R2（AOP design doc）/ R3（manifest 规范）**
│   §6   我方时间线
│   §7   fallback：NetScope 做不到时我方的后备方案
│   §9   联系方式（发出前要填空）
│   §10  ★ 构建栈兼容性 fact sheet（Denali only，AGP 4.2.2 / Gradle 6.7.1 / R8 禁用 / Kotlin 1.6.21 / Transform API / AspectJ 共存 / Proguard 规则要求）
│        → 2026-04-24 晚追加，回应 NetScope "想做 ASM 字节码插桩" 的兼容性问询
│
├─ NETSCOPE_V2_INTEGRATION.md           ← ★★ 2026-04-24 新增（Phase 1 接入会话）
│   §0   v2.0.1 交付物核对（AAR 大小 / 无 .so / 插桩点 / API 字节码核对）
│   §1   改动清单（6 个文件的精确 diff）
│   §2   ★★ Build → Sign → Install 流水（Chery 8155 platform_chery.keystore 重签流程）
│   §3   验收清单（Gradle / APK 字节 / logcat / dumpsys 四层验证）
│   §4   Soak 验证（AGM3 + Chery 8155 各 3×180s）
│   §5   Phase 2 计划（:netmonitor 重写适配新 API，~半人日）
│   §6   回滚方案
│   §7   对外发布建议
│
└─ NEXT_AGENT_HANDOFF.md                ← 你现在看的这份文件
```

## 10. 快速自检

接手后动手前，建议先跑一遍：

```bash
# 1. 确认设备在线 & app 活着（注意：AGM3 和 Chery 8155 是两台不同设备）
adb devices
adb shell pidof com.telenav.app.arp

# 2. 确认当前 prop / 构建签名
adb shell getprop | grep -E 'debug\.(net|ns)|ro.build.fingerprint'
adb shell "stat /data/app/com.telenav.app.arp-*/base.apk"

# 3. 确认当前 libFoundationJni.so 的 md5 和历史一致（没一致就说明用户换 .so 了，需要重新分析！）
cd /tmp && rm -rf verify && mkdir verify && cd verify
adb pull /data/app/com.telenav.app.arp-*/base.apk .
unzip -o base.apk 'lib/arm64-v8a/libFoundationJni.so' > /dev/null
md5sum lib/arm64-v8a/libFoundationJni.so
# 期望: 02cd184e930f63c7bc26fb32e2452e7e
# （AGM3 和 Chery 8155 当前安装的版本 MD5 都应等于这个；不等 = 用户换 .so 了）

# 4. 确认当前 APK 是 "NetScope 静态剔除"（Stub 替身）状态（pivot 后的默认发版策略）
unzip -l base.apk | grep -iE 'libnetscope|libbytehook|libshadowhook' && echo "  ⚠ APK 里还含 NetScope native，不是 pivot 后的默认态，动手前问清用户" \
  || echo "  <no NetScope so - stub state, OK>"

# 5. 看最近 24h 有没有新 tombstone（注意 dumpsys 而不是 cmd）
adb shell "dumpsys dropbox --print | grep -i SYSTEM_TOMBSTONE | tail -10"

# 6. 读完三份 doc（ASDK_HTTPCLIENT_CRASH_HANDOFF / TRAFFIC_MONITOR_TIERED_PLAN 顶部 PIVOT + §Tier 1.3 /
#    NETSCOPE_AOP_REQUEST），再问用户要做啥
```

祝你好运。

—— 前任 agent（2026-04-24）
