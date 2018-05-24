#设置默认launcher

##平台:android O  

###需求  
手机中有多个launcher,需要给每个用户设置一个默认的Launcher(每类用户是同一个),考虑到android的多用户问题,不单是第一次开机要设置,每一次创建新的用户(user,guest,owner)
都要去设置一个默认的,并且要保证每个用户修改为自己喜欢的Launcher后,重启机器不能去覆盖用户的设置.

##思路  
1.在手机的'/data/system/users/'目录下.有用户的列表,以及用户的文件夹,比如owner用户是0文件夹,在文件夹里有一个偏好设置保存的文件:package-restrictions.xml.  
2.作为owner用户,可以在第一次开机的时候对其进行设置,作为其他用户可以在创建的时候去设置;  

先把方案给出:
需要修改三个文件:
`frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java`
`frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`
`frameworks/base/packages/SystemUI/AndroidManifest.xml`
UserManagerService.java-->createUserInternalUnchecked
```
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        mPm.setDefaultLauncherAsUser(userId);//add
        return userInfo;
    }
```

PackageManagerService.java-->systemReady最后添加:  
`setDefaultLauncher();`

在PackageManagerService.java添加两个方法:  
```
    public void setDefaultLauncher(){
       if(isFirstBoot()) {
           setDefaultLauncherAsUser(0);//owner用户第一次开机进行设置
       }
    }

    public void setDefaultLauncherAsUser(int userId){//给普通user用户和guest用户使用,
        final PackageManager mPm = mContext.getPackageManager();

        Intent homeIntent=new Intent();

        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setAction(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_DEFAULT);
        String defaultLauncherPkg = "com.test.launcher";
        String defaultLauncherClass = "com.test.launcher.Launcher");

        ResolveInfo info = mPm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        ComponentName DefaultLauncher=new ComponentName(defaultLauncherPkg,defaultLauncherClass);

        ArrayList<ResolveInfo> homeActivities = new ArrayList<ResolveInfo>();
        ComponentName currentDefaultHome = mPm.getHomeActivities(homeActivities);
        ComponentName[]mHomeComponentSet = new ComponentName[homeActivities.size()];
        for (int i = 0; i < homeActivities.size(); i++) {
            final ResolveInfo candidate = homeActivities.get(i);
            final ActivityInfo activityInfo= candidate.activityInfo;
            ComponentName activityName = new ComponentName(activityInfo.packageName, activityInfo.name);
            mHomeComponentSet[i] = activityName;
        }

        IntentFilter mHomeFilter = new IntentFilter(Intent.ACTION_MAIN);
        mHomeFilter.addCategory(Intent.CATEGORY_HOME);
        mHomeFilter.addCategory(Intent.CATEGORY_DEFAULT);
        mPm.addPreferredActivityAsUser(mHomeFilter, IntentFilter.MATCH_CATEGORY_EMPTY,mHomeComponentSet, DefaultLauncher, userId);
    }
```
PackageManagerService.java-->findPreferredActivity  
```
                            // Okay we found a previously set preferred or last chosen app.
                            // If the result set is different from when this
                            // was created, we need to clear it and re-ask the
                            // user their preference, if we're looking for an "always" type entry.
                            if (always && !pa.mPref.sameSet(query)) {
                                if (!(intent.getAction() != null
                                        && intent.getAction().equals(intent.ACTION_MAIN)
                                        && intent.getCategories() != null
                                        && intent.getCategories().contains(intent.CATEGORY_HOME))) {//add 添加过滤条件
                                Slog.i(TAG, "Result set changed, dropping preferred activity for "
                                        + intent + " type " + resolvedType);
                                if (DEBUG_PREFERRED) {
                                    Slog.v(TAG, "Removing preferred activity since set changed "
                                            + pa.mPref.mComponent);
                                }
                                pir.removeFilter(pa);
                                // Re-add the filter as a "last chosen" entry (!always)
                                PreferredActivity lastChosen = new PreferredActivity(
                                        pa, pa.mPref.mMatch, null, pa.mPref.mComponent, false);
                                pir.addFilter(lastChosen);
                                changed = true;
                                return null;
                            }
                            }
```

在`frameworks/base/packages/SystemUI/AndroidManifest.xml`添加设置偏好的权限.  
`    <uses-permission android:name="android.permission.SET_PREFERRED_APPLICATIONS" />`

解决该问题遇到的坑以及参考的有价值的文章:  
###1号坑
网上有不少方案修改`packages/apps/Provision`,该方案是可行的,但是会带来问题  
1.会设置两次默认值,导致第一次开机解锁后会出现闪屏;  
2.开机时间加长.

###2号坑
直接配置一个默认的xml,让其加载到默认设置`package-restrictions.xml`中.
很可惜,该方案对其他默认APP有效,对Launcher无效.具体分析参考:  
[1] <http://www.dxjia.cn/2016/03/08/aosp-default-application-setting/>  "Android源码设置default application" 
[2] <https://stackoverflow.com/questions/34073290/set-default-application-on-aosp>  "为什么不能设置Launcher?"


###3号坑
如果不添加`SET_PREFERRED_APPLICATIONS`权限,在Launcher界面下拉菜单添加user或者guest导致system UI crash.

思考:
1.owner也是用户,可以将设置默认launcher的代码写在一个共同的节点代码.目前没有找到.
2.最好使用PackageManager.addPreferredActivityAsUser方法,PackageManager.replacePreferredActivityAsUser






