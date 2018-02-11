#添加JNI层

为了使用java代码调用c代码.需要实现对应的JNI方法.

```
frameworks/base/services/core/jni/
    ----com_android_server_FregService.cpp
    ----Android.mk
    ----onload.cpp
system/core/rootdir/ueventd.rc
```
com_android_server_FregService.cpp
```
#define LOG_TAG "FregServiceJNI"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <utils/misc.h>
#include <utils/Log.h>
#include <hardware/hardware.h>
#include <hardware/freg.h>

#include <stdio.h>

namespace android
{
        static void freg_setVal(JNIEnv* env, jobject clazz, jint ptr, jint value) {
                freg_device_t* device = (freg_device_t*)ptr;
                if(!device) {
                        ALOGE("Device freg is not open.");
                        return;
                }

                int val = value;

                ALOGI("Set value %d to device freg.", val);

                device->set_val(device, val);
        }

        static jint freg_getVal(JNIEnv* env, jobject clazz, jint ptr) {
                freg_device_t* device = (freg_device_t*)ptr;
                if(!device) {
                        ALOGE("Device freg is not open.");
                        return 0;
                }

                int val = 0;

                device->get_val(device, &val);

                ALOGI("Get value %d from device freg.", val);

                return val;
        }

        static inline int freg_device_open(const hw_module_t* module, struct freg_device_t** device) {
                return module->methods->open(module, FREG_HARDWARE_DEVICE_ID, (struct hw_device_t**)device);
        }

        static jint freg_init(JNIEnv* env, jclass clazz) {
                freg_module_t* module;
                freg_device_t* device;

                ALOGI("Initializing HAL stub freg......");

                if(hw_get_module(FREG_HARDWARE_MODULE_ID, (const struct hw_module_t**)&module) == 0) {
                        ALOGI("Device freg found.");
                        if(freg_device_open(&(module->common), &device) == 0) {
                                ALOGI("Device freg is open.");
                                return (jint)device;
                        }

                        ALOGE("Failed to open device freg.");
                        return 0;
                }

                ALOGE("Failed to get HAL stub freg.");

                return 0;
        }

        static const JNINativeMethod method_table[] = {
                {"init_native", "()I", (void*)freg_init},
                {"setVal_native", "(II)V", (void*)freg_setVal},
                {"getVal_native", "(I)I", (void*)freg_getVal},
        };

        int register_android_server_FregService(JNIEnv *env) {
                return jniRegisterNativeMethods(env, "com/android/server/FregService", method_table, NELEM(method_table));
        }
};
```
Android.mk,在`LOCAL_SRC_FILES +=`里添加
```
    $(LOCAL_REL_DIR)/com_android_server_FregService.cpp \
```
onload.cpp,在`namespace android`里添加:
```
int register_android_server_FregService(JNIEnv* env);
```
在`extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)`中添加:
```
    register_android_server_FregService(env);
`
编译:
```
mmm frameworks/base/services/core/jni
```
打包system image:
```
make snod
```
添加硬件设备访问权限:
在system/core/rootdir/ueventd.rc添加:
```
/dev/freg      0666   root     root
```
这样JNI方法就添加完成了,如果你编译的是eng版本,SELinux是关闭的,那么该JNI方法是可以正常运行的.如果是user版本需要配置SELinux的策略文件才能访问.







