# Android Classloader

**Android N**

最近的工作遇到了运用反射来加载类,需要用到这块的知识,因此有了这篇文章.

##1. java类加载器
Java虚拟机类加载过程是把Class类文件加载到内存，并对Class文件中的数据进行校验、转换解析和初始化，最终形成可以被虚拟机直接使用的java类型的过程。
具体过程参考<<深入理解java虚拟机>>.在加载阶段，java虚拟机需要完成以下3件事：

- 通过一个类的全限定名来获取定义此类的二进制字节流。

- 将定义类的二进制字节流所代表的静态存储结构转换为方法区的运行时数据结构。

- 在java堆中生成一个代表该类的java.lang.Class对象，作为方法区数据的访问入口,一个类不管new多少实例,只有一个对应的class对象.

这个加载过程是使用类加载器完成的.

在java里面默认的有三个类加载器:BootStrap,ExtClassLoader,AppClassLoader.类加载器也是需要加载的,BootStrap是用C++书写,直接在java虚拟机的内核里面,用于加载类加载器,
以及rt.jar包,ExtClassLoader是用来加载在`Java/jre7/lib/ext/`下面的jar包.

每个ClassLoader必须有一个父ClassLoader，在装载Class文件时，子ClassLoader会先请求父ClassLoader加载该Class文件，只有当其父ClassLoader找不到该Class文件时，
子ClassLoader才会继续装载该类，这是一种安全机制。叫类加载器的委托机制.

在使用标准Java虚拟机时，我们经常自定义继承自ClassLoader的类加载器。Android中ClassLoader的defineClass方法具体是调用VMClassLoader的defineClass本地静态方法。
而这个本地方法除了抛出一个“UnsupportedOperationException”之外，什么都没做，甚至连返回值都为空.所以在android中继承ClassLoader定义自己的类加载器是行不通的.
为了在android中实现动态的加载类,Android从ClassLoader派生出了两个类：DexClassLoader和PathClassLoader.

- PathClassLoader是android中的默认加载器,只能加载/data/app中的apk,即安装在手机中的apk;
- DexClassLoader可以加载任何路径的apk/dex/jar;

##2. android的类加载器
###2.1 DexClassLoader
加载器的代码在`libcore/dalvik/src/main/java/dalvik/system/`,DexClassLoader.java:
```
package dalvik.system;

/**
 * A class loader that loads classes from {@code .jar} and {@code .apk} files
 * containing a {@code classes.dex} entry. This can be used to execute code not
 * installed as part of an application.
 *
 * <p>This class loader requires an application-private, writable directory to
 * cache optimized classes. Use {@code Context.getCodeCacheDir()} to create
 * such a directory: <pre>   {@code
 *   File dexOutputDir = context.getCodeCacheDir();
 * }</pre>
 *
 * <p><strong>Do not cache optimized classes on external storage.</strong>
 * External storage does not provide access controls necessary to protect your
 * application from code injection attacks.
 */
public class DexClassLoader extends BaseDexClassLoader {
    /**
     * Creates a {@code DexClassLoader} that finds interpreted and native
     * code.  Interpreted classes are found in a set of DEX files contained
     * in Jar or APK files.
     *
     * <p>The path lists are separated using the character specified by the
     * {@code path.separator} system property, which defaults to {@code :}.
     *
     * @param dexPath the list of jar/apk files containing classes and
     *     resources, delimited by {@code File.pathSeparator}, which
     *     defaults to {@code ":"} on Android
     * @param optimizedDirectory this parameter is deprecated and has no effect
     * @param librarySearchPath the list of directories containing native
     *     libraries, delimited by {@code File.pathSeparator}; may be
     *     {@code null}
     * @param parent the parent class loader
     */
    public DexClassLoader(String dexPath, String optimizedDirectory,
            String librarySearchPath, ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
    }
}
```
DexClassLoader构造器,只有dexPath,optimizedDirectory,librarySearchPath,parent:
###2.2 PathClassLoader
PathClassLoader的源码也在`libcore/dalvik/src/main/java/dalvik/system/`,PathClassLoader.java:
```
public class PathClassLoader extends BaseDexClassLoader {
    /**
     * Creates a {@code PathClassLoader} that operates on a given list of files
     * and directories. This method is equivalent to calling
     * {@link #PathClassLoader(String, String, ClassLoader)} with a
     * {@code null} value for the second argument (see description there).
     *
     * @param dexPath the list of jar/apk files containing classes and
     * resources, delimited by {@code File.pathSeparator}, which
     * defaults to {@code ":"} on Android
     * @param parent the parent class loader
     */
    public PathClassLoader(String dexPath, ClassLoader parent) {
        super(dexPath, null, null, parent);
    }

    /**
     * Creates a {@code PathClassLoader} that operates on two given
     * lists of files and directories. The entries of the first list
     * should be one of the following:
     *
     * <ul>
     * <li>JAR/ZIP/APK files, possibly containing a "classes.dex" file as
     * well as arbitrary resources.
     * <li>Raw ".dex" files (not inside a zip file).
     * </ul>
     *
     * The entries of the second list should be directories containing
     * native library files.
     *
     * @param dexPath the list of jar/apk files containing classes and
     * resources, delimited by {@code File.pathSeparator}, which
     * defaults to {@code ":"} on Android
     * @param librarySearchPath the list of directories containing native
     * libraries, delimited by {@code File.pathSeparator}; may be
     * {@code null}
     * @param parent the parent class loader
     */
    public PathClassLoader(String dexPath, String librarySearchPath, ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
    }
}
```
PathClassLoader同样是继承BaseDexClassLoader类,有两个构造方法,最终都是调用BaseDexClassLoader的构造方法进行构造.但是与DexClassLoader相比,
没有了optimizedDirectory参数,其他都一样.原因是PathClassLoader加载的是android安装的apk的类,这些class的dex有一个固定的目录`/data/dalvik-cache`:
```
Android:/data/dalvik-cache # cd arm/
system@app@ApplicationsProvider@ApplicationsProvider.apk@classes.dex
system@app@ApplicationsProvider@ApplicationsProvider.apk@classes.vdex
system@app@AtciService@AtciService.apk@classes.art
system@app@AtciService@AtciService.apk@classes.dex
system@app@AtciService@AtciService.apk@classes.vdex
system@app@BasicDreams@BasicDreams.apk@classes.art
system@app@BasicDreams@BasicDreams.apk@classes.dex
system@app@BasicDreams@BasicDreams.apk@classes.vdex
system@app@BatteryWarning@BatteryWarning.apk@classes.art
system@app@BatteryWarning@BatteryWarning.apk@classes.dex
system@app@BatteryWarning@BatteryWarning.apk@classes.vdex
system@app@Bluetooth@Bluetooth.apk@classes.art
system@app@Bluetooth@Bluetooth.apk@classes.dex
system@app@Bluetooth@Bluetooth.apk@classes.vdex
system@app@BluetoothMidiService@BluetoothMidiService.apk@classes.art
system@app@BluetoothMidiService@BluetoothMidiService.apk@classes.dex
system@app@BluetoothMidiService@BluetoothMidiService.apk@classes.vdex
system@app@BookmarkProvider@BookmarkProvider.apk@classes.art
system@app@BookmarkProvider@BookmarkProvider.apk@classes.dex
system@app@BookmarkProvider@BookmarkProvider.apk@classes.vdex
system@app@BuiltInPrintService@BuiltInPrintService.apk@classes.art
system@app@BuiltInPrintService@BuiltInPrintService.apk@classes.dex
system@app@BuiltInPrintService@BuiltInPrintService.apk@classes.vdex
......
```
###2.3 BaseDexClassLoader
DexClassLoader和PathClassLoader的构造方法都是调用了BaseDexClassLoader,在BaseDexClassLoader.java里面:
```
public class BaseDexClassLoader extends ClassLoader{
     .....
    public BaseDexClassLoader(String dexPath, File optimizedDirectory,
            String librarySearchPath, ClassLoader parent) {
        super(parent);
        this.pathList = new DexPathList(this, dexPath, librarySearchPath, null);

        if (reporter != null) {
            reporter.report(this.pathList.getDexPaths());
        }
    }
     ......
}
```
说明这最后都是从ClassLoader来的,ClassLoader才是祖师爷呀.在BaseDexClassLoader的构造方法里创建了DexPathList,也就干了这么一件事.
BaseDexClassLoader创建时的四个参数的意义:

- dexPath: 是加载apk/dex/jar的路径,dexPath,当有多个路径则采用:分割;
- optimizedDirectory: 是dex的输出路径(因为加载apk/jar的时候会解压除dex文件，这个路径就是保存dex文件的),优化后的dex文件存在的目录;
- librarySearchPath: 是加载的时候需要用到的lib库;
- parent: 给DexClassLoader指定父加载器;

####2.3.1 DexPathList
接着分析DexPathList代码,DexPathList.java在`libcore/dalvik/src/main/java/dalvik/system`,
```
    /**
     * Constructs an instance.
     *
     * @param definingContext the context in which any as-yet unresolved
     * classes should be defined
     * @param dexPath list of dex/resource path elements, separated by
     * {@code File.pathSeparator}
     * @param librarySearchPath list of native library directory path elements,
     * separated by {@code File.pathSeparator}
     * @param optimizedDirectory directory where optimized {@code .dex} files
     * should be found and written to, or {@code null} to use the default
     * system directory for same
     */
    public DexPathList(ClassLoader definingContext, String dexPath,
            String librarySearchPath, File optimizedDirectory) {

        if (definingContext == null) {
            throw new NullPointerException("definingContext == null");
        }

        if (dexPath == null) {
            throw new NullPointerException("dexPath == null");
        }

        if (optimizedDirectory != null) {
            if (!optimizedDirectory.exists())  {
                throw new IllegalArgumentException(
                        "optimizedDirectory doesn't exist: "
                        + optimizedDirectory);
            }

            if (!(optimizedDirectory.canRead()
                            && optimizedDirectory.canWrite())) {
                throw new IllegalArgumentException(
                        "optimizedDirectory not readable/writable: "
                        + optimizedDirectory);
            }
        }
        this.definingContext = definingContext;

        ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();
        // save dexPath for BaseDexClassLoader
        // 记录所有的dexFile文件
        this.dexElements = makeDexElements(splitDexPath(dexPath), optimizedDirectory,
                                           suppressedExceptions, definingContext);

        // Native libraries may exist in both the system and
        // application library paths, and we use this search order:
        //
        //   1. This class loader's library path for application libraries (librarySearchPath):
        //   1.1. Native library directories
        //   1.2. Path to libraries in apk-files
        //   2. The VM's library path from the system property for system libraries
        //      also known as java.library.path
        //
        // This order was reversed prior to Gingerbread; see http://b/2933456.
        // app的native库 
        this.nativeLibraryDirectories = splitPaths(librarySearchPath, false);
        // 系统的native库
        this.systemNativeLibraryDirectories =
                splitPaths(System.getProperty("java.library.path"), true);
        // 把app的native库和system的native库合并,汇总
        List<File> allNativeLibraryDirectories = new ArrayList<>(nativeLibraryDirectories);
        allNativeLibraryDirectories.addAll(systemNativeLibraryDirectories);

        // 记录所有的native动态库
        this.nativeLibraryPathElements = makePathElements(allNativeLibraryDirectories);

        if (suppressedExceptions.size() > 0) {
            this.dexElementsSuppressedExceptions =
                suppressedExceptions.toArray(new IOException[suppressedExceptions.size()]);
        } else {
            dexElementsSuppressedExceptions = null;
        }
    }

```
总结的构造的过程中主要干了两件事:记录DexFile(dexElements)和记录native动态库(nativeLibraryPathElements).

##2.4 ClassLoader
所有的类加载器最终都是在调用ClassLoader类,该类是类加载的核心,其他都只是进行封装,打包.ClassLoader.java在`libcore/ojluni/src/main/java/java/lang/ClassLoader.java`,
下面只是列出了主要代码部分:
```
public abstract class ClassLoader {

    static private class SystemClassLoader {
        public static ClassLoader loader = ClassLoader.createSystemClassLoader();
    }

    /**
     * Encapsulates the set of parallel capable loader types.
     */
    private static ClassLoader createSystemClassLoader() {
        String classPath = System.getProperty("java.class.path", ".");
        String librarySearchPath = System.getProperty("java.library.path", "");

        // String[] paths = classPath.split(":");
        // URL[] urls = new URL[paths.length];
        // for (int i = 0; i < paths.length; i++) {
        // try {
        // urls[i] = new URL("file://" + paths[i]);
        // }
        // catch (Exception ex) {
        // ex.printStackTrace();
        // }
        // }
        //
        // return new java.net.URLClassLoader(urls, null);

        // TODO Make this a java.net.URLClassLoader once we have those?
        return new PathClassLoader(classPath, librarySearchPath, BootClassLoader.getInstance());
    }

    public static ClassLoader getSystemClassLoader() {
        // 返回系统默认的加载器,appclassloader
        return SystemClassLoader.loader;
    }

    // 加载使用双亲委派模式
    protected Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException
    {
            // First, check if the class has already been loaded
            // 首先,检查请求的类是否已经加载过
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    if (parent != null) {//委派父类加载器加载
                        c = parent.loadClass(name, false);
                    } else {//委派启动类加载器加载
                        c = findBootstrapClassOrNull(name);
                    }
                } catch (ClassNotFoundException e) {//父类加载器无法完成加载请求
                    // ClassNotFoundException thrown if class not found
                    // from the non-null parent class loader
                }

                if (c == null) {//本身加载器无法完成加载
                    // If still not found, then invoke findClass in order
                    // to find the class.
                    c = findClass(name);
                }
            }
            return c;
    }

    /**
     * Returns the class with the given <a href="#name">binary name</a> if this
     * loader has been recorded by the Java virtual machine as an initiating
     * loader of a class with that <a href="#name">binary name</a>.  Otherwise
     * <tt>null</tt> is returned.
     *
     * @param  name
     *         The <a href="#name">binary name</a> of the class
     *
     * @return  The <tt>Class</tt> object, or <tt>null</tt> if the class has
     *          not been loaded
     *
     * @since  1.1
     */
    protected final Class<?> findLoadedClass(String name) {
        ClassLoader loader;
        if (this == BootClassLoader.getInstance())
            loader = null;
        else
            loader = this;
        return VMClassLoader.findLoadedClass(loader, name);
    }

    // 自定义ClassLoader需要重写该方法
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }
```

















































