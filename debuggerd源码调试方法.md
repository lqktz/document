# debuggerd源码调试方法

两个重要的文件：

`debugger.c->/system/core/libcutils/debugger.c`

`dubuggerd.cpp->/system/core/debuggerd/debuggerd.cpp`

修改debuggerd.cpp代码后，对该模块进行单编：

```shell
mmm system/core/debuggerd
```

单编后的编译结果在（编译成功会在屏幕打印信息中显示）：

`/out/target/product/项目名/system/bin/debuggerd`

用该编译结果替Android手机中的debuggerd文件：

切换到`/out/target/product/项目名/system/bin/debuggerd`，使用

```shell
adb remount
adb push debuggerd /system/bin
```

/system/bin是android系统中debuggerd文件的位置。

如果修改了debugger.c文件，则需要对所在模块进行单编：

```shell
mmm system/core/libcutils
```

单编的结果在`/out/target/product/项目名/symbols/system/lib/libcutils.so`

用该文件去替换手机系统里面的文件：

```shell
adb push libcutils.so /system/lib
```

由于debuggerd进程是一个守护进程，所以修改Android文件之后要重启才能生效。

为了抓取debuggerd进程的相关log信息，必须在开机过程中进行抓取，先关闭Android设备，在终端执行：

`adb  logcat -v time -v threadtime > liqiang.log`

这样就会把log抓取为文件，就可以使用log进行分析。

如果要对selinux进行设置，每次开机都要重新设置，进入手机，设置方法如下：

`setenforce 0`或者

`setenforce 1`

0：premissive

1：enforcing

[debuggerd源码分析参考博客](http://android.jobbole.com/84877/)