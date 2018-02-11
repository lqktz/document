#Watchdog机制分析

Android在软件层面有一个watchdog机制,检测一些重要的服务,当这些服务出现问题时,通知Android进行重启,定期检测重要的服务是否出现deadblock.
watchdog主要就是检测mMonitors里面(重要服务,核心线程)的服务是不是处于block状态.

- 监视reboot广播；  
- 监视mMonitors关键系统服务是否死锁。  

##1 Watchdog初始化:
###1.1 startOtherServices()
`frameworks/base/services/java/com/android/server/SystemServer.java`
在`startOtherServices()`中进行初始化:
```
            traceBeginAndSlog("InitWatchdog");
            final Watchdog watchdog = Watchdog.getInstance();//1 获取对象
            watchdog.init(context, mActivityManagerService);//2 初始化
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            Watchdog.getInstance().start();//3 启动watchdog
```
####1.1.1 getInstance()
```
    public static Watchdog getInstance() {
        if (sWatchdog == null) {
            sWatchdog = new Watchdog();
        }

        return sWatchdog;
    }
```
单例模式
#####1.1.1.1 new Watchdog()
```
    private Watchdog() {
        super("watchdog");
        // Initialize handler checkers for each common thread we want to check.  Note
        // that we are not currently checking the background thread, since it can
        // potentially hold longer running operations with no guarantees about the timeliness
        // of operations there.

        // 逐一添加HandlerChecker
        // The shared foreground thread is the main checker.  It is where we
        // will also dispatch monitor checks and do other work.
        mMonitorChecker = new HandlerChecker(FgThread.getHandler(),
                "foreground thread", DEFAULT_TIMEOUT);
        mHandlerCheckers.add(mMonitorChecker);
        // Add checker for main thread.  We only do a quick check since there
        // can be UI running on the thread.
        mHandlerCheckers.add(new HandlerChecker(new Handler(Looper.getMainLooper()),
                "main thread", DEFAULT_TIMEOUT));
        // Add checker for shared UI thread.
        mHandlerCheckers.add(new HandlerChecker(UiThread.getHandler(),
                "ui thread", DEFAULT_TIMEOUT));
        // And also check IO thread.
        mHandlerCheckers.add(new HandlerChecker(IoThread.getHandler(),
                "i/o thread", DEFAULT_TIMEOUT));
        // And the display thread.
        mHandlerCheckers.add(new HandlerChecker(DisplayThread.getHandler(),
                "display thread", DEFAULT_TIMEOUT));
        // Initialize monitor for Binder threads.

        // 添加需要检测的服务,会形成一个检测集合,这个检测集合里面都是系统层面的核心系统服务
        addMonitor(new BinderThreadMonitor());
        if (SystemProperties.get("ro.have_aee_feature").equals("1")) {
            exceptionHWT = new ExceptionLog();
        }

    }
```
Watchdog继承了Thread类,Watchdog是一个单例线程.在该方法中添加了很多的HandlerChecker,主要可以分为两类:  

- **Monitor Checker :** 用于检查是Monitor对象可能发生的死锁, AMS, PKMS, WMS等核心的系统服务都是Monitor对象  
- **Looper Checker :** 用于检查线程的消息队列是否长时间处于工作状态。Watchdog自身的消息队列，Ui, Io, Display这些全局的消息队列都是被检查的对象。
   此外，一些重要的线程的消息队列，也会加入到Looper Checker中，譬如AMS, PKMS，这些是在对应的对象初始化时加入的  

两类HandlerChecker的侧重点不同，Monitor Checker预警我们不能长时间持有核心系统服务的对象锁，否则会阻塞很多函数的运行;
Looper Checker预警我们不能长时间的霸占消息队列，否则其他消息将得不到处理。这两类都会导致系统卡住(System Not Responding)。

#####1.1.1.1.1 HandlerChecker
```
public final class HandlerChecker implements Runnable {
    private final Handler mHandler; //Handler对象
    private final String mName; //线程描述名
    private final long mWaitMax; //最长等待时间
    //记录着监控的服务
    private final ArrayList<Monitor> mMonitors = new ArrayList<Monitor>();
    private boolean mCompleted; //开始检查时先设置成false
    private Monitor mCurrentMonitor;
    private long mStartTime; //开始准备检查的时间点

    HandlerChecker(Handler handler, String name, long waitMaxMillis) {
        mHandler = handler;
        mName = name;
        mWaitMax = waitMaxMillis;
        mCompleted = true;
    }
}
```
#####1.1.1.1.2 addMonitor
```
    public void addMonitor(Monitor monitor) { 
        synchronized (this) {
            if (isAlive()) {
                throw new RuntimeException("Monitors can't be added once the Watchdog is running");
            }
            mMonitorChecker.addMonitor(monitor);
        }
    }
```
由于mMonitorChecker是一个HandlerChecker类,
```
        public void addMonitor(Monitor monitor) {                                                                                                                        
            mMonitors.add(monitor);
        }
```








