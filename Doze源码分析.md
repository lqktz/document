#Doze源码分析

**平台: Android O**

Doze模式主要是使用DeviceIdleController.java来控制实现的.里面实现了两大块:初始化,状态跳转控制.
此处的Doze和PMS里面的Doze不一样,这里的Doze更应该叫device idle mode.DeviceIdleController是一个服务,可以使用
adb shell services list | grep devicesidle.查看到该服务.DeviceIdleController维护着白名单,使用adb shell dumpsys deviceidle
可以查看到该白名单,白名单分为系统应用白名单和三方应用白名单.

##初始化

###DeviceIdleController创建
DeviceIdleController是在SystemServer中启动的
```
mSystemServiceManager.startService(DeviceIdleController.class);
```
SystemServiceManager的startService使用反射来创建了DeviceIdleController对象,然后使用onstart初始化.
```
    public DeviceIdleController(Context context) {
        super(context);
        // 在创建data/system/deviceidle.xml,用于记录用户设置的白名单
        mConfigFile = new AtomicFile(new File(getSystemDir(), "deviceidle.xml"));
        //[FEATURE]-Add-BEGIN by ziping.zeng@tcl.com, 2016/11/8, task3377129
        if(FileUtils.copyFile(new File("/system/etc/permissions","deviceidle.xml"),
            new File(getSystemDir(),"deviceidle.xml"),
            false)){
            if (DEBUG) Slog.d(TAG, "copy deviceidle.xml success");
        }
        //[FEATURE]-Add-END by ziping.zeng@tcl.com, 2016/11/8, task3377129
        // 创建Handler来处理消息
        mHandler = new MyHandler(BackgroundThread.getHandler().getLooper());
    }
```

###onstart
```
    @Override
    public void onStart() {
        final PackageManager pm = getContext().getPackageManager();

        synchronized (this) {
            // Doze模式分light doze 和deep doze,这里对这两个开关进行初始化
            mLightEnabled = mDeepEnabled = getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_enableAutoPowerModes);

            /// M: Config Doze and App Standby {
            if (SystemProperties.get(CONFIG_AUTO_POWER, "0").equals(ENABLE_LIGHT_DOZE)) {
                mDeepEnabled = false;
                mLightEnabled = true;
            } else if (SystemProperties.get(CONFIG_AUTO_POWER, "0").equals(ENABLE_DEEP_DOZE)) {
                mDeepEnabled = true;
                mLightEnabled = false;
            }else if (SystemProperties.get(CONFIG_AUTO_POWER, "0").
                      equals(ENABLE_LIGHT_AND_DEEP_DOZE)) {
                mDeepEnabled = true;
                mLightEnabled = true;
            }
            // Config Doze and App Standby }
            // sysConfig和PKMS有关,保存PKMS解析xml的信息
            SystemConfig sysConfig = SystemConfig.getInstance();
            // 获取除了idle模式,其他省电模式能使用的
            ArraySet<String> allowPowerExceptIdle = sysConfig.getAllowInPowerSaveExceptIdle();
            for (int i=0; i<allowPowerExceptIdle.size(); i++) {
                String pkg = allowPowerExceptIdle.valueAt(i);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg,
                            PackageManager.MATCH_SYSTEM_ONLY);
                    int appid = UserHandle.getAppId(ai.uid);
                    mPowerSaveWhitelistAppsExceptIdle.put(ai.packageName, appid);
                    mPowerSaveWhitelistSystemAppIdsExceptIdle.put(appid, true);
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
            // 获取省电模式下的白名单
            ArraySet<String> allowPower = sysConfig.getAllowInPowerSave();
            for (int i=0; i<allowPower.size(); i++) {
                String pkg = allowPower.valueAt(i);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg,
                            PackageManager.MATCH_SYSTEM_ONLY);
                    int appid = UserHandle.getAppId(ai.uid);
                    // These apps are on both the whitelist-except-idle as well
                    // as the full whitelist, so they apply in all cases.
                    mPowerSaveWhitelistAppsExceptIdle.put(ai.packageName, appid);
                    mPowerSaveWhitelistSystemAppIdsExceptIdle.put(appid, true);
                    mPowerSaveWhitelistApps.put(ai.packageName, appid);
                    mPowerSaveWhitelistSystemAppIds.put(appid, true);
                } catch (PackageManager.NameNotFoundException e) {
                }
            }

            mConstants = new Constants(mHandler, getContext().getContentResolver());

            readConfigFileLocked();
            updateWhitelistAppIdsLocked();

            mNetworkConnected = true;
            mScreenOn = true;
            // Start out assuming we are charging.  If we aren't, we will at least get
            // a battery update the next time the level drops.
            mCharging = true;
            mState = STATE_ACTIVE;
            mLightState = LIGHT_STATE_ACTIVE;
            mInactiveTimeout = mConstants.INACTIVE_TIMEOUT;
        }

        mBinderService = new BinderService();
        publishBinderService(Context.DEVICE_IDLE_CONTROLLER, mBinderService);
        publishLocalService(LocalService.class, new LocalService());
    }

```

###onBootPhase


##状态跳转控制

###light doze


###deep doze

