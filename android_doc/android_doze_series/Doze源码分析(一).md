# Doze源码分析(一)

**平台: Android O**

## 1 简介

低电耗模式（Doze）和应用待机模式（App standby）模式.Doze应该叫device idle mode.DeviceIdleController.是一个系统服务,可以使用
`adb shell service list | grep devicesidle`查看到该服务:
```
deviceidle: [android.os.IDeviceIdleController]
```
DeviceIdleController维护着白名单,位于白名单的list将受到App Standby的限制,使用`adb shell dumpsys deviceidle`
可以查看到该白名单,白名单分为系统应用白名单和第三方应用白名单.
其核心是`frameworks/base/services/core/java/com/android/server/DeviceIdleController.java`.

## 2 DeviceIdleController服务的启动

DeviceIdleController服务是在SystemServer中启动的,`frameworks/base/services/java/com/android/server/SystemServer.java`:

```
                traceBeginAndSlog("StartDeviceIdleController");
                mSystemServiceManager.startService(DeviceIdleController.class);
                traceEnd();

```

SystemServiceManager的startService使用反射来创建了DeviceIdleController对象,然后使用onstart初始化.
代码位于:`frameworks/base/services/core/java/com/android/server/DeviceIdleController.java`

```
    public DeviceIdleController(Context context) {
        super(context);
        // 在创建data/system/deviceidle.xml,用于记录用户设置的白名单
        mConfigFile = new AtomicFile(new File(getSystemDir(), "deviceidle.xml"));

        if(FileUtils.copyFile(new File("/system/etc/permissions","deviceidle.xml"),
            new File(getSystemDir(),"deviceidle.xml"),
            false)){
            if (DEBUG) Slog.d(TAG, "copy deviceidle.xml success");
        }

        // 创建Handler来处理消息
        mHandler = new MyHandler(BackgroundThread.getHandler().getLooper());
    }
```
接下来看看onStart()方法的具体实现:
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
上面看到的`com.android.internal.R.bool.config_enableAutoPowerModes`就是配置是否打开doze,该配置在`frameworks/base/core/res/res/values/config.xml`
```
    <!-- Set this to true to enable the platform's auto-power-save modes like doze and app standby. These are not enabled by default because they require a standard cloud-to-device messaging service for apps to interact correctly with the modes (such as to be able to deliver an instant message to the device even when it is dozing). This should be enabled if you have such services and expect apps to correctly use them when installed on your device. Otherwise, keep this disabled so that applications can still use their own mechanisms. -->
    <bool name="config_enableAutoPowerModes">false</bool>
```
一般这个都是false,google使用overlay机制在GMS包里面把这个值覆盖,改为true.位置在`vendor/partner_gms/products/gms_overlay/frameworks/base/core/res/res/values/config.xml`


## 3 Doze状态机的切换

### 3.1 DeviceIdleController的控制功能简介
上一篇文章我们介绍了Doze有5中模式切换,作为Doze模式的核心理所当然的是控制这5中状态之间的切换以及怎么控制的.这些功能的实现都在
DeviceIdleController中实现,并且通知其他相关注册了AppIdleStateChangeListener接口的服务进行处理，而反过来这些服务也可以向DeviceIdleController查询device的状态，是一种交互的关系。
这种关系如下图所示:
![doze_stateMachine](https://raw.githubusercontent.com/lqktz/document/master/res/doze_stateMachine.png)

### 3.2 状态转换简介

- 当设备亮屏或者处于正常使用状态时其就为ACTIVE状态；
- ACTIVE状态下不插充电器或者usb且灭屏设备就会切换到INACTIVE状态；
- INACTIVE状态经过30分钟，期间检测没有打断状态的行为Doze就切换到IDLE_PENDING的状态；
- 然后再经过30分钟以及一系列的判断，状态切换到SENSING；
- 在SENSING状态下会去检测是否有地理位置变化，没有的话就切到LOCATION状态；
- LOCATION状态下再经过30s的检测时间之后就进入了Doze的核心状态IDLE；
- 在IDLE模式下每隔一段时间就会进入一次IDLE_MAINTANCE，此间用来处理之前被挂起的一些任务；
- IDLE_MAINTANCE状态持续5分钟之后会重新回到IDLE状态；
- 在除ACTIVE以外的所有状态中，检测到打断的行为如亮屏、插入充电器，位置的改变等状态就会回到ACTIVE，重新开始下一个轮回。 

状态转换如下:
![doze_stateMachine](https://raw.githubusercontent.com/lqktz/document/master/res/doze_mode_state.png)




























