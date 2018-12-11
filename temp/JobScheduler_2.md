#  JobSchedulerService 的启动

前面一节介绍了JobScheduler的简单使用, 本节介绍JobSchedulerService的启动.

## SystemServer 中的启动

在`frameworks/base/services/java/com/android/server/SystemServer.java` 的 startOtherServices方法中:

```
            traceBeginAndSlog("StartJobScheduler");
            mSystemServiceManager.startService(JobSchedulerService.class);
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
                throw new RuntimeException("Failed to create " + name                                                                     
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
        //                                                                                                                          
        mHandler = new JobHandler(context.getMainLooper());                                                                                
        mConstants = new Constants();                                                                                                      
        mConstantsObserver = new ConstantsObserver(mHandler);                                                                              
        mJobSchedulerStub = new JobSchedulerStub();                                                                                        
                                                                                                                                           
        // Set up the app standby bucketing tracker                                                                                        
        mStandbyTracker = new StandbyTracker();                                                                                            
        mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);                                                           
        mUsageStats.addAppIdleStateChangeListener(mStandbyTracker);                                                                        
                                                                                                                                           
        // The job store needs to call back                                                                                                
        publishLocalService(JobSchedulerInternal.class, new LocalService());                                                               
                                                                                                                                           
        // Initialize the job store and set up any persisted jobs                                                                          
        mJobs = JobStore.initAndGet(this);        

        // Create the controllers.                                                                                                         
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























