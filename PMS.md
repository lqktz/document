#PackageManagerService 源码分析

平台:android O源码  
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
        //看名字像是dex的管理器
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
            readPermissionsFromXml(f, permissionFlag);
        }

        // Read platform permissions last so it will take precedence
        if (platformFile != null) {
            readPermissionsFromXml(platformFile, permissionFlag);
        }
    }
```
readPermissions就是从指定目录下，读取xml中的配置的权限信息.在eg:/system/etc/permissions/platform.xml
从上面的xml文件可以看出，platform.xml主要作用是：  
- permission和group字段用于建立Linux层GID和Android层permission字段之间的映射关系；  
* assign-permission用于向指定的uid赋予相应的权限；  
* library字段用于可链接的指定系统库;  
* allow-in-power-save-except-idle用于指定进程在省电模式下(非Idle)仍可上网;  
* backup-transport-whitelisted-service用于指定服务具有传输备份数据的权利;  

```
        mGlobalGids = systemConfig.getGlobalGids();
        mSystemPermissions = systemConfig.getSystemPermissions();
        mAvailableFeatures = systemConfig.getAvailableFeatures();
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
```







