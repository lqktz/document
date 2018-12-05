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
    if (!mAppIdleEnabled) return; // mAppIdleEnabled 是使能应用分组功能的开关,默认是true
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

接下来的重点就是在处理`MSG_CHECK_PACKAGE_IDLE_STATE`:

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

        if (isSpecial) { // 对于豁免的app, 做特别的处理,设置为
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

```
void reportEvent(UsageEvents.Event event) {
    if (DEBUG) {
        Slog.d(TAG, mLogPrefix + "Got usage event for " + event.mPackage
                + "[" + event.mTimeStamp + "]: "
                + eventToString(event.mEventType));
    }

    // 
    if (event.mTimeStamp >= mDailyExpiryDate.getTimeInMillis()) {
        // Need to rollover
        rolloverStats(event.mTimeStamp);
    }

    final IntervalStats currentDailyStats = mCurrentStats[UsageStatsManager.INTERVAL_DAILY];

    final Configuration newFullConfig = event.mConfiguration;
    if (event.mEventType == UsageEvents.Event.CONFIGURATION_CHANGE &&
            currentDailyStats.activeConfiguration != null) {
        // Make the event configuration a delta.
        event.mConfiguration = Configuration.generateDelta(
                currentDailyStats.activeConfiguration, newFullConfig);
    }

    // Add the event to the daily list.
    if (currentDailyStats.events == null) {
        currentDailyStats.events = new EventList();
    }
    if (event.mEventType != UsageEvents.Event.SYSTEM_INTERACTION) {
        currentDailyStats.events.insert(event);
    }

    boolean incrementAppLaunch = false;
    if (event.mEventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
        if (event.mPackage != null && !event.mPackage.equals(mLastBackgroundedPackage)) {
            incrementAppLaunch = true;
        }
    } else if (event.mEventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
        if (event.mPackage != null) {
            mLastBackgroundedPackage = event.mPackage;
        }
    }

    for (IntervalStats stats : mCurrentStats) {
        switch (event.mEventType) {
            case UsageEvents.Event.CONFIGURATION_CHANGE: {
                 stats.updateConfigurationStats(newFullConfig, event.mTimeStamp);
                 } break;
            case UsageEvents.Event.CHOOSER_ACTION: {
                 stats.updateChooserCounts(event.mPackage, event.mContentType, event.mAction);
                 String[] annotations = event.mContentAnnotations;
                 if (annotations != null) {
                     for (String annotation : annotations) {
                         stats.updateChooserCounts(event.mPackage, annotation, event.mAction);
                     }
                 }
                 } break;
            case UsageEvents.Event.SCREEN_INTERACTIVE: {
                 stats.updateScreenInteractive(event.mTimeStamp);
                 } break;
            case UsageEvents.Event.SCREEN_NON_INTERACTIVE: {
                 stats.updateScreenNonInteractive(event.mTimeStamp);
                 } break;
            case UsageEvents.Event.KEYGUARD_SHOWN: {
                 stats.updateKeyguardShown(event.mTimeStamp);
                 } break;
            case UsageEvents.Event.KEYGUARD_HIDDEN: {
                 stats.updateKeyguardHidden(event.mTimeStamp);
                 } break;
            default: {
                 stats.update(event.mPackage, event.mTimeStamp, event.mEventType);
                 if (incrementAppLaunch) {
                     stats.incrementAppLaunchCount(event.mPackage);
                 }
                 } break;
        }
    }

    notifyStatsChanged();
}
```










