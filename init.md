# init进程学习笔记
**Android N平台**
***
涉及源码位置:  
aosp/system/core/init/init.cpp  
aosp/system/core/rootdir/init.rc  
aosp/system/core/init/property_service.cpp  
***
### 0 init进程的主要职责
- init如何创建zygote。  
- init的属性服务是如何工作的。  
### 1 init.cpp分析  
#### 1.1从init进程的入口函数main()开始分析  
```C++
int main(int argc, char** argv) {
    //和ueventd守护进程相关
    if (!strcmp(basename(argv[0]), "ueventd")) {
        return ueventd_main(argc, argv);
    }
    //和watchdogd守护进程相关
    if (!strcmp(basename(argv[0]), "watchdogd")) {
        return watchdogd_main(argc, argv);
    }
    //设置允许当前进程创建文件或者目录最大可操作的权限
    // Clear the umask.
    umask(0);
    //将环境中的'key-value'添加到当前的环境中来
    add_environment("PATH", _PATH_DEFPATH);

    bool is_first_stage = (argc == 1) || (strcmp(argv[1], "--second-stage") != 0);
    //创建一些文件夹,并挂载设备,和Linux相关
    // Get the basic filesystem setup we need put together in the initramdisk
    // on / and then we'll let the rc file figure out the rest.
    if (is_first_stage) {
        mount("tmpfs", "/dev", "tmpfs", MS_NOSUID, "mode=0755");
        mkdir("/dev/pts", 0755);
        mkdir("/dev/socket", 0755);
        mount("devpts", "/dev/pts", "devpts", 0, NULL);
        mount("proc", "/proc", "proc", 0, NULL);
        mount("sysfs", "/sys", "sysfs", 0, NULL);
    }

    // We must have some place other than / to create the device nodes for
    // kmsg and null, otherwise we won't be able to remount / read-only
    // later on. Now that tmpfs is mounted on /dev, we can actually talk
    // to the outside world.
    //重定向标准输入/输出/错误输出到/dev/_null_。
    open_devnull_stdio();
    //初始化klog
    klog_init();
    klog_set_level(KLOG_NOTICE_LEVEL);

    NOTICE("init%s started!\n", is_first_stage ? "" : " second stage");

    if (!is_first_stage) {
        // Indicate that booting is in progress to background fw loaders, etc.
        close(open("/dev/.booting", O_WRONLY | O_CREAT | O_CLOEXEC, 0000));
        //初始化属性相关资源
        property_init();

        // If arguments are passed both on the command line and in DT,
        // properties set in DT always have priority over the command-line ones.
        process_kernel_dt();
        process_kernel_cmdline();

        // Propogate the kernel variables to internal variables
        // used by init as well as the current required properties.
        export_kernel_boot_props();
    }
    //启动SELinux,加载策略文件
    // Set up SELinux, including loading the SELinux policy if we're in the kernel domain.
    selinux_initialize(is_first_stage);

    // If we're in the kernel domain, re-exec init to transition to the init domain now
    // that the SELinux policy has been loaded.
    if (is_first_stage) {
        if (restorecon("/init") == -1) {
            ERROR("restorecon failed: %s\n", strerror(errno));
            security_failure();
        }
        char* path = argv[0];
        char* args[] = { path, const_cast<char*>("--second-stage"), nullptr };
        if (execv(path, args) == -1) {
            ERROR("execv(\"%s\") failed: %s\n", path, strerror(errno));
            security_failure();
        }
    }

    // These directories were necessarily created before initial policy load
    // and therefore need their security context restored to the proper value.
    // This must happen before /dev is populated by ueventd.
    INFO("Running restorecon...\n");
    restorecon("/dev");
    restorecon("/dev/socket");
    restorecon("/dev/__properties__");
    restorecon_recursive("/sys");

    epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (epoll_fd == -1) {
        ERROR("epoll_create1 failed: %s\n", strerror(errno));
        exit(1);
    }

    signal_handler_init();

    property_load_boot_defaults();
    //启动属性服务
    start_property_service();
#ifdef BOOT_TRACE
    if (boot_trace) {
        ERROR("enable boot systrace...");
        property_set("debug.atrace.tags.enableflags", "0x1ffffe");
    }
#endif
    //解析init.rc配置文件
    init_parse_config_file("/init.rc");

    action_for_each_trigger("early-init", action_add_queue_tail);

    // Queue an action that waits for coldboot done so we know ueventd has set up all of /dev...
    queue_builtin_action(wait_for_coldboot_done_action, "wait_for_coldboot_done");
    // ... so that we can start queuing up actions that require stuff from /dev.
    queue_builtin_action(mix_hwrng_into_linux_rng_action, "mix_hwrng_into_linux_rng");
    queue_builtin_action(keychord_init_action, "keychord_init");
    queue_builtin_action(console_init_action, "console_init");

    // Trigger all the boot actions to get us started.
    action_for_each_trigger("init", action_add_queue_tail);

    // Repeat mix_hwrng_into_linux_rng in case /dev/hw_random or /dev/random
    // wasn't ready immediately after wait_for_coldboot_done
    queue_builtin_action(mix_hwrng_into_linux_rng_action, "mix_hwrng_into_linux_rng");

    // Don't mount filesystems or start core system services in charger mode.
    char bootmode[PROP_VALUE_MAX];
    if (property_get("ro.bootmode", bootmode) > 0 && strcmp(bootmode, "charger") == 0) {
        action_for_each_trigger("charger", action_add_queue_tail);
    } else {
        action_for_each_trigger("late-init", action_add_queue_tail);
    }

    // Run all property triggers based on current state of the properties.
    queue_builtin_action(queue_property_triggers_action, "queue_property_triggers");

    while (true) {
        if (!waiting_for_exec) {
            execute_one_command();
            restart_processes();
        }

        int timeout = -1;
        if (process_needs_restart) {
            timeout = (process_needs_restart - gettime()) * 1000;
            if (timeout < 0)
                timeout = 0;
        }

        if (!action_queue_empty() || cur_action) {
            timeout = 0;
        }

        bootchart_sample(&timeout);

        epoll_event ev;
        int nr = TEMP_FAILURE_RETRY(epoll_wait(epoll_fd, &ev, 1, timeout));
        if (nr == -1) {
            ERROR("epoll_wait failed: %s\n", strerror(errno));
        } else if (nr == 1) {
            ((void (*)()) ev.data.ptr)();
        }
    }

    return 0;
}
```
main函数里涉及不少东西,只是把当前知道的注释了一下,以后补充,这里关注一下,属性服务的启动,以及对init.rc文件的解析.  
#### 1.2 属性服务  
从上面的init.cpp的main函数中涉及属性服务的代码有  
```C++
    property_init();
    start_property_service();
```
从property_init()开始分析,该方法的主要工作是初始化属性服务配置.位置在`aosp/system/core/init/property_service.cpp`
```C++
void property_init() {
    if (property_area_initialized) {
        return;
    }

    property_area_initialized = true;
   //__system_property_area_init()函数是用来初始化属性内存区域
    if (__system_property_area_init()) {
        return;
    }

    pa_workspace.size = 0;
    pa_workspace.fd = open(PROP_FILENAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (pa_workspace.fd == -1) {
        ERROR("Failed to open %s: %s\n", PROP_FILENAME, strerror(errno));
        return;
    }
}
```
接下来查看start_property_service函数的具体代码：  
```C++
void start_property_service() {
    //创建一个非阻塞的socket,
    property_set_fd = create_socket(PROP_SERVICE_NAME, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK,
                                    0666, 0, 0, NULL);
    if (property_set_fd == -1) {
        ERROR("start_property_service socket creation failed: %s\n", strerror(errno));
        exit(1);
    }
    //使用listen函数对之前创建的socket进行监听
    listen(property_set_fd, 8);

    register_epoll_handler(property_set_fd, handle_property_set_fd);
}
```
`listen(property_set_fd, 8);`中的8指属性服务最多可以同时为8个试图设置属性的用户提供服务.property_set_fd代表监听
的端口(socket),这样属性服务就建立了.`register_epoll_handler(property_set_fd, handle_property_set_fd)`将`property_set_fd`
放入了epoll句柄中，用epoll来监听`property_set_fd`：当`property_set_fd`中有数据到来时，init进程将用`handle_property_set_fd`
函数进行处理。(网上资料说:在linux新的内核中，epoll用来替换select，epoll最大的好处在于它不会随着监听fd数目的增长而降低效率。
因为内核中的select实现是采用轮询来处理的，轮询的fd数目越多，自然耗时越多,epoll还没有研究过,抽时间学习一下).
当有`property_set_fd`这个socket有数据来时,就会产生调用到`handle_property_set_fd`方法,接着分析该方法:  
```C++
static void handle_property_set_fd()
{
    prop_msg msg;
    int s;
    int r;
    struct ucred cr;
    struct sockaddr_un addr;
    socklen_t addr_size = sizeof(addr);
    socklen_t cr_size = sizeof(cr);
    char * source_ctx = NULL;
    struct pollfd ufds[1];
    const int timeout_ms = 2 * 1000;  /* Default 2 sec timeout for caller to send property. */
    int nr;

    if ((s = accept(property_set_fd, (struct sockaddr *) &addr, &addr_size)) < 0) {
        return;
    }

    /* Check socket options here */
    if (getsockopt(s, SOL_SOCKET, SO_PEERCRED, &cr, &cr_size) < 0) {
        close(s);
        ERROR("Unable to receive socket options\n");
        return;
    }

    ufds[0].fd = s;
    ufds[0].events = POLLIN;
    ufds[0].revents = 0;
    nr = TEMP_FAILURE_RETRY(poll(ufds, 1, timeout_ms));
    if (nr == 0) {
        ERROR("sys_prop: timeout waiting for uid=%d to send property message.\n", cr.uid);
        close(s);
        return;
    } else if (nr < 0) {
        ERROR("sys_prop: error waiting for uid=%d to send property message: %s\n", cr.uid, strerror(errno));
        close(s);
        return;
    }

    r = TEMP_FAILURE_RETRY(recv(s, &msg, sizeof(msg), MSG_DONTWAIT));
    if(r != sizeof(prop_msg)) {
        ERROR("sys_prop: mis-match msg size received: %d expected: %zu: %s\n",
              r, sizeof(prop_msg), strerror(errno));
        close(s);
        return;
    }

    switch(msg.cmd) {
    case PROP_MSG_SETPROP:
        msg.name[PROP_NAME_MAX-1] = 0;
        msg.value[PROP_VALUE_MAX-1] = 0;

        if (!is_legal_property_name(msg.name, strlen(msg.name))) {
            ERROR("sys_prop: illegal property name. Got: \"%s\"\n", msg.name);
            close(s);
            return;
        }

        getpeercon(s, &source_ctx);

        if(memcmp(msg.name,"ctl.",4) == 0) {
            // Keep the old close-socket-early behavior when handling
            // ctl.* properties.
            close(s);
            if (check_control_mac_perms(msg.value, source_ctx)) {
#ifdef MTK_INIT
                //INFO("[PropSet]: pid:%u uid:%u gid:%u %s %s\n", cr.pid, cr.uid, cr.gid, msg.name, msg.value);
#endif
                handle_control_message((char*) msg.name + 4, (char*) msg.value);
            } else {
                ERROR("sys_prop: Unable to %s service ctl [%s] uid:%d gid:%d pid:%d\n",
                        msg.name + 4, msg.value, cr.uid, cr.gid, cr.pid);
            }
        } else {
            //check_perms:检测设置系统属性的权限,允许返回1,否则返回0
            if (check_perms(msg.name, source_ctx)) {
#ifdef MTK_INIT
                //INFO("[PropSet]: pid:%u uid:%u gid:%u set %s=%s\n", cr.pid, cr.uid, cr.gid, msg.name, msg.value);
                if(strcmp(msg.name, ANDROID_RB_PROPERTY) == 0) {
                    INFO("pid %d set %s=%s\n", cr.pid, msg.name, msg.value);
                    reboot_pid(cr.pid);
                }
#endif
                //设置系统属性
                property_set((char*) msg.name, (char*) msg.value);
            } else {
                ERROR("sys_prop: permission denied uid:%d  name:%s\n",
                      cr.uid, msg.name);
            }

            // Note: bionic's property client code assumes that the
            // property server will not close the socket until *AFTER*
            // the property is written to memory.
            close(s);
        }
        freecon(source_ctx);
        break;

    default:
        close(s);
        break;
    }
}

```
接着看`property_set((char*) msg.name, (char*) msg.value)`的具体实现:  
```C++
int property_set(const char* name, const char* value) {
    int rc = property_set_impl(name, value);
    if (rc == -1) {
        ERROR("property_set(\"%s\", \"%s\") failed\n", name, value);
    }
    return rc;
}
```
看来实现设置的活交给了`property_set_impl(name, value)`:  
```C++
static int property_set_impl(const char* name, const char* value) {
    size_t namelen = strlen(name);
    size_t valuelen = strlen(value);
    //判断属性名的合法性
    if (!is_legal_property_name(name, namelen)) return -1;
    if (valuelen >= PROP_VALUE_MAX) return -1;
    //如果属性的名称等于“selinux.reload_policy”，并且前面给它设置的值等于1，那么就表示要重新加载SEAndroid策略
    if (strcmp("selinux.reload_policy", name) == 0 && strcmp("1", value) == 0) {
        //加载SEAndroid策略
        if (selinux_reload_policy() != 0) {
            ERROR("Failed to reload policy\n");
        }
    } else if (strcmp("selinux.restorecon_recursive", name) == 0 && valuelen > 0) {
        if (restorecon_recursive(value) != 0) {
            ERROR("Failed to restorecon_recursive %s\n", value);
        }
    }
    //查找名称为name的属性，如果存在的话，那么就会得到一个类型为prop_info的结构体pi，否则返回Null
    prop_info* pi = (prop_info*) __system_property_find(name);

    if(pi != 0) {//属性如果存在
        /* ro.* properties may NEVER be modified once set */
        //如果属性是ro.开头，不能修改，直接返回．
        if(!strncmp(name, "ro.", 3)) {
           return -1;
        }
　　　　//属性可以修改，进行修改
        __system_property_update(pi, value, valuelen);
    } else {//属性不存在
        //属性不存在，添加该属性，在属性内存区域的属性值列表pa_info_array的最后增加一项
        int rc = __system_property_add(name, namelen, value, valuelen);
        if (rc < 0) {
            return rc;
        }
    }
    /* If name starts with "net." treat as a DNS property. */
    //接着处理net.开头的属性,
    //如果属性的名称是以“net.”开头，但是又不等于“net.change”(net.change是一个特殊的属性，记录网络属性是否发生变化)，那么就将名称为“net.change”的属性设置为name，表示网络属性发生了变化
    if (strncmp("net.", name, strlen("net.")) == 0)  {
        if (strcmp("net.change", name) == 0) {
           return 0;
        }
       /*
        * The 'net.change' property is a special property used track when any
        * 'net.*' property name is updated. It is _ONLY_ updated here. Its value
        * contains the last updated 'net.*' property.
        */
        //设置`net.change`属性
        property_set("net.change", name);
    } else if (persistent_properties_loaded &&
            strncmp("persist.", name, strlen("persist.")) == 0) {//对`persist.`属性进行操作,该属性应该是持久化储存到文件
        /*
         * Don't write properties to disk until after we have read all default properties
         * to prevent them from being overwritten by default values.
         */
　　　　//调用函数write_persistent_property执行持久化操作，以便系统下次启动后，可以将该属性的初始值设置为系统上次关闭时的值
        write_persistent_property(name, value);
    }
    //发送一个属性改变的通知，以便init进程可以执行在启动脚本init.rc中配置的操作
    property_changed(name, value);
    return 0;
}
```
`property_set_impl`对以ro、net和persist开头的属性进行不同的处理，给张来自罗升阳blog的一张图，帮助对android属性服务有个整体上的认识(Android属性的实现框架):  
![Android属性的实现框架](http://raw.githubusercontent.com/lqktz/document/master/res/init_property.png)
#### 1.3 读取init.rc文件  
init.rc是一个配置文件，内部由Android初始化语言编写（Android Init Language）编写的脚本，它主要包含五种类型语句：
Action、Commands、Services、Options和Import．完整的init文件比较长，这里重点分析Zygote的启动，后续要分析该进程． 
下面简单的用init.rc中的例子对Action、Commands、Services、Options和Import进行说明。
```
# Copyright (C) 2012 The Android Open Source Project
#
# IMPORTANT: Do not create world writable files or directories.
# This is a common source of Android security bugs.
#
#导入相关的初始化配置文件
import /init.environ.rc
import /init.usb.rc
#平台相关的如：高通、MTK
import /init.${ro.hardware}.rc
import /init.usb.configfs.rc
#导入初始化zygote进程的配置文件
import /init.${ro.zygote}.rc
#on 对应action，是启动，early-init市条件 write、mkdir、start是命令（commands）
on early-init
    # Set init and its forked children's oom_adj.
    write /proc/1/oom_score_adj -1000

    # Disable sysrq from keyboard
    write /proc/sys/kernel/sysrq 0

    # Set the security context of /adb_keys if present.
    restorecon /adb_keys

    # Shouldn't be necessary, but sdcard won't start without it. http://b/22568628.
    mkdir /mnt 0775 root system

    # Set the security context of /postinstall if present.
    restorecon /postinstall

    start ueventd
#每一个service对应一个新的进程，ueventd进程名，/sbin/ueventd进程的位置（程序执行的路径）也就是options，后面还可以跟参数，
#class、critical、seclabel都是命令
service ueventd /sbin/ueventd
    class core
    critical
    seclabel u:r:ueventd:s0
```
对于这些commands在Android源码中有文档说明，在`aosp/system/core/init/readme.txt`，每个命令都有对于的代码实现，接下来就会分析到。  
我们
