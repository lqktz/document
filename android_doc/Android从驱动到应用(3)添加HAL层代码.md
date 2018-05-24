# Android从驱动到应用(3)添加HAL层代码

在前面,我们添加里成功了驱动层的代码,下来接着添加HAL层的代码.让我们的用户空间的代码也能使用到添加的新的虚拟寄存器设备.

```
hardware
    ----libhardware/include/hardware/freg.h
    ----libhardware/modules/freg
        |---freg.cpp
        |---Android.mk
```
freg.h:
```
#ifndef ANDROID_FREG_INTERFACE_H
#define ANDROID_FREG_INTERFACE_H

#include <hardware/hardware.h>

__BEGIN_DECLS

/**
 * The id of this module
 */
#define FREG_HARDWARE_MODULE_ID "freg"

/**
 * The id of this device
 */
#define FREG_HARDWARE_DEVICE_ID "freg"

struct freg_module_t {
        struct hw_module_t common;
};

struct freg_device_t {
        struct hw_device_t common;
        int fd;
        int (*set_val)(struct freg_device_t* dev, int val);
        int (*get_val)(struct freg_device_t* dev, int* val);
};

__END_DECLS

#endif
```
freg.h按照android对HAL层规范的要求，分别定义模块ID、模块结构体以及硬件接口结构体.

freg.h:
```
#define LOG_TAG "FregHALStub"

#include <hardware/hardware.h>
#include <hardware/freg.h>

#include <fcntl.h>
#include <errno.h>

#include <cutils/log.h>
#include <cutils/atomic.h>

#include <malloc.h>
#include <stdint.h>
#include <sys/time.h>

#include <hardware/hardware.h>
#include <system/audio.h>
#include <hardware/audio.h>



#define DEVICE_NAME "/dev/freg"
#define MODULE_NAME "Freg"
#define MODULE_AUTHOR "shyluo@gmail.com"

static int freg_device_open(const struct hw_module_t* module, const char* id, struct hw_device_t** device);
static int freg_device_close(struct hw_device_t* device);
static int freg_set_val(struct freg_device_t* dev, int val);
static int freg_get_val(struct freg_device_t* dev, int* val);

static struct hw_module_methods_t freg_module_methods = {
        open: freg_device_open
};

struct freg_module_t HAL_MODULE_INFO_SYM = {
        common: {
                tag: HARDWARE_MODULE_TAG,
                version_major: 1,
                version_minor: 0,
                id: FREG_HARDWARE_MODULE_ID,
                name: MODULE_NAME,
                author: MODULE_AUTHOR,
                methods: &freg_module_methods,
        }
};

static int freg_device_open(const struct hw_module_t* module, const char* id, struct hw_device_t** device) {
        if(!strcmp(id, FREG_HARDWARE_DEVICE_ID)) {
                struct freg_device_t* dev;

                dev = (struct freg_device_t*)malloc(sizeof(struct freg_device_t));
                if(!dev) {
                        ALOGE("Failed to alloc space for freg_device_t.");
                        return -EFAULT;
                }

                memset(dev, 0, sizeof(struct freg_device_t));

                dev->common.tag = HARDWARE_DEVICE_TAG;
                dev->common.version = 0;
                dev->common.module = (hw_module_t*)module;
                dev->common.close = freg_device_close;
                dev->set_val = freg_set_val;
                dev->get_val = freg_get_val;

                if((dev->fd = open(DEVICE_NAME, O_RDWR)) == -1) {
                        ALOGE("Failed to open device file /dev/freg -- %s.", strerror(errno));
                        free(dev);
                        return -EFAULT;
                }

                *device = &(dev->common);

                ALOGI("Open device file /dev/freg successfully.");

                return 0;
        }

        return -EFAULT;
}

static int freg_device_close(struct hw_device_t* device) {
        struct freg_device_t* freg_device = (struct freg_device_t*)device;
        if(freg_device) {
                close(freg_device->fd);
                free(freg_device);
        }

        return 0;
}

static int freg_set_val(struct freg_device_t* dev, int val) {
        if(!dev) {
                ALOGE("Null dev pointer.");
                return -EFAULT;
        }

        ALOGI("Set value %d to device file /dev/freg.", val);
        write(dev->fd, &val, sizeof(val));

        return 0;
}

static int freg_get_val(struct freg_device_t* dev, int* val) {
        if(!dev) {
                ALOGE("Null dev pointer.");
                return -EFAULT;
        }

        if(!val) {
                ALOGE("Null val pointer.");
                return -EFAULT;
        }

        read(dev->fd, val, sizeof(*val));

        ALOGI("Get value %d from device file /dev/freg.", *val);

        return 0;
}
```
freg.cpp相对罗大神blog的修改是include文件,由于free函数和malloc方法的头文件有变化,还有log系统,LOGE->ALOGE,LOGI->ALOGI.这两个改动是依据代码编译报错修改而来.  

Android.mk:
```
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_PRELINK_MODULE := false
LOCAL_MODULE_PATH := $(TARGET_OUT_SHARED_LIBRARIES)/hw
LOCAL_SHARED_LIBRARIES := liblog
LOCAL_SRC_FILES := freg.cpp
LOCAL_MODULE := freg.default
include $(BUILD_SHARED_LIBRARY)
```
编译该模块:
```
mmm hardware/libhardware/modules/freg
```
这样就会在`out/target/product/generic/system/lib/hw`目录下看到freg.default.so文件.  
打包system image :`make snod`  
HAL层书写完毕.





