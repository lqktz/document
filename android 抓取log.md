# Android 抓取log

**平台: Android 8.0 **

在android中有各类log,日志文件,在此做一个总结.以及简单的解释.

# 1 logcat

## 1.1 日志类型简介

有四种类型的日志: 

- main :应用程序级别的log
- system : 系统级别的日志
- radio : 与无线点设备相关的日志
- events : 记录一些事件的日志,用于诊断系统问题的.

main,system,radio类型的日志是形式是:
```
prioprity       |tag    |msg
```
eg: 
```
05-09 12:32:26.454   752  3893 V ActivityManager: Sending arguments to: ServiceRecord{d2cb13e u0 com.jrdcom.mmitest/.common.ParaShow} android.content.Intent$FilterComparison@34956390 args=Intent { cmp=com.jrdcom.mmitest/.common.ParaShow }
```
prioprity : V D I W E F  
events的log的结构如下:
```
tag    |msg
```
eg:
```
05-09 07:46:33.523   752  2906 I am_create_activity: [0,221906435,9,com.android.settings/.Settings$SystemDashboardActivity,NULL,NULL,NULL,32768]
```
在`frameworks/base/services/core/java/com/android/server/am/EventLogTags.logtags`对`am_create_activity`进行了结构说明:
```
# A new activity is being created in an existing task:
30005 am_create_activity (User|1|5),(Token|1|5),(Task ID|1|5),(Component Name|3),(Action|3),(MIME Type|3),(URI|3),(Flags|1|5)
```
还有一部分在`frameworks/base/services/core/java/com/android/server/EventLogTags.logtags`.这个文件在设备的`/system/etc/event-log-tags`
对`(User|1|5)`对应例子里`[]`里面的第一个参数0,这个0名称叫user,数据类型是1,数据单位是5.

数据类型:  

- 1: int
- 2: long
- 3: string
- 4: list

数据单位:  

- 1: Number of objects(对象个数)
- 2: Number of bytes(字节数)
- 3: Number of milliseconds(毫秒)
- 4: Number of allocations(分配个数)
- 5: Id
- 6: Percent(百分比)

## 1.2 获取四种log的方式
```
adb logcat -v time > logcat.txt      //默认是-b main -b system
adb logcat -v time -b main
adb logcat -v time -b radio
adb logcat -v time -b system
adb logcat -v time -b events
```

# 2 bugreport

## 2.1 简介与获取
bugreport 会输出系统的信息,一般会比较大.获取:
```
adb bugreport
```

## 2.2 chkbugreport分析工具
获取的bugreport太大,不利于分析,sony公司做了一个分析工具,可以使用该工具分析.该工具是一个jar包,可以直接获取jar包,
也可以使用源码进行编译获取.源码地址:https://github.com/sonyxperiadev/ChkBugReport
下载源码:
```
git clone https://github.com/sonyxperiadev/ChkBugReport.git
```
执行`ChkBugReport/core/createjar.sh`脚本,会产生一个jar文件`chkbugreport-0.5-216.jar`,将该文件放在`home/bin/`目录下,方便其他地方使用.将获取的bugreport.zip,解压.
使用
```
chkbugreport.jar bugreport-android-O00623-2018-05-09-14-18-58.txt
```
会在目录下产生一个对应的out文件夹,双击打开里面的`index.html`.就可以更加清晰的分析bugreport内容.

## 2.3 battery-historian工具

Google也推出了自己的用于分析bugreport,主要是用于分析耗电软件,github:https://github.com/google/battery-historian.环境配置的方法按照说明即可,在
网上也有非常详细的配置教程,注意的一点就是要确保网络可以链接到Google.
该工具主要用于分析耗电,可以可视化.而且持续更新,由于sony在手机行业的溃败,chkbugreport工具已经没有再更新,不过不影响他的正常使用.

















