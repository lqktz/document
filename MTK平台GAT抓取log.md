#MTK平台GAT抓取log  

**平台:MTK**  
***
DESCRIPTION:  
如何用GAT抓取log , 比如当MTKLogger出现异常，或SD卡不可用时

利用MTK 提供的GAT(官网可以下载)可以进行抓取:  
一. 抓取问题复现的log  
1.连接usb到pc，确认手机usb debugging选项是开启的  
2.打开GAT  
3.设定log存储路径，window->preferences->savelog  
4.点击控制栏 L字样的按钮，开始录制  
5.复现现象  
6.再次点击L字样的按钮，停止录制  
7.到第4步设定的路径上取log  

二. 抓取开机log  
1.连接usb到pc，确认手机usb debugging选项是开启的  
2.手机关机  
3.打开GAT  
4.设定log存储路径，window->preferences->savelog  
5.点击控制栏 L字样的按钮，开始录制  
6.手机开机，进入待机5分钟以上  
7.再次点击L字样的按钮，停止录制  
8.到第4步设定的路径上取log  
***

**平台:MTK**  
***
DESCRIPTION:  

如何抓取开机Log
一般分析开机失败或者开机过程异常问题，都牵涉到如何抓取开机log的问题，为了顺利抓取开机Log便于分析问题，参照如下抓取有效log：
1.如果开机过程还没有出现开机动画，就已经异常，直接抓取UART串口log；
2.如果开机动画已经显示，后面出现异常，可以首先check SD卡是否已经mount成功，如果SD卡mount成功，直接提供Mobilelog；
否则，可以通过adb logcat抓取log或者通过我司release的GAT Tool抓取log，其中logcat抓取log的command：
`adb logcat -v time -b main -b events -b system>logcat.txt`。

***

**打印CPP方法调用堆栈**  

一、CPP打印堆栈：
    #include <utils/CallStack.h>
    ...
    android::CallStack stack; 
    stack.update(1, gettid()); 
    stack.log("satcklog", ANDROID_LOG_ERROR, "stackdump:");
    ...
    }
    在mk文件中，加入：
    LOCAL_SHARED_LIBRARIES += libutils

    这样设置打印出来的堆栈信息的Log tag为satcklog，信息格式类似于：
    01-01 08:02:30.615   372  2158 E satcklog: stackdump:#00 pc 0002671b  /system/lib/libcamera_client.so (_ZN7android16CameraParameters12setVideoSizeEii+70)

二、用addr2line工具进行具体的代码行数定位。

    其中addr2line的使用，以PIXI5-8 TMO举例，在根目录使用如下命令可以定位地址信息对应的代码：
    ./prebuilts/gcc/linux-x86/arm/arm-linux-androideabi-4.9/bin/arm-linux-androideabi-addr2line -C -f -e out/target/product/pixi584g/symbols/system/lib/libcamera_client.so 0002671b 
    输出为：
    android::CameraParameters::setVideoSize(int, int)
    /proc/self/cwd/frameworks/av/camera/CameraParameters.cpp:384 (discriminator 1)

    即可定位具体的代码行数。 
***







