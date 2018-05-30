# Android.bp 添加宏开关

平台:  android 8.1 + mt6739

作者: 李强   日期: 2018-04-18

以前在android系统控制编译的Android.mk不是纯文本形式,里面还有流控制,而Android.bp是类似JSON的纯文本形式.
对于Android.mk里面流控制部分,在Android.bp里要借助使用go语言文件去进行控制.

这里的添加宏开关两种情况:

- 无流控制的宏开关添加
- 有流控制的宏开关添加

## 1.无流控制的宏开关添加Demo

### 1.1 在已有的Android.bp中添加宏
首先找要添加的Android.bp文件中是否有`cppflags`或者'cflags',基本上都是有的,例如:
```
cc_defaults {
    name: "fs_mgr_defaults",
    defaults: ["BBB"],// new add
    sanitize: {
        misc_undefined: ["integer"],
    },
    local_include_dirs: ["include/"],
    cppflags: ["-Werror", "-DMTK_FSTAB_FLAGS"],
}
```
例如要添加的宏:
```
LOCAL_CFLAGS += -DTEST1
LOCAL_CFLAGS += -DTEST2=1
```
将上面的宏补在原有的'cc_defaults'里面的'cppflags'后面:
```
cc_defaults {
    name: "fs_mgr_defaults",
    sanitize: {
        misc_undefined: ["integer"],
    },
    local_include_dirs: ["include/"],
    cppflags: ["-Werror",
               "-DMTK_FSTAB_FLAGS",
               "-DTEST1",
               "-DTEST2=1"],
}
```

### 1.2 androidmk命令
如果要转化的Android.mk内容没有流控制,可以使用Androidmk命令直接转换.
该命令在:`out/soong/host/linux-x86/bin/androidmk`,使用方法:

```
androidmk Android.mk > Android.bp
```
如果要转换的`Android.mk`里面没有复杂结构,就可以转换成功,如果报错就可能有复杂结构.需要手动转换.

## 2. 有流控制的宏开关添加Demo

在Android.mk中添加的宏开关:
```
ifeq ($(ENABLE_USER2ENG),true)
LOCAL_CFLAGS += -DALLOW_ADBD_DISABLE_VERITY=1
LOCAL_CFLAGS += -DENABLE_USER2ENG=1
endif
```

如果要将以上的宏开关添加到Android.bp中去要通过使用go语言书写一个新文件:

比如我的修改是在`system/core/fs_mgr/Android.bp`,那么要在添加
`system/core/fs_mgr/fs_mgr.go`:
```
package fs_mgr

import (
        "android/soong/android"
        "android/soong/cc"
        "fmt"
)

func init() {
    // for DEBUG
    fmt.Println("init start")
    android.RegisterModuleType("AAA", fs_mgrDefaultsFactory)
}

func fs_mgrDefaultsFactory() (android.Module) {
    module := cc.DefaultsFactory()
    android.AddLoadHook(module, fs_mgrDefaults)
    return module
}

func fs_mgrDefaults(ctx android.LoadHookContext) {
    type props struct {
        Cflags []string
    }
    p := &props{}
    p.Cflags = globalDefaults(ctx)
    ctx.AppendProperties(p)
}

func globalDefaults(ctx android.BaseContext) ([]string) {
    var cppflags []string

    fmt.Println("ENABLE_USER2ENG:",
        ctx.AConfig().IsEnvTrue("ENABLE_USER2ENG"))
    if ctx.AConfig().IsEnvTrue("ENABLE_USER2ENG") {
          cppflags = append(cppflags,
                         "-DALLOW_ADBD_DISABLE_VERITY=1",
                         "-DENABLE_USER2ENG=1")
    }

    return cppflags
}
```

`Android.bp`需要修改的地方:
```
/// add start
bootstrap_go_package {
    // name and pkgPath need to  according to your module
    name: "soong-fs_mgr",
    pkgPath: "android/soong/fs_mgr",
    deps: [
        "blueprint",
        "blueprint-pathtools",
        "soong",
        "soong-android",
        "soong-cc",
        "soong-genrule",
    ],
    srcs: [
          // include new add .go file
          "fs_mgr.go",
    ],
    pluginFor: ["soong_build"],
}

// AAA is a module
AAA {
    name: "BBB",
}
/// add end

cc_defaults {
    name: "fs_mgr_defaults",
    defaults: ["BBB"],// new add
    sanitize: {
        misc_undefined: ["integer"],
    },
    local_include_dirs: ["include/"],
    cppflags: ["-Werror", "-DMTK_FSTAB_FLAGS"],
}
```
参照该例子修改时注意`AAA`,`BBB`的对应关系即可.

## 3. 一些相关的经验

### 3.1
在go文件中使用`fmt.Println`添加打印信息,可以调试go代码有没有按照正确的.使用方式参考上面的例子.
这些打印信息会在用`mmm`或者`make`命令编译是打印在屏幕上.

### 3.2
如果添加了*.go文件,可以在使用到宏的地方加入编译会报错的代码,例如上面的例子:
```
#ifdef ALLOW_ADBD_DISABLE_VERITY
    if (verity.disabled) {
        retval = FS_MGR_SETUP_VERITY_DISABLED;
        LINFO << "Attempt to cleanly disable verity - only works in USERDEBUG";
        goto out;
    }
#endif
```
改为:
```
#ifdef ALLOW_ADBD_DISABLE_VERITY
11111111111
    if (verity.disabled) {
        retval = FS_MGR_SETUP_VERITY_DISABLED;
        LINFO << "Attempt to cleanly disable verity - only works in USERDEBUG";
        goto out;
    }
#endif
```
这样就可以很快的验证自己添加的flag是否生效.

## 4. Android.bp的相关知识
该部分内容结合上面的例子,主要参考`android/build/soong/README.md`

### 4.1 注释
有两种形式:单行注释`//`和多行注释`/*  */`

### 4.2 module
在上文的例子中:
```
AAA {
    name: "BBB",
}
```
`AAA` 必须要在go文件中注册,`name`的值必须要是在所有Android.bp文件中是唯一的(建议按照所在的模块取名字).
每个module必须要有一个name.在module中的值都是用`name: value`的形式来,比如:
```
    name: "libfstab",
    vendor_available: true,
    defaults: ["fs_mgr_defaults"],
    srcs: [
        "fs_mgr_fstab.cpp",
        "fs_mgr_boot_config.cpp",
        "fs_mgr_slotselect.cpp",
    ],
    export_include_dirs: ["include_fstab"],
    header_libs: ["libbase_headers"],

```

## 参考:
1. [Android编译系统中的Android.bp、Blueprint与Soong](http://note.qidong.name/2017/08/android-blueprint/)  
2. [Android 编译系统之Android.bp ](https://blog.csdn.net/drageon_j/article/details/77336817)  













