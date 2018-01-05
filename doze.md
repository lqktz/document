#Doze and app standby介绍

##简介
从android 6.0(API 23) 开始,android为了延长电池的使用时间,引入了两个新的省电特性.当手机没有进行充电时,通过管理app的行为到达省电目的.
当设备较长时间没有使用的时候,设备会进入Doze模式,此时,屏幕是灭屏的,app的cpu和网络的使用将会延时处理.app standby是将app没有处于前台,
该app的网络使用将会被限制,此时不需要灭屏.  
Doze and app standby 需要 API 23以及更高的版本.

###
##理解Doze
在设备不充电,灭屏,较长一段时间用户没有使用移动设备的情况下,设备会进入Doze模式.在Doze模式下,系统会限制APP的网络和cup服务来达到省电的目的.
并且Doze模式还会延迟app的jobs, syncs, and standard alarms.
系统会定期退出一段时间让被阻止的APP去完成自己被延时的工作,这个时间片段被叫做maintenance window.在maintenance window期间所有app都可以去完成
被挂起的jobs, syncs, and standard alarms以及允许APP连接网络.
![Doze](https://raw.githubusercontent.com/lqktz/document/master/res/doze.png)
当一个maintenance window结束之后,设备又会进入Doze模式.挂起网络,延迟APP的jobs, syncs, and standard alarms.不同的是maintenance window之间的
间隔会越来越长.这样的策略是为了省电.
##Doze模式的限制
处于Doze模式,APP会有以下限制  

* 网络访问被暂停
* 系统会忽略wack lock
* 标准的alarm(setExact() 和 setWindow())会被推迟到下一个maintenance window去执行  
  如果在设备处于Doze下,需要使用alarm去唤醒,需要使用setAndAllowWhileIdle() / setExactAndAllowWhileIdle().  
* 系统不再进行wifi扫描  
* 系统不允许执行 sync adapters  
* 系统不允许执行 JobScheduler(也就是限制了jobs)  

##在Doze下适配APP
由于在Doze模式下,APP的jobs, syncs, and standard alarms以及APP连接网络,会被收到限制,但是APP如果是真的要使用,有两种方式:  

* 使用白名单,在白名单有系统白名单,用户添加的白名单,临时白名单(10s);在白名单之中的APP能够在Doze模式下豁免;
* 使用setAndAllowWhileIdle() / setExactAndAllowWhileIdle(),不过这也是有限制的,每个APP每9分钟只能触发一次,这两个方法的本质是将APP加入临时的白名单,
  来达到在Doze模式下,仍然可以使用alarm下唤醒机器.
* 使用 Firebase Cloud Messaging(FCM),之前是Google Cloud Messaging(GCM),该服务就是google的专用通道,在android中有较高的优先级.APP要执行的操作,
![FCM](https://raw.githubusercontent.com/lqktz/document/master/res/FCM.png)


在设备Doze模式下的设备,APP只要将要唤醒,更新等操作,从服务器发送到FCM是,由FCM统一发送即可,避免了每个
APP都与设备建立一个长链接用于推送信息.并且FCM在android拥有高的优先级,能够在Doze模式下,不影响其他APP的前提下,唤醒APP,达到省电目的.


##理解APP standby
当用户没有使用APP,系统会判定进入APP进入空闲状态.当包含以下任意一种



























