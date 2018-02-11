#测试frg驱动的功能内置C程程序

在上一节驱动程序已经添加,可是还不知道该驱动程序是否可以正常工作,用该节的代码来验证一下.

```
android/external
    ----freg
        ----freg.c
        ----Android.mk
```


在'android/external'下
```
mkdir freg
```
freg.c
```
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>

#define FREG_DEVICE_NAME "/dev/freg"

int main(int argc, char** argv)
{
    int fd = -1;
    int val = 0;

    fd = open(FREG_DEVICE_NAME, O_RDWR);
    if(fd == -1)
    {
        printf("Failed to open device %s.\n", FREG_DEVICE_NAME);
        return -1;
    }

    printf("Read original value:\n");
    read(fd, &val, sizeof(val));
    printf("%d.\n\n", val);

    val = 5;
    printf("Write value %d to %s.\n\n", val, FREG_DEVICE_NAME);
        write(fd, &val, sizeof(val));


    printf("Read the value again:\n");
        read(fd, &val, sizeof(val));
        printf("%d.\n\n", val);

    close(fd);

    return 0;
}
```
freg.c是代码的逻辑实现,先读取'/dev/freg'里面的值,在将写入新值5,再读取出来
Android.mk
```
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := freg
LOCAL_SRC_FILES := $(call all-subdir-c-files)
include $(BUILD_EXECUTABLE)
```
Android.mk里该bin文件命名为freg

单编:
```
mmm ./external/freg
```
打包system image:
```
make snod
```
刷入机器,`adb shell`进入机器,在`system/bin/`会出现名称叫freg的bin文件.在`system/bin/`里执行命令:
```
./freg
```
执行我们写的freg程序,会输出:
```
      Read the original value:

      0.

      Write value 5 to /dev/freg.

      Read the value again:

      5.
```
我们进入`proc/`,`cat freg`,发现值为5.说明修改成功.到此说明驱动的添加没有问题.
