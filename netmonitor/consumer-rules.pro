# NetMonitor public surface used by the host application via the
# `NetMonitor` facade. Keep these so consumer R8 cannot rename/strip them.
-keep class com.telenav.netmonitor.NetMonitor { *; }
-keep class com.telenav.netmonitor.NetMonitorConfig { *; }
-keep class com.telenav.netmonitor.NetMonitorInitializer { *; }
-keep class com.telenav.netmonitor.NetMonitorService { *; }
-keep interface com.telenav.netmonitor.NetworkUsageSource { *; }
-keep class com.telenav.netmonitor.SubsystemUsage { *; }
-keep interface com.telenav.netmonitor.NetMonitorLog { *; }

# The NetScope bytecode-transform plugin rewrites instrumented call sites
# in the host application to dispatch through these SDK types. They must
# survive consumer R8 — keep their public surface and the marker interface
# the plugin uses to identify instrumented classes.
-keep class indi.arrowyi.netscope.sdk.** { *; }
-keep interface indi.arrowyi.netscope.sdk.integration.NetScopeInstrumented
-keepclassmembers class * implements indi.arrowyi.netscope.sdk.integration.NetScopeInstrumented { *; }
-dontwarn indi.arrowyi.netscope.sdk.**
-keep class indi.arrowyi.netscope.hook.** { *; }
-dontwarn indi.arrowyi.netscope.hook.**
