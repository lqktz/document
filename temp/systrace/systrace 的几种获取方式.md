# systrace的几种获取方式

## 0 systrace 简介
systrace是android本身提供的一套分析耗时的工具,可以帮助定位问题.本文只是介绍获取systrace的4种方式.
并不涉及具体的怎么使用systrace去分析问题.

## 1 获取的方式

### 1.1 使用DDMS获取

打开DDMS,下面是在Ubuntu中打开DDMS的方式,因为新版的Android Studio不再提供进入DDMS的入口.
```
Ubuntu~$ ./Android/Sdk/tools/monitor
```
1. DDMS获取systrace第一步
![DDMS获取systrace第一步](/res/DDMS_systrace_01.png)

2. DDMS获取systrace第二步
![DDMS获取systrace第二步](/res/DDMS_systrace_02.png)
**说明:**
- Destination File: 保存的路径,文件一定要是html类型
- Trace duration(seconds): 抓取时间, 默认是5s
- Trace Buffer Size(kb): buffer大小,抓取的时间加长,该值要相应的加大,一般设置为2048的倍数
- Enable Application Traces from: 如果在app中自己添加了Trace,抓取时,需要选择进程
- Commonly Used Tags:默认都会抓取的Trace Tag
- Advanced Options: 高级选项,依据自己需求选择勾选

点击ok之后,就会开始抓取,就可以操作android设备.此时会出现以下界面: 
![DDMS获取systrace第三步](/res/DDMS_systrace_03.png)

当界面消失时,就说明抓取完毕, 在指定的路径Destination File,就会有一个html文件,该文件就是systrace文件.

### 1.2 使用systrace.py脚本获取

systrace.py位于`Android/Sdk/platform-tools/systrace`, 使用 `./systrace.py -h` 可以看到参数介绍.
查看 trace tag
```
Ubuntu:~/Android/Sdk/platform-tools/systrace$ ./systrace.py -list
Usage: systrace.py [options] [category1 [category2 ...]]

systrace.py: error: no such option: -i
Ubuntu:~/Android/Sdk/platform-tools/systrace$ ./systrace.py --list
         gfx - Graphics
       input - Input
        view - View System
     webview - WebView
          wm - Window Manager
          am - Activity Manager
          sm - Sync Manager
       audio - Audio
       video - Video
      camera - Camera
         hal - Hardware Modules
         res - Resource Loading
      dalvik - Dalvik VM
          rs - RenderScript
      bionic - Bionic C Library
       power - Power Management
          pm - Package Manager
          ss - System Server
    database - Database
     network - Network
         adb - ADB
    vibrator - Vibrator
        aidl - AIDL calls
         pdx - PDX services
       sched - CPU Scheduling
         irq - IRQ Events
         i2c - I2C Events
        freq - CPU Frequency
        idle - CPU Idle
        disk - Disk I/O
         mmc - eMMC commands
        load - CPU Load
        sync - Synchronization
       workq - Kernel Workqueues
  memreclaim - Kernel Memory Reclaim
  regulators - Voltage and Current Regulators
  binder_driver - Binder Kernel driver
  binder_lock - Binder global lock trace
   pagecache - Page cache

```
可以看到和使用DDMS是一样的. 这里给一个Demo使用: 
```
./systrace.py gfx input view webview -o ./trace.html
```
这样就会在该目录下产生systrace文件.

### 1.3 使用atrace命令获取

adb shell进入安卓:
```
phoneName:/ # atrace --help
usage: atrace [options] [categories...]
options include:
  -a appname      enable app-level tracing for a comma separated list of cmdlines; * is a wildcard matching any process
  -b N            use a trace buffer size of N KB
  -c              trace into a circular buffer
  -f filename     use the categories written in a file as space-separated
                    values in a line
  -k fname,...    trace the listed kernel functions
  -n              ignore signals
  -s N            sleep for N seconds before tracing [default 0]
  -t N            trace for N seconds [default 5]
  -z              compress the trace dump
  --async_start   start circular trace and return immediately
  --async_dump    dump the current contents of circular trace buffer
  --async_stop    stop tracing and dump the current contents of circular
                    trace buffer
  --stream        stream trace to stdout as it enters the trace buffer
                    Note: this can take significant CPU time, and is best
                    used for measuring things that are not affected by
                    CPU performance, like pagecache usage.
  --list_categories
                  list the available tracing categories
 -o filename      write the trace to the specified file instead
                    of stdout.

```
执行下面命令,开始抓取

```
atrace --async_start -c -b 16384 -a '*' res pdx idle dalvik freq am network binder_driver input hal view database disk sched binder_lock wm bionic gfx audio
```
操作android设备,执行下面命令,结束
```
atrace --async_stop -z -c -o /data/local/traces/trace-test.ctrace
```
会在产生一个`/data/local/traces/trace-test.ctrace`文件, 将文件copy出来,接下来使用systrace.py 将其转换成html文件
```
./systrace.py --from-file=trace-test.ctrace -o trace-test.html
```
这样就会产生systrace文件

### 1.4 开发者选项里获取

开发者选项里打开`System Tracing`. 显示如下界面: 
![开发者选项获取systrace](/res/Setting_systrace_01.png)

里面有一些配置,都很简单,按字面意思理解就行. 打开Record trace就开始抓取.
操作设备, 抓取完毕,需要点击通知栏的System Tracing结束.如下图: 
![开发者选项获取systrace](/res/Setting_systrace_02.png)

会在/data/local/traces/产生一个带时间戳的ctrace文件, 拷贝出来, 用systrace.py 将其转换成html文件
```
./systrace.py --from-file=traceXXXXX.ctrace -o trace.html
```
这样就会产生systrace文件

## 2 打开方式

需要使用**Chrome**打开. 
- 如果安装了最新版本的Chrome,可以直接双击打开.
- 如果是老版本的Chrome,需要在地址栏输入`chrome://tracing/`
![Chrome打开systrace](/res/Chrome_systrace.png)
选择load,加载之前抓取的html, 成功打开后界面: 
![Chrome打开systrace](/res/Chrome_open_systrace.png)
