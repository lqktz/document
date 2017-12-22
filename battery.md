#Android O电量统计

平台:Android O
***
## 电量统计  
电池电量统计:  

- 软件电量计算:BatteryStatsHelper类中的processAppUsage()方法  
- 硬件电量计算:BatteryStatsHelper类中的processMiscUsage()方法  
***
`frameworks/base/core/java/com/android/internal/os/BatteryStatsHelper.java`  
`framework/base/core/java/com/andoroid/internal/os/PowerProfile.java`
`device/mediateksample/a3a64g/overlay/frameworks/base/core/res/res/xml/power_profile.xml`  
`frameworks/base/core/java/com/android/internal/os/CpuPowerCalculator.java`  
`frameworks/base/core/java/com/android/internal/os/WakelockPowerCalculator.java`  
`frameworks/base/core/java/com/android/internal/os/MobileRadioPowerCalculator.java`  
`frameworks/base/core/java/com/android/internal/os/WifiPowerCalculator.java`  
`frameworks/base/core/java/com/android/internal/os/WifiPowerEstimator.java`  
`frameworks/base/core/java/com/android/internal/os/BluetoothPowerCalculator.java`  
`frameworks/base/core/java/com/android/internal/os/SensorPowerCalculator.java`  
`frameworks/base/core/java/com/android/internal/os/CameraPowerCalculator.java`  
`frameworks/base/core/java/com/android/internal/os/FlashlightPowerCalculator.java`  
***
耗电类型:  
```
    public enum DrainType {
        IDLE,
        CELL,
        PHONE,
        WIFI,
        BLUETOOTH,
        FLASHLIGHT,
        SCREEN,
        APP,
        USER,
        UNACCOUNTED,
        OVERCOUNTED,
        CAMERA,
        MEMORY
    }
```

###1 软件电量统计  
软件的耗电,都属于上面分类的APP类别  
#### BatteryStatsHelper.java-->processAppUsage()
```
    private void processAppUsage(SparseArray<UserHandle> asUsers) {
        // 对所有用户进行统计?
        final boolean forAllUsers = (asUsers.get(UserHandle.USER_ALL) != null);
        mStatsPeriod = mTypeBatteryRealtimeUs;

        BatterySipper osSipper = null;
        // 获取每一个uid的统计信息
        final SparseArray<? extends Uid> uidStats = mStats.getUidStats();
        final int NU = uidStats.size();
        // 遍历每个uid的耗电
        for (int iu = 0; iu < NU; iu++) {
            final Uid u = uidStats.valueAt(iu);
            // 统计归属于BatterySipper.DrainType.APP类别
            final BatterySipper app = new BatterySipper(BatterySipper.DrainType.APP, u, 0);

            mCpuPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mWakelockPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mMobileRadioPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs,
                    mStatsType);
            mWifiPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mBluetoothPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs,
                    mStatsType);
            mSensorPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mCameraPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mFlashlightPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs,
                    mStatsType);

            final double totalPower = app.sumPower();// 耗电累加
            if (DEBUG && totalPower != 0) {
                Log.d(TAG, String.format("UID %d: total power=%s", u.getUid(),
                        makemAh(totalPower)));
            }

            // 此时耗电统计已经完成,只是为了将数据分类统计.
            // Add the app to the list if it is consuming power.
            if (totalPower != 0 || u.getUid() == 0) {//有耗电去统计,
                // Add the app to the app list, WiFi, Bluetooth, etc, or into "Other Users" list.
                //
                final int uid = app.getUid();
                final int userId = UserHandle.getUserId(uid);
                if (uid == Process.WIFI_UID) {// mWifiSippers保存使用了wifi的app及其对应的功耗
                    mWifiSippers.add(app);
                } else if (uid == Process.BLUETOOTH_UID) {// mBluetoothSippers保存使用了Bluetooth的app及其对应的功耗
                    mBluetoothSippers.add(app);
                } else if (!forAllUsers && asUsers.get(userId) == null
                        && UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID) {
                    // We are told to just report this user's apps as one large entry.
                    List<BatterySipper> list = mUserSippers.get(userId);
                    if (list == null) {
                        list = new ArrayList<>();
                        mUserSippers.put(userId, list);
                    }
                    list.add(app);
                } else {
                    mUsageList.add(app);
                }

                if (uid == 0) {// 如果这次统计的是OS的耗电,那么初始化变量osSipper
                    osSipper = app;
                }
            }
        }

        // osSipper被初始化过,也就是上面统计的uid里面有系统的uid,下面把系统的耗电累加起来
        if (osSipper != null) {// 长时间cpu唤醒,但是屏幕没有亮,该部分的耗算入OS耗电
            // The device has probably been awake for longer than the screen on
            // time and application wake lock time would account for.  Assign
            // this remainder to the OS, if possible.
            mWakelockPowerCalculator.calculateRemaining(osSipper, mStats, mRawRealtimeUs,
                    mRawUptimeUs, mStatsType);
            // OS耗电求和
            osSipper.sumPower();
        }
    }
```
其中mStatsType 定义为:
```
   private int mStatsType = BatteryStats.STATS_SINCE_CHARGED;
```
表明是从上次充满电算起,实测中表明,充满电要拔掉充电线才清零开始算.  
mStatsType的状态有三种,下面的定义来自BatteryStats.java  
```
    /**
     * Include all of the data in the stats, including previously saved data.
     */
    public static final int STATS_SINCE_CHARGED = 0;

    /**
     * Include only the current run in the stats.
     */
    public static final int STATS_CURRENT = 1;

    /**
     * Include only the run since the last time the device was unplugged in the stats.
     */
    public static final int STATS_SINCE_UNPLUGGED = 2;
```
##### 1.1cpu耗电计算  
`frameworks/base/core/java/com/android/internal/os/CpuPowerCalculator.java`  
```
    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {

        app.cpuTimeMs = (u.getUserCpuTimeUs(statsType) + u.getSystemCpuTimeUs(statsType)) / 1000;

        // Aggregate total time spent on each cluster.
        long totalTime = 0;
        final int numClusters = mProfile.getNumCpuClusters();
        for (int cluster = 0; cluster < numClusters; cluster++) {
            final int speedsForCluster = mProfile.getNumSpeedStepsInCpuCluster(cluster);
            for (int speed = 0; speed < speedsForCluster; speed++) {
                totalTime += u.getTimeAtCpuSpeed(cluster, speed, statsType);
            }
        }
        totalTime = Math.max(totalTime, 1);

        double cpuPowerMaMs = 0;
        for (int cluster = 0; cluster < numClusters; cluster++) {//cluster是指cpu的簇数量,在电池配置文件中有(1)
            final int speedsForCluster = mProfile.getNumSpeedStepsInCpuCluster(cluster);
            for (int speed = 0; speed < speedsForCluster; speed++) {//speed值cpu的频点数(8)
                final double ratio = (double) u.getTimeAtCpuSpeed(cluster, speed, statsType) /
                        totalTime;
                final double cpuSpeedStepPower = ratio * app.cpuTimeMs *
                        mProfile.getAveragePowerForCpu(cluster, speed);//不同cpu频率的单位耗电在电池配置文件中
                if (DEBUG && ratio != 0) {
                    Log.d(TAG, "UID " + u.getUid() + ": CPU cluster #" + cluster + " step #"
                            + speed + " ratio=" + BatteryStatsHelper.makemAh(ratio) + " power="
                            + BatteryStatsHelper.makemAh(cpuSpeedStepPower / (60 * 60 * 1000)));
                }
                cpuPowerMaMs += cpuSpeedStepPower;
            }
        }
        // 保存cpu的耗电
        app.cpuPowerMah = cpuPowerMaMs / (60 * 60 * 1000);

        if (DEBUG && (app.cpuTimeMs != 0 || app.cpuPowerMah != 0)) {
            Log.d(TAG, "UID " + u.getUid() + ": CPU time=" + app.cpuTimeMs + " ms power="
                    + BatteryStatsHelper.makemAh(app.cpuPowerMah));
        }

        // Keep track of the package with highest drain.
        double highestDrain = 0;

        app.cpuFgTimeMs = 0;
        final ArrayMap<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
        final int processStatsCount = processStats.size();
        for (int i = 0; i < processStatsCount; i++) {
            final BatteryStats.Uid.Proc ps = processStats.valueAt(i);
            final String processName = processStats.keyAt(i);
            app.cpuFgTimeMs += ps.getForegroundTime(statsType);

            final long costValue = ps.getUserTime(statsType) + ps.getSystemTime(statsType)
                    + ps.getForegroundTime(statsType);

            // Each App can have multiple packages and with multiple running processes.
            // Keep track of the package who's process has the highest drain.
            if (app.packageWithHighestDrain == null ||
                    app.packageWithHighestDrain.startsWith("*")) {
                highestDrain = costValue;
                app.packageWithHighestDrain = processName;
            } else if (highestDrain < costValue && !processName.startsWith("*")) {
                highestDrain = costValue;
                app.packageWithHighestDrain = processName;
            }
        }

        // Ensure that the CPU times make sense.
        if (app.cpuFgTimeMs > app.cpuTimeMs) {
            if (DEBUG && app.cpuFgTimeMs > app.cpuTimeMs + 10000) {
                Log.d(TAG, "WARNING! Cputime is more than 10 seconds behind Foreground time");
            }

            // Statistics may not have been gathered yet.
            app.cpuTimeMs = app.cpuFgTimeMs;
        }
    }
```
关于4g项目中的电池配置文件:  
```xml
    <array name="cpu.clusters.cores">
        <value>4</value>
    </array>
    <array name="cpu.speeds.cluster0">
        <value>299000</value>
        <value>442000</value>
        <value>598000</value>
        <value>819000</value>
        <value>1040000</value>
        <value>1170000</value>
        <value>1300000</value>
        <value>1443000</value>
    </array>
    <array name="cpu.active.cluster0">
        <value>90.1</value>
        <value>110.2</value>
        <value>130.3</value>
        <value>160.4</value>
        <value>190.5</value>
        <value>220.6</value>
        <value>250.7</value>
        <value>280.8</value>
    </array>
    <item name="cpu.idle">4.8</item>
    <item name="cpu.awake">21.1</item>
```
cpu耗电的计算公式:  
cpuPower = ratio_1 * cpu_time * cpu_ratio_1_power + … +ratio_n * cpu_time * cpu_ratio_n_power  
其中： ratio_i = cpu_speed_time/ cpu_speeds_total_time，（i=1,2,…,N，N为CPU频点个数）  


#### 1.2 wakeup耗电统计  
统计唤醒机制导致的耗电
WakelockPowerCalculator->calculateApp()
```
    public WakelockPowerCalculator(PowerProfile profile) {
        // 从电源配置文件中获取cpu.awake的值
        mPowerWakelock = profile.getAveragePower(PowerProfile.POWER_CPU_AWAKE);
    }

    // 计算app在cpu处于awake状态下的耗电量
    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        long wakeLockTimeUs = 0;
        final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelockStats =
                u.getWakelockStats();

        // 唤醒锁的状态的次数
        final int wakelockStatsCount = wakelockStats.size();

        // 将每次唤醒中以PARTIAL_WAKE_LOCK方式唤醒的时间统计出来
        for (int i = 0; i < wakelockStatsCount; i++) {
            final BatteryStats.Uid.Wakelock wakelock = wakelockStats.valueAt(i);

            // Only care about partial wake locks since full wake locks
            // are canceled when the user turns the screen off.
            // 只统计PARTIAL_WAKE_LOCK.长时间运行的后台服务，例如Service等
            BatteryStats.Timer timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_PARTIAL);
            if (timer != null) {
                wakeLockTimeUs += timer.getTotalTimeLocked(rawRealtimeUs, statsType);
            }
        }

        // 时间单位转换
        app.wakeLockTimeMs = wakeLockTimeUs / 1000; // convert to millis
        mTotalAppWakelockTimeMs += app.wakeLockTimeMs;

        // Add cost of holding a wake lock.
        // 将计算的唤醒耗电保存
        app.wakeLockPowerMah = (app.wakeLockTimeMs * mPowerWakelock) / (1000*60*60);
        if (DEBUG && app.wakeLockPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": wake " + app.wakeLockTimeMs
                    + " power=" + BatteryStatsHelper.makemAh(app.wakeLockPowerMah));
        }
    }

    @Override
    // 统计app之外的耗电量,长时间就是cpu被唤醒,但是屏幕没有亮的耗电,该部分的耗电会被算入OS
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        long wakeTimeMillis = stats.getBatteryUptime(rawUptimeUs) / 1000;// 返回当前电池正常运行时间
        // 电池运行时间 - PARTIAL_WAKE_LOCK占用的时间 + 设备在运行,屏幕亮着的时间
        wakeTimeMillis -= mTotalAppWakelockTimeMs
                + (stats.getScreenOnTime(rawRealtimeUs, statsType) / 1000);
        if (wakeTimeMillis > 0) {// 如果有未计算在内的耗电,添加到app.wakeLockPowerMah的耗电中
            final double power = (wakeTimeMillis * mPowerWakelock) / (1000*60*60);
            if (DEBUG) {
                Log.d(TAG, "OS wakeLockTime " + wakeTimeMillis + " power "
                        + BatteryStatsHelper.makemAh(power));
            }
            app.wakeLockTimeMs += wakeTimeMillis;
            app.wakeLockPowerMah += power;
        }
    }
```
PowerProfile.POWER_CPU_AWAKE:  
```xml
    <item name="cpu.awake">21.1</item>
```
计算公式:  
app wakeLock耗电计算公式:  
wakeLockPowerMah = (app.wakeLockTimeMs * mPowerWakelock) / (10006060);  
OS wakeLock耗电计算公式:  
power = (wakeTimeMillis * mPowerWakelock) / (1000*60*60);  

其中: wakeTimeMillis是排除亮屏幕 和 app wakeLock 时间的电池总运行时间.  
calculateRemaining统计的部分耗电会算入系统总耗电.  

#### 1.3 radio耗电统计  
```
    /**
     * Return estimated power (in mAs) of sending or receiving a packet with the mobile radio.
     */
    // 估计传输一个数据包(2k)的耗电
    private double getMobilePowerPerPacket(long rawRealtimeUs, int statsType) {
        final long MOBILE_BPS = 200000; // TODO: Extract average bit rates from system
        final double MOBILE_POWER = mPowerRadioOn / 3600;
        // 获取发送和接受的数据总量
        final long mobileRx = mStats.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        final long mobileTx = mStats.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);
        final long mobileData = mobileRx + mobileTx;
        // 获取cell处于radio.active状态的总时间
        final long radioDataUptimeMs =
                mStats.getMobileRadioActiveTime(rawRealtimeUs, statsType) / 1000;
        // 计算单位时间的传输数据量(发送 + 接受)
        final double mobilePps = (mobileData != 0 && radioDataUptimeMs != 0)
                ? (mobileData / (double)radioDataUptimeMs)
                : (((double)MOBILE_BPS) / 8 / 2048);
        return (MOBILE_POWER / mobilePps) / (60*60);
    }

    public MobileRadioPowerCalculator(PowerProfile profile, BatteryStats stats) {
        // 获取处于radio.active的单位耗电.注意:mPowerRadioOn是RADIO_ACTIVE,不是RADIO_ON!!!!
        mPowerRadioOn = profile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE);
        // 获取不同的信号等级的单位耗电(一般是5级,4g项目是2个,第一个代表无信号,第二个代表有信号)
        for (int i = 0; i < mPowerBins.length; i++) {// android会进行转换，length为6,(0-5),0代表无信号,5代表信号最强
            mPowerBins[i] = profile.getAveragePower(PowerProfile.POWER_RADIO_ON, i);
        }
        // 获取扫描信号的单位耗电
        mPowerScan = profile.getAveragePower(PowerProfile.POWER_RADIO_SCANNING);
        mStats = stats;
    }

    // 统计app移动通信的耗电
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        // Add cost of mobile traffic.
        app.mobileRxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        app.mobileTxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);
        // 获取处于radio.active状态的时间(ms)
        app.mobileActive = u.getMobileRadioActiveTime(statsType) / 1000;
        app.mobileActiveCount = u.getMobileRadioActiveCount(statsType);
        app.mobileRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        app.mobileTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);

        if (app.mobileActive > 0) {// 如果统计到了处于radio.active状态的时间,用时间进行计算
            // We are tracking when the radio is up, so can use the active time to
            // determine power use.
            mTotalAppMobileActiveMs += app.mobileActive;
            app.mobileRadioPowerMah = (app.mobileActive * mPowerRadioOn) / (1000*60*60);
        } else {// 如果没有统计到时间,使用app传输的数据量来统计
            // We are not tracking when the radio is up, so must approximate power use
            // based on the number of packets.
            app.mobileRadioPowerMah = (app.mobileRxPackets + app.mobileTxPackets)
                    * getMobilePowerPerPacket(rawRealtimeUs, statsType);
        }
        if (DEBUG && app.mobileRadioPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": mobile packets "
                    + (app.mobileRxPackets + app.mobileTxPackets)
                    + " active time " + app.mobileActive
                    + " power=" + BatteryStatsHelper.makemAh(app.mobileRadioPowerMah));
        }
    }

```
```xml
    <item name="radio.active">180.1</item>
    <item name="radio.scanning">90.1</item>
    <array name="radio.on">
        <value>6.2</value>
        <value>6.2</value>
    </array>
```
app使用耗电指是在radio.active的状态下,其他的参数在计算radio耗电的时候使用.  
计算公式:  
情况一：当追踪信号活动时间，即mobileActive > 0，采用：  

mobileRadioPowerMah = (app.mobileActive * mPowerRadioOn) / (1000* 60* 60);  

情况二：当没有追踪信号活动时间，则采用：  

mobileRadioPowerMah = (app.mobileRxPackets + app.mobileTxPackets) * MobilePowerPerPacket  

其中MobilePowerPerPacket = (（mPowerRadioOn / 3600） / mobilePps) / (60*60)， mobilePps= （mobileRx + mobileTx）/radioDataUptimeMs  

#### 1.4 wifi 耗电
wifi耗电分两种: WifiPowerCalculator 和 WifiPowerEstimator.  
WifiPowerCalculator: 通过wifi处于保持链接(wifi.controller.idle),接受数据(wifi.controller.rx),发送数据(wifi.controller.tx).三种状态来计算.  
WifiPowerEstimator : 保持链接(wifi.on),发送或者接受数据(wifi.active),扫描周围的热点(wifi.scan)来估计(通过数据包的量).  
使用哪个和机器有关:  
```java
        // checkHasWifiPowerReporting can change if we get energy data at a later point, so
        // always check this field.
        final boolean hasWifiPowerReporting = checkHasWifiPowerReporting(mStats, mPowerProfile);
        if (mWifiPowerCalculator == null || hasWifiPowerReporting != mHasWifiPowerReporting) {
            mWifiPowerCalculator = hasWifiPowerReporting ?
                    new WifiPowerCalculator(mPowerProfile) :
                    new WifiPowerEstimator(mPowerProfile);
            mHasWifiPowerReporting = hasWifiPowerReporting;
        }
```
其实还是取决于电源配置文件:  
```xml
    <item name="wifi.controller.idle">0</item>
    <item name="wifi.controller.rx">0</item>
    <item name="wifi.controller.tx">0</item>
```
当配置为如上信息,就会使用WifiPowerEstimator类的方法进行估计.这里我们把两种方法都分析一下:  
##### WifiPowerCalculator
从名字看出这个类是计算,也就是底层提供数据能够普计算  
WifiPowerCalculator.java->calculateApp()
```java
    public WifiPowerCalculator(PowerProfile profile) {
        // 获取三种状态的单位功耗
        mIdleCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE);
        mTxCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX);
        mRxCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        final BatteryStats.ControllerActivityCounter counter = u.getWifiControllerActivity();
        if (counter == null) {
            return;
        }
        // 获取三种状态的占用时间
        final long idleTime = counter.getIdleTimeCounter().getCountLocked(statsType);
        final long txTime = counter.getTxTimeCounters()[0].getCountLocked(statsType);
        final long rxTime = counter.getRxTimeCounter().getCountLocked(statsType);
        app.wifiRunningTimeMs = idleTime + rxTime + txTime;
        mTotalAppRunningTime += app.wifiRunningTimeMs;
        // 依据三种不同的wifi状态去计算
        app.wifiPowerMah =
                ((idleTime * mIdleCurrentMa) + (txTime * mTxCurrentMa) + (rxTime * mRxCurrentMa))
                / (1000*60*60);
        mTotalAppPowerDrain += app.wifiPowerMah;

        // 统计数据包量 和 byte 数
        app.wifiRxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);
        app.wifiRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);
        if (DEBUG && app.wifiPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": idle=" + idleTime + "ms rx=" + rxTime + "ms tx=" +
                    txTime + "ms power=" + BatteryStatsHelper.makemAh(app.wifiPowerMah));
        }
    }

    // 统计在wifi耗电除了app耗电被report的还有哪些耗电,也就是这里的耗电是不包括app使用wifi耗电的.
    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        Log.d(TAG,"step into calculateRemaining");
        final BatteryStats.ControllerActivityCounter counter = stats.getWifiControllerActivity();

        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(statsType);
        final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(statsType);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(statsType);

        app.wifiRunningTimeMs = Math.max(0,
                (idleTimeMs + rxTimeMs + txTimeMs) - mTotalAppRunningTime);

        double powerDrainMah = counter.getPowerCounter().getCountLocked(statsType)
                / (double)(1000*60*60);
        if (powerDrainMah == 0) {
            // Some controllers do not report power drain, so we can calculate it here.
            powerDrainMah = ((idleTimeMs * mIdleCurrentMa) + (txTimeMs * mTxCurrentMa)
                    + (rxTimeMs * mRxCurrentMa)) / (1000*60*60);
        }
        Log.d(TAG,"powerDrainMah = " + powerDrainMah + ",mTotalAppPowerDrain =" + mTotalAppPowerDrain);
        app.wifiPowerMah = Math.max(0, powerDrainMah - mTotalAppPowerDrain);

       if (DEBUG) {
            Log.d(TAG, "left over WiFi power: " + BatteryStatsHelper.makemAh(app.wifiPowerMah));
       }
    }

    @Override
    public void reset() {
        mTotalAppPowerDrain = 0;
        mTotalAppRunningTime = 0;
    }
```
不过,在4g项目中mTxCurrentMa,mRxCurrentMa,mIdleCurrentMa在电池配置文件中都是0.
耗电计算公式:  
wifiPowerMah = ((idleTime * mIdleCurrentMa) + (txTime * mTxCurrentMa) + (rxTime * mRxCurrentMa)) / (1000* 60* 60);  
##### WifiPowerEstimator
在4g项目中是通过该方式进行计算的,这里是进行了估算,
WifiPowerEstimator->calculateApp
```
    public WifiPowerEstimator(PowerProfile profile) {
        mWifiPowerPerPacket = getWifiPowerPerPacket(profile);
        mWifiPowerOn = profile.getAveragePower(PowerProfile.POWER_WIFI_ON);// wifi.on
        mWifiPowerScan = profile.getAveragePower(PowerProfile.POWER_WIFI_SCAN);// wifi.scan
        mWifiPowerBatchScan = profile.getAveragePower(PowerProfile.POWER_WIFI_BATCHED_SCAN);// wifi.batchedscan
    }

    /**
     * Return estimated power per Wi-Fi packet in mAh/packet where 1 packet = 2 KB.
     */
    private static double getWifiPowerPerPacket(PowerProfile profile) {
        // wifi的bit速率
        final long WIFI_BPS = 1000000; // TODO: Extract average bit rates from system
        final double WIFI_POWER = profile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE)
                / 3600;// PowerProfile.POWER_WIFI_ACTIVE->wifi.active
        return WIFI_POWER / (((double)WIFI_BPS) / 8 / 2048);// 计算每一个数据包的耗电量
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        app.wifiRxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);
        app.wifiRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);
        // 计算收发数据包的耗电量
        final double wifiPacketPower = (app.wifiRxPackets + app.wifiTxPackets)
                * mWifiPowerPerPacket;

        // 计算wifi保持连接的功耗
        app.wifiRunningTimeMs = u.getWifiRunningTime(rawRealtimeUs, statsType) / 1000;
        mTotalAppWifiRunningTimeMs += app.wifiRunningTimeMs;
        final double wifiLockPower = (app.wifiRunningTimeMs * mWifiPowerOn) / (1000*60*60);

        // 计算wifi扫描时候的功耗
        final long wifiScanTimeMs = u.getWifiScanTime(rawRealtimeUs, statsType) / 1000;
        final double wifiScanPower = (wifiScanTimeMs * mWifiPowerScan) / (1000*60*60);

        // 计算wifi批量扫描的功耗
        double wifiBatchScanPower = 0;
        for (int bin = 0; bin < BatteryStats.Uid.NUM_WIFI_BATCHED_SCAN_BINS; bin++) {// BatteryStats.Uid.NUM_WIFI_BATCHED_SCAN_BINS = 5
            final long batchScanTimeMs =
                    u.getWifiBatchedScanTime(bin, rawRealtimeUs, statsType) / 1000;
            final double batchScanPower = (batchScanTimeMs * mWifiPowerBatchScan) / (1000*60*60);
            wifiBatchScanPower += batchScanPower;
        }
        // 将四种耗电累加
        app.wifiPowerMah = wifiPacketPower + wifiLockPower + wifiScanPower + wifiBatchScanPower;
        if (DEBUG && app.wifiPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": power=" +
                    BatteryStatsHelper.makemAh(app.wifiPowerMah));
        }
    }
```
通过估计传输一个数据包的耗电来估计app使用wifi时的耗电.  
计算公式:  
wifiPowerMah = wifiPacketPower + wifiLockPower + wifiScanPower + wifiBatchScanPower;  

其中:wifiPacketPower = (wifiRxPackets + wifiTxPackets) * mWifiPowerPerPacket; wifiLockPower =   
        (wifiRunningTimeMs * mWifiPowerOn) / (1000* 60* 60); wifiScanPower = (wifiScanTimeMs * mWifiPowerScan) / (1000* 60* 60);  

wifiBatchScanPower = ∑ (batchScanTimeMs * mWifiPowerBatchScan) / (1000* 60* 60) ，5次相加。  

#### 1.5 bluetooth耗电统计
```
    public BluetoothPowerCalculator(PowerProfile profile) {
        mIdleMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE);
        mRxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX);
        mTxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX);
    }
```
目前没有配置POWER_BLUETOOTH_CONTROLLER_IDLE,POWER_BLUETOOTH_CONTROLLER_RX,POWER_BLUETOOTH_CONTROLLER_TX.
```
    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {

        final BatteryStats.ControllerActivityCounter counter = u.getBluetoothControllerActivity();
        if (counter == null) {
            return;
        }

        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(statsType);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(statsType);
        final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(statsType);
        final long totalTimeMs = idleTimeMs + txTimeMs + rxTimeMs;
        // 通过获取bluetooth的耗电,转换单位
        double powerMah = counter.getPowerCounter().getCountLocked(statsType)// 具体怎么获取的不知道.....
                / (double)(1000*60*60);

        if (powerMah == 0) {
            powerMah = ((idleTimeMs * mIdleMa) + (rxTimeMs * mRxMa) + (txTimeMs * mTxMa))
                    / (1000*60*60);
        }

        app.bluetoothPowerMah = powerMah;
        app.bluetoothRunningTimeMs = totalTimeMs;
        app.btRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_BT_RX_DATA, statsType);
        app.btTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_BT_TX_DATA, statsType);

        mAppTotalPowerMah += powerMah;
        mAppTotalTimeMs += totalTimeMs;
    }
```
计算公式:  
powerMah = counter.getPowerCounter().getCountLocked(statsType)/ (double)(1000*60*60);// 获bluetooth的总耗电,转换单位  
如果获取的是0使用以下方式计算:  
powerMah = ((idleTimeMs * mIdleMa) + (rxTimeMs * mRxMa) + (txTimeMs * mTxMa)) / (1000*60*60);  
不过在4g中,这么计算是0.  

#### 1.6 传感器耗电
```
    public SensorPowerCalculator(PowerProfile profile, SensorManager sensorManager) {
        mSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);//获取sensor的列表
        mGpsPowerOn = profile.getAveragePower(PowerProfile.POWER_GPS_ON);//GPS耗电统计放在Sensor耗电中统计
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        // Process Sensor usage
        final SparseArray<? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
        final int NSE = sensorStats.size();//获取总的sensor数量
        for (int ise = 0; ise < NSE; ise++) {// 计算每个sensor的耗电
            final BatteryStats.Uid.Sensor sensor = sensorStats.valueAt(ise);
            final int sensorHandle = sensorStats.keyAt(ise);
            final BatteryStats.Timer timer = sensor.getSensorTime();
            final long sensorTime = timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
            switch (sensorHandle) {
                case BatteryStats.Uid.Sensor.GPS:
                    app.gpsTimeMs = sensorTime;
                    app.gpsPowerMah = (app.gpsTimeMs * mGpsPowerOn) / (1000*60*60);
                    break;
                default:// 除了GPS的sensor都在这计算
                    final int sensorsCount = mSensors.size();
                    for (int i = 0; i < sensorsCount; i++) {
                        final Sensor s = mSensors.get(i);
                        if (s.getHandle() == sensorHandle) {
                            app.sensorPowerMah += (sensorTime * s.getPower()) / (1000*60*60);
                            break;
                        }
                    }
                    break;
            }
        }
    }
```
关于传感器的配置文件:  
```xml
    <item name="gps.on">40.8</item>
```
GPS功耗计算公式:  
gpsPowerMah = (app.gpsTimeMs * mGpsPowerOn) / (1000* 60* 60);  
sensorPowerMah = ∑ (sensorTime * s.getPower()) / (1000*60*60);

#### 1.7 Camera耗电统计
此块耗电并没有算入之前定义的DrainType.CAMERA.不知道google是还没完善还是处于什么考虑
```
    public CameraPowerCalculator(PowerProfile profile) {
        mCameraPowerOnAvg = profile.getAveragePower(PowerProfile.POWER_CAMERA);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {

        // Calculate camera power usage.  Right now, this is a (very) rough estimate based on the
        // average power usage for a typical camera application.
        // 用Camera打开的时间 * 单位时间功耗
        final BatteryStats.Timer timer = u.getCameraTurnedOnTimer();
        if (timer != null) {
            final long totalTime = timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
            app.cameraTimeMs = totalTime;
            app.cameraPowerMah = (totalTime * mCameraPowerOnAvg) / (1000*60*60);
        } else {
            app.cameraTimeMs = 0;
            app.cameraPowerMah = 0;
        }
    }
```
PowerProfile.POWER_CAMERA:  
```xml
    <item name="camera.avg">401.2</item>
```
计算公式:  
cameraPowerMah = (totalTime * mCameraPowerOnAvg) / (1000 * 60 * 60)  

#### 1.8 Flashlight耗电统计
该耗电并没有算入DrainType.FLASHLIGHT.情况同Camera.统计闪光灯模块耗电,eg:拍照闪光灯,手电筒
```java
    public FlashlightPowerCalculator(PowerProfile profile) {
        mFlashlightPowerOnAvg = profile.getAveragePower(PowerProfile.POWER_FLASHLIGHT);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {

        // Calculate flashlight power usage.  Right now, this is based on the average power draw
        // of the flash unit when kept on over a short period of time.
        // android 中亮度只有一个,iOS中有多个,所以这里就是: 时间 * 单位耗电
        final BatteryStats.Timer timer = u.getFlashlightTurnedOnTimer();
        if (timer != null) {
            final long totalTime = timer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
            app.flashlightTimeMs = totalTime;
            app.flashlightPowerMah = (totalTime * mFlashlightPowerOnAvg) / (1000*60*60);
        } else {
            app.flashlightTimeMs = 0;
            app.flashlightPowerMah = 0;
        }
    }
```
PowerProfile.POWER_FLASHLIGHT:  
```xml
    <item name="camera.flashlight">239.6</item>
```
计算公式:  
flashlightPowerMah = (totalTime * mFlashlightPowerOnAvg) / (1000*60*60)  

#### 1.9 OS耗电
统计的过程中用osSipper保存系统耗电
```
        // osSipper被初始化过,也就是上面统计的uid里面有系统的uid,下面把系统的耗电累加起来
        if (osSipper != null) {// 长时间cpu唤醒,但是屏幕没有亮,该部分的耗算入OS耗电
            // The device has probably been awake for longer than the screen on
            // time and application wake lock time would account for.  Assign
            // this remainder to the OS, if possible.
            // 该部分在上面分析过
            mWakelockPowerCalculator.calculateRemaining(osSipper, mStats, mRawRealtimeUs,
                    mRawUptimeUs, mStatsType);
            // OS耗电求和
            osSipper.sumPower();
        }
```

值得注意的是app的耗电,没有统计屏幕耗电.

***

### 2 硬件电量统计  
####  BatteryStatsHelper.java-->processMiscUsage()
```
    private void processMiscUsage() {
        addUserUsage();
        addPhoneUsage();
        addScreenUsage();
        addWiFiUsage();
        addBluetoothUsage();
        addMemoryUsage();
        addIdleUsage(); // Not including cellular idle power
        // Don't compute radio usage if it's a wifi-only device
        if (!mWifiOnly) {
            addRadioUsage();
        }
    }
```
#### 2.1 user耗电统计  
属于BatterySipper.DrainType.USER
```
    private void addUserUsage() {
        for (int i = 0; i < mUserSippers.size(); i++) {
            final int userId = mUserSippers.keyAt(i);
            BatterySipper bs = new BatterySipper(DrainType.USER, null, 0);
            bs.userId = userId;
            // 将每个user用户的耗电求和
            aggregateSippers(bs, mUserSippers.valueAt(i), "User");
            mUsageList.add(bs);
        }
    }
```
aggregateSippers:  
```
    private void aggregateSippers(BatterySipper bs, List<BatterySipper> from, String tag) {
        for (int i = 0; i < from.size(); i++) {
            BatterySipper wbs = from.get(i);
            if (DEBUG) Log.d(TAG, tag + " adding sipper " + wbs + ": cpu=" + wbs.cpuTimeMs);
            bs.add(wbs);
        }
        bs.computeMobilemspp();
        bs.sumPower();
    }
```
计算公式:  
user_power = user_1_power + user_2_power + … +　user_n_power; (n为所有的user的总数)  

#### 2.2 Phone耗电统计  
属于:BatterySipper.DrainType.PHONE.计算通话耗电量
```
    private void addPhoneUsage() {
        long phoneOnTimeMs = mStats.getPhoneOnTime(mRawRealtimeUs, mStatsType) / 1000;
        // Phone的耗电也采用POWER_RADIO_ACTIVE,后面的radio的耗电统计也用到这个单位耗电量
        // 计算功耗
        double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / (60*60*1000);
        // 保存
        if (phoneOnPower != 0) {
            addEntry(BatterySipper.DrainType.PHONE, phoneOnTimeMs, phoneOnPower);
        }
    }
```
PowerProfile.POWER_RADIO_ACTIVE : 
```xml
    <item name="radio.active">180.1</item>
```
计算公式:  
phone_powers = (phoneOnTimeMs * phoneOnPower) / (60* 60* 1000)  

#### 2.3 屏幕耗电统计  
属于:BatterySipper.DrainType.SCREEN.屏幕耗电是单独算的,没有算入app耗电  
```
    /**
     * Screen power is the additional power the screen takes while the device is running.
     */
    private void addScreenUsage() {
        double power = 0;
        // 计算屏幕处于POWER_SCREEN_ON状态的功耗
        long screenOnTimeMs = mStats.getScreenOnTime(mRawRealtimeUs, mStatsType) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        // 获取屏幕最高亮度的单位耗电
        final double screenFullPower =
                mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        // 计算不同亮度等级的耗电量,并进行统计.
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {// 5个亮度等级
            double screenBinPower = screenFullPower * (i + 0.5f)
                    / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            long brightnessTime = mStats.getScreenBrightnessTime(i, mRawRealtimeUs, mStatsType)
                    / 1000;
            double p = screenBinPower * brightnessTime;
            if (DEBUG && p != 0) {
                Log.d(TAG, "Screen bin #" + i + ": time=" + brightnessTime
                        + " power=" + makemAh(p / (60 * 60 * 1000)));
            }
            power += p;
        }
        power /= (60 * 60 * 1000); // To hours
        if (power != 0) {
            addEntry(BatterySipper.DrainType.SCREEN, screenOnTimeMs, power);
        }
    }
```
POWER_SCREEN_ON 和 POWER_SCREEN_FULL对应的值:  
```xml
    <item name="screen.on">21.7</item>
    <item name="screen.full">304.3</item>// 不是最大亮度耗电,比最大亮度功耗大10%
```
屏幕亮度的等级:  
```java
    public static final int SCREEN_BRIGHTNESS_DARK = 0;
    public static final int SCREEN_BRIGHTNESS_DIM = 1;
    public static final int SCREEN_BRIGHTNESS_MEDIUM = 2;
    public static final int SCREEN_BRIGHTNESS_LIGHT = 3;
    public static final int SCREEN_BRIGHTNESS_BRIGHT = 4;

    static final String[] SCREEN_BRIGHTNESS_NAMES = {
        "dark", "dim", "medium", "light", "bright"
    };

    static final String[] SCREEN_BRIGHTNESS_SHORT_NAMES = {
        "0", "1", "2", "3", "4"
    };

    public static final int NUM_SCREEN_BRIGHTNESS_BINS = 5;
```
计算公式:  
公式：screen_power = screenOnTimeMs * screenOnPower + backlight_power

其中：backlight_power = 0.1 * dark_brightness_time * screenFullPower + 0.3 * dim_brightness_time * screenFullPower  
                       + 0.5 * medium_brightness_time * screenFullPower + 0.7 * light_brightness_time  
                       + 0.9 * bright_brightness_time * screenFullPower;
#### 2.4 wifi耗电统计  
属于 BatterySipper.DrainType.WIFI,每个app使用wifi的耗电计算了,这里统计的是所有app在wifi上耗电总和实际wifi运行耗电的差值.
```java
    /**
     * We do per-app blaming of WiFi activity. If energy info is reported from the controller,
     * then only the WiFi process gets blamed here since we normalize power calculations and
     * assign all the power drain to apps. If energy info is not reported, we attribute the
     * difference between total running time of WiFi for all apps and the actual running time
     * of WiFi to the WiFi subsystem.
     */
    private void addWiFiUsage() {
        BatterySipper bs = new BatterySipper(DrainType.WIFI, null, 0);// DrainType.WIFI就是BatterySipper.DrainType.WIFI
        mWifiPowerCalculator.calculateRemaining(bs, mStats, mRawRealtimeUs, mRawUptimeUs,
                mStatsType);
        aggregateSippers(bs, mWifiSippers, "WIFI");
        if (bs.totalPowerMah > 0) {
            mUsageList.add(bs);
        }
    }
```
在我们的项目中使用的是WifiPowerEstimator.calculateRemaining():  
```
    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        // 获取毫秒级的wifi运行的总时间
        final long totalRunningTimeMs = stats.getGlobalWifiRunningTime(rawRealtimeUs, statsType)
                / 1000;
        // 这里的耗电是以保持连接计算的
        final double powerDrain = ((totalRunningTimeMs - mTotalAppWifiRunningTimeMs) * mWifiPowerOn)
                / (1000*60*60);
        app.wifiRunningTimeMs = totalRunningTimeMs;
        app.wifiPowerMah = Math.max(0, powerDrain);
    }
```
mWifiPowerOn:  
```xml
    <item name="wifi.on">1.0</item>
```
这块的功耗还是比较低的.
#### 2.5 蓝牙耗电统计  
属于BatterySipper.DrainType.BLUETOOTH于此处统计的Bluetooth耗电是指app耗电之外的蓝牙耗电
```java
    /**
     * Bluetooth usage is not attributed to any apps yet, so the entire blame goes to the
     * Bluetooth Category.
     */
    private void addBluetoothUsage() {
        BatterySipper bs = new BatterySipper(BatterySipper.DrainType.BLUETOOTH, null, 0);
        mBluetoothPowerCalculator.calculateRemaining(bs, mStats, mRawRealtimeUs, mRawUptimeUs,
                mStatsType);
        // 对mBluetoothSippers里的统计量,进行求和,都算入Bluetooth
        aggregateSippers(bs, mBluetoothSippers, "Bluetooth");
        if (bs.totalPowerMah > 0) {
            mUsageList.add(bs);// 保存到所有app的耗电统计结果清单中
        }
    }
```
看下具体计算:  
```java
    public BluetoothPowerCalculator(PowerProfile profile) {
        mIdleMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE);
        mRxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX);
        mTxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX);
    }
```
可是在电源配置文件中没有这三个值,使用默认值是0.
```java
    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        final BatteryStats.ControllerActivityCounter counter =
                stats.getBluetoothControllerActivity();

        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(statsType);
        final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(statsType);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(statsType);
        final long totalTimeMs = idleTimeMs + txTimeMs + rxTimeMs;
        double powerMah = counter.getPowerCounter().getCountLocked(statsType)
                 / (double)(1000*60*60);

        if (powerMah == 0) {
            // Some devices do not report the power, so calculate it.
            powerMah = ((idleTimeMs * mIdleMa) + (rxTimeMs * mRxMa) + (txTimeMs * mTxMa))
                    / (1000*60*60);
        }

        // Subtract what the apps used, but clamp to 0.
        powerMah = Math.max(0, powerMah - mAppTotalPowerMah);

        if (DEBUG && powerMah != 0) {
            Log.d(TAG, "Bluetooth active: time=" + (totalTimeMs)
                    + " power=" + BatteryStatsHelper.makemAh(powerMah));
        }

        app.bluetoothPowerMah = powerMah;
        app.bluetoothRunningTimeMs = Math.max(0, totalTimeMs - mAppTotalTimeMs);
    }
```
```xml
    <item name="bluetooth.active">49.8</item>
    <item name="bluetooth.on">1.8</item>
```
也就是说bluetooth.active 和 bluetooth.on并没有使用.那么岂不是bluetooth耗电是0?  
不是的,在统计app耗电的时候,当app的uid是Process.BLUETOOTH_UID就将其耗电计入到mBluetoothSippers.统计bluetooth耗电就将这些值求和.
后续该模块google可能还要修改.  

#### 2.6 内存耗电统计  
属于BatterySipper.DrainType.MEMORY,这部分是android O才添加的
```
    private void addMemoryUsage() {
        BatterySipper memory = new BatterySipper(DrainType.MEMORY, null, 0);
        mMemoryPowerCalculator.calculateRemaining(memory, mStats, mRawRealtimeUs, mRawUptimeUs,
                mStatsType);
        // 汇总一下,和os耗电统计的sumPower()是同一个方法
        memory.sumPower();
        // 添加到app耗电的列表中
        if (memory.totalPowerMah > 0) {
            mUsageList.add(memory);
        }
```
MemoryPowerCalculator.calculateRemaining():  
```
    public MemoryPowerCalculator(PowerProfile profile) {
        int numBuckets = profile.getNumElements(PowerProfile.POWER_MEMORY);
        powerAverages = new double[numBuckets];
        for (int i = 0; i < numBuckets; i++) {// 4g只有一个等级
            powerAverages[i] = profile.getAveragePower(PowerProfile.POWER_MEMORY, i);
            if (powerAverages[i] == 0 && DEBUG) {
                Log.d(TAG, "Problem with PowerProfile. Received 0 value in MemoryPowerCalculator");
            }
        }
    }

    // app使用Memory的耗电还是空方法,看来google在耗电统计方面还在做努力
    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {}

    // 计算Memory的耗电
    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        double totalMah = 0;
        long totalTimeMs = 0;
        // 获取kernel使用memery耗电的时间片段统计
        LongSparseArray<? extends BatteryStats.Timer> timers = stats.getKernelMemoryStats();
        for (int i = 0; i < timers.size() && i < powerAverages.length; i++) {
            // 获取单位时间片段的单位耗电
            double mAatRail = powerAverages[(int) timers.keyAt(i)];
            // 获取该时间片段的时长
            long timeMs = timers.valueAt(i).getTotalTimeLocked(rawRealtimeUs, statsType);
            double mAm = (mAatRail * timeMs) / (1000*60);
            if(DEBUG) {
                Log.d(TAG, "Calculating mAh for bucket " + timers.keyAt(i) + " while unplugged");
                Log.d(TAG, "Converted power profile number from "
                        + powerAverages[(int) timers.keyAt(i)] + " into " + mAatRail);
                Log.d(TAG, "Calculated mAm " + mAm);
            }
            totalMah += mAm/60;
            totalTimeMs += timeMs;
        }
        app.usagePowerMah = totalMah;
        app.usageTimeMs = totalTimeMs;
        if (DEBUG) {
            Log.d(TAG, String.format("Calculated total mAh for memory %f while unplugged %d ",
                    totalMah, totalTimeMs));
        }
```
PowerProfile.POWER_MEMORY:  
```xml
    <array name="memory.bandwidths">
        <value>22.7</value>
    </array>
```
计算公式:  
totalMah = ∑ (mAatRail * timeMs) / (1000*60)  

#### 2.7 手机空闲耗电统计  
统计的是基准功率的功耗,统计cpu在idle和awake的情况下,处于低电量状态时,只有idle状态的耗电,
```
    /**
     * Calculate the baseline power usage for the device when it is in suspend and idle.
     * The device is drawing POWER_CPU_IDLE power at its lowest power state.
     * The device is drawing POWER_CPU_IDLE + POWER_CPU_AWAKE power when a wakelock is held.
     */
    private void addIdleUsage() {
        // 获取处于suspend状态的时间
        final double suspendPowerMaMs = (mTypeBatteryRealtimeUs / 1000) *
                mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE);
        // 从android 7开始算入POWER_CPU_AWAKE状态的耗电
        final double idlePowerMaMs = (mTypeBatteryUptimeUs / 1000) *
                mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_AWAKE);
        final double totalPowerMah = (suspendPowerMaMs + idlePowerMaMs) / (60 * 60 * 1000);
        if (DEBUG && totalPowerMah != 0) {
            Log.d(TAG, "Suspend: time=" + (mTypeBatteryRealtimeUs / 1000)
                    + " power=" + makemAh(suspendPowerMaMs / (60 * 60 * 1000)));
            Log.d(TAG, "Idle: time=" + (mTypeBatteryUptimeUs / 1000)
                    + " power=" + makemAh(idlePowerMaMs / (60 * 60 * 1000)));
        }

        if (totalPowerMah != 0) {
            addEntry(BatterySipper.DrainType.IDLE, mTypeBatteryRealtimeUs / 1000, totalPowerMah);
        }
    }
```
PowerProfile.POWER_CPU_IDLE 和 PowerProfile.POWER_CPU_AWAKE对应的值:  
```xml
    <item name="cpu.idle">4.8</item>
    <item name="cpu.awake">21.1</item>
```
计算公式:  
idlePower = (idleTimeMs * cpuIdlePower + awakeTimeMs * cpuAwakePower) / (60* 60* 1000)  

值得注意的是: 该模块并不包括手机处于cell网络下的idle状态耗电.  

#### 2.8 Radio耗电统计
如果只有wifi的设备就不统计.addRadioUsage():  
```
    private void addRadioUsage() {
        BatterySipper radio = new BatterySipper(BatterySipper.DrainType.CELL, null, 0);
        mMobileRadioPowerCalculator.calculateRemaining(radio, mStats, mRawRealtimeUs, mRawUptimeUs,
                mStatsType);
        radio.sumPower();
        if (radio.totalPowerMah > 0) {
            mUsageList.add(radio);
        }
    }
```
看下MobileRadioPowerCalculator.calculateRemaining()方法是怎么计算的:  
```
    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        double power = 0;// 统计radio的最终耗电
        long signalTimeMs = 0;// 和cell连接的总时长
        long noCoverageTimeMs = 0;// 没有信号的时间长度
        for (int i = 0; i < mPowerBins.length; i++) {// 实测该模块这部分耗电最大,由于时间大.
            long strengthTimeMs = stats.getPhoneSignalStrengthTime(i, rawRealtimeUs, statsType)
                    / 1000;
            final double p = (strengthTimeMs * mPowerBins[i]) / (60*60*1000);
            if (DEBUG && p != 0) {
                Log.d(TAG, "Cell strength #" + i + ": time=" + strengthTimeMs + " power="
                        + BatteryStatsHelper.makemAh(p));
            }
            power += p;
            signalTimeMs += strengthTimeMs;
            if (i == 0) {// i = 0是代表没有信号的时间长度(none)
                noCoverageTimeMs = strengthTimeMs;
            }
        }
        // 计算扫描时的耗电
        final long scanningTimeMs = stats.getPhoneSignalScanningTime(rawRealtimeUs, statsType)
                / 1000;
        final double p = (scanningTimeMs * mPowerScan) / (60*60*1000);
        if (DEBUG && p != 0) {
            Log.d(TAG, "Cell radio scanning: time=" + scanningTimeMs
                    + " power=" + BatteryStatsHelper.makemAh(p));
        }
        power += p;
        // 计算Active状态的耗电,排除app使用cell的耗电
        long radioActiveTimeMs = mStats.getMobileRadioActiveTime(rawRealtimeUs, statsType) / 1000;
        // 排除统计了的app使用耗电,app的cell耗电,只统计处于active状态下的耗电
        long remainingActiveTimeMs = radioActiveTimeMs - mTotalAppMobileActiveMs;
        if (remainingActiveTimeMs > 0) {
            power += (mPowerRadioOn * remainingActiveTimeMs) / (1000*60*60);
        }
        // 保存
        if (power != 0) {
            if (signalTimeMs != 0) {// 无信号百分比
                app.noCoveragePercent = noCoverageTimeMs * 100.0 / signalTimeMs;
            }
            app.mobileActive = remainingActiveTimeMs;// radio活动,除app
            app.mobileActiveCount = stats.getMobileRadioActiveUnknownCount(statsType);// 获取radio的活跃次数
            app.mobileRadioPowerMah = power;// 获取连接cell的功耗
        }
    }
```
计算公式:  
mobileRadioPowerMah = strengthOnPower + scanningPower + remainingActivePower  

其中： strengthOnPower = none_strength_Ms * none_strength_Power + poor_strength_Ms * poor_strength_Power  
         + moderate_strength_Ms * moderate_strength_Power + good_strength_Ms * good_strength_Power  
         + great_strength_Ms * great_strength_Power；  

scanningPower = scanningTimeMs * mPowerScan；  

remainingActivePower = （radioActiveTimeMs - mTotalAppMobileActiveMs）* mPowerRadioOn；  
该模块统计是排除了app的使用网络造成的耗电.

