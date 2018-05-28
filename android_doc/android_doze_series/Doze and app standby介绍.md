# Doze and app standby介绍

## 1 简介

从android 6.0(API 23) 开始,android为了延长电池的使用时间,引入了两个新的省电特性.当手机没有进行充电时,通过管理app的行为到达省电目的.
当设备较长时间没有使用的时候,设备会进入Doze模式,此时,屏幕是灭屏的,app的cpu和网络的使用将会延时处理.app standby是将app没有处于前台,
该app的网络使用将会被限制,此时不需要灭屏.  
Doze and app standby 需要 API 23以及更高的版本.

## 2 理解Doze

在设备不充电,灭屏,较长一段时间用户没有使用移动设备的情况下,设备会进入Doze模式.在Doze模式下,系统会限制APP的网络和cup服务来达到省电的目的.
并且Doze模式还会延迟app的jobs, syncs, and standard alarms.
系统会定期退出一段时间让被阻止的APP去完成自己被延时的工作,这个时间片段被叫做maintenance window.在maintenance window期间所有app都可以去完成
被挂起的jobs, syncs, and standard alarms以及允许APP连接网络.
![Doze](https://raw.githubusercontent.com/lqktz/document/master/res/doze.png)
当一个maintenance window结束之后,设备又会进入Doze模式.挂起网络,延迟APP的jobs, syncs, and standard alarms.不同的是maintenance window之间的
间隔会越来越长.这样的策略是为了省电.

### 2.1 Doze模式的限制

处于Doze模式,APP会有以下限制  

- 网络访问被暂停
- 系统会忽略wack lock
- 标准的alarm(setExact() 和 setWindow())会被推迟到下一个maintenance window去执行  
  如果在设备处于Doze下,需要使用alarm去唤醒,需要使用setAndAllowWhileIdle() / setExactAndAllowWhileIdle().  
- 系统不再进行wifi扫描  
- 系统不允许执行 sync adapters  
- 系统不允许执行 JobScheduler(也就是限制了jobs)  

### 2.2 Doze的状态以及变化

Doze的状态的变化是Doze的核心设计,下面介绍Doze的进入,状态变化,退出

#### 2.2.1 Doze状态的进入

满足以下条件,进入Doze:  

- 用户不操作设备一段时间 （通过动作监测来判断）
- 屏幕关闭 
- 设备未连接电源充电 

#### 2.2.2 Doze的五种状态
Doze的核心就是使用了状态的变化,主要是使用控制机器在五种状态下面切换,下面对五种状态进行说明:

- ACTIVE：手机设备处于激活活动状
- INACTIVE：屏幕关闭进入非活动状态 
- IDLE_PENDING：每隔30分钟让App进入等待空闲预备状态 
- IDLE：空闲状态
- IDLE_MAINTENANCE：处理挂起任务
下图是状态转移的图:
![doze_state_change:](https://raw.githubusercontent.com/lqktz/document/master/res/doze_state_change.png)

#### 2.2.3 Doze的退出
退出Doze和进入相对应,只要进入条件的其中一个不满足就会退出Doze,对应如下:

- 用户唤醒装置移动
- 打开屏幕
- 连接电源

### 2.3 在Doze下适配APP

由于在Doze模式下,APP的jobs, syncs, and standard alarms以及APP连接网络,会被收到限制,但是APP如果是真的要使用,有两种方式:  

- 使用白名单,在白名单有系统白名单,用户添加的白名单,临时白名单(10s);在白名单之中的APP能够在Doze模式下豁免;
- 使用setAndAllowWhileIdle() / setExactAndAllowWhileIdle(),不过这也是有限制的,每个APP每9分钟只能触发一次,这两个方法的本质是将APP加入临时的白名单,
  来达到在Doze模式下,仍然可以使用alarm下唤醒机器.
- 使用 Firebase Cloud Messaging(FCM),之前是Google Cloud Messaging(GCM),该服务就是google的专用通道,在android中有较高的优先级.APP要执行的操作,
![FCM](https://raw.githubusercontent.com/lqktz/document/master/res/FCM.png)


在设备Doze模式下的设备,APP只要将要唤醒,更新等操作,从服务器发送到FCM是,由FCM统一发送即可,避免了每个
APP都与设备建立一个长链接用于推送信息.并且FCM在android拥有高的优先级,能够在Doze模式下,不影响其他APP的前提下,唤醒APP,达到省电目的.


## 2 理解APP standby

### 2.1 APP standby策略

当用户不触摸使用APP,一段时间后,系统会判定进入APP进入空闲状态.当包含以下任意一种情况,App就会退出App Standby状态:

- 用户主动启动App;
- App有一个前台进程,或包含一个前台服务,或被另一个activity或前台service使用;
- App生成一个用户所能在锁屏或通知托盘看到的Notification,而当用户设备插入电源时，系统将会释放App的待机状态，允许他们自由的连接网络及其执行未完成的工作和同步。如果设备空闲很长一段时间，系统将允许空闲App一天一次访问网络。

### 2.2 Doze和App Standby的区别

Doze模式需要屏幕关闭（通常晚上睡觉或长时间屏幕关闭才会进入），而App Standby不需要屏幕关闭，App进入后台一段时间也会受到连接网络等限制。

## 3 低电耗模式测试app
App要去测试,适配Doze,App Standby,避免一些功能不能使用.

### 3.1 测试Doze模式

- 使用 Android 6.0（API 级别 23）或更高版本的系统映像配置硬件设备或虚拟设备
- 将设备连接到开发计算机并安装应用
- 运行应用并使其保持活动状态
- 关闭设备屏幕。（应用保持活动状态。） 
- 通过运行以下命令强制系统在低电耗模式之间循环切换：
```
$ adb shell dumpsys battery unplug
$ adb shell dumpsys deviceidle step
```
可能需要多次运行第二个命令。不断地重复，直到设备变为空闲状态。  

- 在重新激活设备后观察应用的行为.确保应用在设备退出低电耗模式时正常恢复.

### 3.2 测试App Standby模式

- 使用 Android 6.0（API 级别 23）或更高版本的系统映像配置硬件设备或虚拟设备
- 将设备连接到开发计算机并安装应用
- 运行应用并使其保持活动状态
- 通过运行以下命令强制应用进入应用待机模式：
```
$ adb shell am set-inactive <packageName> false
$ adb shell am get-inactive <packageName>
```
- 观察唤醒后的应用行为。确保应用从待机模式中正常恢复。 特别地，您应检查应用的通知和后台作业是否按预期继续运行 




























