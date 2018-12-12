#  JobSchedulerService 的启动

前面一节介绍了JobScheduler的简单使用, 本节介绍JobSchedulerService的启动.

## 1 SystemServer 中的启动

在`frameworks/base/services/java/com/android/server/SystemServer.java` 的 startOtherServices方法中:

```
            traceBeginAndSlog("StartJobScheduler");
            mSystemServiceManager.startService(JobSchedulerService.class);
            traceEnd();
            
            // 启动所有的服务的startBootPhase方法, 包括JobSchedulerService的
            traceBeginAndSlog("StartBootPhaseSystemServicesReady");
            mSystemServiceManager.startBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
            traceEnd();
```

`mSystemServiceManager` 是SystemServiceManager,只会创建一次,启动服务时, 就调用startService方法.
启动的Service都必须继承`SystemServer`.

```
    /**
     * Creates and starts a system service. The class must be a subclass of
     * {@link com.android.server.SystemService}.
     *
     * @param serviceClass A Java class that implements the SystemService interface.
     * @return The service instance, never null.
     * @throws RuntimeException if the service fails to start.
     */
    @SuppressWarnings("unchecked")
    public <T extends SystemService> T startService(Class<T> serviceClass) {
        try {                                                                                                                             
            final String name = serviceClass.getName();                                                                                   
            Slog.i(TAG, "Starting " + name);                                                                                              
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "StartService " + name);                                                      
                                                                                                                                          
            // Create the service. 确保是继承了SystemService                                                                                                      
            if (!SystemService.class.isAssignableFrom(serviceClass)) {                                                                    
                throw n:ew RuntimeException("Failed to create " + name                                                                     
                        + ": service must extend " + SystemService.class.getName());                                                      
            }                                                                                                                             
            final T service;                                                                                                              
            try { // 通过反射, 创建服务                                                                                                                    
                Constructor<T> constructor = serviceClass.getConstructor(Context.class);                                                  
                service = constructor.newInstance(mContext);                                                                              
            } catch (InstantiationException ex) {                                                                                         
                throw new RuntimeException("Failed to create service " + name                                                             
                        + ": service could not be instantiated", ex);                                                                     
            } catch (IllegalAccessException ex) {                                                                                         
                throw new RuntimeException("Failed to create service " + name                                                             
                        + ": service must have a public constructor with a Context argument", ex);                                        
            } catch (NoSuchMethodException ex) {                                                                                          
                throw new RuntimeException("Failed to create service " + name                                                             
                        + ": service must have a public constructor with a Context argument", ex);                                        
            } catch (InvocationTargetException ex) {                                                                                      
                throw new RuntimeException("Failed to create service " + name                                                             
                        + ": service constructor threw an exception", ex);                                                                
            }                                                                                                                             
                                                                                                                                          
            startService(service); // 调用到下面的startServices(@NonNull final SystemService service)
            return service;                                                                                                               
        } finally {                                                                                                                       
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);                                                                                
        }                                                                                                                                 
    }

    public void startService(@NonNull final SystemService service) {
        // Register it. 注册服务                                                                          
        mServices.add(service);                     
        // Start it.                                                         
        long time = SystemClock.elapsedRealtime();                        
        try {                                    
            service.onStart();// 启动服务
        } catch (RuntimeException ex) {
            throw new RuntimeException("Failed to start service " + service.getClass().getName()
                    + ": onStart threw an exception", ex);
        }
        warnIfTooLong(SystemClock.elapsedRealtime() - time, service, "onStart");
    }
```
 对于具体的`JobSchedulerService`,创建服务就是调用`JobSchedulerService`的构造方法,
`service.onStart()` 就是调用的`JobSchedulerService.onStart()`.

## 2 JobSchedulerService 创建

### 2.1 JobSchedulerService 的构造器

先来分析`JobSchedulerService`的构造方法:

```
    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public JobSchedulerService(Context context) {
        super(context);                                                                                                                    
                                                                                                                                           
        mLocalPM = LocalServices.getService(PackageManagerInternal.class);                                                                 
        mActivityManagerInternal = Preconditions.checkNotNull(                                                                             
                LocalServices.getService(ActivityManagerInternal.class));                                                                  
        // 创建主线程looper                                                                                                                         
        mHandler = new JobHandler(context.getMainLooper()); 
        // 初始化JobSchedulerService 里使用到的常量                                                                          
        mConstants = new Constants();
        // 继承ContentObserver类,通过实现onChange方法监听数据库的变化                                                                                                      
        mConstantsObserver = new ConstantsObserver(mHandler);
        // 创建binder服务端
        mJobSchedulerStub = new JobSchedulerStub();                                                                                        
                                                                                                                                           
        // Set up the app standby bucketing tracker
        // android P 添加的新功能---应用待机分组
        mStandbyTracker = new StandbyTracker();                                                                                            
        mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);                                                           
        mUsageStats.addAppIdleStateChangeListener(mStandbyTracker);                                                                        
                                                                                                                                           
        // The job store needs to call back                                                                                                
        publishLocalService(JobSchedulerInternal.class, new LocalService());                                                               
                                                                                                                                           
        // Initialize the job store and set up any persisted jobs
        // 初始化 JobStore                                                                       
        mJobs = JobStore.initAndGet(this);        

        // Create the controllers.
        // 8 个 controller用于监听广播, job就是依据设备的电量,网络等状态变化触发的
        mControllers = new ArrayList<StateController>();
        mControllers.add(new ConnectivityController(this));
        mControllers.add(new TimeController(this));
        mControllers.add(new IdleController(this));
        mBatteryController = new BatteryController(this);
        mControllers.add(mBatteryController);
        mStorageController = new StorageController(this);
        mControllers.add(mStorageController);
        mControllers.add(new BackgroundJobsController(this));
        mControllers.add(new ContentObserverController(this));
        mDeviceIdleJobsController = new DeviceIdleJobsController(this);
        mControllers.add(mDeviceIdleJobsController);

        // If the job store determined that it can't yet reschedule persisted jobs,
        // we need to start watching the clock.
        if (!mJobs.jobTimesInflatedValid()) {
            Slog.w(TAG, "!!! RTC not yet good; tracking time updates for job scheduling");
            context.registerReceiver(mTimeSetReceiver, new IntentFilter(Intent.ACTION_TIME_CHANGED));
        }

        mPowerControllerHelper = new PowerControllerHelper();
    }
```

### 2.2 JobHandler 创建

```
    final private class JobHandler extends Handler {
                                                                                                                                          
        public JobHandler(Looper looper) {                                                                                                
            super(looper); // 说明JobHandler是使用了system_server进程主线程的looper
        }                                                                                                                                 
                                                                                                                                          
        @Override                                                                                                                         
        public void handleMessage(Message message) {                                                                                      
            synchronized (mLock) {                                                                                                        
                if (!mReadyToRock) {                                                                                                      
                    return;                                                                                                               
                }                                                                                                                         
                switch (message.what) {                                                                                                   
                    case MSG_JOB_EXPIRED: {                                                                                               
                        JobStatus runNow = (JobStatus) message.obj;                                                                       
                        // runNow can be null, which is a controller's way of indicating that its                                         
                        // state is such that all ready jobs should be run immediately.                                                   
                        if (runNow != null && isReadyToBeExecutedLocked(runNow)) {                                                        
                            mJobPackageTracker.notePending(runNow);                                                                       
                            addOrderedItem(mPendingJobs, runNow, mEnqueueTimeComparator);                                                 
                        } else {                                                                                                          
                            queueReadyJobsForExecutionLocked();                                                                           
                        }                                                                                                                 
                    } break;                                                                                                              
                    case MSG_CHECK_JOB:                                                                                                   
                        if (mReportedActive) {                                                                                            
                            // if jobs are currently being run, queue all ready jobs for execution.                                       
                            queueReadyJobsForExecutionLocked();                                                                           
                        } else {                                                                                                          
                            // Check the list of jobs and run some of them if we feel inclined.                                           
                            maybeQueueReadyJobsForExecutionLocked();                                                                      
                        }                                                                                                                 
                        break; 
			.....// 好多case,不一一列出
                }
                maybeRunPendingJobsLocked();
                // Don't remove JOB_EXPIRED in case one came along while processing the queue.
                removeMessages(MSG_CHECK_JOB);
            }
        }
    }
                                                                                                           

```

### 2.3 JobSchedulerStub binder 服务端创建

```
    /**
     * Binder stub trampoline implementation
     */
    // 实现了 IJobScheduler 的 binder 服务端
    final class JobSchedulerStub extends IJobScheduler.Stub {
		......
    }
```

### 2.4 应用待机分组

专门的文章来分析

### 2.5 初始化JobStore

在`frameworks/baseservices/core/java/com/android/server/job/JobStore.java` .

### 2.5.1 创建JobStore

```
    /** Used by the {@link JobSchedulerService} to instantiate the JobStore. */
    static JobStore initAndGet(JobSchedulerService jobManagerService) { 
        synchronized (sSingletonLock) {
            if (sSingleton == null) {
                sSingleton = new JobStore(jobManagerService.getContext(),
                        jobManagerService.getLock(), Environment.getDataDirectory());
            }
            return sSingleton;
        }
    }
```

单例模式创建`JobStore`,看来在系统里面`jobstore`是唯一的,接着看JobStore的创建:

```
    /**
     * Construct the instance of the job store. This results in a blocking read from disk.
     */
    private JobStore(Context context, Object lock, File dataDir) {
        mLock = lock;
        mContext = context;
        mDirtyOperations = 0;
        // 在/data 目录下创建system目录
        File systemDir = new File(dataDir, "system");
        // 在/data/system目录下创建job目录
        File jobDir = new File(systemDir, "job");
        // /data/system/job目录创建完毕
        jobDir.mkdirs();
        // mJobsFile就是`/data/system/job/jobs.xml`
        mJobsFile = new AtomicFile(new File(jobDir, "jobs.xml"), "jobs");
        // 用于记录所有的 JobStatus对象
        mJobSet = new JobSet(); // 注意此处的mJobSet是JobStore这个类的变量

        // If the current RTC is earlier than the timestamp on our persisted jobs file,
        // we suspect that the RTC is uninitialized and so we cannot draw conclusions
        // about persisted job scheduling.
        //
        // Note that if the persisted jobs file does not exist, we proceed with the
        // assumption that the RTC is good.  This is less work and is safe: if the
        // clock updates to sanity then we'll be saving the persisted jobs file in that
        // correct state, which is normal; or we'll wind up writing the jobs file with
        // an incorrect historical timestamp.  That's fine; at worst we'll reboot with
        // a *correct* timestamp, see a bunch of overdue jobs, and run them; then
        // settle into normal operation.
        // 如果RTC的时间比我们在jobs.xml里面的时间戳好早,可能RTC没有被初始化,这样的情况下,执行持久化的job,可能会在RTC回复正常是,系统会抽风
        // 这里的mRtcGood为true则说明RTC正常
        mXmlTimestamp = mJobsFile.getLastModifiedTime();
        mRtcGood = (sSystemClock.millis() > mXmlTimestamp);
        // 详见2.5.2
        readJobMapFromDisk(mJobSet, mRtcGood);
    }
```

#### 2.5.2 readJobMapFromDisk实现

`readJobMapFromDisk(mJobSet, mRtcGood)`的实现如下:

```
    @VisibleForTesting
    public void readJobMapFromDisk(JobSet jobSet, boolean rtcGood) {
        new ReadJobMapFromDiskRunnable(jobSet, rtcGood).run();// 实现了Runnable的run方法
    }
```

ReadJobMapFromDiskRunnable类的定义和run方法实现:
```
    /**
     * Runnable that reads list of persisted job from xml. This is run once at start up, so doesn't
     * need to go through {@link JobStore#add(com.android.server.job.controllers.JobStatus)}.
     */
    private final class ReadJobMapFromDiskRunnable implements Runnable {
        private final JobSet jobSet;
        private final boolean rtcGood;

        ReadJobMapFromDiskRunnable(JobSet jobSet, boolean rtcIsGood) {
            this.jobSet = jobSet;
            this.rtcGood = rtcIsGood;
        }
        @Override
        public void run() { // 开启线程执行操作, 看来可能是一个耗时的操作
            int numJobs = 0;
            int numSystemJobs = 0;
            int numSyncJobs = 0;
            try {
                List<JobStatus> jobs;
                // mJobsFile -- > `/data/system/job/jobs.xml`
                FileInputStream fis = mJobsFile.openRead();
                synchronized (mLock) {
                    // 
                    jobs = readJobMapImpl(fis, rtcGood);
                    if (jobs != null) {
                        long now = sElapsedRealtimeClock.millis();
                        IActivityManager am = ActivityManager.getService();
                        for (int i=0; i<jobs.size(); i++) {
                            // 读取jobs里面对应的JobStatus对象
                            JobStatus js = jobs.get(i);
                            js.prepareLocked(am);
                            js.enqueueTime = now;
                            // 将读取的JobStatus对象保存在jobSet中
                            this.jobSet.add(js);

                            numJobs++;
                            if (js.getUid() == Process.SYSTEM_UID) {
                                numSystemJobs++;
                                if (isSyncJob(js)) {
                                    numSyncJobs++;
                                }
                            }
                        }
                    }
                }
                fis.close();
            } catch (FileNotFoundException e) {
             .........
```

先分析jobSet.add过程:jobSet由`JobSet mJobSet` 参数传参来的,这里就分析JobSet结构就行: 

mJobSet 的定义方式: 

```
        @VisibleForTesting // Key is the getUid() originator of the jobs in each sheaf
        final SparseArray<ArraySet<JobStatus>> mJobs;

        public JobSet() {
            mJobs = new SparseArray<ArraySet<JobStatus>>();
            mJobsPerSourceUid = new SparseArray<>();
        }

        public boolean add(JobStatus job) {
            final int uid = job.getUid();
            final int sourceUid = job.getSourceUid();
            ArraySet<JobStatus> jobs = mJobs.get(uid); // 查看有没有该uid的集合(ArraySet<JobStatus>)
            if (jobs == null) { // 该uid是第一次创建job,就重新创建
                jobs = new ArraySet<JobStatus>();
                mJobs.put(uid, jobs); // 并将ArraySet<JobStatus>添加到
            }
            ArraySet<JobStatus> jobsForSourceUid = mJobsPerSourceUid.get(sourceUid);
            if (jobsForSourceUid == null) {
                jobsForSourceUid = new ArraySet<>();
                mJobsPerSourceUid.put(sourceUid, jobsForSourceUid);
            }
            final boolean added = jobs.add(job);// 将job(JobStatus) 添加到ArraySet<JobStatus> 中
            final boolean addedInSource = jobsForSourceUid.add(job);
            if (added != addedInSource) {
                Slog.wtf(TAG, "mJobs and mJobsPerSourceUid mismatch; caller= " + added
                        + " source= " + addedInSource);
            }
            return added || addedInSource;
        }
```

`mJobs` 是`SparseArray<ArraySet<JobStatus>>`类型, mJobs是所有应用的job的集合, ArraySet<JobStatus>是同一个uid的job的集合.
JobStatus是app定义的一个jobinfo的封装, 这个封装过程是在`List<JobStatus> jobs = readJobMapImpl(fis, rtcGood)`, jobs保存一个个的JobStatus,
这些JobStatus是来自`readJobMapImpl(fis, rtcGood)`,定义job是这样的:`JobInfo jobInfo = new JobInfo.Builder`,怎么就从`JobInfo`变成`JobStatus`类型???
详见**2.5.3 readJobMapImpl**

#### 2.5.3 readJobMapImpl实现

`frameworks/base/services/core/java/com/android/server/job/JobStore.java`

##### 2.5.3.1 readJobMapImpl实现
这里的`readJobMapImpl`就是解析`/data/system/job/jobs.xml`, 那么这里给出jobs.xml的内容demo, 有助于帮助理解解析过程:

```
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<job-info version="0">
    <job jobid="10087" package="com.lq.tct.tctapplication" class="com.lq.tct.tctapplication.MyJobService" sourcePackageName="com.lq.tct.tctapplication" sourceUserId="0" uid="10174" priority="0" flags="0" lastSuccessfulRunTime="0" lastFailedRunTime="0">
        <constraints unmetered="true" charging="true" />
        <one-off deadline="1264103317926" delay="1264103270271" backoff-policy="0" initial-backoff="10000" />
        <extras />
    </job>
    <job jobid="1" package="com.tencent.mobileqq" class="com.tencent.mobileqq.msf.service.MSFAliveJobService" sourcePackageName="com.tencent.mobileqq" sourceUserId="0" uid="10163" priority="0" flags="0" lastSuccessfulRunTime="1262980997656" lastFailedRunTime="0">
        <constraints />
        <periodic period="900000" flex="900000" deadline="1262982797615" delay="1262981897615" />
        <extras />
    </job>
		.....
</job-info>
```

好了, 下面的代码就是把上面的格式的xml,读取出来, 然后封装成 `JobStatus` , 最后装到List里面:

```
        private List<JobStatus> readJobMapImpl(FileInputStream fis, boolean rtcIsGood)
                throws XmlPullParserException, IOException {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());

            int eventType = parser.getEventType();
            ......

            String tagName = parser.getName();
            if ("job-info".equals(tagName)) { // job-info 是 jobsxml的开始标签, 说明开始解析了
                final List<JobStatus> jobs = new ArrayList<JobStatus>(); // 创建好存JobStatus的容器
                // Read in version info.
                try {// 读取jobs的版本号, 不符合就game over
                    int version = Integer.parseInt(parser.getAttributeValue(null, "version"));
                    if (version != JOBS_FILE_VERSION) {
                        Slog.d(TAG, "Invalid version number, aborting jobs file read.");
                        return null;
                    }
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Invalid version number, aborting jobs file read.");
                    return null;
                }
                eventType = parser.next();
                do { // 一切检查都pass
                    // Read each <job/>
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        // Start reading job.
                        if ("job".equals(tagName)) { 
                            // 将一个job标签里的内容封装成一个JobStatus,从persistedJob名字也可以看出,这是从持久化文件jobs读取到的一个JobStatus
                            // restoreJobFromXml无疑将成为重点,注意这个和RTC时间相关的变量还在参数列表中
                            JobStatus persistedJob = restoreJobFromXml(rtcIsGood, parser); 
                            if (persistedJob != null) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Read out " + persistedJob);
                                }
                                jobs.add(persistedJob);
                            } else {
                                Slog.d(TAG, "Error reading job from file.");
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
                return jobs;
            }
            return null; // 没有找到job-info标签,直接返回
        }
```

##### 2.5.3.2 restoreJobFromXml实现
接着分析`restoreJobFromXml`的实现:

```
        // 作用就是解析一个job标签的内容, 封装成JobStatus
        private JobStatus restoreJobFromXml(boolean rtcIsGood, XmlPullParser parser)
                throws XmlPullParserException, IOException {
            JobInfo.Builder jobBuilder;
            int uid, sourceUserId;
            long lastSuccessfulRunTime;
            long lastFailedRunTime;
            int internalFlags = 0;

            // Read out job identifier attributes and priority.
            try {
                // 最终调用JobInfo.Builder构建JobInfo. 详见2.5.3.3
                jobBuilder = buildBuilderFromXml(parser);
                jobBuilder.setPersisted(true);

                // 依次解析uid,priority,flags等,并在jobBuilder调用setPriority设置,这就是在构建JobInfo呀!!!
                uid = Integer.parseInt(parser.getAttributeValue(null, "uid"));

                String val = parser.getAttributeValue(null, "priority");
                if (val != null) {
                    jobBuilder.setPriority(Integer.parseInt(val));
                }
                                      ....
            }

            ......// 读取参数, 以及获取时间方面的参数
            

            // And now we're done
            JobSchedulerInternal service = LocalServices.getService(JobSchedulerInternal.class);
            // 获取sourcePackageName的应用待机分组的bucket, 由于不同的bucket在job的执行时间点上有不同的延迟
            final int appBucket = JobSchedulerService.standbyBucketForPackage(sourcePackageName,
                    sourceUserId, elapsedNow);
            // 获得job的心跳
            long currentHeartbeat = service != null ? service.currentHeartbeat() : 0;
            // 关于job的所有信息都知道了, 创建JobStatus
            JobStatus js = new JobStatus(
                    jobBuilder.build(), uid, sourcePackageName, sourceUserId,
                    appBucket, currentHeartbeat, sourceTag,
                    elapsedRuntimes.first, elapsedRuntimes.second,
                    lastSuccessfulRunTime, lastFailedRunTime,
                    (rtcIsGood) ? null : rtcRuntimes, internalFlags); // rtcIsGood 这个和RTC时间是否正常的标签,在这里的到了使用
            return js;
        }

```
##### 2.5.3.3 buildBuilderFromXml实现

下面是buildBuilderFromXml的实现, 终于和`JobInfo jobInfo = new JobInfo.Builder`对应上了:

```
        private JobInfo.Builder buildBuilderFromXml(XmlPullParser parser) throws NumberFormatException {
            // Pull out required fields from <job> attributes.
            int jobId = Integer.parseInt(parser.getAttributeValue(null, "jobid"));
            String packageName = parser.getAttributeValue(null, "package");
            String className = parser.getAttributeValue(null, "class");
            ComponentName cname = new ComponentName(packageName, className);

            return new JobInfo.Builder(jobId, cname);
        }
```

##### 2.5.3.4 JobStatus实现

```
    /**
     * Create a new JobStatus that was loaded from disk. We ignore the provided
     * {@link android.app.job.JobInfo} time criteria because we can load a persisted periodic job
     * from the {@link com.android.server.job.JobStore} and still want to respect its
     * wallclock runtime rather than resetting it on every boot.
     * We consider a freshly loaded job to no longer be in back-off, and the associated
     * standby bucket is whatever the OS thinks it should be at this moment.
     */
    // 注释中就说了,创建一个JobStatus从磁盘加载来的
    // 注意参数中的JobInfo job, 是由于`new JobStatus` 调用了`jobBuilder.build()`
    public JobStatus(JobInfo job, int callingUid, String sourcePkgName, int sourceUserId,
            int standbyBucket, long baseHeartbeat, String sourceTag,
            long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis,
            long lastSuccessfulRunTime, long lastFailedRunTime,
            Pair<Long, Long> persistedExecutionTimesUTC,
            int innerFlags) { 
        this(job, callingUid, resolveTargetSdkVersion(job), sourcePkgName, sourceUserId,
                standbyBucket, baseHeartbeat,
                sourceTag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis,
                lastSuccessfulRunTime, lastFailedRunTime, innerFlags);

        // Only during initial inflation do we record the UTC-timebase execution bounds
        // read from the persistent store.  If we ever have to recreate the JobStatus on
        // the fly, it means we're rescheduling the job; and this means that the calculated
        // elapsed timebase bounds intrinsically become correct.
        this.mPersistedUtcTimes = persistedExecutionTimesUTC;
        if (persistedExecutionTimesUTC != null) {
            if (DEBUG) {
                Slog.i(TAG, "+ restored job with RTC times because of bad boot clock");
            }
        }
    }
```

从磁盘读取持久化的job, 就是JobStore的初始化过程,梳理一下: 

 - 从`/data/system/job/jobs.xml`中解析job标签,构建成`JobInfo`
 - 将`JobInfo`封装成`JobStatus`
 - `JobStatus`被添加到`ArraySet<JobStatus>`集合,同一个uid的`JobStatus`都会被放到同一个`ArraySet`
 - 所有的`ArraySet`又会放到一个`SparseArray<ArraySet<JobStatus>>`集合. `mJobs`就是这个`SparseArray<ArraySet<JobStatus>>`.

创建完`JobStore`, 现在回到`JobSchedulerService`的构造函数里,接着分析.

### 2.6 controller 的创建

看下controller的构造方法:

```
        // Create the controllers.
        // 8 个 controller用于监听广播, job就是依据设备的电量,网络等状态变化触发的
        mControllers = new ArrayList<StateController>();
        mControllers.add(new ConnectivityController(this));
        mControllers.add(new TimeController(this));
        mControllers.add(new IdleController(this));
        // 这样构造BatteryController, 是由于mBatteryController 在其他地方还要使用
        mBatteryController = new BatteryController(this);
        mControllers.add(mBatteryController);
        mStorageController = new StorageController(this);
        mControllers.add(mStorageController);
        mControllers.add(new BackgroundJobsController(this));
        mControllers.add(new ContentObserverController(this));
        mDeviceIdleJobsController = new DeviceIdleJobsController(this);
        mControllers.add(mDeviceIdleJobsController);
```

各类controller用于检测, 设备的各种状态, 用于触发job的执行

## 3 JobSchedulerService.onStart()

```
    public void onStart() {
        publishBinderService(Context.JOB_SCHEDULER_SERVICE, mJobSchedulerStub);
    }
```

调用父类`frameworks/base/services/core/java/com/android/server/SystemService.java`的`publishBinderService`方法

```
    /**
     * Publish the service so it is accessible to other services and apps.
     *
     * @param name the name of the new service
     * @param service the service object
     */
    protected final void publishBinderService(String name, IBinder service) {
        publishBinderService(name, service, false);
    }

        /**
     * Publish the service so it is accessible to other services and apps.
     *
     * @param name the name of the new service
     * @param service the service object
     * @param allowIsolated set to true to allow isolated sandboxed processes
     * to access this service
     */
    protected final void publishBinderService(String name, IBinder service,
            boolean allowIsolated) {
        publishBinderService(name, service, allowIsolated, DUMP_FLAG_PRIORITY_DEFAULT);
    }

    /**
     * Publish the service so it is accessible to other services and apps.
     *
     * @param name the name of the new service
     * @param service the service object
     * @param allowIsolated set to true to allow isolated sandboxed processes
     * to access this service
     * @param dumpPriority supported dump priority levels as a bitmask
     */
    protected final void publishBinderService(String name, IBinder service,
            boolean allowIsolated, int dumpPriority) {
        ServiceManager.addService(name, service, allowIsolated, dumpPriority);
    }
```

最终就是调用了`ServiceManager.addService`, 注册binder服务.

## 4 JobSchedulerService.onBootPhase阶段

### 4.1 onBootPhase

```
    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mConstantsObserver.start(getContext().getContentResolver());

            mAppStateTracker = Preconditions.checkNotNull(
                    LocalServices.getService(AppStateTracker.class));
            setNextHeartbeatAlarm();

            // Register br for package removals and user removals.
            final IntentFilter filter = new IntentFilter();
            // 注册需要监听的监听广播
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
            filter.addDataScheme("package");
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, filter, null, null);
            final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);
            try {
                ActivityManager.getService().registerUidObserver(mUidObserver,
                        ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE
                        | ActivityManager.UID_OBSERVER_IDLE | ActivityManager.UID_OBSERVER_ACTIVE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }
            // Remove any jobs that are not associated with any of the current users.
            cancelJobsForNonExistentUsers();
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mLock) {
                // Let's go!
                mReadyToRock = true;
                mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                        BatteryStats.SERVICE_NAME));
                mLocalDeviceIdleController
                        = LocalServices.getService(DeviceIdleController.LocalService.class);
                // Create the "runners".
                for (int i = 0; i < MAX_JOB_CONTEXTS_COUNT; i++) {
                    mActiveServices.add( // 创建JobServiceContext
                            new JobServiceContext(this, mBatteryStats, mJobPackageTracker,
                                    getContext().getMainLooper()));
                }
                // Attach jobs to their controllers.
                mJobs.forEachJob((job) -> {
                    for (int controller = 0; controller < mControllers.size(); controller++) {
                        final StateController sc = mControllers.get(controller);
                        sc.maybeStartTrackingJobLocked(job, null);
                    }
                });
                // GO GO GO! job的执行
                mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
            }
        }
    }
```

MAX_JOB_CONTEXTS_COUNT指同时执行的job数量, android p默认是16. 在android O上是区分低内存设备的.

### 4.2 JobServiceContext

```
    JobServiceContext(Context context, Object lock, IBatteryStats batteryStats,
            JobPackageTracker tracker, JobCompletedListener completedListener, Looper looper) {
        mContext = context;
        mLock = lock;
        mBatteryStats = batteryStats;
        mJobPackageTracker = tracker;
        mCallbackHandler = new JobServiceHandler(looper);
        mCompletedListener = completedListener;
        mAvailable = true;
        mVerb = VERB_FINISHED;
        mPreferredUid = NO_PREFERRED_UID;
    }
```

## 参考Blog
 - [理解JobScheduler机制](http://gityuan.com/2017/03/10/job_scheduler_service/)
 - [ContentObserver](https://www.jianshu.com/p/3bc164010b5f)
 - [Binder学习笔记（七）—— ServiceManager如何响应addService请求](https://www.cnblogs.com/palance/p/5472315.html)
















