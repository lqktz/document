# Android从驱动到应用(1)添加驱动

学习知识,光说不练假把式.系列文章按照罗升阳大神的blog进行实践.向罗大神致敬!实现的是在从驱动层,HAL层,系统添加,JNI方法添加,AIDL方法添加,配置SELinux策略.
由于android版本的差异,部分代码和配置需要修改,kernel和android源码版本不一样才能出错,这样才有试错,学习成长的机会.本文是参看<<Android系统源代码情景分析(修订版)>>
以及罗大神的blog和其他网友的blog而来.

平台: Android N + kernel 3.18 + MTK
```
android/kernel-3.18/drivers/
    ----freg
        ----freg.c
        ----freg.h
        ----Kconfig
        ----Makefile
```

freg.c
```
nclude <linux/init.h>
#include <linux/module.h>
#include <linux/types.h>
#include <linux/fs.h>
#include <linux/proc_fs.h>
#include <linux/device.h>
#include <linux/slab.h>
#include <linux/seq_file.h>
#include <asm/uaccess.h>

#include "freg.h"

static int freg_major = 0;
static int freg_minor = 0;

static struct class* freg_class = NULL;
static struct fake_reg_dev* freg_dev = NULL;

static int freg_open(struct inode* inode, struct file* filp);
static int freg_release(struct inode* inode, struct file* filp);
static ssize_t freg_read(struct file* filp, char __user *buf, size_t count, loff_t* f_pos);
static ssize_t freg_write(struct file* filp, const char __user *buf, size_t count, loff_t* f_pos);

static struct file_operations freg_fops = {
    .owner = THIS_MODULE,
    .open = freg_open,
    .release = freg_release,
    .read = freg_read,
    .write = freg_write,
};

#define SEQ_printf(m, x...)     \
    do {                \
        if (m)          \
            seq_printf(m, x);   \
        else            \
            pr_err(x);      \
    } while (0)

static int freg_proc_show(struct seq_file *m, void *v)
{
    SEQ_printf(m, "%d\n", freg_dev->val);
    return 0;
}

static int freg_proc_open(struct inode *inode, struct file *file)
{
    return single_open(file, freg_proc_show, inode->i_private);
}

static ssize_t __freg_set_val(struct fake_reg_dev* dev, const char* buf, size_t count){
    int val = 0;

    val = simple_strtol(buf, NULL, 10);

    if(down_interruptible(&(dev->sem))){
        return -ERESTARTSYS;
    }

    dev->val = val;
    up(&(dev->sem));

    return count;
}

/*
static ssize_t freg_proc_read(char* page, char** start, off_t off, int count, int* eof, void* data){
    if(off >0 ){
        *eof = 1;
        return 0;
    }

    return __freg_get_val(freg_dev, page);
}*/

static ssize_t freg_proc_write(struct file *filp, const char *ubuf, size_t cnt, loff_t *data){
    int err = 0;
    char* page = NULL;

    if(cnt > PAGE_SIZE){
        printk(KERN_ALERT"The buff is too large: %lu.\n", cnt);
        return -EFAULT;
    }

    page = (char*) __get_free_page(GFP_KERNEL);
    if(!page){
        printk(KERN_ALERT"Failed to alloc page.\n");
        return -ENOMEM;
    }

    if(copy_from_user(page, ubuf, cnt)){
        printk(KERN_ALERT"Failed to copy buff from user.\n");
        err = -EFAULT;
        goto out;
    }

    err = __freg_set_val(freg_dev, page, cnt);

out:
    free_page((unsigned long)page);
    return err;
}

static const struct file_operations freg_proc_fops = {
    .open = freg_proc_open,
    .write = freg_proc_write,
    .read = seq_read,
    .llseek = seq_lseek,
    .release = single_release,
};


static ssize_t freg_val_show(struct device* dev, struct device_attribute* attr, char* buf);
static ssize_t freg_val_store(struct device* dev, struct device_attribute* attr, const char* buf, size_t count);

static DEVICE_ATTR(val, S_IRUGO | S_IWUSR, freg_val_show, freg_val_store);

static int freg_open(struct inode * inode, struct file * filp){
    struct fake_reg_dev * dev;

    dev = container_of(inode->i_cdev, struct fake_reg_dev, dev);
    filp->private_data = dev;

    return 0;
}

static int freg_release(struct inode* inode, struct file* filp){
    return 0;
}

static ssize_t freg_read(struct file* filp, char __user *buf, size_t count, loff_t* f_pos){
    ssize_t err = 0;
    struct fake_reg_dev* dev = filp->private_data;

    if(down_interruptible(&(dev->sem))){
        return -ERESTARTSYS;
    }

    if(count < sizeof(dev->val)){
        goto out;
    }

    if(copy_to_user(buf, &(dev->val), sizeof(dev->val))){
        err = -EFAULT;
        goto out;
    }

    err = sizeof(dev->val);

out:
    up(&(dev->sem));
    return err;
}

static ssize_t freg_write(struct file * filp, const char __user * buf, size_t count, loff_t * f_pos){
    struct fake_reg_dev* dev = filp->private_data;
    ssize_t err = 0;

    if(down_interruptible(&(dev->sem))){
        return -ERESTARTSYS;
    }

    if(count != sizeof(dev->val)){
        goto out;
    }

    if(copy_from_user(&(dev->val), buf, count)){
        err = -EFAULT;
        goto out;
    }

    err = sizeof(dev->val);

out:
    up(&(dev->sem));
    return err;
}

static ssize_t __freg_get_val(struct fake_reg_dev* dev, char* buf){
    int val = 0;

    if(down_interruptible(&dev->sem)){
        return -ERESTARTSYS;
    }

    val = dev->val;
    up(&(dev->sem));

    return snprintf(buf, PAGE_SIZE, "%d\n", val);
}


static ssize_t freg_val_show(struct device* dev, struct device_attribute* attr, char* buf){
    struct fake_reg_dev* hdev = (struct fake_reg_dev*)dev_get_drvdata(dev);

    return __freg_get_val(hdev, buf);
}

static ssize_t freg_val_store(struct device*dev, struct device_attribute* attr, const char* buf, size_t count){
    struct fake_reg_dev* hdev = (struct fake_reg_dev*)dev_get_drvdata(dev);

    return __freg_set_val(hdev, buf, count);
}


static void freg_create_proc(void){
    proc_create(FREG_DEVICE_PROC_NAME, 0644, 0,  &freg_proc_fops);
}

static void freg_remove_proc(void){
    remove_proc_entry(FREG_DEVICE_PROC_NAME, NULL);
}

static int __freg_setup_dev(struct fake_reg_dev* dev){
    int err;
    dev_t devno = MKDEV(freg_major, freg_minor);

    memset(dev, 0, sizeof(struct fake_reg_dev));

    cdev_init(&(dev->dev), &freg_fops);
    dev->dev.owner = THIS_MODULE;
    dev->dev.ops = &freg_fops;

    err = cdev_add(&(dev->dev), devno, 1);
    if(err){
        return err;
    }

    //init_MUTEX(&(dev->sem));
    sema_init(&(dev->sem), 1);
    dev->val = 0;

    return 0;
}

static int __init freg_init(void){
    int err = -1;
    dev_t dev = 0;
    struct device* temp = NULL;

    printk(KERN_ALERT"Initializing freg device.\n");

    err = alloc_chrdev_region(&dev, 0, 1, FREG_DEVICE_NODE_NAME);
    if(err < 0){
        printk(KERN_ALERT"Failed to alloc char dev region.\n");
        goto fail;
    }

    freg_major = MAJOR(dev);
    freg_minor = MINOR(dev);

    freg_dev = kmalloc(sizeof(struct fake_reg_dev), GFP_KERNEL);
    if(!freg_dev){
        err = -ENOMEM;
        printk(KERN_ALERT"Failed to alloc freg device.\n");
        goto unregister;
    }

    err = __freg_setup_dev(freg_dev);
    if(err){
        printk(KERN_ALERT"Failed to setup freg device: %d.\n", err);
        goto cleanup;
    }

    freg_class = class_create(THIS_MODULE, FREG_DEVICE_CLASS_NAME);
    if(IS_ERR(freg_class)){
        err = PTR_ERR(freg_class);
        printk(KERN_ALERT"Failed to create freg device class.\n");
        goto destroy_cdev;
    }

    temp = device_create(freg_class, NULL, dev, NULL, "%s", FREG_DEVICE_FILE_NAME);
    if(IS_ERR(temp)){
        err = PTR_ERR(temp);
        printk(KERN_ALERT"Failed to create freg device.\n");
        goto destroy_class;
    }

    err = device_create_file(temp, &dev_attr_val);
    if(err < 0){
        printk(KERN_ALERT"Failed to create attribute val of freg device.\n");
        goto destroy_device;
    }

    dev_set_drvdata(temp, freg_dev);

    freg_create_proc();

    printk(KERN_ALERT"Succedded to initialize freg device.\n");

    return 0;

destroy_device:
    device_destroy(freg_class, dev);
destroy_class:
    class_destroy(freg_class);
destroy_cdev:
    cdev_del(&(freg_dev->dev));
cleanup:
    kfree(freg_dev);
unregister:
    unregister_chrdev_region(MKDEV(freg_major, freg_minor), 1);
fail:
    return err;
}

static void __exit freg_exit(void){
    dev_t devno = MKDEV(freg_major, freg_minor);

    printk(KERN_ALERT"Destory freg device.\n");

    freg_remove_proc();

    if(freg_class){
        device_destroy(freg_class, MKDEV(freg_major, freg_minor));
        class_destroy(freg_class);
    }

    if(freg_dev){
        cdev_del(&(freg_dev->dev));
        kfree(freg_dev);
    }

    unregister_chrdev_region(devno, 1);
}

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Fake Register Driver");

module_init(freg_init);
module_exit(freg_exit);
```
在kernel添加一个寄存器的驱动程序,具体代码的含义可以参考罗升阳的blog.

freg.h
```
#ifndef _FAKE_REG_H_
#define _FAKE_REG_H_

#include <linux/cdev.h>
#include <linux/semaphore.h>

#define FREG_DEVICE_NODE_NAME  "freg"
#define FREG_DEVICE_FILE_NAME  "freg"
#define FREG_DEVICE_PROC_NAME  "freg"
#define FREG_DEVICE_CLASS_NAME "freg"

struct fake_reg_dev {
    int val;
    struct semaphore sem;
    struct cdev dev;
};

#endif
```

Kconfig
```
config FREG
    tristate "Fake Register Driver"
    default y
    help
    This is the freg driver for android system.
```
这里设置为`default y`是让内核默认是编译的,若果设置成n就是默认不编译.按照罗大神的单编kernel没有成功,我有机器,没有使用模拟器

Makefile
```
obj-$(CONFIG_FREG) += freg.o
```
修改`android/kernel-3.18/drivers`下面的两个文件Kconfig和Makefile
在Kconfig文件
```
menu "Device Drivers"
```
下添加:
```
source "drivers/freg/Kconfig"
```
然后在android目录下进行make编译.如果在kernel-3.18目录下make过可能会导致整编不过,此时需要在把kernel-3.18下的目录下的.config文件删除.
编译成功进行刷机,`adb shell`进入机器,就可以看到里面有
```
proc/freg
sys/class/freg
dev/freg
```
dev查看需要root权限,可以先把机器root.dev目录下的访问需要权限,实现在添加HAL以及add到system_server时也会遇到SELinux的限制.这些在后续的相关文章有介绍.
