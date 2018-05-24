# PowerUsageSummary详解

平台: Android O

***

`packages/apps/Settings/src/com/android/settings/fuelgauge/PowerUsageSummary.java`

PowerUsageSummary.java是在fuelgauge下的入口,也是核心方法.  
功能: 完成在Settings模块的电池耗电情况的显示.   

本文的分析重点是界面上的显示的耗电数据是怎么显示出来的.  
```
    protected void refreshUi() {
        final Context context = getContext();
        if (context == null) {
            return;
        }

        cacheRemoveAllPrefs(mAppListGroup);
        mAppListGroup.setOrderingAsAdded(false);
        boolean addedSome = false;
        // 获取电源配置文件
        final PowerProfile powerProfile = mStatsHelper.getPowerProfile();
        // 获取电池的状态,是一个BatteryStats对象
        final BatteryStats stats = mStatsHelper.getStats();
        // 获取屏幕的单位耗电,具体意义参见屏幕耗电计算方法
        final double averagePower = powerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);

        final long elapsedRealtimeUs = SystemClock.elapsedRealtime() * 1000;
        Intent batteryBroadcast = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryInfo batteryInfo = BatteryInfo.getBatteryInfo(context, batteryBroadcast,
                mStatsHelper.getStats(), elapsedRealtimeUs, false);
        mBatteryHeaderPreferenceController.updateHeaderPreference(batteryInfo);

        final TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorControlNormal, value, true);
        final int colorControl = context.getColor(value.resourceId);
        // 消耗的总电量
        final int dischargeAmount = USE_FAKE_DATA ? 5000
                : stats != null ? stats.getDischargeAmount(mStatsType) : 0;
        // 计算最近一次充满电到现在的时间
        final long lastFullChargeTime = mBatteryUtils.calculateLastFullChargeTime(mStatsHelper,
                System.currentTimeMillis());
        // 设置Preference的小标题,有关屏幕的耗电量,单位:mAh
        updateScreenPreference();//1
        // 设置Preference的小标题,有关最近一次充满电到现在的时间
        updateLastFullChargePreference(lastFullChargeTime);//2

        final CharSequence timeSequence = Utils.formatElapsedTime(context, lastFullChargeTime,
                false);

        // 获取设置的显示是App耗电列表还是Device耗电列表的title,并设置
        // 分别为: Device usage since full charge / App usage since full charge
        final int resId = mShowAllApps ? R.string.power_usage_list_summary_device
                : R.string.power_usage_list_summary;
        mAppListGroup.setTitle(TextUtils.expandTemplate(getText(resId), timeSequence));

        // 当屏幕耗电单位耗电大于10毫安,或者使用伪造数据就进行统计计算(也就是刷新数据),没有看错,是伪造耗电数据.....不知道google是出于什么考虑
        if (averagePower >= MIN_AVERAGE_POWER_THRESHOLD_MILLI_AMP || USE_FAKE_DATA) {
            // 是获取一个BatterySipper类型的数组,getCoalescedUsageList的参数依据是不是用伪造的数据进行判断
            final List<BatterySipper> usageList = getCoalescedUsageList(
                    USE_FAKE_DATA ? getFakeStats() : mStatsHelper.getUsageList());//3
            // 依据mShowAllApps标示是否要显示所有的app,如果不是,则删除其中要隐藏的app
            double hiddenPowerMah = mShowAllApps ? 0 :
                    mBatteryUtils.removeHiddenBatterySippers(usageList);
            // 将usagelist进行排序,如果有隐藏的app,这里已经排除.
            // 这里排序的目的是,手机并不是显示所有的耗电项目,符合一定标准的才会显示
            // 这里的排序使用的是 Collections.sort ,排序结果为降序
            mBatteryUtils.sortUsageList(usageList);

            // 获取所有耗电项目的数目
            final int numSippers = usageList.size();
            for (int i = 0; i < numSippers; i++) {
                // 每个BatterySipper实例代表一个应用uid的消耗的电量信息
                final BatterySipper sipper = usageList.get(i);
                // 获取电池总电量,如果是伪造,则使用4000mAh的电池容量
                double totalPower = USE_FAKE_DATA ? 4000 : mStatsHelper.getTotalPower();
                // 计算百分比排出了隐藏的app消耗的电量
                // 计算百分比公式 (sipper.totalPowerMah / (totalPowerMah - hiddenPowerMah)) * dischargeAmount
                final double percentOfTotal = mBatteryUtils.calculateBatteryPercent(
                        sipper.totalPowerMah, totalPower, hiddenPowerMah, dischargeAmount);

                // 并不是计算出来的百分比都会使用,只是会显示满足条件的,下面的代码主要进行筛选
                // 不足 1% 的忽略,出现第一个小于 1% 的应该结束计算才对呀,后面的一定不满足条件了.可是google没有这么写,一直在这浪费资源
                if (((int) (percentOfTotal + .5)) < 1) {
                    continue;
                }
                if (sipper.drainType == BatterySipper.DrainType.OVERCOUNTED) {// 指统计多了的
                    // Don't show over-counted unless it is at least 2/3 the size of
                    // the largest real entry, and its percent of total is more significant
                    if (sipper.totalPowerMah < ((mStatsHelper.getMaxRealPower() * 2) / 3)) {
                        continue;
                    }
                    if (percentOfTotal < 10) {// 小于 5% 不统计
                        continue;
                    }
                    if ("user".equals(Build.TYPE)) {// user版本不统计
                        continue;
                    }
                }
                if (sipper.drainType == BatterySipper.DrainType.UNACCOUNTED) {// DrainType是UNACCOUNTED指的是未统计的耗电
                    // Don't show over-counted unless it is at least 1/2 the size of
                    // the largest real entry, and its percent of total is more significant
                    if (sipper.totalPowerMah < (mStatsHelper.getMaxRealPower() / 2)) {
                        continue;
                    }
                    if (percentOfTotal < 5) {// 小于 5% 不统计
                        continue;
                    }
                    if ("user".equals(Build.TYPE)) {// user版本不统计
                        continue;
                    }
                }

                // 到此,此次循环拿出的BatterySipper统计的百分比就是一个合格的,接下来就是进行封装和显示

                // UserHandle是设备上用户的表示
                final UserHandle userHandle = new UserHandle(UserHandle.getUserId(sipper.getUid()));
                // 将BatterySipper类型包裹城一个BatteryEntry类型,多了包名和icon信息
                final BatteryEntry entry = new BatteryEntry(getActivity(), mHandler, mUm, sipper);
                final Drawable badgedIcon = mUm.getBadgedIconForUser(entry.getIcon(),
                        userHandle);
                final CharSequence contentDescription = mUm.getBadgedLabelForUser(entry.getLabel(),
                        userHandle);

                // Preference 是进行显示相关
                // 此处的 key 可能是uid,DrainType,package name,-1
                final String key = extractKeyFromSipper(sipper);
                PowerGaugePreference pref = (PowerGaugePreference) getCachedPreference(key);
                if (pref == null) {
                    pref = new PowerGaugePreference(getPrefContext(), badgedIcon,
                            contentDescription, entry);
                    pref.setKey(key);
                }

                final double percentOfMax = (sipper.totalPowerMah * 100)
                        / mStatsHelper.getMaxPower();
                sipper.percent = percentOfTotal;
                pref.setTitle(entry.getLabel());
                pref.setOrder(i + 1);
                // 会调用notifyChanged()通知UI更新数据
                pref.setPercent(percentOfTotal);
                if (sipper.usageTimeMs == 0 && sipper.drainType == DrainType.APP) {
                    sipper.usageTimeMs = mBatteryUtils.getProcessTimeMs(
                            BatteryUtils.StatusType.FOREGROUND, sipper.uidObj, mStatsType);
                }
                // 设置的是preference,和setSummary功能一样,就是设置的小标题,大于 1min 才会被显示
                setUsageSummary(pref, sipper);
                if ((sipper.drainType != DrainType.APP
                        || sipper.uidObj.getUid() == Process.ROOT_UID)
                        && sipper.drainType != DrainType.USER) {
                    pref.setTint(colorControl);
                }
                addedSome = true;
                // 添加Preference
                mAppListGroup.addPreference(pref);
                // 使用伪造数据MAX_ITEMS_TO_LIST等于30,真实数据等于10
                if (mAppListGroup.getPreferenceCount() - getCachedCount()
                        > (MAX_ITEMS_TO_LIST + 1)) {// 如果是真实的数据,这里最多显示是12个
                    break;
                }
            }
        }
        if (!addedSome) {// 没有添加任何Preference,就要该代码块
            addNotAvailableMessage();
        }
        removeCachedPrefs(mAppListGroup);

        BatteryEntry.startRequestQueue();
    }
```
可以下面的log分析:  
```
                Log.d(TAG,"sipper.drainType = " + sipper.drainType);

                int uid = -2;
                if(sipper.uidObj != null){
                   uid = sipper.uidObj.getUid();
                }
                Log.d(TAG,"uid = " + uid );

                Log.d("qli3","numSippers = " + numSippers );
                if (sipper.mPackages != null) {
                    for(int j = 0; j < sipper.mPackages.length; j++){
                       Log.d(TAG,"sipper.mPackages = " + sipper.mPackages[j]);
                    }
                }
```






