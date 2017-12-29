#Doze and app standby介绍

##简介
从android 6.0(API 23) 开始,android为了延长电池的使用时间,引入了两个新的省电特性.当手机没有进行充电时,通过管理app的行为到达省电目的.
当设备较长时间没有使用的时候,设备会进入Doze模式,此时,屏幕是灭屏的,app的cpu和网络的使用将会延时处理.app standby是将app没有处于前台,
该app的网络使用将会被限制,此时不需要灭屏.  
Doze and app standby 需要 API 23以及更高的版本.

###
##理解Doze
在设备不充电,灭屏,较长一段时间用户没有使用移动设备的情况下,设备会进入Doze模式.在Doze模式下,系统会限制APP的网络和cup服务来达到省电的目的.
并且Doze模式还会阻止APP






