# Multi-release JARs (Java 9+) ship META-INF/versions/9/module-info.class;
# ProGuard warns "class in incorrectly named file" and fails. Use -ignorewarnings
# to continue past this non-fatal issue (the class is simply not written out).
-ignorewarnings
