# tct-block实现原理

## 1 主要功能模块介绍

主要模块的功能模块:
'vendor/jrdcom/proprietary/tct-blockinfo/':

```
.:
Android.mk  README.md  src

./src:
com

./src/com:
tct

./src/com/tct:
blockinfo

./src/com/tct/blockinfo:
AbstractSampler.java  BlockInfo.java  CpuSampler.java  HandlerThreadFactory.java  LogWriter.java  LooperMonitor.java  StackSampler.java
```

该模块主要参考[AndroidPerformanceMonitor](https://github.com/markzhai/AndroidPerformanceMonitor),感谢大牛的贡献.

最核心的代码在'LooperMonitor.java'中,该模块的核心是基于message处理时间,如果超过一定的阈值,就可以认定为卡顿,
    这样就可以进行我们的操作,比如:搜集系统的log信息,至于搜集哪些信息,我们可以自己定制.而且不需要root权限,
    android本身就有接口函数(Looper.myLooper().setMessageLogging),如果app想要使用该功能,就需要将检测的模块集成到代码中,
    tct-block功能就是将检测模块直接集成到系统ActivityThread,所有的应用进程就可以被检测了.只要应用卡顿,就搜集卡顿时刻的信息.
    具体的原理可以百度"BlockCanary",有很多介绍.

重要代码:

```
    @Override
    public void println(String x) {
        if (Debug.isDebuggerConnected()) {
            return ;
        }

        if (!mPrintingStarted) {
            mStartTimestamp = System.currentTimeMillis();
            mStartThreadTimestamp = SystemClock.currentThreadTimeMillis();
            mPrintingStarted = true;
            startDump();
        } else {
            final long endTime = System.currentTimeMillis();
            mPrintingStarted = false;
            if (isBlock(endTime)) {
                long realTimeStart = mStartTimestamp;
                long startThreadTimestamp = mStartThreadTimestamp;
                long endThreadTimestamp = SystemClock.currentThreadTimeMillis();
                HandlerThreadFactory.getWriteLogThreadHandler().post(new Runnable() {
                        @Override
                        public void run() {
                        notifyBlockEvent(realTimeStart, endTime, startThreadTimestamp, endThreadTimestamp);
                        }
                        });
            }
            stopDump();
        }
    }

```
该模块最终被打包成jar文件:
```
PRODUCT_PACKAGES += tct-blockinfo
PRODUCT_BOOT_JARS += tct-blockinfo
```
在ActivityThread中使用反射进行调用.

# 2 ActivityThread调用:
应用启动进程时会在ActivityThread的里走到handleBindApplication方法,在handleBindApplication方法里面添加:

```
Looper.myLooper().setMessageLogging((android.util.Printer)Class                                                       
        .forName("com.tct.blockinfo.LooperMonitor")                                                                   
        .getDeclaredConstructor(Context.class).newInstance(mInitialApplication));                                     

private Printer mLogging;
public void setMessageLogging(@Nullable Printer printer) {                                                                                                           
    mLogging = printer;
}

public static void loop() {
    // This must be in a local variable, in case a UI event sets the logger
    final Printer logging = me.mLogging;
    if (logging != null) {
        logging.println(">>>>> Dispatching to " + msg.target + " " +
                msg.callback + ": " + msg.what);
    }

    ......
        msg.target.dispatchMessage(msg);
    ......

        if (logging != null) {
            logging.println("<<<<< Finished to " + msg.target + " " + msg.callback);
        }
    ......
```

在`dispatchMessage`前后分别调用`println`方法,该方法就是`LooperMonitor.java`里面的`println`方法,该方法里面会记录两次调用的时间差,
当差值大于3000ms就搜集相关的log信息.

# 3 信息的保存

主要是调用`LogWriter.java`的save方法保存,需要注意的是修改文件的权限,不然在user版不能使用`adb pull `出文件.

```
    Runtime.getRuntime().exec("chmod 777 " + path);
```

搜集的log信息就会被保存到`/data/blockinfo`目录下面.
这里有一个问题就是,需要`/data/blockinfo`目录能被所有进程能够写.

# 4 /data/blockinfo文件夹创建

```
# add blockinfo dir for blockinfo feature by qli3@tcl.com. task: 6517476
mkdir /data/blockinfo 0777 system system
```

在init.rc中创建文件夹,接下来需要解决SELinux权限.
`file_contexts`添加:

```
/data/blockinfo(/.*)?    u:object_r:media_rw_data_file:s0
```

这里必须使用`media_rw_data_file`,其他的file类型是被neverallow的,只有这一个特列.
在system_app.te, priv_app.te添加

```
allow system_app proc_stat:file {read getattr open};
allow system_app media_rw_data_file:dir { search read open};
allow system_app media_rw_data_file:file { read open getattr};
```

其他还有一些权限,可以调试的时候看却什么就加上.

# 其他
- 该功能使用perso开关控制`ro.tct.blockinfo`
- 在AMS添加getCurrentCPUState,并在AP侧添加接口
