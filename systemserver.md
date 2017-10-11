#Aandroid SystemServer学习笔记  
###版本:Android O  
SystemServer和Zygote是Android java世界的两大支柱,SystemServer是Zygote孵化出来的进程,进程名为system_server,几乎所有的系统服务都在该进程中,eg:AMS,PMS,WMS .etc  
##1 分析SystemServer代码
### 1.1 从SystemServer.java分析
源码位置:`frameworks\base\services\java\com\android\server\SystemServer.java`,从main()函数开始分析:  
```java
    /**
     * The main entry point from zygote.
     */
    public static void main(String[] args) {
        new SystemServer().run();
    }
```
new出一个SystemServer类,执行其run()方法.  
```java
    public SystemServer() {
        // Check for factory test mode.
        mFactoryTestMode = FactoryTest.getMode();
        // Remember if it's runtime restart(when sys.boot_completed is already set) or reboot
        mRuntimeRestart = "1".equals(SystemProperties.get("sys.boot_completed"));
    }
```
这是SystemServer的构造方法,具体没有研究,有blog说SystemServer.java是final的,不能被继承,但是在O中不是(也没有具体研究,研究了再补充).接下来看run()方法:  
```java
    private void run() {
        try {
            traceBeginAndSlog("InitBeforeStartServices");
            // If a device's clock is before 1970 (before 0), a lot of
            // APIs crash dealing with negative numbers, notably
            // java.io.File#setLastModified, so instead we fake it and
            // hope that time from cell towers or NTP fixes it shortly.
            if (System.currentTimeMillis() < EARLIEST_SUPPORTED_TIME) {
                Slog.w(TAG, "System clock is before 1970; setting to 1970.");
                SystemClock.setCurrentTimeMillis(EARLIEST_SUPPORTED_TIME);
            }

            //
            // Default the timezone property to GMT if not set.
            //
            String timezoneProperty =  SystemProperties.get("persist.sys.timezone");
            if (timezoneProperty == null || timezoneProperty.isEmpty()) {
                Slog.w(TAG, "Timezone not set; setting to GMT.");
                SystemProperties.set("persist.sys.timezone", "GMT");
            }

            // If the system has "persist.sys.language" and friends set, replace them with
            // "persist.sys.locale". Note that the default locale at this point is calculated
            // using the "-Duser.locale" command line flag. That flag is usually populated by
            // AndroidRuntime using the same set of system properties, but only the system_server
            // and system apps are allowed to set them.
            //
            // NOTE: Most changes made here will need an equivalent change to
            // core/jni/AndroidRuntime.cpp
            if (!SystemProperties.get("persist.sys.language").isEmpty()) {
                final String languageTag = Locale.getDefault().toLanguageTag();

                SystemProperties.set("persist.sys.locale", languageTag);
                SystemProperties.set("persist.sys.language", "");
                SystemProperties.set("persist.sys.country", "");
                SystemProperties.set("persist.sys.localevar", "");
            }

            // The system server should never make non-oneway calls
            Binder.setWarnOnBlocking(true);

            // Here we go!
            Slog.i(TAG, "Entered the Android system server!");
            int uptimeMillis = (int) SystemClock.elapsedRealtime();
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_SYSTEM_RUN, uptimeMillis);
            if (!mRuntimeRestart) {
                MetricsLogger.histogram(null, "boot_system_server_init", uptimeMillis);
            }

            /// M: BOOTPROF
            addBootEvent("Android:SysServerInit_START");

            // In case the runtime switched since last boot (such as when
            // the old runtime was removed in an OTA), set the system
            // property so that it is in sync. We can | xq oqi't do this in
            // libnativehelper's JniInvocation::Init code where we already
            // had to fallback to a different runtime because it is
            // running as root and we need to be the system user to set
            // the property. http://b/11463182
            SystemProperties.set("persist.sys.dalvik.vm.lib.2", VMRuntime.getRuntime().vmLibrary());

            // Enable the sampling profiler.
            if (SamplingProfilerIntegration.isEnabled()) {
                SamplingProfilerIntegration.start();
                mProfilerSnapshotTimer = new Timer();
                mProfilerSnapshotTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            SamplingProfilerIntegration.writeSnapshot("system_server", null);
                        }
                    }, SNAPSHOT_INTERVAL, SNAPSHOT_INTERVAL);
            }

            // Mmmmmm... more memory!
            VMRuntime.getRuntime().clearGrowthLimit();

            // The system server has to run all of the time, so it needs to be
            // as efficient as possible with its memory usage.
            VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);

            // Some devices rely on runtime fingerprint generation, so make sure
            // we've defined it before booting further.
            Build.ensureFingerprintProperty();

            // Within the system server, it is an error to access Environment paths without
            // explicitly specifying a user.
            Environment.setUserRequired(true);

            // Within the system server, any incoming Bundles should be defused
            // to avoid throwing BadParcelableException.
            BaseBundle.setShouldDefuse(true);

            // Ensure binder calls into the system always run at foreground priority.
            BinderInternal.disableBackgroundScheduling(true);

            // Increase the number of binder threads in system_server
            BinderInternal.setMaxThreads(sMaxBinderThreads);

            // Prepare the main looper thread (this thread).
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);
            Looper.prepareMainLooper();

            // Initialize native services.
            System.loadLibrary("android_servers");

            // Check whether we failed to shut down last time we tried.
            // This call may not return.
            performPendingShutdown();

            // Initialize the system context.
            createSystemContext();

            // Create the system service manager.
            mSystemServiceManager = new SystemServiceManager(mSystemContext);
            mSystemServiceManager.setRuntimeRestarted(mRuntimeRestart);
            LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);
            // Prepare the thread pool for init tasks that can be parallelized
            SystemServerInitThreadPool.get();
        } finally {
            traceEnd();  // InitBeforeStartServices
        }

        // Start services.
        try {
            traceBeginAndSlog("StartServices");
            startBootstrapServices();
            startCoreServices();
            startOtherServices();
            SystemServerInitThreadPool.shutdown();
        } catch (Throwable ex) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting system services", ex);
            throw ex;
        } finally {
            traceEnd();
        }

        // For debug builds, log event loop stalls to dropbox for analysis.
        if (StrictMode.conditionallyEnableDebugLogging()) {
            Slog.i(TAG, "Enabled StrictMode for system server main thread.");
        }
        /// M: open wtf when load is user or userdebug.
        if (!"eng".equals(Build.TYPE) && !mRuntimeRestart && !isFirstBootOrUpgrade()) {
            int uptimeMillis = (int) SystemClock.elapsedRealtime();
            MetricsLogger.histogram(null, "boot_system_server_ready", uptimeMillis);
            final int MAX_UPTIME_MILLIS = 60 * 1000;
            if (uptimeMillis > MAX_UPTIME_MILLIS) {
                Slog.wtf(SYSTEM_SERVER_TIMING_TAG,
                        "SystemServer init took too long. uptimeMillis=" + uptimeMillis);
            }
        }

        /// M: BOOTPROF
        addBootEvent("Android:SysServerInit_END");

        // Loop forever.
        Looper.loop();
        throw new RuntimeException("Main thread loop unexpectedly exited");
    }
```
最终的实现实在run方法里面,接下来分块分析run方法:  
```java
            if (System.currentTimeMillis() < EARLIEST_SUPPORTED_TIME) {
                Slog.w(TAG, "System clock is before 1970; setting to 1970.");
                SystemClock.setCurrentTimeMillis(EARLIEST_SUPPORTED_TIME);
            }
```
为了判断系统时钟是否早于1970/01/01/00:00,如果早于这个时间,系统后面的处理可能会出问题,所以如果早于该时间,统一设置为1970/01/01/00:00  
```java
            String timezoneProperty =  SystemProperties.get("persist.sys.timezone");
            if (timezoneProperty == null || timezoneProperty.isEmpty()) {
                Slog.w(TAG, "Timezone not set; setting to GMT.");
                SystemProperties.set("persist.sys.timezone", "GMT");
            }
```
如果没有设置时区,统一设置为GMT
```java
            if (!SystemProperties.get("persist.sys.language").isEmpty()) {
                final String languageTag = Locale.getDefault().toLanguageTag();

                SystemProperties.set("persist.sys.locale", languageTag);
                SystemProperties.set("persist.sys.language", "");
                SystemProperties.set("persist.sys.country", "");
                SystemProperties.set("persist.sys.localevar", "");
            }
```
设置系统语言环境;  
```java
            // Here we go!
            Slog.i(TAG, "Entered the Android system server!");
            int uptimeMillis = (int) SystemClock.elapsedRealtime();
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_SYSTEM_RUN, uptimeMillis);
            if (!mRuntimeRestart) {
                MetricsLogger.histogram(null, "boot_system_server_init", uptimeMillis);
            }

            /// M: BOOTPROF
            addBootEvent("Android:SysServerInit_START");

            // In case the runtime switched since last boot (such as when
            // the old runtime was removed in an OTA), set the system
            // property so that it is in sync. We can | xq oqi't do this in
            // libnativehelper's JniInvocation::Init code where we already
            // had to fallback to a different runtime because it is
            // running as root and we need to be the system user to set
            // the property. http://b/11463182
            SystemProperties.set("persist.sys.dalvik.vm.lib.2", VMRuntime.getRuntime().vmLibrary());

            // Enable the sampling profiler.
            if (SamplingProfilerIntegration.isEnabled()) {
                SamplingProfilerIntegration.start();
                mProfilerSnapshotTimer = new Timer();
                mProfilerSnapshotTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            SamplingProfilerIntegration.writeSnapshot("system_server", null);
                        }
                    }, SNAPSHOT_INTERVAL, SNAPSHOT_INTERVAL);
            }

            // Mmmmmm... more memory!
            VMRuntime.getRuntime().clearGrowthLimit();

            // The system server has to run all of the time, so it needs to be
            // as efficient as possible with its memory usage.
            VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);

            // Some devices rely on runtime fingerprint generation, so make sure
            // we've defined it before booting further.
            Build.ensureFingerprintProperty();

            // Within the system server, it is an error to access Environment paths without
            // explicitly specifying a user.
            Environment.setUserRequired(true);

            // Within the system server, any incoming Bundles should be defused
            // to avoid throwing BadParcelableException.
            BaseBundle.setShouldDefuse(true);

            // Ensure binder calls into the system always run at foreground priority.
            BinderInternal.disableBackgroundScheduling(true);

            // Increase the number of binder threads in system_server
            BinderInternal.setMaxThreads(sMaxBinderThreads);
```
这段代码的主要作用是设置虚拟机(VMRuntime)运行内存,相关的操作,(没有细致的研究过).  
```java
            // Prepare the main looper thread (this thread).
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);
            Looper.prepareMainLooper();
```
创建消息循环,和ActivityThread.java里的main()函数里建立异步消息循环一样.
```java
            // Initialize native services.
            System.loadLibrary("android_servers");
```
加载了libandroid_servers.so文件
```java
            // Check whether we failed to shut down last time we tried.
            // This call may not return.
            performPendingShutdown();
```
performPendingShutdown()函数代码如下:  
```java
    private void performPendingShutdown() {
        final String shutdownAction = SystemProperties.get(
                ShutdownThread.SHUTDOWN_ACTION_PROPERTY, "");
        if (shutdownAction != null && shutdownAction.length() > 0) {
            boolean reboot = (shutdownAction.charAt(0) == '1');

            final String reason;
            if (shutdownAction.length() > 1) {
                reason = shutdownAction.substring(1, shutdownAction.length());
            } else {
                reason = null;
            }

            // If it's a pending reboot into recovery to apply an update,
            // always make sure uncrypt gets executed properly when needed.
            // If '/cache/recovery/block.map' hasn't been created, stop the
            // reboot which will fail for sure, and get a chance to capture a
            // bugreport when that's still feasible. (Bug: 26444951)
            if (reason != null && reason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
                File packageFile = new File(UNCRYPT_PACKAGE_FILE);
                if (packageFile.exists()) {
                    String filename = null;
                    try {
                        filename = FileUtils.readTextFile(packageFile, 0, null);
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading uncrypt package file", e);
                    }

                    if (filename != null && filename.startsWith("/data")) {
                        if (!new File(BLOCK_MAP_FILE).exists()) {
                            Slog.e(TAG, "Can't find block map file, uncrypt failed or " +
                                       "unexpected runtime restart?");
                            return;
                        }
                    }
                }
            }
            ShutdownThread.rebootOrShutdown(null, reboot, reason);
        }
    }

```
依据注释,和代码名,最近一次关机操作(启动SystemServer是在开机过程中),是非正常状态,该代码将shutdown操作悬挂.(想要明白此处还要明白android的关机流程,还要努力!!!).
```java
            // Initialize the system context.
            createSystemContext();
```
看看createSystemContext()做了什么
```java
    private void createSystemContext() {
        ActivityThread activityThread = ActivityThread.systemMain();
        mSystemContext = activityThread.getSystemContext();
        mSystemContext.setTheme(DEFAULT_SYSTEM_THEME);

        final Context systemUiContext = activityThread.getSystemUiContext();
        systemUiContext.setTheme(DEFAULT_SYSTEM_THEME);
    }
```
这里还是通过ActivityThread.java来操作,和启动一个应用进程类似,只不过应用进程是使用ActivityThread.java->main(),这里是使用ActivityThread.java->systemMain();这两者一定有区别,因为普通进程是要attach到AMS的,这里AMS还没有出生...可以对比ActivityThread.java里面的代码进行分析,对比.为了不偏离主线,这里就不深入分析了.接着往下看:
```java
            // Create the system service manager.
            mSystemServiceManager = new SystemServiceManager(mSystemContext);
            mSystemServiceManager.setRuntimeRestarted(mRuntimeRestart);
            LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);
            // Prepare the thread pool for init tasks that can be parallelized
            SystemServerInitThreadPool.get();
```
启动AMS\PMS\WMS等等这些系统级的服务前,要把管理他们的服务SystemServiceManager启动起来吧,老铁,这没有问题吧~~~`LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);`将SystemServiceManager对象保存SystemServer进程中的一个数据结构中.`SystemServerInitThreadPool.get();`初始化线程池,为了初始化任务能够并行处理.这些启动系统级服务的准备工作都做好了,是不是该启动服务了:
```java
        // Start services.
        try {
            traceBeginAndSlog("StartServices");
            startBootstrapServices();
            startCoreServices();
            startOtherServices();
            SystemServerInitThreadPool.shutdown();
        } catch (Throwable ex) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting system services", ex);
            throw ex;
        } finally {
            traceEnd();
        }
```
终于到启动这些重量级的系统级的服务了,通过`startBootstrapServices()`主要用于启动系统Boot级服务 ,`startCoreServices()`主要用于启动系统核心的服务,`startOtherServices()`主要用于启动一些非紧要或者是非需要及时启动的服务.启动完成之后使用SystemServerInitThreadPool.shutdown()讲线程池关闭.接着我们注意分析`startBootstrapServices()`,`startCoreServices()`,`startOtherServices()`这三个函数.  
首先看`startBootstrapServices()`:
```java
    /**
     * Starts the small tangle of critical services that are needed to get
     * the system off the ground.  These services have complex mutual dependencies
     * which is why we initialize them all in one place here.  Unless your service
     * is also entwined in these dependencies, it should be initialized in one of
     * the other functions.
     */
    private void startBootstrapServices() {
       ......
       Installer installer = mSystemServiceManager.startService(Installer.class);
        traceEnd();
       ......
        // In some cases after launching an app we need to access device identifiers,
        // therefore register the device identifier policy before the activity manager.
        traceBeginAndSlog("DeviceIdentifiersPolicyService");
        mSystemServiceManager.startService(DeviceIdentifiersPolicyService.class);
        traceEnd();

        // Activity manager runs the show.
        traceBeginAndSlog("StartActivityManager");
        mActivityManagerService = mSystemServiceManager.startService(
                ActivityManagerService.Lifecycle.class).getService();
        mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
        mActivityManagerService.setInstaller(installer);
        traceEnd();

        // Power manager needs to be started early because other services need it.
        // Native daemons may be watching for it to be registered so it must be ready
        // to handle incoming binder calls immediately (including being able to verify
        // the permissions for those calls).
        traceBeginAndSlog("StartPowerManager");
        mPowerManagerService = mSystemServiceManager.startService(PowerManagerService.class);
        traceEnd();

        // Now that the power manager has been started, let the activity manager
        // initialize power management features.
        traceBeginAndSlog("InitPowerManagement");
        mActivityManagerService.initPowerManagement();
        traceEnd();

        // Bring up recovery system in case a rescue party needs a reboot
        if (!SystemProperties.getBoolean("config.disable_noncore", false)) {
            traceBeginAndSlog("StartRecoverySystemService");
            mSystemServiceManager.startService(RecoverySystemService.class);
            traceEnd();
        }

        // Now that we have the bare essentials of the OS up and running, take
        // note that we just booted, which might send out a rescue party if
        // we're stuck in a runtime restart loop.
        RescueParty.noteBoot(mSystemContext);

        // Manages LEDs and display backlight so we need it to bring up the display.
        traceBeginAndSlog("StartLightsService");
        mSystemServiceManager.startService(LightsService.class);
        traceEnd();

        // Display manager is needed to provide display metrics before package manager
        // starts up.
        traceBeginAndSlog("StartDisplayManager");
        mDisplayManagerService = mSystemServiceManager.startService(DisplayManagerService.class);
        traceEnd();

        // We need the default display before we can initialize the package manager.
        traceBeginAndSlog("WaitForDisplay");
        mSystemServiceManager.startBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
        traceEnd();

        // Only run "core" apps if we're encrypting the device.
        String cryptState = SystemProperties.get("vold.decrypt");
        if (ENCRYPTING_STATE.equals(cryptState)) {
            Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
            mOnlyCore = true;
        } else if (ENCRYPTED_STATE.equals(cryptState)) {
            Slog.w(TAG, "Device encrypted - only parsing core apps");
            mOnlyCore = true;
        }

        // Start the package manager.
        if (!mRuntimeRestart) {
            MetricsLogger.histogram(null, "boot_package_manager_init_start",
                    (int) SystemClock.elapsedRealtime());
        }
        traceBeginAndSlog("StartPackageManagerService");
        mPackageManagerService = PackageManagerService.main(mSystemContext, installer,
                mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);
        mFirstBoot = mPackageManagerService.isFirstBoot();
        mPackageManager = mSystemContext.getPackageManager();
        traceEnd();
        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            MetricsLogger.histogram(null, "boot_package_manager_init_ready",
                    (int) SystemClock.elapsedRealtime());
        }
        // Manages A/B OTA dexopting. This is a bootstrap service as we need it to rename
        // A/B artifacts after boot, before anything else might touch/need them.
        // Note: this isn't needed during decryption (we don't have /data anyways).
        if (!mOnlyCore) {
            boolean disableOtaDexopt = SystemProperties.getBoolean("config.disable_otadexopt",
                    false);
            if (!disableOtaDexopt) {
                traceBeginAndSlog("StartOtaDexOptService");
                try {
                    OtaDexoptService.main(mSystemContext, mPackageManagerService);
                } catch (Throwable e) {
                    reportWtf("starting OtaDexOptService", e);
                } finally {
                    traceEnd();
                }
            }
        }

        traceBeginAndSlog("StartUserManagerService");
        mSystemServiceManager.startService(UserManagerService.LifeCycle.class);
        traceEnd();

        // Initialize attribute cache used to cache resources from packages.
        traceBeginAndSlog("InitAttributerCache");
        AttributeCache.init(mSystemContext);
        traceEnd();

        // Set up the Application instance for the system process and get started.
        traceBeginAndSlog("SetSystemProcess");
        mActivityManagerService.setSystemProcess();
        traceEnd();

        // DisplayManagerService needs to setup android.display scheduling related policies
        // since setSystemProcess() would have overridden policies due to setProcessGroup
        mDisplayManagerService.setupSchedulerPolicies();

        /// M: CTA requirement - permission control  @{
        /// M: MOTA for CTA permissions handling
        /*
         * This function is used for granting CTA permissions after OTA upgrade.
         * This should be placed after AMS is added to ServiceManager and before
         * starting other services since granting permissions needs AMS instance
         * to do permission checking.
         */
        mPackageManagerService.onAmsAddedtoServiceMgr();
        /// @}

        // Manages Overlay packages
        traceBeginAndSlog("StartOverlayManagerService");
        mSystemServiceManager.startService(new OverlayManagerService(mSystemContext, installer));
        traceEnd();

        // The sensor service needs access to package manager service, app ops
        // service, and permissions service, therefore we start it after them.
        // Start sensor service in a separate thread. Completion should be checked
        // before using it.
        mSensorServiceStart = SystemServerInitThreadPool.get().submit(() -> {
            BootTimingsTraceLog traceLog = new BootTimingsTraceLog(
                    SYSTEM_SERVER_TIMING_ASYNC_TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
            traceLog.traceBegin(START_SENSOR_SERVICE);
            startSensorService();
            traceLog.traceEnd();
        }, START_SENSOR_SERVICE);
    }
```
首先分析
```java
Installer installer = mSystemServiceManager.startService(Installer.class);
```
mSystemServiceManager是系统服务管理对象,在前面介绍的run()里面已经实例化了.这里简单介绍一下Installer类，该类是系统安装apk时的一个服务类，继承SystemService（系统服务的一个抽象接口），我们需要在启动完成Installer服务之后才能启动其他的系统服务.接着可以看到ActivityManagerService,PowerManagerService,RecoverySystemService,LightsService,DisplayManagerService,UserManagerService这些服务都是使用mSystemServiceManager.startService()方法将服务启动起来.  
发现PackageManagerService服务和其他的有点不一样,他是直接调用了静态方法main()方法实现了
```java
        // Start the package manager.
        if (!mRuntimeRestart) {
            MetricsLogger.histogram(null, "boot_package_manager_init_start",
                    (int) SystemClock.elapsedRealtime());
        }
        traceBeginAndSlog("StartPackageManagerService");
        mPackageManagerService = PackageManagerService.main(mSystemContext, installer,
                mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);
        mFirstBoot = mPackageManagerService.isFirstBoot();
        mPackageManager = mSystemContext.getPackageManager();
        traceEnd();
        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            MetricsLogger.histogram(null, "boot_package_manager_init_ready",
                    (int) SystemClock.elapsedRealtime());
        }
```
分析main()函数:
```java
    public static PackageManagerService main(Context context, Installer installer,
            boolean factoryTest, boolean onlyCore) {
        // Self-check for initial settings.
        PackageManagerServiceCompilerMapping.checkProperties();

        PackageManagerService m = new PackageManagerService(context, installer,
                factoryTest, onlyCore);
        m.enableSystemUserPackages();
        ServiceManager.addService("package", m);
        return m;
    }
```
这里是直接newPackageManagerService,然后调用ServiceManager.addService,通过了binder(以后再仔细研究).
这里有一段涉及vold服务的:
```java
        // Only run "core" apps if we're encrypting the device.
        String cryptState = SystemProperties.get("vold.decrypt");
        if (ENCRYPTING_STATE.equals(cryptState)) {
            Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
            mOnlyCore = true;
        } else if (ENCRYPTED_STATE.equals(cryptState)) {
            Slog.w(TAG, "Device encrypted - only parsing core apps");
            mOnlyCore = true;
        }

```
android 4.0新增的一个功能，即设备加密（encrypting the device）,该功能由系统属性vold.decrypt指定.涉及设备安全和加密的,vold(Volume Daemon)用于管理和控制Android平台外部存储设备的后台进程，这些管理和控制，包括SD卡的插拔事件检测、SD卡挂载、卸载、格式化等。这里的设置是当我们的设备处于加密状态,只启动核心服务,通过设置mOnlyCore来进行标示.
接着分析startCoreServices()函数:  
```java
    /**
     * Starts some essential services that are not tangled up in the bootstrap process.
     */
    private void startCoreServices() {
        // Records errors and logs, for example wtf()
        traceBeginAndSlog("StartDropBoxManager");
        mSystemServiceManager.startService(DropBoxManagerService.class);
        traceEnd();

        traceBeginAndSlog("StartBatteryService");
        // Tracks the battery level.  Requires LightService.
        mSystemServiceManager.startService(BatteryService.class);
        traceEnd();

        // Tracks application usage stats.
        traceBeginAndSlog("StartUsageService");
        mSystemServiceManager.startService(UsageStatsService.class);
        mActivityManagerService.setUsageStatsManager(
                LocalServices.getService(UsageStatsManagerInternal.class));
        traceEnd();

        // Tracks whether the updatable WebView is in a ready state and watches for update installs.
        traceBeginAndSlog("StartWebViewUpdateService");
        mWebViewUpdateService = mSystemServiceManager.startService(WebViewUpdateService.class);
        traceEnd();
    }

```
启动了DropBoxManagerService(系统出问题的调用栈信息),BatteryService(电池相关的服务),UsageStatsService,WebViewUpdateService.  
最后看一下startOtherServices方法，主要用于启动系统中其他的服务，代码很多，这里就不贴代码了，启动的流程和ActivityManagerService的流程类似，会调用服务的构造方法与onStart方法初始化变量。

##总结
- SystemServer进程是android中一个很重要的进程由Zygote进程启动,是Zygote的嫡长子,如果该进程崩溃,Zygote会调用方法kill掉自己；
- SystemServer进程主要用于启动系统中的服务；
- SystemServer进程启动服务的启动函数为main函数,其实真正干活的还是在run()方法里面；
- SystemServer在执行过程中首先会初始化一些系统变量，加载类库，创建Context对象，创建SystemServiceManager对象等之后才开始启动系统服务；
- SystemServer进程将系统服务分为三类：boot服务，core服务和other服务，并逐步启动;
- SertemServer进程在尝试启动服务之前会首先尝试与Zygote建立socket通讯，只有通讯成功之后才会开始尝试启动服务；
- 创建的系统服务过程中主要通过SystemServiceManager对象来管理，通过调用服务对象的构造方法和onStart方法初始化服务的相关变量；
- 服务对象都有自己的异步消息对象，并运行在单独的线程中；

参考文章:  
<http://blog.csdn.net/qq_23547831/article/details/51105171>

