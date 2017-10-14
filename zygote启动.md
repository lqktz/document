#Zygote进程启动过程
zygote进程是由init进程启动的,在android中所有应用进程以及系统服务进程都是由zygote fork()出来的,并且system_server是zygote的嫡长子,
当zygote完成启动立启动system_server,这是在zygote.rc中的参数`--start-system-serve`决定的.  
在`aosp/frameworks/base/cmds/app_process/app_main.cpp`的main函数里`runtime.start("com.android.internal.os.ZygoteInit", args, zygote);`,
进入java层的`aosp/frameworks/base/core/java/com/android/internal/os/ZygoteInit.java`的main函数 
