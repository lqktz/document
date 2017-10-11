# Android开机总体流程

## 启动环节
1. **启动电源以及系统启动**  
当电源按下，引导芯片代码开始从预定义的地方（固化在ROM）开始执行。加载引导程序到RAM，然后执行.  
2. **引导程序Bootloader**  
引导程序是在Android操作系统开始运行前的一个小程序，它的主要作用是把系统OS拉起来并运行。  
3. **linux内核启动**  
内核启动时，设置缓存、被保护存储器、计划列表，加载驱动。当内核完成系统设置，它首先在系统文件中寻找”init”文件，然后启动root进程或者系统的第一个进程。  
4. **init进程启动**  
Anroid系统的"天字号"进程--init进程,准确的说init进程是Linux系统的用户空间的第一个进程,所以init进程也是
android系统用户空间的第一个进程.
5. **Zygote进程启动**  
init进程,那么Zygote进程就是二号进程,android启动之后的所有进程都是Zygote进程fork()出来的,Zygote进程是由
init进程启动起来的.  
6. **system_server进程启动**  
system_server进程其实是systemserver进程,只是在代码中把进程的名字修改了一下(为什么要修改?不知道...),该进程负责启动系统中重量级的服务,例如:
ActivityManagerService,PackageManagerService.etc,system_server进程是Zygote进程的嫡长子,Zygote最重视该进程,其他进程crash,Zygote进程只是打印信息,system_server进程
crash,Zygote进程会调用方法kill自己.android会重启...
7. **系统服务启动**  
这些系统服务都还是在system_server进程中


