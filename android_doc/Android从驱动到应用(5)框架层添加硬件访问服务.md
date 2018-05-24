# Android从驱动到应用(5)框架层添加硬件访问服务

本节内容包括添加硬件访问服务,配置selinux权限,启动服务.

##1. 添加硬件访问服务
由于freg服务是在system_server进程中,而访问该服务的app是在自己的进程里,属于两个不同的进程,因此需要用到跨进程通讯Binder,freg服务是一个stub端,要实现AIDL方法.  
`frameworks/base/core/java/android/os/IFregService.aidl`:
```
package android.os;

/** @hide */
interface IFregService {
        void setVal(int val);
        int getVal();
}
```
该方法中注意要添加`/** @hide */`,否则编译会报错,提示更新更新API.加了hide相当于不对外公开,就不需要更新API  
在`frameworks/base/Android.mk`的`LOCAL_SRC_FILES`添加
```
    core/java/android/os/IFregService.aidl \
```
作用是添加`IFregService.aidl`源文件.需要注意的是在mk文件的书写中,每行的开头要用table键,而不是8个空格!!!  

添加系统服务代码`frameworks/base/services/core/java/com/android/server/FregService.java`:
```
package com.android.server;

import android.content.Context;
import android.os.IFregService;
import android.util.Slog;

public class FregService extends IFregService.Stub {
        private static final String TAG = "FregService";

        private int mPtr = 0;

        FregService() {
                mPtr = init_native();

                if(mPtr == 0) {
                        Slog.e(TAG, "Failed to initialize freg service.");
                }
        }

        public void setVal(int val) {
                if(mPtr == 0) {
                        Slog.e(TAG, "Freg service is not initialized.");
                        return;
                }

                setVal_native(mPtr, val);
        }

        public int getVal() {
                if(mPtr == 0) {
                        Slog.e(TAG, "Freg service is not initialized.");
                        return 0;
                }

                return getVal_native(mPtr);
        }

        private static native int init_native();
        private static native void setVal_native(int ptr, int val);
        private static native int getVal_native(int ptr);
};
```
调用native方法,实现对`dev/freg`的读写操作.

##2. 配置selinux权限
在device.te文件中添加:  
```
type freg_device, dev_type;
```
在domain.te文件中添加
```
allow domain freg_device:chr_file rw_file_perms;
```
在file_contexts文件中添加
```
/dev/freg           u:object_r:freg_device:s0
```
在service.te中文件中添加
```
type freg_service, system_api_service, system_server_service, service_manager_type;
```
在service_contexts文件中添加
```
freg                                      u:object_r:freg_service:s0
```
在system_server.te文件中添加
```
allow system_server freg_device:chr_file rw_file_perms;
```
在untrusted_app.te文件中添加
```
allow untrusted_app freg_service:service_manager find;
```
在system_app.te文件中添加
```
allow system_app freg_service:service_manager find;
```
参考blog:[在/dev下添加新设备驱动下Selinux相关设置](http://blog.csdn.net/fantasy_wxe/article/details/52013922)
##3. 启动服务
在'frameworks/base/services/java/com/android/server/SystemServer.java'中的startOtherServices()添加如下代码:
```
           try {
               Slog.i(TAG, "Freg Service");
               ServiceManager.addService("freg", new FregService());
           } catch (Throwable e) {
               Slog.e(TAG, "Failure starting Freg Service", e);
           }
```
把new的实例FregService注册到ServiceManager,取名:freg.这个名字在配置selinux权限
