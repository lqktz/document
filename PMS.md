#PackageManagerService 源码分析

平台:android O源码  
本文分析的问题:  
apk的安装有如下四种方式：  
1. apk随着PMS的启动而安装(本文)  
2. adb install安装  
3. ODM内置商店静默安装  
4. 拷贝apk到手机，界面安装  
这四种方式在代码里的实现其实就是如下两种：  
1. PMS调用scanDirLI扫描安装(本文分析的方式)  
2. 直接或间接调用installPackageAsUser安装  
***
在systemserver的分析过程中,在startBootstrapServices()中启动PMS是通过调用PackageManagerService.main()启动了PMS服务的,本文接着从这开始分析.  
`frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`
```
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
上面的main()方法比较简单,重点就是创建了PackageManagerService对象,并且把它加入到ServiceManager中,本文的重点就是分析PackageManagerService对象的创建.
在这其中会涉及到在同一个包下面的其他.java类,比较核心的有:Settings.java,  接着分析PackageManagerService.java的构造方法,该方法相当的长,分段进行分析  
```
    public PackageManagerService(Context context, Installer installer,
            boolean factoryTest, boolean onlyCore) {
        LockGuard.installLock(mPackages, LockGuard.INDEX_PACKAGES);
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "create package manager");
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());

        /// M: Add for Mtprof tool.
        mMTPROFDisable = false;
        addBootEvent("Android:PackageManagerService_Start");
        //mSdkVersion标示SDK版本,让apk知道自己运行在哪个版本
        if (mSdkVersion <= 0) {
            Slog.w(TAG, "**** ro.build.version.sdk not set!");
        }

        mContext = context;
        //标示了CTA表示在产品要在国内买,需要过CTA,类似CTS
        /// M: CTA requirement - permission control
        mPermissionReviewRequired = CtaUtils.isCtaSupported() ? true :
            context.getResources().getBoolean(R.bool.config_permissionReviewRequired);
        ///@}
        //是否运行在工厂模式下
        mFactoryTest = factoryTest;
        //判断是否只扫描系统目录,这个在后面多次用到
        mOnlyCore = onlyCore;
        //获取手机分辨率信息
        mMetrics = new DisplayMetrics();
        //mPackages是一个map集合存放packagesetting对象,是全局变量.PMS中的每一个应用程序的安装信息都是使用一个packagesetting对象进行描述
        //Settings类是用来管理应用程序的安装信息的
        mSettings = new Settings(mPackages);
```
由于这个Settings类比较重要,接下来分析Settings的构造方法,Settings的构造函数主要用于创建一些目录和文件，并配置相应的权限.
`frameworks/base/services/core/java/com/android/server/pm/Settings.java`
```
    Settings(Object lock) {
        //Environment.getDataDirectory()获取的是/data目录
        this(Environment.getDataDirectory(), lock);
    }

    Settings(File dataDir, Object lock) {
        mLock = lock;

        mRuntimePermissionsPersistence = new RuntimePermissionPersistence(mLock);
        //目录指向为/data/system
        mSystemDir = new File(dataDir, "system");
        //创建前面指向好的目录
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG
                |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                -1, -1);
        //packages.xml和packages-backup.xml为一组，用于描述系统所安装的Package信息，其中packages-backup.xml是packages.xml的备份
        //PKMS写把数据写到backup文件中，信息全部写成功后在改名为非backup文件，以防止在写文件的过程中出错，导致信息丢失
        mSettingsFilename = new File(mSystemDir, "packages.xml");
        mBackupSettingsFilename = new File(mSystemDir, "packages-backup.xml");
        //packages.list保存中所有安装apk的信息
        mPackageListFilename = new File(mSystemDir, "packages.list");
        FileUtils.setPermissions(mPackageListFilename, 0640, SYSTEM_UID, PACKAGE_INFO_GID);

        final File kernelDir = new File("/config/sdcardfs");
        mKernelMappingFilename = kernelDir.exists() ? kernelDir : null;
        //packages-stopped.xml用于描述系统中强行停止运行的package信息，backup也是备份文件
        // Deprecated: Needed for migration
        mStoppedPackagesFilename = new File(mSystemDir, "packages-stopped.xml");
        mBackupStoppedPackagesFilename = new File(mSystemDir, "packages-stopped-backup.xml");
    }
```
回到`frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`,继续分析PackageManagerService的构造方法:  
```
        //设置系统的shareuserid,这个是在manifest.xml中设置,拥有相同shareuserid的app将会共享权限.
        //shareuserid属性设置为"android.uid.system"那么说明是系统app
        mSettings.addSharedUserLPw("android.uid.system", Process.SYSTEM_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.phone", RADIO_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.log", LOG_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.nfc", NFC_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.bluetooth", BLUETOOTH_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.shell", SHELL_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);

```
用到了Settings类的方法:addSharedUserLPw,作用是添加共享用户:  
```
    //name和uid一一对应，例如："android.uid.system"：Process.SYSTEM_UID（1000）
    //                      "android.uid.phone" ：RADIO_UID（Process.PHONE_UID， 1001）
    // 在PMS中，每一个共享Linux用户都是使用一个SharedUserSetting对象来描述的。这些对象保存在mSharedUsers中
    SharedUserSetting addSharedUserLPw(String name, int uid, int pkgFlags, int pkgPrivateFlags) {
    //依据key(这里其实就是包名)值,从mSharedUsers对象中获取对应的SharedUserSetting对象.
    SharedUserSetting s = mSharedUsers.get(name);
        if (s != null) {//如果在mSharedUsers里面存在给的包名对应的SharedUserSetting对象
            if (s.userId == uid) {//并且userId=uid,说明PMS已经为该应用程序分配过了uid
                return s;
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate shared user, keeping first: " + name);
            return null;
        }
        // 如果mSharedUsers中不存在与该包名对应的SharedUserSetting对象，则为该应用程序分配一个参数uid所描述的Linux用户ID
        s = new SharedUserSetting(name, pkgFlags, pkgPrivateFlags);
        s.userId = uid;
        // 在系统中保存值为uid的Linux用户ID，成功返回true
        if (addUserIdLPw(uid, s, name)) {
            //将新new的SharedUserSetting对象添加到mSharedUsers中
            mSharedUsers.put(name, s);
            return s;
        }
        return null;
    }
```
在系统保存值为uid的Linux用户ID,使用到了addUserIdLPw方法:  
```
    private boolean addUserIdLPw(int uid, Object obj, Object name) {
        if (uid > Process.LAST_APPLICATION_UID) {//大于19999说明是一个非法的uid,超出了uid的上线
            return false;
        }

        if (uid >= Process.FIRST_APPLICATION_UID) {//大于等于10000普通apk的uid,保存在mUserIds中
            //计算数组长度
            int N = mUserIds.size();
            //计算索引值
            final int index = uid - Process.FIRST_APPLICATION_UID;
            while (index >= N) {//索引值大于数组长度,在N到index之间的位置都填上null
                mUserIds.add(null);
                N++;
            }
            // 如果数组的目标索引值位置有不为null的值，说明已经添加过
            if (mUserIds.get(index) != null) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Adding duplicate user id: " + uid
                        + " name=" + name);
                return false;
            }
            //没有添加过,则在索引位置填上obj
            mUserIds.set(index, obj);
        } else {//小于10000,系统apk使用的uid,保存在mOtherUserIds中
            if (mOtherUserIds.get(uid) != null) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Adding duplicate shared id: " + uid
                                + " name=" + name);
                return false;
            }
            mOtherUserIds.put(uid, obj);
        }
        return true;
    }
```
PMS在创建了Settings之后会,调用一系列的addSharedUserLPw方法,形成下图的结构:  
![1](https://raw.githubusercontent.com/lqktz/document/54f0dd65b3310f52a314d526879427d73bde28c1/res/PMS_addshareuserid.jpg)
如图所示，PKMS将根据参数构建出SharedUserSettings对象，可以通过两个维度来引用创建出的对象，即名称和uid。
在Settings中mSharedUsers是一个map对象，利用名称作为索引管理SharedUserSettings对象。
Settings中的mOtherUserIds和mUserIds，均是利用userId作为索引管理SharedUserSettings对象。不同的是mOtherUserIds是SparseArray，
以系统uid作为键值；mUserIds是ArrayList，普通APK的uid为ArrayList的下标。  
接着分析一下这个SharedUserSettings:  
```
    //重点看这一个方法
    void addPackage(PackageSetting packageSetting) {
        if (packages.add(packageSetting)) {//SharedUserSettings保存着一个packages集合用来存储packageSetting<-存储一个应用的安装信息
            setFlags(this.pkgFlags | packageSetting.pkgFlags);
            setPrivateFlags(this.pkgPrivateFlags | packageSetting.pkgPrivateFlags);
        }
    }
```
回到`frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`,继续分析PackageManagerService的构造方法:  
```
        // 应用安装器,构造函数传入的InstallerService，与底层Installd通信,installer由SystemServer构造
        mInstaller = installer;
        // 实例化优化器,PackageDexOptimizer用于辅助进行dex优化
        mPackageDexOptimizer = new PackageDexOptimizer(installer, mInstallLock, context,
                "*dexopt*");
        //在pm/dex/包下,用于记录dex文件使用情况的记录在/data/system/package-dex-usage.list中
        mDexManager = new DexManager(this, mPackageDexOptimizer, installer, mInstallLock);
        //定义一些回调函数
        mMoveCallbacks = new MoveCallbacks(FgThread.get().getLooper());
        //定义权限更改监听器
        mOnPermissionChangeListeners = new OnPermissionChangeListeners(
                FgThread.get().getLooper());
        //获取默认显示屏信息
        getDefaultDisplayMetrics(context, mMetrics);

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "get system config");
        //获得SystemConfig对象,通过该对象获取系统配置
        SystemConfig systemConfig = SystemConfig.getInstance();
```
由于SystemConfig是一个比较重要的类,下面的代码多次使用,进行详细分析.
`frameworks/base/core/java/com/android/server/SystemConfig.java`
```
    static SystemConfig sInstance;
    //SystemConfig是单例模式
    public static SystemConfig getInstance() {
        synchronized (SystemConfig.class) {
            if (sInstance == null) {
                sInstance = new SystemConfig();
            }
            return sInstance;
        }
    }

    SystemConfig() {//从不少目录读取权限,可能有些目录不存在,但是/system一定存在
        //Environment.getRootDirectory()获取到的是/system目录,从/system目录读取权限
        // Read configuration from system
        readPermissions(Environment.buildPath(
                Environment.getRootDirectory(), "etc", "sysconfig"), ALLOW_ALL);
        // Read configuration from the old permissions dir
        readPermissions(Environment.buildPath(
                Environment.getRootDirectory(), "etc", "permissions"), ALLOW_ALL);
        //从/vendor目录下读取权限
        // Allow Vendor to customize system configs around libs, features, permissions and apps
        int vendorPermissionFlag = ALLOW_LIBS | ALLOW_FEATURES | ALLOW_PERMISSIONS |
                ALLOW_APP_CONFIGS;
        readPermissions(Environment.buildPath(
                Environment.getVendorDirectory(), "etc", "sysconfig"), vendorPermissionFlag);
        readPermissions(Environment.buildPath(
                Environment.getVendorDirectory(), "etc", "permissions"), vendorPermissionFlag);
        //从/odm目录读取权限
        // Allow ODM to customize system configs around libs, features and apps
        int odmPermissionFlag = ALLOW_LIBS | ALLOW_FEATURES | ALLOW_APP_CONFIGS;
        readPermissions(Environment.buildPath(
                Environment.getOdmDirectory(), "etc", "sysconfig"), odmPermissionFlag);
        readPermissions(Environment.buildPath(
                Environment.getOdmDirectory(), "etc", "permissions"), odmPermissionFlag);
        //从/oem目录读取权限
        // Only allow OEM to customize features
        readPermissions(Environment.buildPath(
                Environment.getOemDirectory(), "etc", "sysconfig"), ALLOW_FEATURES);
        readPermissions(Environment.buildPath(
                Environment.getOemDirectory(), "etc", "permissions"), ALLOW_FEATURES);
    }

    //接着看readPermissions方法的实现
    void readPermissions(File libraryDir, int permissionFlag) {
        //检测是否存在,是否可读
        // Read permissions from given directory.
        if (!libraryDir.exists() || !libraryDir.isDirectory()) {
            if (permissionFlag == ALLOW_ALL) {
                Slog.w(TAG, "No directory " + libraryDir + ", skipping");
            }
            return;
        }
        if (!libraryDir.canRead()) {
            Slog.w(TAG, "Directory " + libraryDir + " cannot be read");
            return;
        }
        // 遍历目标文件夹下所有的.xml文件
        // Iterate over the files in the directory and scan .xml files
        File platformFile = null;
        for (File f : libraryDir.listFiles()) {
            // 最后解析platform.xml文件
            // We'll read platform.xml last
            if (f.getPath().endsWith("etc/permissions/platform.xml")) {
                platformFile = f;
                continue;
            }

            if (!f.getPath().endsWith(".xml")) {
                Slog.i(TAG, "Non-xml file " + f + " in " + libraryDir + " directory, ignoring");
                continue;
            }
            if (!f.canRead()) {
                Slog.w(TAG, "Permissions library file " + f + " cannot be read");
                continue;
            }
            //将可读取的xml进行读取
            readPermissionsFromXml(f, permissionFlag);//下面详解readPermissionsFromXml方法
        }

        // 最后解析platform.xml文件，该文件的优先级最高
        // Read platform permissions last so it will take precedence
        if (platformFile != null) {
            readPermissionsFromXml(platformFile, permissionFlag);
        }
    }
```
readPermissions就是从指定目录下，读取xml中的配置的权限信息.将xml文件转换成对应的数据结构.eg:/system/etc/permissions/platform.xml.
看一下platform.xml里面的主要标签：  

* permission和group字段用于建立Linux层GID和Android层permission字段之间的映射关系；  
* assign-permission用于向指定的uid赋予相应的权限；  
* library字段用于可链接的指定系统库;  
* allow-in-power-save-except-idle用于指定进程在省电模式下(非Idle)仍可上网;  
* backup-transport-whitelisted-service用于指定服务具有传输备份数据的权利;  

上面这些标签是用readPermissionsFromXml方法解析的.在readPermissions方法中两次调用到了readPermissionsFromXml,该方法是SystemConfig的核心方法.源码:  
```
    private void readPermissionsFromXml(File permFile, int permissionFlag) {
        FileReader permReader = null;
        try {
            //利用file构造fileReader
            permReader = new FileReader(permFile);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Couldn't find or open permissions file " + permFile);
            return;
        }
        //读取系统属性"ro.config.low_ram"，如果该属性为true，不会加载指定notLowRam的feature属性
        final boolean lowRam = ActivityManager.isLowRamDeviceStatic();

        try {
            //创建xml解析器
            XmlPullParser parser = Xml.newPullParser();
            //给解析器输入FileReader读取的内容
            parser.setInput(permReader);

            //寻找解析的起点
            int type;
            while ((type=parser.next()) != parser.START_TAG
                       && type != parser.END_DOCUMENT) {
                ;
            }

            if (type != parser.START_TAG) {
                throw new XmlPullParserException("No start tag found");
            }

            if (!parser.getName().equals("permissions") && !parser.getName().equals("config")) {
                throw new XmlPullParserException("Unexpected start tag in " + permFile
                        + ": found " + parser.getName() + ", expected 'permissions' or 'config'");
            }

            //根据SystemConfig构造方法中设置的flag，决定当前目录下，从xml文件中解析内容的范围
            boolean allowAll = permissionFlag == ALLOW_ALL;
            boolean allowLibs = (permissionFlag & ALLOW_LIBS) != 0;
            boolean allowFeatures = (permissionFlag & ALLOW_FEATURES) != 0;
            boolean allowPermissions = (permissionFlag & ALLOW_PERMISSIONS) != 0;
            boolean allowAppConfigs = (permissionFlag & ALLOW_APP_CONFIGS) != 0;
            boolean allowPrivappPermissions = (permissionFlag & ALLOW_PRIVAPP_PERMISSIONS) != 0;
            while (true) {
                XmlUtils.nextElement(parser);
                //解析完成退出
                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                String name = parser.getName();
                //解析group标签
                if ("group".equals(name) && allowAll) {
                    //以字符串的形式获取属性值,如果获取的属性值是一个int,那么就要进行转换
                    String gidStr = parser.getAttributeValue(null, "gid");
                    if (gidStr != null) {
                        //将Gid字符串转化成整形，保存到mGlobalGids中
                        int gid = android.os.Process.getGidForName(gidStr);
                        mGlobalGids = appendInt(mGlobalGids, gid);
                    } else {
                        Slog.w(TAG, "<group> without gid in " + permFile + " at "
                                + parser.getPositionDescription());
                    }

                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else if ("permission".equals(name) && allowPermissions) {//解析permission标签
                    String perm = parser.getAttributeValue(null, "name");
                    if (perm == null) {
                        Slog.w(TAG, "<permission> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    perm = perm.intern();
                    //调用readPermission解析permission标签,注意不是readPermissions方法!!!
                    readPermission(parser, perm);

                } else if ("assign-permission".equals(name) && allowPermissions) {//解析assign-permission标签,记录uid分配的权限,最终记录到mSystemPermissions
                    //获取权限名称
                    String perm = parser.getAttributeValue(null, "name");
                    if (perm == null) {
                        Slog.w(TAG, "<assign-permission> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    //获取该权限的uid的值
                    String uidStr = parser.getAttributeValue(null, "uid");
                    if (uidStr == null) {
                        Slog.w(TAG, "<assign-permission> without uid in " + permFile + " at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    //将uid从string转换成int
                    int uid = Process.getUidForName(uidStr);
                    if (uid < 0) {
                        Slog.w(TAG, "<assign-permission> with unknown uid \""
                                + uidStr + "  in " + permFile + " at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    //使用了字符串池,intern()是String类的方法
                    perm = perm.intern();
                    //从系统获取该uid已经拥有的权限
                    ArraySet<String> perms = mSystemPermissions.get(uid);
                    //如果该uid之前没有添加过权限,创建一个,并将添加到mSystemPermissions中
                    if (perms == null) {
                        perms = new ArraySet<String>();
                        mSystemPermissions.put(uid, perms);//保存着以uid为key的权限映射表,是一个稀疏数组
                    }
                    //将uid新增的权限，加入到它的ArraySet
                    perms.add(perm);
                    XmlUtils.skipCurrentTag(parser);

                } else if ("library".equals(name) && allowLibs) {//解析library标签,最终记录到mSharedLibraries
                    String lname = parser.getAttributeValue(null, "name");
                    String lfile = parser.getAttributeValue(null, "file");
                    if (lname == null) {
                        Slog.w(TAG, "<library> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else if (lfile == null) {
                        Slog.w(TAG, "<library> without file in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        //Log.i(TAG, "Got library " + lname + " in " + lfile);
                        //将解析好的library标签的内容添加到mSharedLibraries,是一个map集合
                        mSharedLibraries.put(lname, lfile);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("feature".equals(name) && allowFeatures) {//解析feature标签
                    String fname = parser.getAttributeValue(null, "name");
                    int fversion = XmlUtils.readIntAttribute(parser, "version", 0);
                    boolean allowed;
                    if (!lowRam) {//如果是低内存就不解析
                        allowed = true;
                    } else {
                        String notLowRam = parser.getAttributeValue(null, "notLowRam");
                        allowed = !"true".equals(notLowRam);
                    }
                    if (fname == null) {
                        Slog.w(TAG, "<feature> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else if (allowed) {
                    //将feature构造成featureInfo，加入到mAvailableFeatures对象中,该方法在SystemConfig中
                        addFeature(fname, fversion);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("unavailable-feature".equals(name) && allowFeatures) {//mUnavailableFeatures保存不支持的feature
                    String fname = parser.getAttributeValue(null, "name");
                    if (fname == null) {
                        Slog.w(TAG, "<unavailable-feature> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        mUnavailableFeatures.add(fname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("allow-in-power-save-except-idle".equals(name) && allowAll) {//保存省电模式下,可以使用网络的应用
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<allow-in-power-save-except-idle> without package in "
                                + permFile + " at " + parser.getPositionDescription());
                    } else {
                        //保存在mAllowInPowerSaveExceptIdle中
                        mAllowInPowerSaveExceptIdle.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("allow-in-power-save".equals(name) && allowAll) {
                //mAllowInPowerSave与mAllowInPowerSaveExceptIdle类似，权限更高
                //这与Android M新特性Doze and App Standby模式有关
                //DeviceIdleController用于判断设备是否进入Idle状态，进入Idle状态时，mAllowInPowerSaveExceptIdle中的应用要被禁掉
                //但mAllowInPowerSave中的应用仍可运行
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<allow-in-power-save> without package in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        mAllowInPowerSave.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("allow-in-data-usage-save".equals(name) && allowAll) {
                   //mAllowInDataUsageSave保存此标签对应的packageName
                   //貌似android 7新增了一个节省数据流量的能力，有此标签的应用在节省数据流量时，仍可访问网络
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<allow-in-data-usage-save> without package in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mAllowInDataUsageSave.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("allow-unthrottled-location".equals(name) && allowAll) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<allow-unthrottled-location> without package in "
                            + permFile + " at " + parser.getPositionDescription());
                    } else {
                        mAllowUnthrottledLocation.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("allow-implicit-broadcast".equals(name) && allowAll) {
                    String action = parser.getAttributeValue(null, "action");
                    if (action == null) {
                        Slog.w(TAG, "<allow-implicit-broadcast> without action in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mAllowImplicitBroadcasts.add(action);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("app-link".equals(name) && allowAppConfigs) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<app-link> without package in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        mLinkedApps.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("system-user-whitelisted-app".equals(name) && allowAppConfigs) {
                //mSystemUserWhitelistedApps保存此标签对应的packageName
                //指定以system user权限运行的app
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<system-user-whitelisted-app> without package in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mSystemUserWhitelistedApps.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("system-user-blacklisted-app".equals(name) && allowAppConfigs) {
                //mSystemUserBlacklistedApp保存此标签对应的packageName
                //指定在system user权限下，不应该运行的app
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<system-user-blacklisted-app without package in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mSystemUserBlacklistedApps.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("default-enabled-vr-app".equals(name) && allowAppConfigs) {
                //mDefaultVrComponents保存此标签对应的packageName
                //指定默认运行在VR模式下的components
                    String pkgname = parser.getAttributeValue(null, "package");
                    String clsname = parser.getAttributeValue(null, "class");
                    if (pkgname == null) {
                        Slog.w(TAG, "<default-enabled-vr-app without package in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else if (clsname == null) {
                        Slog.w(TAG, "<default-enabled-vr-app without class in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mDefaultVrComponents.add(new ComponentName(pkgname, clsname));
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("backup-transport-whitelisted-service".equals(name) && allowFeatures) {
                //mBackupTransportWhitelist保存此标签对应的packageName
                //保存能够传输备份数据的服务
                    String serviceName = parser.getAttributeValue(null, "service");
                    if (serviceName == null) {
                        Slog.w(TAG, "<backup-transport-whitelisted-service> without service in "
                                + permFile + " at " + parser.getPositionDescription());
                    } else {
                        ComponentName cn = ComponentName.unflattenFromString(serviceName);
                        if (cn == null) {
                            Slog.w(TAG,
                                    "<backup-transport-whitelisted-service> with invalid service name "
                                    + serviceName + " in "+ permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mBackupTransportWhitelist.add(cn);
                        }
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("disabled-until-used-preinstalled-carrier-associated-app".equals(name)
                        && allowAppConfigs) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    String carrierPkgname = parser.getAttributeValue(null, "carrierAppPackage");
                    if (pkgname == null || carrierPkgname == null) {
                        Slog.w(TAG, "<disabled-until-used-preinstalled-carrier-associated-app"
                                + " without package or carrierAppPackage in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        List<String> associatedPkgs =
                                mDisabledUntilUsedPreinstalledCarrierAssociatedApps.get(
                                        carrierPkgname);
                        if (associatedPkgs == null) {
                            associatedPkgs = new ArrayList<>();
                            mDisabledUntilUsedPreinstalledCarrierAssociatedApps.put(
                                    carrierPkgname, associatedPkgs);
                        }
                        associatedPkgs.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("privapp-permissions".equals(name) && allowPrivappPermissions) {
                    readPrivAppPermissions(parser);
                } else {
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Got exception parsing permissions.", e);
        } catch (IOException e) {
            Slog.w(TAG, "Got exception parsing permissions.", e);
        } finally {
            IoUtils.closeQuietly(permReader);
        }
        //对相关的feature加密
        // Some devices can be field-converted to FBE, so offer to splice in
        // those features if not already defined by the static config
        if (StorageManager.isFileEncryptedNativeOnly()) {
            addFeature(PackageManager.FEATURE_FILE_BASED_ENCRYPTION, 0);
            addFeature(PackageManager.FEATURE_SECURELY_REMOVES_USERS, 0);
        }
        //移除mUnavailableFeatures中记录的不支持的feature
        for (String featureName : mUnavailableFeatures) {
            removeFeature(featureName);
        }
    }
```
回到`frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`,继续分析PackageManagerService的构造方法:  
```
        mGlobalGids = systemConfig.getGlobalGids();
        mSystemPermissions = systemConfig.getSystemPermissions();
        mAvailableFeatures = systemConfig.getAvailableFeatures();
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
```
分别调用SystemConfig类的getGlobalGids(),getSystemPermissions(),getAvailableFeatures()方法:  
```
    public int[] getGlobalGids() {
        return mGlobalGids;
    }

    public SparseArray<ArraySet<String>> getSystemPermissions() {
        return mSystemPermissions;
    }

    public ArrayMap<String, FeatureInfo> getAvailableFeatures() {
        return mAvailableFeatures;
    }
```
可以看出这三个方法只是将readPermissionsFromXml方法中解析xml文件得到的数据结构获取出来.PMS调用SystemConfig的作用就是解析xml.
回到`frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`,继续分析PackageManagerService的构造方法:  
```
            //mHandlerThread将负责Apk的安装和卸载
            mHandlerThread = new ServiceThread(TAG,
                    Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
            mHandlerThread.start();
            //PackageHandler、ProcessLoggingHandler共用ServiceThread
            // 以mHandlerThread线程的looper创建的Handler实例，该Handler运行在mHandlerThread线程
            // 该消息循环用于处理apk的安装请求
            mHandler = new PackageHandler(mHandlerThread.getLooper());
            mProcessLoggingHandler = new ProcessLoggingHandler();
            //Watchdog监控ServiceThread是否长时间阻塞
            Watchdog.getInstance().addThread(mHandler, WATCHDOG_TIMEOUT);

            mDefaultPermissionPolicy = new DefaultPermissionGrantPolicy(this);
            mInstantAppRegistry = new InstantAppRegistry(this);
            //在/data目录下创建一系列的目录
            File dataDir = Environment.getDataDirectory();//获取的是/data目录
            mAppInstallDir = new File(dataDir, "app");//data/app保存用户自己的app
            mAppLib32InstallDir = new File(dataDir, "app-lib");
            mAsecInternalPath = new File(dataDir, "app-asec").getPath();
            mDrmAppPrivateInstallDir = new File(dataDir, "app-private");// /data/app-parivate目录，保存的是受DRM保护的私有app
            //实例化多用户管理服务,用于管理多用户
            sUserManager = new UserManagerService(context, this,
                    new UserDataPreparer(mInstaller, mInstallLock, mContext, mOnlyCore), mPackages);
            //取出SystemConfig中的mPermissions
            //向包管理器中传播权限配置。
            // Propagate permission configuration in to package manager.
            ArrayMap<String, SystemConfig.PermissionEntry> permConfig
                    = systemConfig.getPermissions();

            //从SystemConfig中的mPermissions获取信息,存储到mSettings.mPermissions中
            for (int i=0; i<permConfig.size(); i++) {
                SystemConfig.PermissionEntry perm = permConfig.valueAt(i);
                // 根据权限名获取基本权限信息
                BasePermission bp = mSettings.mPermissions.get(perm.name);
                if (bp == null) {
                    bp = new BasePermission(perm.name, "android", BasePermission.TYPE_BUILTIN);
                    mSettings.mPermissions.put(perm.name, bp);
                }
                if (perm.gids != null) {
                    bp.setGids(perm.gids, perm.perUser);
                }
            }

            //取出systemConfig对象中的链接库信息，保存到PKMS中
            ArrayMap<String, String> libConfig = systemConfig.getSharedLibraries();
            final int builtInLibCount = libConfig.size();
            for (int i = 0; i < builtInLibCount; i++) {
                String name = libConfig.keyAt(i);
                String path = libConfig.valueAt(i);
                addSharedLibraryLPw(path, null, name, SharedLibraryInfo.VERSION_UNDEFINED,
                        SharedLibraryInfo.TYPE_BUILTIN, PLATFORM_PACKAGE_NAME, 0);
            }

            // 解析SELinux的策略文件
            mFoundPolicyFile = SELinuxMMAC.readInstallPolicy();

            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "read user settings");
            //mFirstBoot用于判断机器是否时第一次开机,这里的readLPw恢复上一次的应用程序安装信息，扫描package.xml文件,如果没有该文件判断为第一次开机
            mFirstBoot = !mSettings.readLPw(sUserManager.getUsers(false));
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
```
Android系统每次启动时，都会重新安装一遍系统中的应用程序，但是有些应用程序信息每次安装都是需要保持一致的，
如应用程序的Linux用户ID等。否则应用程序每次在系统重启后表现可能不一致。因此PMS每次在安装完成应用程序之后，
都需要将它们的信息保存下来，以便下次安装时可以恢复回来。恢复上一次的应用程序安装信息是通过Settings类的readLPw方法实现的。
重点分析Settings类的方法readLPw:  
进入`frameworks/base/services/core/java/com/android/server/pm/Settings.java`  
```
    boolean readLPw(@NonNull List<UserInfo> users) {
        FileInputStream str = null;
        // 先检查/data/system/packages-backup.xml文件是否存在，
        // 如果存在就将它的内容作为上一次的应用程序安装信息
        if (mBackupSettingsFilename.exists()) {
            try {
                str = new FileInputStream(mBackupSettingsFilename);
                mReadMessages.append("Reading from backup settings file\n");
                PackageManagerService.reportSettingsProblem(Log.INFO,
                        "Need to read from backup settings file");
                if (mSettingsFilename.exists()) {
                    // If both the backup and settings file exist, we
                    // ignore the settings since it might have been
                    // corrupted.
                    Slog.w(PackageManagerService.TAG, "Cleaning up settings file "
                            + mSettingsFilename);
                    mSettingsFilename.delete();
                }
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        mPendingPackages.clear();
        mPastSignatures.clear();
        mKeySetRefs.clear();
        mInstallerPackages.clear();

        try {
            if (str == null) {
                if (!mSettingsFilename.exists()) {
                    mReadMessages.append("No settings file found\n");
                    PackageManagerService.reportSettingsProblem(Log.INFO,
                            "No settings file; creating initial state");
                    // It's enough to just touch version details to create them
                    // with default values
                    // 如果原文件不存在，根据默认值创建版本信息。
                    findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL).forceCurrent();
                    findOrCreateVersion(StorageManager.UUID_PRIMARY_PHYSICAL).forceCurrent();
                    return false;
                }
                str = new FileInputStream(mSettingsFilename);
            }
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(str, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                mReadMessages.append("No start tag found in settings file\n");
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "No start tag found in package manager settings");
                Slog.wtf(PackageManagerService.TAG,
                        "No start tag found in package manager settings");

                /// M: Create version info when packages.xml is corrupt
                findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL);
                findOrCreateVersion(StorageManager.UUID_PRIMARY_PHYSICAL);

                return false;
            }

            int outerDepth = parser.getDepth();
            //开始解析
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("package")) {
                // 解析标签为package的元素，一个应用一个package标签.
                // 获取上一次安装这个应用程序时所分配给它的Linux用户ID
                    readPackageLPw(parser);//1
                } else if (tagName.equals("permissions")) {
                // 解析系统定义了哪些权限，由哪个包定义
                    readPermissionsLPw(mPermissions, parser);
                } else if (tagName.equals("permission-trees")) {
                    readPermissionsLPw(mPermissionTrees, parser);
                } else if (tagName.equals("shared-user")) {
                // shared-user标签是以sharedUserId的名字为name属性，然后为它分配一个userId赋值给userId属性。
                // 其他应用用到该sharedUserId的，userId都是shared-user标签中的userId属性值
                // 解析上一次应用程序安装信息中的共享Linux用户信息
                // 就是前面在Settings类中addSharedUserLPw方法写信息,最终保存在mSharedUsers中
                    readSharedUserLPw(parser);//解析上一次应用程序安装信息中的共享linux用户信息
                } else if (tagName.equals("preferred-packages")) {

                ......

            str.close();

        } catch (XmlPullParserException e) {
        ......
```
分析readPackageLPw方法:  
```
    private void readPackageLPw(XmlPullParser parser) throws XmlPullParserException, IOException {
        //定义变量
        ......

        try {
            //获取应用程序包名,Linux用户Id,sharedUserId等信息,将前面定义的变量初始化
            name = parser.getAttributeValue(null, ATTR_NAME);
            realName = parser.getAttributeValue(null, "realName");
            idStr = parser.getAttributeValue(null, "userId");
            uidError = parser.getAttributeValue(null, "uidError");
            sharedIdStr = parser.getAttributeValue(null, "sharedUserId");
            codePathStr = parser.getAttributeValue(null, "codePath");
            resourcePathStr = parser.getAttributeValue(null, "resourcePath");
            //初始化变量,并且做相应的合法性判断,eg:==NULL?
            .....
           } else if (userId > 0) {
              //packageSetting用于保存一个应用程序的的安装信息,使用addPackageLPw将以上app的安装信息封装成packageSetting对象保存在mPackages中
                packageSetting = addPackageLPw(name.intern(), realName, new File(codePathStr),//addPackageLPw源码分析
                        new File(resourcePathStr), legacyNativeLibraryPathStr, primaryCpuAbiString,
                        secondaryCpuAbiString, cpuAbiOverrideString, userId, versionCode, pkgFlags,
                        pkgPrivateFlags, flagsEx, parentPackageName, null /*childPackageNames*/,
                        null /*usesStaticLibraries*/, null /*usesStaticLibraryVersions*/);
                //对返回的packageSetting对象进行合法性判断
                if (PackageManagerService.DEBUG_SETTINGS)
                    Log.i(PackageManagerService.TAG, "Reading package " + name + ": userId="
                            + userId + " pkg=" + packageSetting);
                if (packageSetting == null) {
                    PackageManagerService.reportSettingsProblem(Log.ERROR, "Failure adding uid "
                            + userId + " while parsing settings at "
                            + parser.getPositionDescription());
                } else {
                    packageSetting.setTimeStamp(timeStamp);
                    packageSetting.firstInstallTime = firstInstallTime;
                    packageSetting.lastUpdateTime = lastUpdateTime;
                }
```
进入addPackageLPw方法:  
```
    // 在系统中保存值为userId的Linux用户ID
    // 在PMS中，每一个应用程序的安装信息都是使用一个PackageSetting对象来描述的。这些对象保存在mPackages中。
    PackageSetting addPackageLPw(String name, String realName, File codePath, File resourcePath,
            String legacyNativeLibraryPathString, String primaryCpuAbiString,
            String secondaryCpuAbiString, String cpuAbiOverrideString, int uid, int vc, int
            pkgFlags, int pkgPrivateFlags, int flagsEx, String parentPackageName,
            List<String> childPackageNames, String[] usesStaticLibraries,
            int[] usesStaticLibraryNames) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {//判断一下mPackages是否已经有了
            if (p.appId == uid) {
                return p;//在mPackages应经存在,直接将mPackages里的PackageSetting对象直接返回
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate package, keeping first: " + name);
            return null;
        }
        //mPackages里面没有,依据传入的信息,new一个PackageSetting对象,并且添加到mPackages中,并将该对象返回
        /// M: [FlagExt] Add flagsEx
        p = new PackageSetting(name, realName, codePath, resourcePath,
                legacyNativeLibraryPathString, primaryCpuAbiString, secondaryCpuAbiString,
                cpuAbiOverrideString, vc, pkgFlags, pkgPrivateFlags, flagsEx, parentPackageName,
                childPackageNames, 0 /*userId*/, usesStaticLibraries, usesStaticLibraryNames);
        p.appId = uid;
        if (addUserIdLPw(uid, p, name)) {//addUserIdLPw是在系统中保存值为uid的Linux用户Id,该方法在前面分析过
            mPackages.put(name, p);
            return p;
        }
        return null;
    }
```
回到readPackageLPw方法,继续分析:  
```
            } else if (sharedIdStr != null) {
            // 如果sharedIdStr不为null，说明安装该应用时PMS给它分配了一个共享的uid。此时不能马上保存该uid，
            // 因为这个uid不属于它自己所有，而是所有shareuserId了该uid的app共享.所以等解析完shared-user节点之后，再为它保存上一次所使用的Linux用户ID
                if (sharedUserId > 0) {
                    /// M: [FlagExt] Add flagsEx
                    packageSetting = new PackageSetting(name.intern(), realName, new File(
                            codePathStr), new File(resourcePathStr), legacyNativeLibraryPathStr,
                            primaryCpuAbiString, secondaryCpuAbiString, cpuAbiOverrideString,
                            versionCode, pkgFlags, pkgPrivateFlags, flagsEx, parentPackageName,
                            null /*childPackageNames*/, sharedUserId,
                            null /*usesStaticLibraries*/, null /*usesStaticLibraryVersions*/);
                    packageSetting.setTimeStamp(timeStamp);
                    packageSetting.firstInstallTime = firstInstallTime;
                    packageSetting.lastUpdateTime = lastUpdateTime;
                    //将是shareuserId的应用信息,先暂时保存在mPendingPackages中
                    mPendingPackages.add(packageSetting);
                    if (PackageManagerService.DEBUG_SETTINGS)
                        Log.i(PackageManagerService.TAG, "Reading package " + name
                                + ": sharedUserId=" + sharedUserId + " pkg=" + packageSetting);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: package " + name
                                    + " has bad sharedId " + sharedIdStr + " at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: package " + name + " has bad userId "
                                + idStr + " at " + parser.getPositionDescription());
            }
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: package " + name + " has bad userId "
                            + idStr + " at " + parser.getPositionDescription());
        }
        if (packageSetting != null) {
            packageSetting.uidError = "true".equals(uidError);
            packageSetting.installerPackageName = installerPackageName;
            packageSetting.isOrphaned = "true".equals(isOrphaned);
            packageSetting.volumeUuid = volumeUuid;
            packageSetting.categoryHint = categoryHint;
            packageSetting.legacyNativeLibraryPathString = legacyNativeLibraryPathStr;
            packageSetting.primaryCpuAbiString = primaryCpuAbiString;
            packageSetting.secondaryCpuAbiString = secondaryCpuAbiString;
            packageSetting.updateAvailable = "true".equals(updateAvailable);
            // Handle legacy string here for single-user mode
            final String enabledStr = parser.getAttributeValue(null, ATTR_ENABLED);
            if (enabledStr != null) {
                try {
                    packageSetting.setEnabled(Integer.parseInt(enabledStr), 0 /* userId */, null);
                } catch (NumberFormatException e) {
                    if (enabledStr.equalsIgnoreCase("true")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 0, null);
                    } else if (enabledStr.equalsIgnoreCase("false")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0, null);
                    } else if (enabledStr.equalsIgnoreCase("default")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, 0, null);
                    } else {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: package " + name
                                        + " has bad enabled value: " + idStr + " at "
                                        + parser.getPositionDescription());
                    }
                }
            } else {
                packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, 0, null);
            }

            if (installerPackageName != null) {
                mInstallerPackages.add(installerPackageName);
            }

            final String installStatusStr = parser.getAttributeValue(null, "installStatus");
            if (installStatusStr != null) {
                if (installStatusStr.equalsIgnoreCase("false")) {
                    packageSetting.installStatus = PackageSettingBase.PKG_INSTALL_INCOMPLETE;
                } else {
                    packageSetting.installStatus = PackageSettingBase.PKG_INSTALL_COMPLETE;
                }
            }

            int outerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                // Legacy
                if (tagName.equals(TAG_DISABLED_COMPONENTS)) {
                    readDisabledComponentsLPw(packageSetting, parser, 0);
                } else if (tagName.equals(TAG_ENABLED_COMPONENTS)) {
                    readEnabledComponentsLPw(packageSetting, parser, 0);
                } else if (tagName.equals("sigs")) {
                    packageSetting.signatures.readXml(parser, mPastSignatures);
                } else if (tagName.equals(TAG_PERMISSIONS)) {
                    readInstallPermissionsLPr(parser,
                            packageSetting.getPermissionsState());
                    packageSetting.installPermissionsFixed = true;
                } else if (tagName.equals("proper-signing-keyset")) {
                    long id = Long.parseLong(parser.getAttributeValue(null, "identifier"));
                    Integer refCt = mKeySetRefs.get(id);
                    if (refCt != null) {
                        mKeySetRefs.put(id, refCt + 1);
                    } else {
                        mKeySetRefs.put(id, 1);
                    }
                    packageSetting.keySetData.setProperSigningKeySet(id);
                } else if (tagName.equals("signing-keyset")) {
                    // from v1 of keysetmanagerservice - no longer used
                } else if (tagName.equals("upgrade-keyset")) {
                    long id = Long.parseLong(parser.getAttributeValue(null, "identifier"));
                    packageSetting.keySetData.addUpgradeKeySetById(id);
                } else if (tagName.equals("defined-keyset")) {
                    long id = Long.parseLong(parser.getAttributeValue(null, "identifier"));
                    String alias = parser.getAttributeValue(null, "alias");
                    Integer refCt = mKeySetRefs.get(id);
                    if (refCt != null) {
                        mKeySetRefs.put(id, refCt + 1);
                    } else {
                        mKeySetRefs.put(id, 1);
                    }
                    packageSetting.keySetData.addDefinedKeySet(id, alias);
                } else if (tagName.equals(TAG_DOMAIN_VERIFICATION)) {
                    readDomainVerificationLPw(parser, packageSetting);
                } else if (tagName.equals(TAG_CHILD_PACKAGE)) {
                    String childPackageName = parser.getAttributeValue(null, ATTR_NAME);
                    if (packageSetting.childPackageNames == null) {
                        packageSetting.childPackageNames = new ArrayList<>();
                    }
                    packageSetting.childPackageNames.add(childPackageName);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unknown element under <package>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } else {
            XmlUtils.skipCurrentTag(parser);
        }
    }
```
回到readLPw继续分析:  
```
        // If the build is setup to drop runtime permissions
        // on update drop the files before loading them.
        if (PackageManagerService.CLEAR_RUNTIME_PERMISSIONS_ON_UPGRADE) {
            final VersionInfo internal = getInternalVersion();
            if (!Build.FINGERPRINT.equals(internal.fingerprint)) {
                for (UserInfo user : users) {
                    mRuntimePermissionsPersistence.deleteUserRuntimePermissionsFile(user.id);
                }
            }
        }
        //在readPackageLPw方法中将shareuserId的app安装信息都暂时的保存在mPendingPackages,现在进行处理
        final int N = mPendingPackages.size();

        for (int i = 0; i < N; i++) {
            final PackageSetting p = mPendingPackages.get(i);
            final int sharedUserId = p.getSharedUserId();
            // 根据uid获取对应的对象，如果在mUserIds或mOtherUserIds中存在一个与userId对应的Object对象，
            // 且该对象是SharedUserSetting的类型，则说明pp所描述的应用程序上一次所使用的Linux用户ID是有效的
            final Object idObj = getUserIdLPr(sharedUserId);
            //每一个共享Linux用户Id都是使用SharedUserSetting对象来描述,并且保存在mSharedUsers中
            if (idObj instanceof SharedUserSetting) {
                final SharedUserSetting sharedUser = (SharedUserSetting) idObj;
                p.sharedUser = sharedUser;
                p.appId = sharedUser.userId;
                //addPackageSettingLPw将PackageSetting对象p添加到mPackages,把sharedUser赋给p.sharedUser保存
                addPackageSettingLPw(p, sharedUser);
            } else if (idObj != null) {
                String msg = "Bad package setting: package " + p.name + " has shared uid "
                        + sharedUserId + " that is not a shared uid\n";
                mReadMessages.append(msg);
                PackageManagerService.reportSettingsProblem(Log.ERROR, msg);
            } else {
                String msg = "Bad package setting: package " + p.name + " has shared uid "
                        + sharedUserId + " that is not defined\n";
                mReadMessages.append(msg);
                PackageManagerService.reportSettingsProblem(Log.ERROR, msg);
            }
        }
        mPendingPackages.clear();

        if (mBackupStoppedPackagesFilename.exists()
                || mStoppedPackagesFilename.exists()) {
            // Read old file
            readStoppedLPw();
            mBackupStoppedPackagesFilename.delete();
            mStoppedPackagesFilename.delete();
            // Migrate to new file format
            writePackageRestrictionsLPr(UserHandle.USER_SYSTEM);
        } else {
            for (UserInfo user : users) {
                readPackageRestrictionsLPr(user.id);
            }
        }

        for (UserInfo user : users) {
            mRuntimePermissionsPersistence.readStateForUserSyncLPr(user.id);
        }

        /*
         * Make sure all the updated system packages have their shared users
         * associated with them.
         */
        final Iterator<PackageSetting> disabledIt = mDisabledSysPackages.values().iterator();
        while (disabledIt.hasNext()) {
            final PackageSetting disabledPs = disabledIt.next();
            final Object id = getUserIdLPr(disabledPs.appId);
            if (id != null && id instanceof SharedUserSetting) {
                disabledPs.sharedUser = (SharedUserSetting) id;
            }
        }

        mReadMessages.append("Read completed successfully: " + mPackages.size() + " packages, "
                + mSharedUsers.size() + " shared uids\n");

        writeKernelMappingLPr();

        return true;
    }

```
经过上面的readLPw函数,将之前手机里面的安装信息都加载进来,接下来进入下一个阶段:开始扫描手机指定放apk的目录.  
回到`frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`,继续分析PackageManagerService的构造方法:  
```
            // 记录开始扫描的时间
            long startTime = SystemClock.uptimeMillis();

            .....

           //获取目录:/system/framework/
            File frameworkDir = new File(Environment.getRootDirectory(), "framework");

           .....

           //初始化扫描参数
            // Set flag to monitor and not change apk file paths when
            // scanning install directories.
            int scanFlags = SCAN_BOOTING | SCAN_INITIAL;

            if (mIsUpgrade || mFirstBoot) {
                scanFlags = scanFlags | SCAN_FIRST_BOOT_OR_UPGRADE;
            }

            // Collect vendor overlay packages. (Do this before scanning any apps.)
            // For security and version matching reason, only consider
            // overlay packages if they reside in the right directory.
            scanDirTracedLI(new File(VENDOR_OVERLAY_DIR), mDefParseFlags//scanDirTracedLI详解其实调用还是scanDirLI,下来分析scanDirLI
                    | PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR
                    | PackageParser.PARSE_TRUSTED_OVERLAY, scanFlags | SCAN_TRUSTED_OVERLAY, 0);

            //依次扫描放apk的目录
            ......
```
scanDirLI是用来扫描一个指定目录下的apk文件.接下来分析scanDirLI方法:  
```
    private void scanDirLI(File dir, int parseFlags, int scanFlags, long currentTime) {
        final File[] files = dir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            Log.d(TAG, "No files in app dir " + dir);
            return;
        }

        /// M: Add for Mtprof tool.
        addBootEvent("Android:PMS_scan_data:" + dir.getPath().toString());

        //为了加快扫描速度,使用多线程进行扫描,启动多线程扫描来自QCOM平台
        int iMultitaskNum = SystemProperties.getInt("persist.pm.multitask", 6);
        final MultiTaskDealer dealer = (iMultitaskNum > 1) ? MultiTaskDealer.startDealer(
                MultiTaskDealer.PACKAGEMANAGER_SCANER, iMultitaskNum) : null;

        ParallelPackageParser parallelPackageParser = new ParallelPackageParser(
                mSeparateProcesses, mOnlyCore, mMetrics, mCacheDir,
                mParallelPackageParserCallback);

        // Submit files for parsing in parallel
        int fileCount = 0;
        for (File file : files) {
            // Added by zhongyang_lib for apk lib
            if(file == null){
                continue;
            }
            try{
                if(!file.getAbsolutePath().equals(file.getCanonicalPath()) && file.getName().endsWith(".so")) {
                    //indicate file is a link file
                    Log.i(TAG,"file: "+file.getAbsolutePath()+" is a link file: "+ file.getCanonicalPath());
                    file = new File(file.getCanonicalPath());
                }
            }catch(IOException e){
                Log.e(TAG,"scan link file:"+file.getAbsolutePath()+"  caused an exception",e);
                continue;
            }
            parallelPackageParser.submit(file, parseFlags);
            fileCount++;
        }

        // Process results one by one
        for (; fileCount > 0; fileCount--) {
            ParallelPackageParser.ParseResult parseResult = parallelPackageParser.take();
            Throwable throwable = parseResult.throwable;
            int errorCode = PackageManager.INSTALL_SUCCEEDED;

            //merge from qcom platform by tian.pan@tcl.com for task5306252 start
            Runnable scanTask = new Runnable() {
              public void run() {
                int parserErrorCode = errorCode;

            if (throwable == null) {
                // Static shared libraries have synthetic package names
                if (parseResult.pkg.applicationInfo.isStaticSharedLibrary()) {
                    renameStaticSharedLibraryPackage(parseResult.pkg);
                }

                try {
                    if (errorCode == PackageManager.INSTALL_SUCCEEDED) {
                        //使用scanPackageLI解析apk文件
                        scanPackageLI(parseResult.pkg, parseResult.scanFile, parseFlags, scanFlags,
                                currentTime, null);
                    }
                } catch (PackageManagerException e) {
                        parserErrorCode = e.error;
                    Slog.w(TAG, "Failed to scan " + parseResult.scanFile + ": " + e.getMessage());
                }
            } else if (throwable instanceof PackageParser.PackageParserException) {
                    PackageParser.PackageParserException e = (PackageParser.PackageParserException)throwable;
                    parserErrorCode = e.error;
                Slog.w(TAG, "Failed to parse " + parseResult.scanFile + ": " + e.getMessage());
            } else {
                throw new IllegalStateException("Unexpected exception occurred while parsing "
                        + parseResult.scanFile, throwable);
            }
            // Delete invalid userdata apps
            if ((parseFlags & PackageParser.PARSE_IS_SYSTEM) == 0 &&
                        parserErrorCode == PackageManager.INSTALL_FAILED_INVALID_APK) {
                    logCriticalInfo(Log.WARN, "Deleting invalid package at " + parseResult.scanFile);
                //删除无效的apk文件
                removeCodePathLI(parseResult.scanFile);
            }
        }
            };

            if (dealer != null) {
                dealer.addTask(scanTask);
            } else {
                scanTask.run();
            }
        }

        if (dealer != null) {
            dealer.waitAll();
        }
        //merge from qcom platform by tian.pan@tcl.com for task5306252 end

        parallelPackageParser.close();
    }
```
scanDirLI方法只是去扫描了指定的文件夹  
*        /system/framework
*        /system/app
*        /vendor/app
*        /data/app
*        /data/app-private
然后并没有处理扫描到的apk文件,而是交给scanPackageLI去处理,
上面代码中使用到了一个核心的方法scanPackageLI,这个方法就是实现对apk文件进行解析和安装的文件.该方法有三个,参数对应的是6参数的,来看源码:  
```
    /**
     *  Scans a package and returns the newly parsed package.//返回的是刚刚解析的这个包.
     *  @throws PackageManagerException on a parse error.
     */
    private PackageParser.Package scanPackageLI(PackageParser.Package pkg, File scanFile,
            final int policyFlags, int scanFlags, long currentTime, @Nullable UserHandle user)
            throws PackageManagerException {
        // If the package has children and this is the first dive in the function
        // we scan the package with the SCAN_CHECK_ONLY flag set to see whether all
        // packages (parent and children) would be successfully scanned before the
        // actual scan since scanning mutates internal state and we want to atomically
        // install the package and its children.
        // 从注释看,一个package里面可能存在多个需要解析的文件
        if ((scanFlags & SCAN_CHECK_ONLY) == 0) {
            if (pkg.childPackages != null && pkg.childPackages.size() > 0) {
                scanFlags |= SCAN_CHECK_ONLY;
            }
        } else {
            scanFlags &= ~SCAN_CHECK_ONLY;
        }

        // Scan the parent
        //真正的解析工作是scanPackageInternalLI,返回的解析的包也是scanPackageInternalLI返回的
        PackageParser.Package scannedPkg = scanPackageInternalLI(pkg, scanFile, policyFlags,
                scanFlags, currentTime, user);

        // Scan the children
        final int childCount = (pkg.childPackages != null) ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPackage = pkg.childPackages.get(i);
            scanPackageInternalLI(childPackage, scanFile, policyFlags, scanFlags,//是scanPackageInternalLI干活
                    currentTime, user);
        }

        if ((scanFlags & SCAN_CHECK_ONLY) != 0) {
            return scanPackageLI(pkg, scanFile, policyFlags, scanFlags, currentTime, user);
        }

        return scannedPkg;
    }
```
OTA升级使用到scanPackageInternalLI,该方法作用就是比对新旧包,判断是否更换.分析scanPackageInternalLI的源码:  
```
    /**
     *  Scans a package and returns the newly parsed package.
     *  @throws PackageManagerException on a parse error.
     */
    //scanPackageInternalLI方法是把扫描到的androidmainifest数据和之前在手机内扫描得到的数据做对比，然后进行对应操作。
    private PackageParser.Package scanPackageInternalLI(PackageParser.Package pkg, File scanFile,
            int policyFlags, int scanFlags, long currentTime, @Nullable UserHandle user)
            throws PackageManagerException {
        PackageSetting ps = null;
        PackageSetting updatedPkg;
        // reader
        synchronized (mPackages) {
            // 看是否是已知的包,并对其进行操作
            // Look to see if we already know about this package.
            String oldName = mSettings.getRenamedPackageLPr(pkg.packageName);
            if (pkg.mOriginalPackages != null && pkg.mOriginalPackages.contains(oldName)) {
                // This package has been renamed to its original name.  Let's
                // use that.
                ps = mSettings.getPackageLPr(oldName);
            }
            // If there was no original package, see one for the real package name.
            if (ps == null) {
                ps = mSettings.getPackageLPr(pkg.packageName);
            }
            // Check to see if this package could be hiding/updating a system
            // package.  Must look for it either under the original or real
            // package name depending on our state.
            updatedPkg = mSettings.getDisabledSystemPkgLPr(ps != null ? ps.name : pkg.packageName);
            if (DEBUG_INSTALL && updatedPkg != null) Slog.d(TAG, "updatedPkg = " + updatedPkg);

            // 非第一次启动 如果是一个已卸载的系统应用或者vendor app(厂商应用)则直接返回null
            /// M: Skip an uninstalled system or vendor app
            if (!isFirstBoot() && (isVendorApp(pkg) || isSystemApp(pkg))
                 && ((updatedPkg != null)
                      || (ps.getInstallStatus() == PackageSettingBase.PKG_INSTALL_INCOMPLETE))) {
                 Slog.d(TAG, "Skip scanning " + scanFile.toString() + ", package " + updatedPkg +
                            ", install status:  " + ps.getInstallStatus());
                 return null;
            } else if (ps == null && updatedPkg != null) {
                Slog.d(TAG, "Skip scanning uninstalled package: " + pkg.packageName);
                return null;
            }

            // If this is a package we don't know about on the system partition, we
            // may need to remove disabled child packages on the system partition
            // or may need to not add child packages if the parent apk is updated
            // on the data partition and no longer defines this child package.
            if ((policyFlags & PackageParser.PARSE_IS_SYSTEM) != 0) {
                // If this is a parent package for an updated system app and this system
                // app got an OTA update which no longer defines some of the child packages
                // we have to prune them from the disabled system packages.
                PackageSetting disabledPs = mSettings.getDisabledSystemPkgLPr(pkg.packageName);
                if (disabledPs != null) {//和OTA升级相关
                    final int scannedChildCount = (pkg.childPackages != null)
                            ? pkg.childPackages.size() : 0;
                    final int disabledChildCount = disabledPs.childPackageNames != null
                            ? disabledPs.childPackageNames.size() : 0;
                    for (int i = 0; i < disabledChildCount; i++) {
                        String disabledChildPackageName = disabledPs.childPackageNames.get(i);
                        boolean disabledPackageAvailable = false;
                        for (int j = 0; j < scannedChildCount; j++) {
                            PackageParser.Package childPkg = pkg.childPackages.get(j);
                            if (childPkg.packageName.equals(disabledChildPackageName)) {
                                disabledPackageAvailable = true;
                                break;
                            }
                         }
                         if (!disabledPackageAvailable) {
                             mSettings.removeDisabledSystemPackageLPw(disabledChildPackageName);
                         }
                    }
                }
            }
        }

        // 首先检查是否包含更新的系统包，包括vendor目录下
        final boolean isUpdatedPkg = updatedPkg != null;
        /// M: [Operator] Package in vendor folder should also be checked.
        final boolean isUpdatedSystemPkg = isUpdatedPkg
                && ((policyFlags & PackageParser.PARSE_IS_SYSTEM) != 0
                 || (policyFlags & PackageParser.PARSE_IS_OPERATOR) != 0);
        boolean isUpdatedPkgBetter = false;
        // First check if this is a system package that may involve an update
        if (isUpdatedSystemPkg) {//是一个更新的系统包
            // If new package is not located in "/system/priv-app" (e.g. due to an OTA),
            // it needs to drop FLAG_PRIVILEGED.
            //如果新的包不在priv-app下，应该下调Flag
            if (locationIsPrivileged(scanFile)) {
                updatedPkg.pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
            } else {
                updatedPkg.pkgPrivateFlags &= ~ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
            }

            //如果扫描到的包路径发生了变化，根据已经存储的内容来判断该如何操作此app,记录下flag用于后面的使用
            if (ps != null && !ps.codePath.equals(scanFile)) {
                // The path has changed from what was last scanned...  check the
                // version of the new path against what we have stored to determine
                // what to do.
                if (DEBUG_INSTALL) Slog.d(TAG, "Path changing from " + ps.codePath);
                ///M: [Operator] Allow vendor package downgrade.
                ///Always install the updated one on data partition.
                if (pkg.mVersionCode < ps.versionCode
                       || ((policyFlags & PackageParser.PARSE_IS_OPERATOR) != 0)
                       /// M: Removable system app support
                       || isRemovableSysApp(pkg.packageName)) {
                    // The system package has been updated and the code path does not match
                    // Ignore entry. Skip it.
                    if (DEBUG_INSTALL) Slog.i(TAG, "Package " + ps.name + " at " + scanFile
                            + " ignored: updated version " + ps.versionCode
                            + " better than this " + pkg.mVersionCode);
                    if (!updatedPkg.codePath.equals(scanFile)) {
                        Slog.w(PackageManagerService.TAG, "Code path for hidden system pkg "
                                + ps.name + " changing from " + updatedPkg.codePathString
                                + " to " + scanFile);
                        updatedPkg.codePath = scanFile;
                        updatedPkg.codePathString = scanFile.toString();
                        updatedPkg.resourcePath = scanFile;
                        updatedPkg.resourcePathString = scanFile.toString();
                    }
                    updatedPkg.pkg = pkg;
                    updatedPkg.versionCode = pkg.mVersionCode;

                    // Update the disabled system child packages to point to the package too.
                    final int childCount = updatedPkg.childPackageNames != null
                            ? updatedPkg.childPackageNames.size() : 0;
                    for (int i = 0; i < childCount; i++) {
                        String childPackageName = updatedPkg.childPackageNames.get(i);
                        PackageSetting updatedChildPkg = mSettings.getDisabledSystemPkgLPr(
                                childPackageName);
                        if (updatedChildPkg != null) {
                            updatedChildPkg.pkg = pkg;
                            updatedChildPkg.versionCode = pkg.mVersionCode;
                        }
                    }
                } else {// /system下的package比我们在/data目下pacakage好
                    // The current app on the system partition is better than
                    // what we have updated to on the data partition; switch
                    // back to the system partition version.
                    // At this point, its safely assumed that package installation for
                    // apps in system partition will go through. If not there won't be a working
                    // version of the app
                    // writer
                    synchronized (mPackages) {
                        // Just remove the loaded entries from package lists.
                        mPackages.remove(ps.name);
                    }

                    logCriticalInfo(Log.WARN, "Package " + ps.name + " at " + scanFile
                            + " reverting from " + ps.codePathString
                            + ": new version " + pkg.mVersionCode
                            + " better than installed " + ps.versionCode);

                    InstallArgs args = createInstallArgsForExisting(packageFlagsToInstallFlags(ps),
                            ps.codePathString, ps.resourcePathString, getAppDexInstructionSets(ps));
                    synchronized (mInstallLock) {
                        args.cleanUpResourcesLI();
                    }
                    synchronized (mPackages) {
                        mSettings.enableSystemPackageLPw(ps.name);
                    }
                    isUpdatedPkgBetter = true;
                }
            }
        }

        String resourcePath = null;
        String baseResourcePath = null;
        if ((policyFlags & PackageParser.PARSE_FORWARD_LOCK) != 0 && !isUpdatedPkgBetter) {
            if (ps != null && ps.resourcePathString != null) {
                resourcePath = ps.resourcePathString;
                baseResourcePath = ps.resourcePathString;
            } else {
                // Should not happen at all. Just log an error.
                Slog.e(TAG, "Resource path not set for package " + pkg.packageName);
            }
        } else {
            resourcePath = pkg.codePath;
            baseResourcePath = pkg.baseCodePath;
        }

        //精确的设置app的路径
        // Set application objects path explicitly.
        pkg.setApplicationVolumeUuid(pkg.volumeUuid);
        pkg.setApplicationInfoCodePath(pkg.codePath);
        pkg.setApplicationInfoBaseCodePath(pkg.baseCodePath);
        pkg.setApplicationInfoSplitCodePaths(pkg.splitCodePaths);
        pkg.setApplicationInfoResourcePath(resourcePath);
        pkg.setApplicationInfoBaseResourcePath(baseResourcePath);
        pkg.setApplicationInfoSplitResourcePaths(pkg.splitCodePaths);

        // throw an exception if we have an update to a system application, but, it's not more
        // recent than the package we've already scanned
        if (isUpdatedSystemPkg && !isUpdatedPkgBetter) {
            // Set CPU Abis to application info.
            if ((scanFlags & SCAN_FIRST_BOOT_OR_UPGRADE) != 0) {
                final String cpuAbiOverride = deriveAbiOverride(pkg.cpuAbiOverride, updatedPkg);
                derivePackageAbi(pkg, scanFile, cpuAbiOverride, false, mAppLib32InstallDir);
            } else {
                pkg.applicationInfo.primaryCpuAbi = updatedPkg.primaryCpuAbiString;
                pkg.applicationInfo.secondaryCpuAbi = updatedPkg.secondaryCpuAbiString;
            }

            throw new PackageManagerException(Log.WARN, "Package " + ps.name + " at "
                    + scanFile + " ignored: updated version " + ps.versionCode
                    + " better than this " + pkg.mVersionCode);
        }

        if (isUpdatedPkg) {
            // An updated system app will not have the PARSE_IS_SYSTEM flag set
            // initially
            /** M: [Operator] Only system app have system flag @{ */
            if (isSystemApp(updatedPkg)) {
                policyFlags |= PackageParser.PARSE_IS_SYSTEM;
            }
            /// M: [Operator] Add operator flags for updated vendor package
            /// We should consider an operator app with flag changed after OTA
            if ((isVendorApp(updatedPkg) || locationIsOperator(updatedPkg.codePath))
                    && locationIsOperator(ps.codePath)) {
                policyFlags |= PackageParser.PARSE_IS_OPERATOR;
            }
            /** @} */

            // An updated privileged app will not have the PARSE_IS_PRIVILEGED
            // flag set initially
            if ((updatedPkg.pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                policyFlags |= PackageParser.PARSE_IS_PRIVILEGED;
            }
        }

        //扫描验证证书
        // Verify certificates against what was last scanned
        collectCertificatesLI(ps, pkg, scanFile, policyFlags);

        //一个新的system app，但有一个已安装的同名非系统app
        // 这里的代码是处理这样的情景：当系统更新后，可能更新包会多出一些system app出来，那么如果此时用户恰好安装了一个同包名的app.
        // 两者的签名还不一致，那么就删除扫描到的系统应用的信息。
        // 当两者签名一致时，如果扫描到的app版本更高，那么就删除安装的应用；如果扫描的app版本低，那么隐藏扫描到的系统应用。
       /*
         * A new system app appeared, but we already had a non-system one of the
         * same name installed earlier.
         */
        boolean shouldHideSystemApp = false;
        if (!isUpdatedPkg && ps != null
                && (policyFlags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0 && !isSystemApp(ps)) {
            /*
             * Check to make sure the signatures match first. If they don't,
             * wipe the installed application and its data.
             */
            //如果签名不同，擦除已安装应用和数据
            if (compareSignatures(ps.signatures.mSignatures, pkg.mSignatures)
                    != PackageManager.SIGNATURE_MATCH) {
                logCriticalInfo(Log.WARN, "Package " + ps.name + " appeared on system, but"
                        + " signatures don't match existing userdata copy; removing");
                try (PackageFreezer freezer = freezePackage(pkg.packageName,
                        "scanPackageInternalLI")) {
                    deletePackageLIF(pkg.packageName, null, true, null, 0, null, false, null);
                }
                ps = null;
            } else {//签名相同,继续判断
                /*
                 * If the newly-added system app is an older version than the
                 * already installed version, hide it. It will be scanned later
                 * and re-added like an update.
                 */
                if (pkg.mVersionCode <= ps.versionCode) {//新添加app更旧，则隐藏此app，后续会重新添加
                    shouldHideSystemApp = true;
                    logCriticalInfo(Log.INFO, "Package " + ps.name + " appeared at " + scanFile
                            + " but new version " + pkg.mVersionCode + " better than installed "
                            + ps.versionCode + "; hiding system");
                } else {//如果比已安装的app新，则保留app数据直接安装更新此system app
                    /*
                     * The newly found system app is a newer version that the
                     * one previously installed. Simply remove the
                     * already-installed application and replace it with our own
                     * while keeping the application data.
                     */
                    logCriticalInfo(Log.WARN, "Package " + ps.name + " at " + scanFile
                            + " reverting from " + ps.codePathString + ": new version "
                            + pkg.mVersionCode + " better than installed " + ps.versionCode);
                    InstallArgs args = createInstallArgsForExisting(packageFlagsToInstallFlags(ps),
                            ps.codePathString, ps.resourcePathString, getAppDexInstructionSets(ps));
                    synchronized (mInstallLock) {
                        args.cleanUpResourcesLI();
                    }
                }
            }
        }

        // The apk is forward locked (not public) if its code and resources
        // are kept in different files. (except for app in either system or
        // vendor path).
        // TODO grab this value from PackageSettings
        //如果app中制定的资源在别的路径，则从PackageSettings中抓取
        if ((policyFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
            if (ps != null && !ps.codePath.equals(ps.resourcePath)) {
                policyFlags |= PackageParser.PARSE_FORWARD_LOCK;
            }
        }

        final int userId = ((user == null) ? 0 : user.getIdentifier());
        if (ps != null && ps.getInstantApp(userId)) {
            scanFlags |= SCAN_AS_INSTANT_APP;
        }

        // Note that we invoke the following method only if we are about to unpack an application
        PackageParser.Package scannedPkg = scanPackageLI(pkg, policyFlags, scanFlags//这里是5参数的scanPackageLI方法,第一个参数是PackageParser.Package类型
                | SCAN_UPDATE_SIGNATURE, currentTime, user);

        /*
         * If the system app should be overridden by a previously installed
         * data, hide the system app now and let the /data/app scan pick it up
         * again.
         */
        if (shouldHideSystemApp) {//该package被隐藏
            synchronized (mPackages) {
                mSettings.disableSystemPackageLPw(pkg.packageName, true);
            }
        }

        return scannedPkg;
    }
```
从上面的分析看出scanPackageInternalLI方法主要还是进行比对新旧package信息(主要是OTA升级带来的升级包),进行相关的参数判断,设置不同的参数,最后交给5参数的scanPackageLI方法,
从5参数的scanPackageLI方法开始才开始,所有合法性验证都通过,开始解析,安装这个package.:  
```
    private PackageParser.Package scanPackageLI(PackageParser.Package pkg, final int policyFlags,
            int scanFlags, long currentTime, @Nullable UserHandle user)
                    throws PackageManagerException {
        boolean success = false;
        try {//scanPackageDirtyLI是安装的核心函数,不论是开机扫描还是adb安装都会调用到该函数进行APK安装
            final PackageParser.Package res = scanPackageDirtyLI(pkg, policyFlags, scanFlags,
                    currentTime, user);
            success = true;
            return res;
        } finally {
            if (!success && (scanFlags & SCAN_DELETE_DATA_ON_FAILURES) != 0) {
                // DELETE_DATA_ON_FAILURES is only used by frozen paths
                destroyAppDataLIF(pkg, UserHandle.USER_ALL,
                        StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);
                destroyAppProfilesLIF(pkg, UserHandle.USER_ALL);
            }
        }
    }
```
快来看看这个scanPackageDirtyLI函数:  
```
    // 获得前面所解析的应用程序的组件配置信息，以及为这个应用程序分配Linux用户ID
    private PackageParser.Package scanPackageDirtyLI(PackageParser.Package pkg,
            final int policyFlags, final int scanFlags, long currentTime, @Nullable UserHandle user)
                    throws PackageManagerException {

        if (DEBUG_PACKAGE_SCANNING) {
            if ((policyFlags & PackageParser.PARSE_CHATTY) != 0)
                Log.d(TAG, "Scanning package " + pkg.packageName);
        }

        applyPolicy(pkg, policyFlags);

        // Begin added by Xutao.Wu for Task5185134 on 2017/09/22
        if (PackageManager.TCT_RETAILDEMO_PACKAGE_NAME.equals(pkg.packageName)){
            if (DEBUG_INSTALL) Slog.w(TAG,"scanPackageDirtyLI, set retaildemo as SYSTEM PRIVILEGED");
            pkg.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        // End added by Xutao.Wu for Task5185134 on 2017/09/22
        // 方法的作用是判断给定的pkg是不是有效的
        assertPackageIsValid(pkg, policyFlags, scanFlags);

        // Initialize package source and resource directories
        final File scanFile = new File(pkg.codePath);
        final File destCodeFile = new File(pkg.applicationInfo.getCodePath());
        final File destResourceFile = new File(pkg.applicationInfo.getResourcePath());

        SharedUserSetting suid = null;
        PackageSetting pkgSetting = null;

        // Getting the package setting may have a side-effect, so if we
        // are only checking if scan would succeed, stash a copy of the
        // old setting to restore at the end.
        PackageSetting nonMutatedPs = null;

        // We keep references to the derived CPU Abis from settings in oder to reuse
        // them in the case where we're not upgrading or booting for the first time.
        String primaryCpuAbiFromSettings = null;
        String secondaryCpuAbiFromSettings = null;

        // writer
        // 为参数pkg所描述的应用程序分配Linux用户ID
        synchronized (mPackages) {
            if (pkg.mSharedUserId != null) {// 如果该应用有sharedUserId属性，则从mSettings中获取要为它分配的共享uid
                // SIDE EFFECTS; may potentially allocate a new shared user
                suid = mSettings.getSharedUserLPw(
                        pkg.mSharedUserId, 0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/, true /*create*/);
                if (DEBUG_PACKAGE_SCANNING) {
                    if ((policyFlags & PackageParser.PARSE_CHATTY) != 0)
                        Log.d(TAG, "Shared UserID " + pkg.mSharedUserId + " (uid=" + suid.userId
                                + "): packages=" + suid.packages);
                }
            }

            // Check if we are renaming from an original package name.
            PackageSetting origPackage = null;
            String realName = null;
            if (pkg.mOriginalPackages != null) {
                // This package may need to be renamed to a previously
                // installed name.  Let's check on that...
                final String renamed = mSettings.getRenamedPackageLPr(pkg.mRealPackage);
                if (pkg.mOriginalPackages.contains(renamed)) {
                    // This package had originally been installed as the
                    // original name, and we have already taken care of
                    // transitioning to the new one.  Just update the new
                    // one to continue using the old name.
                    realName = pkg.mRealPackage;
                    if (!pkg.packageName.equals(renamed)) {
                        // Callers into this function may have already taken
                        // care of renaming the package; only do it here if
                        // it is not already done.
                        pkg.setPackageName(renamed);
                    }
                } else {
                    for (int i=pkg.mOriginalPackages.size()-1; i>=0; i--) {
                        if ((origPackage = mSettings.getPackageLPr(
                                pkg.mOriginalPackages.get(i))) != null) {
                            // We do have the package already installed under its
                            // original name...  should we use it?
                            if (!verifyPackageUpdateLPr(origPackage, pkg)) {
                                // New package is not compatible with original.
                                origPackage = null;
                                continue;
                            } else if (origPackage.sharedUser != null) {
                                // Make sure uid is compatible between packages.
                                if (!origPackage.sharedUser.name.equals(pkg.mSharedUserId)) {
                                    Slog.w(TAG, "Unable to migrate data from " + origPackage.name
                                            + " to " + pkg.packageName + ": old uid "
                                            + origPackage.sharedUser.name
                                            + " differs from " + pkg.mSharedUserId);
                                    origPackage = null;
                                    continue;
                                }
                                // TODO: Add case when shared user id is added [b/28144775]
                            } else {
                                if (DEBUG_UPGRADE) Log.v(TAG, "Renaming new package "
                                        + pkg.packageName + " to old name " + origPackage.name);
                            }
                            break;
                        }
                    }
                }
            }

            if (mTransferedPackages.contains(pkg.packageName)) {
                Slog.w(TAG, "Package " + pkg.packageName
                        + " was transferred to another, but its .apk remains");
            }

            // See comments in nonMutatedPs declaration
            if ((scanFlags & SCAN_CHECK_ONLY) != 0) {
                PackageSetting foundPs = mSettings.getPackageLPr(pkg.packageName);
                if (foundPs != null) {
                    nonMutatedPs = new PackageSetting(foundPs);
                }
            }

            if ((scanFlags & SCAN_FIRST_BOOT_OR_UPGRADE) == 0) {
                PackageSetting foundPs = mSettings.getPackageLPr(pkg.packageName);
                if (foundPs != null) {
                    primaryCpuAbiFromSettings = foundPs.primaryCpuAbiString;
                    secondaryCpuAbiFromSettings = foundPs.secondaryCpuAbiString;
                }
            }

            // 创建PackageSetting并设置
            pkgSetting = mSettings.getPackageLPr(pkg.packageName);
            if (pkgSetting != null && pkgSetting.sharedUser != suid) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Package " + pkg.packageName + " shared user changed from "
                                + (pkgSetting.sharedUser != null
                                        ? pkgSetting.sharedUser.name : "<nothing>")
                                + " to "
                                + (suid != null ? suid.name : "<nothing>")
                                + "; replacing with new");
                pkgSetting = null;
            }
            final PackageSetting oldPkgSetting =
                    pkgSetting == null ? null : new PackageSetting(pkgSetting);
            final PackageSetting disabledPkgSetting =
                    mSettings.getDisabledSystemPkgLPr(pkg.packageName);

            String[] usesStaticLibraries = null;
            if (pkg.usesStaticLibraries != null) {
                usesStaticLibraries = new String[pkg.usesStaticLibraries.size()];
                pkg.usesStaticLibraries.toArray(usesStaticLibraries);
            }

            if (pkgSetting == null) {
                final String parentPackageName = (pkg.parentPackage != null)
                        ? pkg.parentPackage.packageName : null;
                final boolean instantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;
                // REMOVE SharedUserSetting from method; update in a separate call
                pkgSetting = Settings.createNewSetting(pkg.packageName, origPackage,
                        disabledPkgSetting, realName, suid, destCodeFile, destResourceFile,
                        pkg.applicationInfo.nativeLibraryRootDir, pkg.applicationInfo.primaryCpuAbi,
                        pkg.applicationInfo.secondaryCpuAbi, pkg.mVersionCode,
                        pkg.applicationInfo.flags, pkg.applicationInfo.privateFlags,
                        /// M: [FlagExt] Add flagsEx
                        pkg.applicationInfo.flagsEx, user,
                        true /*allowInstall*/, instantApp, parentPackageName,
                        pkg.getChildPackageNames(), UserManagerService.getInstance(),
                        usesStaticLibraries, pkg.usesStaticLibrariesVersions);
                // SIDE EFFECTS; updates system state; move elsewhere
                if (origPackage != null) {
                    mSettings.addRenamedPackageLPw(pkg.packageName, origPackage.name);
                }
                mSettings.addUserToSettingLPw(pkgSetting);
            } else {
                // REMOVE SharedUserSetting from method; update in a separate call.
                //
                // TODO(narayan): This update is bogus. nativeLibraryDir & primaryCpuAbi,
                // secondaryCpuAbi are not known at this point so we always update them
                // to null here, only to reset them at a later point.
                Settings.updatePackageSetting(pkgSetting, disabledPkgSetting, suid, destCodeFile,
                        pkg.applicationInfo.nativeLibraryDir, pkg.applicationInfo.primaryCpuAbi,
                        pkg.applicationInfo.secondaryCpuAbi, pkg.applicationInfo.flags,
                        pkg.applicationInfo.privateFlags, pkg.getChildPackageNames(),
                        UserManagerService.getInstance(), usesStaticLibraries,
                        pkg.usesStaticLibrariesVersions);
            }
            // SIDE EFFECTS; persists system state to files on disk; move elsewhere
            mSettings.writeUserRestrictionsLPw(pkgSetting, oldPkgSetting);

            // SIDE EFFECTS; modifies system state; move elsewhere
            if (pkgSetting.origPackage != null) {
                // If we are first transitioning from an original package,
                // fix up the new package's name now.  We need to do this after
                // looking up the package under its new name, so getPackageLP
                // can take care of fiddling things correctly.
                pkg.setPackageName(origPackage.name);

                // File a report about this.
                String msg = "New package " + pkgSetting.realName
                        + " renamed to replace old package " + pkgSetting.name;
                reportSettingsProblem(Log.WARN, msg);

                // Make a note of it.
                if ((scanFlags & SCAN_CHECK_ONLY) == 0) {
                    mTransferedPackages.add(origPackage.name);
                }

                // No longer need to retain this.
                pkgSetting.origPackage = null;
            }

            // SIDE EFFECTS; modifies system state; move elsewhere
            if ((scanFlags & SCAN_CHECK_ONLY) == 0 && realName != null) {
                // Make a note of it.
                mTransferedPackages.add(pkg.packageName);
            }

            if (mSettings.isDisabledSystemPackageLPr(pkg.packageName)) {
                /** M: [Operator] Operator package should not have FLAG_UPDATED_SYSTEM_APP @{ */
                PackageSetting oldPs = mSettings.getDisabledSystemPkgLPr(pkg.packageName);
                if (oldPs != null && !isVendorApp(oldPs)) {
                pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
                }
                /** @} */
            }

            if ((scanFlags & SCAN_BOOTING) == 0
                    && (policyFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                // Check all shared libraries and map to their actual file path.
                // We only do this here for apps not on a system dir, because those
                // are the only ones that can fail an install due to this.  We
                // will take care of the system apps by updating all of their
                // library paths after the scan is done. Also during the initial
                // scan don't update any libs as we do this wholesale after all
                // apps are scanned to avoid dependency based scanning.
                updateSharedLibrariesLPr(pkg, null);//创建共享库
            }

            if (mFoundPolicyFile) {
                SELinuxMMAC.assignSeInfoValue(pkg);//设置SELinux策略
            }
            pkg.applicationInfo.uid = pkgSetting.appId;
            pkg.mExtras = pkgSetting;


            // Static shared libs have same package with different versions where
            // we internally use a synthetic package name to allow multiple versions
            // of the same package, therefore we need to compare signatures against
            // the package setting for the latest library version.
            //验证签名信息的合法性
            PackageSetting signatureCheckPs = pkgSetting;
            if (pkg.applicationInfo.isStaticSharedLibrary()) {
                SharedLibraryEntry libraryEntry = getLatestSharedLibraVersionLPr(pkg);
                if (libraryEntry != null) {
                    signatureCheckPs = mSettings.getPackageLPr(libraryEntry.apk);
                }
            }

            if (shouldCheckUpgradeKeySetLP(signatureCheckPs, scanFlags)) {
                if (checkUpgradeKeySetLP(signatureCheckPs, pkg)) {
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                    pkgSetting.signatures.mSignatures = pkg.mSignatures;
                } else {
                    if ((policyFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw new PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                "Package " + pkg.packageName + " upgrade keys do not match the "
                                + "previously installed version");
                    } else {
                        pkgSetting.signatures.mSignatures = pkg.mSignatures;
                        String msg = "System package " + pkg.packageName
                                + " signature changed; retaining data.";
                        reportSettingsProblem(Log.WARN, msg);
                    }
                }
            } else {
                try {
                    // SIDE EFFECTS; compareSignaturesCompat() changes KeysetManagerService
                    verifySignaturesLP(signatureCheckPs, pkg);
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                    pkgSetting.signatures.mSignatures = pkg.mSignatures;
                } catch (PackageManagerException e) {
                    ///M: Add for operator APP.
                    if ((policyFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0
                          && (policyFlags & PackageParser.PARSE_IS_OPERATOR) == 0) {
                        throw e;
                    }
                    // The signature has changed, but this package is in the system
                    // image...  let's recover!
                    pkgSetting.signatures.mSignatures = pkg.mSignatures;
                    // However...  if this package is part of a shared user, but it
                    // doesn't match the signature of the shared user, let's fail.
                    // What this means is that you can't change the signatures
                    // associated with an overall shared user, which doesn't seem all
                    // that unreasonable.
                    if (signatureCheckPs.sharedUser != null) {
                        if (compareSignatures(signatureCheckPs.sharedUser.signatures.mSignatures,
                                pkg.mSignatures) != PackageManager.SIGNATURE_MATCH) {
                            throw new PackageManagerException(
                                    INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                                    "Signature mismatch for shared user: "
                                            + pkgSetting.sharedUser);
                        }
                    }
                    // File a report about this.
                    String msg = "System package " + pkg.packageName
                            + " signature changed; retaining data.";
                    reportSettingsProblem(Log.WARN, msg);
                }
            }

            if ((scanFlags & SCAN_CHECK_ONLY) == 0 && pkg.mAdoptPermissions != null) {
                // This package wants to adopt ownership of permissions from
                // another package.
                // 是否需要获取其他包的权限
                for (int i = pkg.mAdoptPermissions.size() - 1; i >= 0; i--) {
                    final String origName = pkg.mAdoptPermissions.get(i);
                    final PackageSetting orig = mSettings.getPackageLPr(origName);
                    if (orig != null) {
                        if (verifyPackageUpdateLPr(orig, pkg)) {
                            Slog.i(TAG, "Adopting permissions from " + origName + " to "
                                    + pkg.packageName);
                            // SIDE EFFECTS; updates permissions system state; move elsewhere
                            mSettings.transferPermissionsLPw(origName, pkg.packageName);
                        }
                    }
                }
            }
        }

        //确定进程名称
        pkg.applicationInfo.processName = fixProcessName(
                pkg.applicationInfo.packageName,
                pkg.applicationInfo.processName);

        if (pkg != mPlatformPackage) {
            // Get all of our default paths setup
            pkg.applicationInfo.initForUser(UserHandle.USER_SYSTEM);
        }

        final String cpuAbiOverride = deriveAbiOverride(pkg.cpuAbiOverride, pkgSetting);

        if ((scanFlags & SCAN_NEW_INSTALL) == 0) {
            if ((scanFlags & SCAN_FIRST_BOOT_OR_UPGRADE) != 0) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "derivePackageAbi");
                final boolean extractNativeLibs = !pkg.isLibrary();
                derivePackageAbi(pkg, scanFile, cpuAbiOverride, extractNativeLibs,
                        mAppLib32InstallDir);
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

                // Some system apps still use directory structure for native libraries
                // in which case we might end up not detecting abi solely based on apk
                // structure. Try to detect abi based on directory structure.
                if (isSystemApp(pkg) && !pkg.isUpdatedSystemApp() &&
                        pkg.applicationInfo.primaryCpuAbi == null) {
                    setBundledAppAbisAndRoots(pkg, pkgSetting);
                    setNativeLibraryPaths(pkg, mAppLib32InstallDir);
                }
            } else {
                // This is not a first boot or an upgrade, don't bother deriving the
                // ABI during the scan. Instead, trust the value that was stored in the
                // package setting.
                pkg.applicationInfo.primaryCpuAbi = primaryCpuAbiFromSettings;
                pkg.applicationInfo.secondaryCpuAbi = secondaryCpuAbiFromSettings;

                setNativeLibraryPaths(pkg, mAppLib32InstallDir);

                if (DEBUG_ABI_SELECTION) {
                    Slog.i(TAG, "Using ABIS and native lib paths from settings : " +
                        pkg.packageName + " " + pkg.applicationInfo.primaryCpuAbi + ", " +
                        pkg.applicationInfo.secondaryCpuAbi);
                }
            }
        } else {
            if ((scanFlags & SCAN_MOVE) != 0) {
                // We haven't run dex-opt for this move (since we've moved the compiled output too)
                // but we already have this packages package info in the PackageSetting. We just
                // use that and derive the native library path based on the new codepath.
                pkg.applicationInfo.primaryCpuAbi = pkgSetting.primaryCpuAbiString;
                pkg.applicationInfo.secondaryCpuAbi = pkgSetting.secondaryCpuAbiString;
            }

            // Set native library paths again. For moves, the path will be updated based on the
            // ABIs we've determined above. For non-moves, the path will be updated based on the
            // ABIs we determined during compilation, but the path will depend on the final
            // package path (after the rename away from the stage path).
            setNativeLibraryPaths(pkg, mAppLib32InstallDir);
        }

        // This is a special case for the "system" package, where the ABI is
        // dictated by the zygote configuration (and init.rc). We should keep track
        // of this ABI so that we can deal with "normal" applications that run under
        // the same UID correctly.
        if (mPlatformPackage == pkg) {
            pkg.applicationInfo.primaryCpuAbi = VMRuntime.getRuntime().is64Bit() ?
                    Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0];
        }

        // If there's a mismatch between the abi-override in the package setting
        // and the abiOverride specified for the install. Warn about this because we
        // would've already compiled the app without taking the package setting into
        // account.
        if ((scanFlags & SCAN_NO_DEX) == 0 && (scanFlags & SCAN_NEW_INSTALL) != 0) {
            if (cpuAbiOverride == null && pkgSetting.cpuAbiOverrideString != null) {
                Slog.w(TAG, "Ignoring persisted ABI override " + cpuAbiOverride +
                        " for package " + pkg.packageName);
            }
        }

        // CPU描述
        pkgSetting.primaryCpuAbiString = pkg.applicationInfo.primaryCpuAbi;
        pkgSetting.secondaryCpuAbiString = pkg.applicationInfo.secondaryCpuAbi;
        pkgSetting.cpuAbiOverrideString = cpuAbiOverride;

        // Copy the derived override back to the parsed package, so that we can
        // update the package settings accordingly.
        pkg.cpuAbiOverride = cpuAbiOverride;

        if (DEBUG_ABI_SELECTION) {
            Slog.d(TAG, "Resolved nativeLibraryRoot for " + pkg.applicationInfo.packageName
                    + " to root=" + pkg.applicationInfo.nativeLibraryRootDir + ", isa="
                    + pkg.applicationInfo.nativeLibraryRootRequiresIsa);
        }

        // Push the derived path down into PackageSettings so we know what to
        // clean up at uninstall time.
        pkgSetting.legacyNativeLibraryPathString = pkg.applicationInfo.nativeLibraryRootDir;

        if (DEBUG_ABI_SELECTION) {
            Slog.d(TAG, "Abis for package[" + pkg.packageName + "] are" +
                    " primary=" + pkg.applicationInfo.primaryCpuAbi +
                    " secondary=" + pkg.applicationInfo.secondaryCpuAbi);
        }

        // SIDE EFFECTS; removes DEX files from disk; move elsewhere
        if ((scanFlags & SCAN_BOOTING) == 0 && pkgSetting.sharedUser != null) {
            // We don't do this here during boot because we can do it all
            // at once after scanning all existing packages.
            //
            // We also do this *before* we perform dexopt on this package, so that
            // we can avoid redundant dexopts, and also to make sure we've got the
            // code and package path correct.
            adjustCpuAbisForSharedUserLPw(pkgSetting.sharedUser.packages, pkg);
        }

        if (mFactoryTest && pkg.requestedPermissions.contains(
                android.Manifest.permission.FACTORY_TEST)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_FACTORY_TEST;
        }

        if (isSystemApp(pkg)) {
            pkgSetting.isOrphaned = true;
        }

        // 更新安装时间
        // Take care of first install / last update times.
        final long scanFileTime = getLastModifiedTime(pkg, scanFile);
        if (currentTime != 0) {
            if (pkgSetting.firstInstallTime == 0) {
                pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = currentTime;
            } else if ((scanFlags & SCAN_UPDATE_TIME) != 0) {
                pkgSetting.lastUpdateTime = currentTime;
            }
        } else if (pkgSetting.firstInstallTime == 0) {
            // We need *something*.  Take time time stamp of the file.
            pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = scanFileTime;
        } else if ((policyFlags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0) {
            if (scanFileTime != pkgSetting.timeStamp) {
                // A package on the system image has changed; consider this
                // to be an update.
                pkgSetting.lastUpdateTime = scanFileTime;
            }
        }
        pkgSetting.setTimeStamp(scanFileTime);

        if ((scanFlags & SCAN_CHECK_ONLY) != 0) {
            if (nonMutatedPs != null) {
                synchronized (mPackages) {
                    mSettings.mPackages.put(nonMutatedPs.name, nonMutatedPs);
                }
            }
        } else {
            final int userId = user == null ? 0 : user.getIdentifier();
            // Modify state for the given package setting
            // 该方法回设置很多东西包括services,providers,
            // 更新lib时杀掉依赖此lib的进程
            // 对framework-res.apk单独处理
            //更新设置apk的provider并更新到数据中
            // 广播接受者receivers,activities,permissionGroups,permissions,Instrumentation信息
            commitPackageSettings(pkg, pkgSetting, user, scanFlags
                    (policyFlags & PackageParser.PARSE_CHATTY) != 0 /*chatty*/);
            if (pkgSetting.getInstantApp(userId)) {
                mInstantAppRegistry.addInstantAppLPw(userId, pkgSetting.appId);
            }
        }
        return pkg;
    }
```
scanPackageDirtyLI流程总结如下：  

- framework-res.apk单独处理   
- 初始化代码路径和资源路径  
- 创建共享库  
- 验证签名信息的合法性  
- 验证新的包的Provider不会与现有包冲突  
- 是否需要获得其他包的权限  
- 确定进程名称  
- Lib库的更新设置操作等  
- 跟新Settings参数  
- 更新安装的时间  
- 四大组件、权限、Instrumentation、把解析的这些信息注册到PMS  

当然上面有不少是在commitPackageSettings方法中实现的.  
到此apk就安装完成了,在PackageManagerService的构造方法.接下来的方法就是使用一下两个方法把
// 为申请了特定的资源访问权限的应用程序分配相应的Linux用户组ID
`updatePermissionsLPw(null, null, StorageManager.UUID_PRIVATE_INTERNAL, updateFlags);`
// 将前面获取到的应用程序安装信息保存在本地的一个配置文件中，以便下一次再安装这些应用程序时
// 可以将需要保持一致的应用程序信息恢复回来
`mSettings.writeLPr();`
