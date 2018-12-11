# Android P 应用分组介绍

## 简介
在Android P 中针对电源管理,添加了应用分组功能.依据应用使用的频率和最近一次使用时间，对其资源请求进行优先级排序。
应用待机分组一共有五个分组，系统会根据每个应用的使用情况，将其划分至五个优先分组中的一个，而每个分组对设备资源
的调度各有不同的限制。

## 1 优先分组
系统动态的分配应用到不同的组,在不同的组会有不同的限制.应用活跃度越高，所处分组的优先级就越高，也就相应地更容易
获取设备资源。尤其是，应用所处的的群组决定了其所安排的任务 (job)，触发标准闹铃以及接受高优先级Firebase Cloud 
Messagesing信息的频率。这些限制只有在非充电状态才有效(adb shell dumpsys battery unplug),在跑Monkey时,也不会影响
动态的分组,在充电状态下应用的行为不会受到限制.

至于如何分组,要看手机厂商制定什么规则,默认的规则是根据应用的近期使用情况进行等级划分。厂商也可以使用机器学习对
应用的使用情况进行预测,将应用放在不同优先级的组.


### 1.1 组的介绍
在[Power management restrictions](https://developer.android.google.cn/topic/performance/power/power-details)中,
对不同的组有相关的介绍.

![app_standby_bucket](https://raw.githubusercontent.com/lqktz/document/master/res/app_standby_bucket_001.png)

主要将应用分成4各组:

- **活跃 (Active)**: 应用正在被使用
- **工作 (Working set)**: 应用使用频率很高
- **常用 (Frequent)**: 应用经常但不是每天被使用
- **极少 (Rare)**: 应用偶尔被使用

**活跃 (Active)**

活跃应用的定义:

- 应用启动了一个Activity；
- 应用正在运行前台服务；
- 另一个前台应用已关联至该应用 (通过同步适配器与前台应用的内容提供器相关联)；
- 用户点击了应用的推送

在任务、标准闹铃以及FCM信息的资源调用上，活跃群组应用免受任何系统限制。

**工作 (Working set)**

工作组定义:

- 若应用的运行频率很高，但目前并未处于“活跃”状态，它就会被划分至工作群组，例如用户常用的社交媒体应用。
- 该群组还包括了那些被间接使用的应用。

工作分组内的应用会在任务 (job) 运行和闹铃触发方面受到部分系统限制.

**豁免(Exempted)**

豁免组的定义:

- 应用在whitelist中,永远不会进入standby状态,所有的行为也不会被限制

**常用 (Frequent)**

常用组的定义:

- 常用应用指用户经常使用但不是每天使用的应用，比如用户在健身房使用的打卡应用可能就属于这一群组。

系统对常用分组采用的限制更强，应用运行任务(job)和触发闹铃的能力都会受到影响，而且接受的高优先性FCM消息也有数量上限.

**极少 (Rare)**

极少组的定义:

- 若应用的使用频率很低，它就会被划分至该分组，酒店应用就是一个很好的例子——用户只有在下榻这个酒店的时候才会打开此应用。

该群组下的应用在任务 (job)、闹铃和高优先性FCM消息的资源调用上都会受到严格的限制。此外，网络访问能力也会受到影响。

**从不(Never)**

- 应用安装后从来没有打开过

### 1.2 查看与设置应用分组

#### 1.2.1 应用代码里查看自身当前所在的分组

APP在代码里查看自身所在的分组,使用接口` UsageStatsManager.getAppStandbyBucket()`. 该接口调用的是源码里面的
`AOSP/frameworks/base/core/java/android/app/usage/UsageStatsManager.java`

```
/**
 * Returns the current standby bucket of the calling app. The system determines the standby
 * state of the app based on app usage patterns. Standby buckets determine how much an app will
 * be restricted from running background tasks such as jobs and alarms.
 * <p>Restrictions increase progressively from {@link #STANDBY_BUCKET_ACTIVE} to
 * {@link #STANDBY_BUCKET_RARE}, with {@link #STANDBY_BUCKET_ACTIVE} being the least
 * restrictive. The battery level of the device might also affect the restrictions.
 * <p>Apps in buckets &le; {@link #STANDBY_BUCKET_ACTIVE} have no standby restrictions imposed.
 * Apps in buckets &gt; {@link #STANDBY_BUCKET_FREQUENT} may have network access restricted when
 * running in the background.                                           
 * <p>The standby state of an app can change at any time either due to a user interaction or a
 * system interaction or some algorithm determining that the app can be restricted for a period
 * of time before the user has a need for it.
 * <p>You can also query the recent history of standby bucket changes by calling
 * {@link #queryEventsForSelf(long, long)} and searching for
 * {@link UsageEvents.Event#STANDBY_BUCKET_CHANGED}.
 *                           
 * @return the current standby bucket of the calling app. One of STANDBY_BUCKET_* constants.
 */                                      
public @StandbyBuckets int getAppStandbyBucket() {
    try {                           
        return mService.getAppStandbyBucket(mContext.getOpPackageName(),
                mContext.getOpPackageName(),
                mContext.getUserId());  
    } catch (RemoteException e) {
    }                    
    return STANDBY_BUCKET_ACTIVE;
}
```

从代码里显示,通过`mService`(对应的是UsageStatsService, 源码在`AOSP/frameworks/base/services/usage/java/com/android/server/usage/UsageStatsService.java`),
获取the calling app的所在分组,如果获取失败,就返回`STANDBY_BUCKET_ACTIVE`, 也就是认为应用是活跃的,不会对其进行限制.从源码角度,我们看到app侧只能查看自身所
处的分组,而不能查看其他分组.

#### 1.2.2 使用 am 命令查看和设置app的分组

使用`am`命令可以设置和查看app的分组, 命令使用说明:

```
    set-standby-bucket [--user <USER_ID>] <PACKAGE> active|working_set|frequent|rare
    Puts an app in the standby bucket.
    get-standby-bucket [--user <USER_ID>] <PACKAGE>
    Returns the standby bucket of an app.
```

获取所有的应用的分组:

```
am get-standby-bucket
```

结果:

```
com.android.wallpaperbackup: 5
com.android.providers.blockednumber: 40
com.android.providers.userdictionary: 40
com.google.vr.apps.ornament: 40
com.android.emergency: 40
com.google.android.inputmethod.japanese: 40
com.android.location.fused: 5
com.android.systemui: 30
```

active|working_set|frequent|rare 分别对应 10|20|30|40 , 至于里面不是这4个数字的,是属于系统内部的分组, 不涉及三方apk分组.

除了`set-standby-bucket`, 和`get-standby-bucket`,还有两个am命令,也会更改app的分组:

```
    set-inactive [--user <USER_ID>] <PACKAGE> true|false
    Sets the inactive state of an app.
    get-inactive [--user <USER_ID>] <PACKAGE>
    Returns the inactive state of an app.
```

设置`set-inactive [--user <USER_ID>] <PACKAGE> true` , 会将<PACKAGE>设置为40.
设置`set-inactive [--user <USER_ID>] <PACKAGE> false` , 会将<PACKAGE>设置为10.

由此,可以看出,android P中将原来一刀切形式的 app standby 合并到了应用分组中了.

### 2 系统层面的源码实现

前面,我们已经提到系统动态的调整app的组,接下来我们分析一下, 看系统层面是怎样实现的.

#### 2.1 设置app的bucket

设置app的bucket,经过层层调用,最终都调用到AppStandbyController中的reportEvent函数. 在ActivityManagerService/NotificationManagerService
中都有调用到.

在NotificationManagerService中的调用;

```
    private UsageStatsManagerInternal mAppUsageStats;

    mAppUsageStats.reportEvent(r.sbn.getPackageName(),
                   getRealUserId(r.sbn.getUserId()),
                   UsageEvents.Event.USER_INTERACTION);
```

在AMS中有调用代码如下:

```
UsageStatsManagerInternal mUsageStatsService;

mUsageStatsService.reportEvent(component.realActivity, component.userId,
                                        UsageEvents.Event.MOVE_TO_FOREGROUND);
```

这里我们以ActivityManagerService的调用为分析线路.

来看`frameworks/base/core/java/android/app/usage/UsageStatsManagerInternal.java` :

```
public abstract class UsageStatsManagerInternal {

    /**
     * Reports an event to the UsageStatsManager.
     *
     * @param component The component for which this event occurred.
     * @param userId The user id to which the component belongs to.
     * @param eventType The event that occurred. Valid values can be found at
     * {@link UsageEvents}
     */
    public abstract void reportEvent(ComponentName component, @UserIdInt int userId, int eventType);

    /**
     * Reports an event to the UsageStatsManager.
     *
     * @param packageName The package for which this event occurred.
     * @param userId The user id to which the component belongs to.
     * @param eventType The event that occurred. Valid values can be found at
     * {@link UsageEvents}
     */
    public abstract void reportEvent(String packageName, @UserIdInt int userId, int eventType);

}
```

只是一个抽象的接口,具体实现在`frameworks/base/services/usage/java/com/android/server/usage/UsageStatsService.java`

```
/**
 * This local service implementation is primarily used by ActivityManagerService.
 * ActivityManagerService will call these methods holding the 'am' lock, which means we
 * shouldn't be doing any IO work or other long running tasks in these methods.
 */
private final class LocalService extends UsageStatsManagerInternal {

    @Override
        public void reportEvent(ComponentName component, int userId, int eventType) {
            if (component == null) {
                Slog.w(TAG, "Event reported without a component name");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = component.getPackageName();
            event.mClass = component.getClassName();

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = eventType;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();// 重点
        }

    @Override
        public void reportEvent(String packageName, int userId, int eventType) {
            if (packageName == null) {
                Slog.w(TAG, "Event reported without a package name");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName;

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = eventType;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

}
```

`mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget()`, 调用到:

```
/**
 * Called by the Binder stub.
 */
void reportEvent(UsageEvents.Event event, int userId) {
    // SPRD: bug702053, cancel event record while monkey test.
    ActivityManagerService am = (ActivityManagerService)ActivityManager.getService();
    if (am.isUserAMonkeyNoCheck()) {
        if (DEBUG) {
            Slog.i(TAG, "->reportEvent event:" + event + ", userId:" + userId
                    + ", monkey test return directly");
        }
        return;
    }
    synchronized (mLock) {
        final long timeNow = checkAndGetTimeLocked();
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        convertToSystemTimeLocked(event);

        if (event.getPackageName() != null
                && mPackageManagerInternal.isPackageEphemeral(userId, event.getPackageName())) {
            event.mFlags |= Event.FLAG_IS_PACKAGE_INSTANT_APP;
        }

        final UserUsageStatsService service =
            getUserDataAndInitializeIfNeededLocked(userId, timeNow);
        service.reportEvent(event); //　检查app的bucket，详见2.3

        // NOTE: Bug #627645 low power Feature BEG-->
        if (mPowerControllerHelper != null) {
            mPowerControllerHelper.reportEvent(event, userId, elapsedRealtime);
        }
        // <-- NOTE: Bug #627645 low power Feature END

        mAppStandby.reportEvent(event, elapsedRealtime, userId); // 重点
        switch (event.mEventType) {
            case Event.MOVE_TO_FOREGROUND:
                mAppTimeLimit.moveToForeground(event.getPackageName(), event.getClassName(),
                        userId);
                break;
            case Event.MOVE_TO_BACKGROUND:
                mAppTimeLimit.moveToBackground(event.getPackageName(), event.getClassName(),
                        userId);
                break;
        }
    }
}
```

`mAppStandby.reportEvent`, 就调用到 `frameworks/base/services/usage/java/com/android/server/usage/AppStandbyController.java`
AppStandbyController是应用分组功能的核心部件,用于控制应用分组的, 系统默认的动态分组的规则就在该部件中.

```
void reportEvent(UsageEvents.Event event, long elapsedRealtime, int userId) {                      
    if (!mAppIdleEnabled) return; // mAppIdleEnabled 是使能应用分组功能的开关
    synchronized (mAppIdleLock) {                                                                                                            
        // TODO: Ideally this should call isAppIdleFiltered() to avoid calling back                                                          
        // about apps that are on some kind of whitelist anyway.                                                                             
        final boolean previouslyIdle = mAppIdleHistory.isIdle(                                                                               
                event.mPackage, userId, elapsedRealtime);                                                                                    
        // Inform listeners if necessary                                                                                                     
        if ((event.mEventType == UsageEvents.Event.MOVE_TO_FOREGROUND  // 表示一个组件移动到前台                                                                      
                    || event.mEventType == UsageEvents.Event.MOVE_TO_BACKGROUND // 标示一个组件移动到后台                                                 
                    || event.mEventType == UsageEvents.Event.SYSTEM_INTERACTION // 该package以某种方式在和系统交互
                    || event.mEventType == UsageEvents.Event.USER_INTERACTION // 该package以某种方式和用户交互                               
                    || event.mEventType == UsageEvents.Event.NOTIFICATION_SEEN // 一个可见的通知                                   
                    || event.mEventType == UsageEvents.Event.SLICE_PINNED  // slice 是android P的新特性, 被一个app 钉住的slice
                    || event.mEventType == UsageEvents.Event.SLICE_PINNED_PRIV)) {  // 一个slice被默认的launcher或者assistant钉住

            final AppUsageHistory appHistory = mAppIdleHistory.getAppUsageHistory( // AppUsageHistory 存储了该用户使用该包一些数据
                    event.mPackage, userId, elapsedRealtime);             
            final int prevBucket = appHistory.currentBucket;                                                                                 
            final int prevBucketReason = appHistory.bucketingReason;                                                                         
            final long nextCheckTime;                                                                                                        
            final int subReason = usageEventToSubReason(event.mEventType);                                                                   
            final int reason = REASON_MAIN_USAGE | subReason;                                                                                
            if (event.mEventType == UsageEvents.Event.NOTIFICATION_SEEN                                                                      
                    || event.mEventType == UsageEvents.Event.SLICE_PINNED) {                                                                 
                // Mild usage elevates to WORKING_SET but doesn't change usage time. // 不改变usage  time 什么意思???
                // mAppIdleHistory.reportUsage 作用是改变app的组, 如果要改变的新组别优先级比原来低, 就不会修改
                mAppIdleHistory.reportUsage(appHistory, event.mPackage,
                        STANDBY_BUCKET_WORKING_SET, subReason,// 提升到STANDBY_BUCKET_WORKING_SET
                        0, elapsedRealtime + mNotificationSeenTimeoutMillis);
                nextCheckTime = mNotificationSeenTimeoutMillis; // 12 小时
            } else if (event.mEventType == UsageEvents.Event.SYSTEM_INTERACTION) {
                mAppIdleHistory.reportUsage(appHistory, event.mPackage,
                        STANDBY_BUCKET_ACTIVE, subReason,// 提升到 STANDBY_BUCKET_ACTIVE                                            
                        0, elapsedRealtime + mSystemInteractionTimeoutMillis);
                nextCheckTime = mSystemInteractionTimeoutMillis;// 10 分钟
            } else { // 除了以上 3 种情况, 其他情况在此设置nextCheckTime
                mAppIdleHistory.reportUsage(appHistory, event.mPackage,                                                                      
                        STANDBY_BUCKET_ACTIVE, subReason,                                                                     
                        elapsedRealtime, elapsedRealtime + mStrongUsageTimeoutMillis);
                nextCheckTime = mStrongUsageTimeoutMillis; // 1 小时                                                       
            }                                                                                                                                
            mHandler.sendMessageDelayed(mHandler.obtainMessage                                                                               
                    (MSG_CHECK_PACKAGE_IDLE_STATE, userId, -1, event.mPackage),                                                              
                    nextCheckTime); // 延时进行idle状态的检测和更新
            final boolean userStartedInteracting =
                appHistory.currentBucket == STANDBY_BUCKET_ACTIVE &&
                prevBucket != appHistory.currentBucket &&
                (prevBucketReason & REASON_MAIN_MASK) != REASON_MAIN_USAGE;
            maybeInformListeners(event.mPackage, userId, elapsedRealtime,
                    appHistory.currentBucket, reason, userStartedInteracting);

            if (previouslyIdle) {
                notifyBatteryStats(event.mPackage, userId, false);
            }
        }
    }
}
```

这里先说一下mAppIdleEnabled, 找到设置该值的方法:

```
void setAppIdleEnabled(boolean enabled) {
    mAppIdleEnabled = enabled;
}                                                                                                                            
```

调用该方法的位置:

```
// Check if app_idle_enabled has changed                                                                                                                     
setAppIdleEnabled(mInjector.isAppIdleEnabled());                                                                                                             
```

mInjector的类型是Injector, 这是一个AppStandbyController.java里的static class,isAppIdleEnabled实现在下面:

```
boolean isAppIdleEnabled() {                                                                                                                                     
    final boolean buildFlag = mContext.getResources().getBoolean(                                                                                                
            com.android.internal.R.bool.config_enableAutoPowerModes);                                                                                            
    final boolean runtimeFlag = Global.getInt(mContext.getContentResolver(),                                                                                     
            Global.APP_STANDBY_ENABLED, 1) == 1                                                                                                                  
        && Global.getInt(mContext.getContentResolver(),                                                                                                      
                Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED, 1) == 1;                                                                                                 
    return buildFlag && runtimeFlag;                                                                                                                             
}
```
mAppIdleEnabled是否为true(打开bucket功能), 要看config_enableAutoPowerModes 和 Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED来决定.
buildFlag获取的是资源文件里的配置config_enableAutoPowerModes, 该配置在`frameworks/base/core/res/res/values/config.xml`:

```
    <!-- Set this to true to enable the platform's auto-power-save modes like doze and                                                                                   
    app standby.  These are not enabled by default because they require a standard                                                                                  
    cloud-to-device messaging service for apps to interact correctly with the modes                                                                                 
    (such as to be able to deliver an instant message to the device even when it is                                                                                 
     dozing).  This should be enabled if you have such services and expect apps to                                                                                   
    correctly use them when installed on your device.  Otherwise, keep this disabled                                                                                
    so that applications can still use their own mechanisms. -->                                                                                                    
    <bool name="config_enableAutoPowerModes">false</bool>                                                                                                                
```

ADAPTIVE_BATTERY_MANAGEMENT_ENABLED和省电模式相关, 在android P中在省电模式下,所有的后台运行的app都将受到限制.

回到reportEvent的的异步消息处理,`MSG_CHECK_PACKAGE_IDLE_STATE`:

```
                case MSG_CHECK_PACKAGE_IDLE_STATE:
                        checkAndUpdateStandbyState((String) msg.obj, msg.arg1, msg.arg2,
                                   mInjector.elapsedRealtime());
```

调用了checkAndUpdateStandbyState方法:

```
    /** Check if we need to update the standby state of a specific app. */
    private void checkAndUpdateStandbyState(String packageName, @UserIdInt int userId,
            int uid, long elapsedRealtime) {
        // 包是由于一些原因在白名单,那么isAppSpecial会返回true, 包将不会进入standby状态
        final boolean isSpecial = isAppSpecial(packageName,
                UserHandle.getAppId(uid),
                userId);

        if (isSpecial) { // 对于豁免的app, 做特别的处理
            synchronized (mAppIdleLock) {
                // STANDBY_BUCKET_EXEMPTED 的值是5, 使用am get-standby-bucket 命令查看,
                // 一些package显示是5,就是说明是在whitelist
                mAppIdleHistory.setAppStandbyBucket(packageName, userId, elapsedRealtime,
                        STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);
            }
            // 通知监听者, 这是一个豁免的package
            maybeInformListeners(packageName, userId, elapsedRealtime,
                    STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT, false);
        } else { // 没有在白名单里的app走的分支
            synchronized (mAppIdleLock) {
                final AppIdleHistory.AppUsageHistory app =
                    mAppIdleHistory.getAppUsageHistory(packageName,
                            userId, elapsedRealtime);
                int reason = app.bucketingReason;
                final int oldMainReason = reason & REASON_MAIN_MASK;

                // If the bucket was forced by the user/developer, leave it alone.
                // A usage event will be the only way to bring it out of this forced state
                // 如果的bucket的设置原因是被用户或者开发者,强制设置的,将不会改变它的组别
                if (oldMainReason == REASON_MAIN_FORCED) {
                    return;
                }
                final int oldBucket = app.currentBucket;
                // 动态调整buncket, 最高等级只能设置到 10 , 也就是STANDBY_BUCKET_ACTIVE
                int newBucket = Math.max(oldBucket, STANDBY_BUCKET_ACTIVE); // Undo EXEMPTED
                boolean predictionLate = predictionTimedOut(app, elapsedRealtime);
                // Compute age-based bucket
                if (oldMainReason == REASON_MAIN_DEFAULT
                        || oldMainReason == REASON_MAIN_USAGE
                        || oldMainReason == REASON_MAIN_TIMEOUT
                        || predictionLate) {

                    if (!predictionLate && app.lastPredictedBucket >= STANDBY_BUCKET_ACTIVE
                            && app.lastPredictedBucket <= STANDBY_BUCKET_RARE) {
                        newBucket = app.lastPredictedBucket;
                        reason = REASON_MAIN_PREDICTED | REASON_SUB_PREDICTED_RESTORED;
                        if (DEBUG) {
                            Slog.d(TAG, "Restored predicted newBucket = " + newBucket);
                        }
                    } else { 
                        // 获取package应该在的新组, 这里不是当前的组, 而是将来的应该在的组别
                        // getBucketForLocked 是一个核心的方法
                        newBucket = getBucketForLocked(packageName, userId,
                                elapsedRealtime);
                        if (DEBUG) {
                            Slog.d(TAG, "Evaluated AOSP newBucket = " + newBucket);
                        }
                        reason = REASON_MAIN_TIMEOUT;
                    }
                }

                // Check if the app is within one of the timeouts for forced bucket elevation
                final long elapsedTimeAdjusted = mAppIdleHistory.getElapsedTime(elapsedRealtime);
                if (newBucket >= STANDBY_BUCKET_ACTIVE
                        && app.bucketActiveTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_ACTIVE;
                    reason = app.bucketingReason;
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at ACTIVE due to min timeout");
                    }
                } else if (newBucket >= STANDBY_BUCKET_WORKING_SET
                        && app.bucketWorkingSetTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_WORKING_SET;
                    // If it was already there, keep the reason, else assume timeout to WS
                    reason = (newBucket == oldBucket)
                        ? app.bucketingReason
                        : REASON_MAIN_USAGE | REASON_SUB_USAGE_ACTIVE_TIMEOUT;
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at WORKING_SET due to min timeout");
                    }
                }
                if (DEBUG) {
                    Slog.d(TAG, "     Old bucket=" + oldBucket
                            + ", newBucket=" + newBucket);
                }
                if (oldBucket < newBucket || predictionLate) { // 注意 这里的oldBucket < newBucket, 说明新的组是降优先级的组别
                    mAppIdleHistory.setAppStandbyBucket(packageName, userId, // 设置新的bucket
                            elapsedRealtime, newBucket, reason);
                    maybeInformListeners(packageName, userId, elapsedRealtime,
                            newBucket, reason, false);
                }
            }
        }
    }
```

checkAndUpdateStandbyState 方法的作用就是,找出真正需要设置新的buncket的app,然后调用getBucketForLocked,获取新的bucket名称,
再调用mAppIdleHistory.setAppStandbyBucket 改变package的bucket, 并通知所有的监听者.

在这个方法里, 还要继续探究的就是getBucketForLocked 方法:

```
/**
 * Evaluates next bucket based on time since last used and the bucketing thresholds.
 * @param packageName the app
 * @param userId the user
 * @param elapsedRealtime as the name suggests, current elapsed time
 * @return the bucket for the app, based on time since last used
 */
@GuardedBy("mAppIdleLock")
@StandbyBuckets int getBucketForLocked(String packageName, int userId,
        long elapsedRealtime) {
    int bucketIndex = mAppIdleHistory.getThresholdIndex(packageName, userId,
            elapsedRealtime, mAppStandbyScreenThresholds, mAppStandbyElapsedThresholds);
    return THRESHOLD_BUCKETS[bucketIndex];
}
```

THRESHOLD_BUCKETS的定义如下:

```
static final int[] THRESHOLD_BUCKETS = {
    STANDBY_BUCKET_ACTIVE,
    STANDBY_BUCKET_WORKING_SET,
    STANDBY_BUCKET_FREQUENT,
    STANDBY_BUCKET_RARE
};
```

从mAppIdleHistory.getThresholdIndex获取一个index,让后在THRESHOLD_BUCKETS查找到对应的组别.在`getThresholdIndex(
packageName, userId, elapsedRealtime, mAppStandbyScreenThresholds, mAppStandbyElapsedThresholds);`中,有两个Threshold:
`mAppStandbyScreenThresholds` 和 `mAppStandbyElapsedThresholds` :

```
long[] mAppStandbyScreenThresholds = SCREEN_TIME_THRESHOLDS;
long[] mAppStandbyElapsedThresholds = ELAPSED_TIME_THRESHOLDS;

static final boolean COMPRESS_TIME = false;
private static final long ONE_MINUTE = 60 * 1000;
private static final long ONE_HOUR = ONE_MINUTE * 60;
private static final long ONE_DAY = ONE_HOUR * 24;

static final long[] SCREEN_TIME_THRESHOLDS = {
    0,
    0,
    COMPRESS_TIME ? 120 * 1000 : 1 * ONE_HOUR,
    COMPRESS_TIME ? 240 * 1000 : 2 * ONE_HOUR
};

static final long[] ELAPSED_TIME_THRESHOLDS = {
    0,
    COMPRESS_TIME ?  1 * ONE_MINUTE : 12 * ONE_HOUR,
    COMPRESS_TIME ?  4 * ONE_MINUTE : 24 * ONE_HOUR,
    COMPRESS_TIME ? 16 * ONE_MINUTE : 48 * ONE_HOUR
};
```

接下来分析getThresholdIndex函数的具体实现, 该方法在`frameworks/base/services/usage/java/com/android/server/usage/AppIdleHistory.java`:

```
/**
 * Returns the index in the arrays of screenTimeThresholds and elapsedTimeThresholds
 * that corresponds to how long since the app was used.
 * @param packageName
 * @param userId
 * @param elapsedRealtime current time
 * @param screenTimeThresholds Array of screen times, in ascending order, first one is 0
 * @param elapsedTimeThresholds Array of elapsed time, in ascending order, first one is 0
 * @return The index whose values the app's used time exceeds (in both arrays)
 */
int getThresholdIndex(String packageName, int userId, long elapsedRealtime,
        long[] screenTimeThresholds, long[] elapsedTimeThresholds) {
    ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
    AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
            elapsedRealtime, false);
    // If we don't have any state for the app, assume never used
    // 对于从来没有使用过的app , 就设置成最低级别的bucket, STANDBY_BUCKET_RARE
    if (appUsageHistory == null) return screenTimeThresholds.length - 1;
    // getScreenOnTime(elapsedRealtime) 获取设备的总亮屏时间(有记录在案的时间)
    // appUsageHistory.lastUsedScreenTime app最后一次亮屏时间点,基于ScreenOn basetime
    // screenOnDelta 计算出来就是app最后一次亮屏使用,到现在,已经有多久的亮屏时间
    // getElapsedTime(elapsedRealtime) 获取是被从bron开始现在的时间
    // appUsageHistory.lastUsedElapsedTime 基于ElapsedTime该package最后一次使用的时间点
    // elapsedDelta 计算出来就是app最后一次使用到现在的时间点
    long screenOnDelta = getScreenOnTime(elapsedRealtime) - appUsageHistory.lastUsedScreenTime;
    long elapsedDelta = getElapsedTime(elapsedRealtime) - appUsageHistory.lastUsedElapsedTime;

    if (DEBUG) Slog.d(TAG, packageName
            + " lastUsedScreen=" + appUsageHistory.lastUsedScreenTime
            + " lastUsedElapsed=" + appUsageHistory.lastUsedElapsedTime);
    if (DEBUG) Slog.d(TAG, packageName + " screenOn=" + screenOnDelta
            + ", elapsed=" + elapsedDelta);
    for (int i = screenTimeThresholds.length - 1; i >= 0; i--) {
        if (screenOnDelta >= screenTimeThresholds[i]
                && elapsedDelta >= elapsedTimeThresholds[i]) {
            return i;
        }
    }
    return 0; // 对应STANDBY_BUCKET_ACTIVE
}


```

计算出screenOnDelta 和 elapsedDelta ,从for循环的便利顺序来看: 

- screenOnDelta超过2小时, elapsedDelta超过48小时bucket为RARE
- screenOnDelta超过1小时, elapsedDelta超过24小时bucket为FREQUENT
- elapsedDelta超过12小时bucket为working_set

虽然从for循环的顺序是上面的判断顺序,但是从时间轴的角度来看,package满足了`screenOnDelta超过2小时, elapsedDelta超过48小时`,
一定在某个时间点也会满足`screenOnDelta超过1小时, elapsedDelta超过24小时`, 在满足了`screenOnDelta超过1小时, elapsedDelta超过24小时`
那么在某个时间点一定也就满足了elapsedDelta超过12小时. 这么来说,一个package如果是在active 的bucket, 则会先到`working_set`,再到`FREQUENT`,
再到`RARE`.

#### 2.2 获取app的bucket

获取app的bucket流程比较简单:

![获取app的bucket流程图](https://raw.githubusercontent.com/lqktz/document/master/res/getAppStandbyBucket.png)

在`AppIdleHistory.java`中的代码如下:

```
public int getAppStandbyBucket(String packageName, int userId, long elapsedRealtime) {
    ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
    AppUsageHistory appUsageHistory =
        getPackageHistory(userHistory, packageName, elapsedRealtime, true);
    return appUsageHistory.currentBucket; // 在AppUsageHistory 中获取currentBucket就是所在的组别
}
```

#### 2.3 检查app的bucket

在前面介绍reportEvent的过程中, 有一下一段调用:

```
    final UserUsageStatsService service =
    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
    service.reportEvent(event); //　检查app的bucket，详见2.3
```

调用了UserUsageStatsService 的report方法:

```
void reportEvent(UsageEvents.Event event) {
    if (DEBUG) {
        Slog.d(TAG, mLogPrefix + "Got usage event for " + event.mPackage
                + "[" + event.mTimeStamp + "]: "
                + eventToString(event.mEventType));
    }

    // event.mTimeStamp 指该event发生的时间点,到机器开机的时间
    // mDailyExpiryDate.getTimeInMillis() 获取的是mTime值, 该值在mDailyExpiryDate.addDays(1) 中设置为一天
    // 此处代码逻辑是event的发生时间点在一天以上,则触发rolloverStats
    if (event.mTimeStamp >= mDailyExpiryDate.getTimeInMillis()) {
        // Need to rollover
        rolloverStats(event.mTimeStamp);
    }

      ......
```

rolloverStats函数中会调用loadActiveStats函数，loadActiveStats函数会调用mListener.onStatsReloaded函数，而这个mLisener正是UsageStatsService。
而UsageStatsService的onStatsReloaded函数，是调用了AppStandbyController的postOneTimeCheckIdleStates，这个函数如下，因为这个时候已经开机，
因此发送了一个`MSG_ONE_TIME_CHECK_IDLE_STATES`消息. 通过该异步消息会调用到checkIdleStates, 该方法最后会调用到checkAndUpdateStandbyState, 
调用的方式如下:

```
for (int i = 0; i < runningUserIds.length; i++) {                                                                                                                
    final int userId = runningUserIds[i];                                                                                                                        
    if (checkUserId != UserHandle.USER_ALL && checkUserId != userId) {                                                                                           
        continue;                                                                                                                                                
    }                                                                                                                                                            
    if (DEBUG) {                                                                                                                                                 
        Slog.d(TAG, "Checking idle state for user " + userId);                                                                                                   
    }                                                                                                                                                            
    List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(                                                                                     
            PackageManager.MATCH_DISABLED_COMPONENTS,                                                                                                            
            userId);                                                                                                                                             
    final int packageCount = packages.size();                                                                                                                    
    for (int p = 0; p < packageCount; p++) {                                                                                                                     
        final PackageInfo pi = packages.get(p);                                                                                                                  
        final String packageName = pi.packageName;                                                                                                               
        checkAndUpdateStandbyState(packageName, userId, pi.applicationInfo.uid,                                                                                  
                elapsedRealtime);                                                                                                                                
    }                                                                                                                                                            
}                                                                                                                                                                
```

这是对每个用户,每个包进行checkAndUpdateStandbyState,来更新状态.

在AppStandbyController里的 onBootPhase 阶段也会调用postOneTimeCheckIdleStates, 这意味着,开机会检测更新一次,之后每隔一天会检测更新一次.

从代码可以看出,应用待机分组和app 是否使用android P 的 SDK 开发没有关系, 所有app, 只要是安装到android P的设备上都会受到系统的限制.

## 3 应用待机模式

在android M 版本上添加了Doze  和 app standby模式, 长时间没有在前台使用, app 的行为也会受到限制,
详情可见[Optimize for Doze and App Standby](https://developer.android.google.cn/training/monitoring-device-state/doze-standby). 
该功能就是将app设置为是否idle状态来进行限制, 处于idle状态的app 的网络,JobScheduler等都会限制住. 在android P中,由于添加了应用待机分组功能, 
app的行为被限制的更加精细化.

在P上的`AppIdleHistory.java`中的setIdle方法设置为

```
/* Returns the new standby bucket the app is assigned to */                                                                                                          
public int setIdle(String packageName, int userId, boolean idle, long elapsedRealtime) {                                                                             
    ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);                                                                                          
    AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,                                                                                    
            elapsedRealtime, true);                                                                                                                                  
    if (idle) {                                                                                                                                                      
        appUsageHistory.currentBucket = STANDBY_BUCKET_RARE;                                                                                                         
        appUsageHistory.bucketingReason = REASON_MAIN_FORCED;                                                                                                        
    } else {                                                                                                                                                         
        appUsageHistory.currentBucket = STANDBY_BUCKET_ACTIVE;                                                                     
        // This is to pretend that the app was just used, don't freeze the state anymore.
        appUsageHistory.bucketingReason = REASON_MAIN_USAGE | REASON_SUB_USAGE_USER_INTERACTION;
    }                                                                                                                                                                
    return appUsageHistory.currentBucket;                                                                                                                            
}
```

对比之前的android 版本的代码:

```
public void setIdle(String packageName, int userId, long elapsedRealtime) {
    ArrayMap<String, PackageHistory> userHistory = getUserHistory(userId);
    PackageHistory packageHistory = getPackageHistory(userHistory, packageName,
            elapsedRealtime);

    shiftHistoryToNow(userHistory, elapsedRealtime);

    packageHistory.recent[HISTORY_SIZE - 1] &= ~FLAG_LAST_STATE;
}
```

android P 已经把原来的standby 功能合并到了新添加的应用待机分组功能.如果是idle状态就是对应`STANDBY_BUCKET_RARE`组,不是idle就是`STANDBY_BUCKET_ACTIVE`组.

## 4 限制相关源码分析

### 4.1 监听的类型

前面分析了大段的代码, 这些代码将不同的app分到了不同的组别, 每次更新组别, 都会去通知监听者,在 checkAndUpdateStandbyState 中,最后会调用
maybeInformListeners 来通知监听者:

```
/** Inform listeners if the bucket has changed since it was last reported to listeners */
private void maybeInformListeners(String packageName, int userId,
        long elapsedRealtime, int bucket, int reason, boolean userStartedInteracting) {                                                                              
    synchronized (mAppIdleLock) {
        if (mAppIdleHistory.shouldInformListeners(packageName, userId,                                                                                               
                    elapsedRealtime, bucket)) {                                                                                                                          
            final StandbyUpdateRecord r = StandbyUpdateRecord.obtain(packageName, userId,                                                                            
                    bucket, reason, userStartedInteracting);                                                                                                         
            if (DEBUG) Slog.d(TAG, "Standby bucket for " + packageName + "=" + bucket);                                                                              
            mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS, r));                                                                                   
        }                                                                                                                                                            
    }
}
```

`MSG_INFORM_LISTENERS` 异步消息进过调用,informListeners

```
case MSG_INFORM_LISTENERS:
    StandbyUpdateRecord r = (StandbyUpdateRecord) msg.obj;
    informListeners(r.packageName, r.userId, r.bucket, r.reason,
        r.isUserInteraction);                                                                                                                        
    r.recycle();
    break;
```

进入informListeners: 

```
void informListeners(String packageName, int userId, int bucket, int reason,
        boolean userInteraction) {                                                                                                                                   
    // app所处的buncket的优先级在RARE以及RARE之下,标记为idle
    final boolean idle = bucket >= STANDBY_BUCKET_RARE;
    synchronized (mPackageAccessListeners) {
        for (AppIdleStateChangeListener listener : mPackageAccessListeners) {
            // onAppIdleStateChanged 用于通知监听者
            listener.onAppIdleStateChanged(packageName, userId, idle, bucket, reason);                                                                               
            // 用户与package交互才导致的更改bucket, 则userInteraction为true,
            if (userInteraction) {                                                                                                                                   
                listener.onUserInteractionStarted(packageName, userId);                                                                                              
            }                                                                                                                                                        
        }                                                                                                                                                            
    }
}
```

从mPackageAccessListeners取出listener, 调用其方法onUserInteractionStarted. 重点就在分析清楚mPackageAccessListeners的结构:

```
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;

@GuardedBy("mPackageAccessListeners")
private ArrayList<AppIdleStateChangeListener>
mPackageAccessListeners = new ArrayList<>();

void addListener(AppIdleStateChangeListener listener) {
    synchronized (mPackageAccessListeners) {
        if (!mPackageAccessListeners.contains(listener)) {                                                                                                           
            mPackageAccessListeners.add(listener);                                                                                                                   
        }                                                                                                                                                            
    }
}

void removeListener(AppIdleStateChangeListener listener) {
    synchronized (mPackageAccessListeners) {
        mPackageAccessListeners.remove(listener);                                                                                                                    
    }
}
```
上面的listener, 就是AppIdleStateChangeListener 类型, 而AppIdleStateChangeListener又定义在UsageStatsManagerInternal.java中: 

```
public static abstract class AppIdleStateChangeListener {

    /** Callback to inform listeners that the idle state has changed to a new bucket. */
    public abstract void onAppIdleStateChanged(String packageName, @UserIdInt int userId,
            boolean idle, int bucket, int reason);                                                                                                                   

    /**
     * Callback to inform listeners that the parole state has changed. This means apps are
     * allowed to do work even if they're idle or in a low bucket.
     */
    public abstract void onParoleStateChanged(boolean isParoleOn);

    /**
     * Optional callback to inform the listener that the app has transitioned into
     * an active state due to user interaction.
     */
    public void onUserInteractionStarted(String packageName, @UserIdInt int userId) {
        // No-op by default                                                                                                                                          
    }
}
```
这就是个AppIdleStateChangeListener的接口, listener对应的就是其真正的实现类:

- NetworkPolicyManagerService.java里的私有类AppIdleStateChangeListener
- AlarmManagerService.java里的final类AppStandbyTracker
- JobSchedulerService.java里的final类StandbyTracker

**感觉这几个Listener不是同一个人写的, 命名不一致**

从接口的实现看, package在不同的组别, 将在 Network, Alarm, JobScheduler 三个方面受到限制.接下来就从三个方面分析其源码实现.
在`informListeners` 源码中, 调用到了`onAppIdleStateChanged` 和 `onUserInteractionStarted` 两个接口. 对于`onUserInteractionStarted`
只有在JobScheduler中才真正的实现.`onAppIdleStateChanged` 在三个AppIdleStateChangeListener接口的实现类里都有实现.

### 4.2 Network的限制

`frameworks/base/services/core/java/com/android/server/net/NetworkPolicyManagerService.java` 的内部类`AppIdleStateChangeListener`定义如下:
这个类名和抽象类的名称一样,别搞混了.

```
private class AppIdleStateChangeListener
extends UsageStatsManagerInternal.AppIdleStateChangeListener {

    @Override
        public void onAppIdleStateChanged(String packageName, int userId, boolean idle, int bucket,                                                                      
                int reason) {                                                                                                                                            
            try {                                                                                                                                                        
                final int uid = mContext.getPackageManager().getPackageUidAsUser(packageName,                                                                            
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);                                                                                              
                synchronized (mUidRulesFirstLock) {                                                                                                                      
                    mLogger.appIdleStateChanged(uid, idle);                                                                                                              
                    updateRuleForAppIdleUL(uid);                                                                                                                         
                    updateRulesForPowerRestrictionsUL(uid);                                                                                                              
                }                                                                                                                                                        
            } catch (NameNotFoundException nnfe) {                                                                                                                       
            }                                                                                                                                                            
        }                                                                                                                                                                

    @Override
        public void onParoleStateChanged(boolean isParoleOn) {                                                                                                           
            synchronized (mUidRulesFirstLock) {                                                                                                                          
                mLogger.paroleStateChanged(isParoleOn);                                                                                                                  
                updateRulesForAppIdleParoleUL();                                                                                                                         
            }                                                                                                                                                            
        }                                                                                                                                                                
}
```

### 4.3 Alarm的限制

AlarmManagerService.java里的final类AppStandbyTracker:

```
/**
 * Tracking of app assignments to standby buckets
 */
final class AppStandbyTracker extends UsageStatsManagerInternal.AppIdleStateChangeListener {

    public void onAppIdleStateChanged(final String packageName, final @UserIdInt int userId,
            boolean idle, int bucket, int reason) {                                                                                                                  
        mHandler.removeMessages(AlarmHandler.APP_STANDBY_BUCKET_CHANGED);                                                                                            
        mHandler.obtainMessage(AlarmHandler.APP_STANDBY_BUCKET_CHANGED, userId, -1, packageName)                                                                     
            .sendToTarget();                                                                                                                                     
    }

    public void onParoleStateChanged(boolean isParoleOn) {
        mHandler.removeMessages(AlarmHandler.APP_STANDBY_BUCKET_CHANGED);                                                                                            
        mHandler.removeMessages(AlarmHandler.APP_STANDBY_PAROLE_CHANGED);                                                                                            
        mHandler.obtainMessage(AlarmHandler.APP_STANDBY_PAROLE_CHANGED,                                                                                              
                Boolean.valueOf(isParoleOn)).sendToTarget();                                                                                                         
    }
};
```

关于alarm的延时定义:

```
        // Keys for specifying throttling delay based on app standby bucketing
        private final String[] KEYS_APP_STANDBY_DELAY = {
                "standby_active_delay",
                "standby_working_delay",
                "standby_frequent_delay",
                "standby_rare_delay",
                "standby_never_delay",
        };

        private static final long DEFAULT_MIN_FUTURITY = 5 * 1000;
        private static final long DEFAULT_MIN_INTERVAL = 60 * 1000;
        private static final long DEFAULT_MAX_INTERVAL = 365 * DateUtils.DAY_IN_MILLIS;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME = DEFAULT_MIN_FUTURITY;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME = 9*60*1000;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION = 10*1000;
        private static final long DEFAULT_LISTENER_TIMEOUT = 5 * 1000;
        private final long[] DEFAULT_APP_STANDBY_DELAYS = {
                0,                       // Active
                6 * 60_000,              // Working
                30 * 60_000,             // Frequent
                2 * 60 * 60_000,         // Rare
                10 * 24 * 60 * 60_000    // Never
        };
```

- AlarmManagerService->setImplLocked
- AlarmManagerSerivice->adjustDeliveryTimeBasedOnStandbyBucketLocked 
- AlarmManagerService->getMinDelayForBucketLocked
由于对alarm不熟悉,就先到这.

### 4.4 JobScheduler的限制

JobScheduler 监听实现：

```
/**
 * Tracking of app assignments to standby buckets
 */
final class StandbyTracker extends AppIdleStateChangeListener {

    // AppIdleStateChangeListener interface for live updates

    @Override
        public void onAppIdleStateChanged(final String packageName, final @UserIdInt int userId,                                                                         
                boolean idle, int bucket, int reason) {                                                                                                                  
            final int uid = mLocalPM.getPackageUid(packageName,                                                                                                          
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);                                                                                                  
            if (uid < 0) {                                                                                                                                               
                if (DEBUG_STANDBY) {                                                                                                                                     
                    Slog.i(TAG, "App idle state change for unknown app "                                                                                                 
                            + packageName + "/" + userId);                                                                                                               
                }                                                                                                                                                        
                return;                                                                                                                                                  
            }                                                                                                                                                            

            final int bucketIndex = standbyBucketToBucketIndex(bucket);                                                                                                  
            // update job bookkeeping out of band                                                                                                                        
            BackgroundThread.getHandler().post(() -> {                                                                                                                   
                    if (DEBUG_STANDBY) {                                                                                                                                     
                    Slog.i(TAG, "Moving uid " + uid + " to bucketIndex " + bucketIndex);                                                                                 
                    }                                                                                                                                                        
                    synchronized (mLock) {                                                                                                                                   
                    mJobs.forEachJobForSourceUid(uid, job -> {                                                                                                           
                        // double-check uid vs package name to disambiguate shared uids                                                                                  
                        if (packageName.equals(job.getSourcePackageName())) {                                                                                            
                        job.setStandbyBucket(bucketIndex);  // 在JobStatus.java中修改job的app所在的bucket
                        }                                                                                                                                                
                        });                                                                                                                                                  
                    onControllerStateChanged();  //重点分析
                    }                                                                                                                                                        
                    });                                                                                                                                                          
        }                                                                                                                                                                
    @Override
        public void onParoleStateChanged(boolean isParoleOn) {                                                                                                           
            if (DEBUG_STANDBY) {                                                                                                                                         
                Slog.i(TAG, "Global parole state now " + (isParoleOn ? "ON" : "OFF"));                                                                                   
            }                                                                                                                                                            
            mInParole = isParoleOn;                                                                                                                                      
        }                                                                                                                                                                

    @Override
        public void onUserInteractionStarted(String packageName, int userId) {                                                                                           
            final int uid = mLocalPM.getPackageUid(packageName,                                                                                                          
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);                                                                                                  
            if (uid < 0) {                                                                                                                                               
                // Quietly ignore; the case is already logged elsewhere                                                                                                  
                return;                                                                                                                                                  
            }                                                                                                                                                            

            long sinceLast = mUsageStats.getTimeSinceLastJobRun(packageName, userId);                                                                                    
            if (sinceLast > 2 * DateUtils.DAY_IN_MILLIS) {                                                                                                               
                // Too long ago, not worth logging                                                                                                                       
                sinceLast = 0L;                                                                                                                                          
            }                                                                                                                                                            
            final DeferredJobCounter counter = new DeferredJobCounter();                                                                                                 
            synchronized (mLock) {                                                                                                                                       
                mJobs.forEachJobForSourceUid(uid, counter);                                                                                                              
            }                                                                                                                                                            
            if (counter.numDeferred() > 0 || sinceLast > 0) {                                                                                                            
                BatteryStatsInternal mBatteryStatsInternal = LocalServices.getService                                                                                    
                    (BatteryStatsInternal.class);                                                                                                                    
                mBatteryStatsInternal.noteJobsDeferred(uid, counter.numDeferred(), sinceLast);  
            }
        }
}
```

在JobSchedulerService 创建的时候就添加Listener：

```
        // Set up the app standby bucketing tracker
        mStandbyTracker = new StandbyTracker();
        mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);
        mUsageStats.addAppIdleStateChangeListener(mStandbyTracker);

```

`onControllerStateChanged()`发送了异步消息`MSG_CHECK_JOB` :

```
case MSG_CHECK_JOB:
if (mReportedActive) {
    // if jobs are currently being run, queue all ready jobs for execution.
    // job 正在执行执行，让其2执行完
    queueReadyJobsForExecutionLocked();
} else {
    // Check the list of jobs and run some of them if we feel inclined.
    maybeQueueReadyJobsForExecutionLocked();
}
break; 
```

`maybeQueueReadyJobsForExecutionLocked()` 的实现:

```
    private void maybeQueueReadyJobsForExecutionLocked() {
        if (DEBUG) Slog.d(TAG, "Maybe queuing ready jobs...");                                                                            
                                                                                                                                          
        noteJobsNonpending(mPendingJobs);                                                                                                 
        mPendingJobs.clear();                                                                                                             
        stopNonReadyActiveJobsLocked();                                                                                                   
        mJobs.forEachJob(mMaybeQueueFunctor);  // 会调用到MaybeReadyJobQueueFunctor的accept()方法
                                                                            
        mMaybeQueueFunctor.postProcess();  // mPendingJobs.addAll(runnableJobs)
                                                                                               
    }
```

`MaybeReadyJobQueueFunctor` 的 accept() 会调用到isReadyToBeExecutedLocked(job):

```
    /**
     * Criteria for moving a job into the pending queue:
     *      - It's ready.
     *      - It's not pending.
     *      - It's not already running on a JSC.
     *      - The user that requested the job is running.
     *      - The job's standby bucket has come due to be runnable.
     *      - The component is enabled and runnable.
     */
    private boolean isReadyToBeExecutedLocked(JobStatus job) {
		.....
        // If the app is in a non-active standby bucket, make sure we've waited
        // an appropriate amount of time since the last invocation.  During device-
        // wide parole, standby bucketing is ignored.
        //
        // Jobs in 'active' apps are not subject to standby, nor are jobs that are
        // specifically marked as exempt.
        if (DEBUG_STANDBY) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " parole=" + mInParole + " active=" + job.uidActive
                    + " exempt=" + job.getJob().isExemptedFromAppStandby());
        }
        if (!mInParole
                && !job.uidActive
                && !job.getJob().isExemptedFromAppStandby()) {
            final int bucket = job.getStandbyBucket();
            if (DEBUG_STANDBY) {
                Slog.v(TAG, "  bucket=" + bucket + " heartbeat=" + mHeartbeat
                        + " next=" + mNextBucketHeartbeat[bucket]);
            }
            if (mHeartbeat < mNextBucketHeartbeat[bucket]) {
                // Only skip this job if the app is still waiting for the end of its nominal
                // bucket interval.  Once it's waited that long, we let it go ahead and clear.
                // The final (NEVER) bucket is special; we never age those apps' jobs into
                // runnability.
                final long appLastRan = heartbeatWhenJobsLastRun(job);
                if (bucket >= mConstants.STANDBY_BEATS.length
                        || (mHeartbeat > appLastRan
                                && mHeartbeat < appLastRan + mConstants.STANDBY_BEATS[bucket])) {
                    // TODO: log/trace that we're deferring the job due to bucketing if we hit this
                    if (job.getWhenStandbyDeferred() == 0) {
                        if (DEBUG_STANDBY) {
                            Slog.v(TAG, "Bucket deferral: " + mHeartbeat + " < "
                                    + (appLastRan + mConstants.STANDBY_BEATS[bucket])
                                    + " for " + job);
                        }
                        job.setWhenStandbyDeferred(sElapsedRealtimeClock.millis());
                    }
                    return false;
                } else {
                    if (DEBUG_STANDBY) {
                        Slog.v(TAG, "Bucket deferred job aged into runnability at "
                                + mHeartbeat + " : " + job);
                    }
                }
            }
	......
	}

```

`STANDBY_BEATS` 的定义`JobSchedulerService.java` 中:

```
        private static final int DEFAULT_STANDBY_WORKING_BEATS = 11;  // ~ 2 hours, with 11min beats
        private static final int DEFAULT_STANDBY_FREQUENT_BEATS = 43; // ~ 8 hours
        private static final int DEFAULT_STANDBY_RARE_BEATS = 130; // ~ 24 hours
        
        /**
         * Mapping: standby bucket -> number of heartbeats between each sweep of that
         * bucket's jobs.
         *
         * Bucket assignments as recorded in the JobStatus objects are normalized to be
         * indices into this array, rather than the raw constants used
         * by AppIdleHistory.
         */
        final int[] STANDBY_BEATS = {
                0,
                DEFAULT_STANDBY_WORKING_BEATS,
                DEFAULT_STANDBY_FREQUENT_BEATS,
                DEFAULT_STANDBY_RARE_BEATS
        };
```

经过一系列的计算,不同bucket的心跳是不一样的,这样就实现了延时实现不同bucket的JS.心跳的计算是在:

```
    /**
     * Heartbeat tracking.  The heartbeat alarm is intentionally non-wakeup.
     */
    class HeartbeatAlarmListener implements AlarmManager.OnAlarmListener {

        @Override
        public void onAlarm() {
            synchronized (mLock) {
                final long sinceLast = sElapsedRealtimeClock.millis() - mLastHeartbeatTime;
                final long beatsElapsed = sinceLast / mConstants.STANDBY_HEARTBEAT_TIME;
                if (beatsElapsed > 0) {
                    mLastHeartbeatTime += beatsElapsed * mConstants.STANDBY_HEARTBEAT_TIME;
                    advanceHeartbeatLocked(beatsElapsed); // 不让触发alarm, 就是延时
                }
            }
            setNextHeartbeatAlarm(); // 设置下一次alarm 时间
        }
    }
```

这块代码,具体没有研究透彻,先告一段落.


参考blog:

 - [Android9.0 应用待机群组](https://www.codetd.com/article/2898647)
 - [Android P 电量管理](https://blog.csdn.net/jilrvrtrc/article/details/81369918)
 - [Power management restrictions](https://developer.android.google.cn/topic/performance/power/power-details)
 - [Android P新特性 ---应用待机群组（#####笔记#####）](https://blog.csdn.net/weixin_42963076/article/details/82689172)



