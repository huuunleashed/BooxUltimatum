# Line numbers stay, so crash reports can be retraced with the mapping file published next to each release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku binder and user-service entry points.
-keep class rikka.shizuku.** { *; }

# Instantiated by Shizuku by class name in its own process, through the (Context) constructor.
-keep class app.booxultimatum.core.exec.ShellService { <init>(...); *; }
-keep class app.booxultimatum.IShellService { *; }
-keep class app.booxultimatum.IShellService$** { *; }
