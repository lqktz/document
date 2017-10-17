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
init进程的main()函数会执行两次,分别是第一阶段和第二阶段,main函数会进入两次,只是两次进去执行的代码不一样  
```cpp
int main(int argc, char** argv) {
    //由于ueventd watchdogd是公用代码，所以启动的时候根据文件名来判断是哪个进程
    //和ueventd守护进程相关
    if (!strcmp(basename(argv[0]), "ueventd")) {
        return ueventd_main(argc, argv);
    }
    //和watchdogd守护进程相关
    if (!strcmp(basename(argv[0]), "watchdogd")) {
        return watchdogd_main(argc, argv);
    }

    // Clear the umask.
    umask(0);
    //添加环境变量 
    add_environment("PATH", _PATH_DEFPATH);

    bool is_first_stage = (argc == 1) || (strcmp(argv[1], "--second-stage") != 0);
    //创建文件夹，挂载设备,和linux相关
    // Get the basic filesystem setup we need put together in the initramdisk
    // on / and then we'll let the rc file figure out the rest.
    if (is_first_stage) {
        mount("tmpfs", "/dev", "tmpfs", MS_NOSUID, "mode=0755");
        mkdir("/dev/pts", 0755);
        mkdir("/dev/socket", 0755);
        mount("devpts", "/dev/pts", "devpts", 0, NULL);
        #define MAKE_STR(x) __STRING(x)
        mount("proc", "/proc", "proc", 0, "hidepid=2,gid=" MAKE_STR(AID_READPROC));
        mount("sysfs", "/sys", "sysfs", 0, NULL);
    }

    // We must have some place other than / to create the device nodes for
    // kmsg and null, otherwise we won't be able to remount / read-only
    // later on. Now that tmpfs is mounted on /dev, we can actually talk
    // to the outside world.
    //重定向标准输入/输出/错误输出到/dev/_null_　
    open_devnull_stdio();
    //对klog进行初始化，设置klog level为NOTICE，所以可以将NOTICE级别的log输出，而INFO级别的log就打印不出来
    //<http://blog.csdn.net/fu_kevin0606/article/details/53339001>
    //初始化klog
    klog_init();
    //设置klog的级别为NOTICE
    klog_set_level(KLOG_NOTICE_LEVEL);

    NOTICE("init %s started!\n", is_first_stage ? "first stage" : "second stage");

    if (!is_first_stage) {//第二阶段执行该代码
        // Indicate that booting is in progress to background fw loaders, etc.
        close(open("/dev/.booting", O_WRONLY | O_CREAT | O_CLOEXEC, 0000));
        //属性服务初始化，接下来会分析
        property_init();

        // If arguments are passed both on the command line and in DT,
        // properties set in DT always have priority over the command-line ones.
        process_kernel_dt();
        process_kernel_cmdline();

        // Propagate the kernel variables to internal variables
        // used by init as well as the current required properties.
        export_kernel_boot_props();
    }
    //初始化SELinux，加载策略文件
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
        //设置第二阶段的参数
        char* args[] = { path, const_cast<char*>("--second-stage"), nullptr };
        //当init是第一阶段,要通过execv重启init进程,进入init的第二阶段
        if (execv(path, args) == -1) {
            ERROR("execv(\"%s\") failed: %s\n", path, strerror(errno));
            security_failure();
        }
    }

    // These directories were necessarily created before initial policy load
    // and therefore need their security context restored to the proper value.
    // This must happen before /dev is populated by ueventd.
    NOTICE("Running restorecon...\n");
    restorecon("/dev");
    restorecon("/dev/socket");
    restorecon("/dev/__properties__");
    restorecon("/property_contexts");
    restorecon_recursive("/sys");

    epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (epoll_fd == -1) {
        ERROR("epoll_create1 failed: %s\n", strerror(errno));
        exit(1);
    }

    signal_handler_init();

    property_load_boot_defaults();
    export_oem_lock_status();
    //启动属性服务
    start_property_service();
    const BuiltinFunctionMap function_map;
    Action::set_function_map(&function_map);
    //将`service`,`on`,`import`分为３个section
    Parser& parser = Parser::GetInstance();
    parser.AddSectionParser("service",std::make_unique<ServiceParser>());
    parser.AddSectionParser("on", std::make_unique<ActionParser>());
    parser.AddSectionParser("import", std::make_unique<ImportParser>());
    //解析init.rc配置文件入口
    parser.ParseConfig("/init.rc");

    ActionManager& am = ActionManager::GetInstance();

    am.QueueEventTrigger("early-init");

    // Queue an action that waits for coldboot done so we know ueventd has set up all of /dev...
    am.QueueBuiltinAction(wait_for_coldboot_done_action, "wait_for_coldboot_done");
    // ... so that we can start queuing up actions that require stuff from /dev.
    am.QueueBuiltinAction(mix_hwrng_into_linux_rng_action, "mix_hwrng_into_linux_rng");
    am.QueueBuiltinAction(set_mmap_rnd_bits_action, "set_mmap_rnd_bits");
    am.QueueBuiltinAction(keychord_init_action, "keychord_init");
    am.QueueBuiltinAction(console_init_action, "console_init");

    // Trigger all the boot actions to get us started.
    am.QueueEventTrigger("init");

    // Repeat mix_hwrng_into_linux_rng in case /dev/hw_random or /dev/random
    // wasn't ready immediately after wait_for_coldboot_done
    am.QueueBuiltinAction(mix_hwrng_into_linux_rng_action, "mix_hwrng_into_linux_rng");

    // Don't mount filesystems or start core system services in charger mode.
    std::string bootmode = property_get("ro.bootmode");
    if (bootmode == "charger") {
        am.QueueEventTrigger("charger");
    } else {
        am.QueueEventTrigger("late-init");
    }

    // Run all property triggers based on current state of the properties.
    am.QueueBuiltinAction(queue_property_triggers_action, "queue_property_triggers");

    while (true) {
        if (!waiting_for_exec) {
            am.ExecuteOneCommand();
            restart_processes();
        }

        int timeout = -1;
        if (process_needs_restart) {
            timeout = (process_needs_restart - gettime()) * 1000;
            if (timeout < 0)
                timeout = 0;
        }

        if (am.HasMoreCommands()) {
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
Android中有很多属性,是通过属性服务(property service)来管理它们的.接着来分析属性服务的代码,从上面的init.cpp的main函数中涉及属性服务的代码有  
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

    ufds[0].fd = s；
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
***
**init.rc简单介绍**  
init.rc是一个配置文件，内部由Android初始化语言编写（Android Init Language）编写的脚本，它主要包含五种类型语句：
Action、Commands、Services、Options和Import．在init.rc文件中一条语句通常占用一行，单词之间是用空格符来相隔的。
如果一行写不下，可以在行尾加上反斜杠，来连接下一行。也就是说，可以用反斜杠将多行代码连接成一行代码。并且使用#
来进行注释。在init.rc中分成三个部分（Section），而每一部分的开头需要指定on（Actions）、service（Services）或
import。也就是说，每一个Actions, import或 Services确定一个Section。而所有的Commands和Options只能属于最近定义的
Section。如果Commands和 Options在第一个Section之前被定义，它们将被忽略。Actions和Services的名称必须唯一。如果
有两个或多个Actions或Services拥有同样的名称，那么init在执行它们时将抛出错误，并忽略这些Action和Service。
完整的init文件比较长，这里重点分析Zygote的启动，后续要分析该进程． 
下面简单的用init.rc中的例子对Action、Commands、Services、Options和Import进行说明。
```shell
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
    //core 是服务的组，同样名字的会在一起被启动
    class core
    critical
    seclabel u:r:ueventd:s0
```
对于这些commands在Android源码中有文档说明，在`aosp/system/core/init/readme.txt`，每个命令都有对于的代码实现，接下来就会分析到.
***
有了对`init.rc`文件的简单认识，回到`init.cpp`中，解析`init.rc`代码的位置，解析`init.rc`主要任务由`aosp/system/core/init/init_parser.cpp`实现．
开始分析:  
```C++
    Parser& parser = Parser::GetInstance();
    parser.AddSectionParser("service",std::make_unique<ServiceParser>());
    parser.AddSectionParser("on", std::make_unique<ActionParser>());
    parser.AddSectionParser("import", std::make_unique<ImportParser>());
```
`Parser::GetInstance()`的实现在`aosp/system/core/init/init_parser.cpp`:  
```C++
Parser& Parser::GetInstance() {
    static Parser instance;
    return instance;
}
```
`parser.AddSectionParser`同样在`aosp/system/core/init/init_parser.cpp`:  
```C++
void Parser::AddSectionParser(const std::string& name,
                              std::unique_ptr<SectionParser> parser) {
    section_parsers_[name] = std::move(parser);
}
```
这就是将`service`,`on`,`import`设置为了３个Section.  
```C++
parser.ParseConfig("/init.rc");
```
这就是解析init.rc函数的入口，在`init_parser.cpp`里面:  
```C++
bool Parser::ParseConfig(const std::string& path) {
    if (is_dir(path.c_str())) {//路径是文件夹，调用解析文件夹的函数处理
        return ParseConfigDir(path);
    }
    //解析init.rc
    return ParseConfigFile(path);
}
```
调用了ParseConfigFile(path):  
```C++
bool Parser::ParseConfigFile(const std::string& path) {
    INFO("Parsing file %s...\n", path.c_str());
    //用于记录解析init.rc的耗时
    Timer t;
    std::string data;
    if (!read_file(path.c_str(), &data)) {
        return false;
    }

    data.push_back('\n'); // TODO: fix parse_config.
    //解析rc文件内容
    ParseData(path, data);
    for (const auto& sp : section_parsers_) {
        //EndFile在Import_parse.cpp
        sp.second->EndFile(path);
    }

    // Turning this on and letting the INFO logging be discarded adds 0.2s to
    // Nexus 9 boot time, so it's disabled by default.
    if (false) DumpState();
    //打印出解析文件的耗时，用来查找耗时操作
    NOTICE("(Parsing %s took %.2fs.)\n", path.c_str(), t.duration());
    return true;
}
```
在该方法中调用的主要的方法有`ParseData`,`EndFile`接下来分别对这两部分进行分析，`ParseData`:  
```C++
void Parser::ParseData(const std::string& filename, const std::string& data) {
    //TODO: Use a parser with const input and remove this copy
    //copy数据
    std::vector<char> data_copy(data.begin(), data.end());
    data_copy.push_back('\0');

    parse_state state;
    state.filename = filename.c_str();
    state.line = 0;
    state.ptr = &data_copy[0];
    state.nexttoken = 0;

    SectionParser* section_parser = nullptr;
    std::vector<std::string> args;

    for (;;) {//循环遍历解析init.rc文件内容
        //next_token在system/core/init/parse.cpp
        switch (next_token(&state)) {
        case T_EOF:
            if (section_parser) {
                section_parser->EndSection();
            }
            return;
        case T_NEWLINE:
            state.line++;
            if (args.empty()) {
                break;
            }
            if (section_parsers_.count(args[0])) {
                if (section_parser) {
                    //Section解析完成
                    section_parser->EndSection();
                }
                section_parser = section_parsers_[args[0]].get();
                std::string ret_err;
                //解析Action,Service, Import 三个Section
                if (!section_parser->ParseSection(args, &ret_err)) {
                    parse_error(&state, "%s\n", ret_err.c_str());
                    section_parser = nullptr;
                }
            } else if (section_parser) {
                std::string ret_err;
                //解析section的内容
                if (!section_parser->ParseLineSection(args, state.filename,
                                                      state.line, &ret_err)) {
                    parse_error(&state, "%s\n", ret_err.c_str());
                }
            }
            args.clear();
            break;
        case T_TEXT:
            args.emplace_back(state.text);
            break;
        }
    }
}
```
重点分析`!section_parser->ParseSection(args, &ret_err)`,`section_parser->ParseLineSection`,`ParseSection`方法在`action`,`service`,
`import`三个不同的section调用的位置不同:  
`action`-->`aosp/system/core/init/action.cpp`:  
`service`-->`aosp/system/core/init/service.cpp`  
`import`-->`aosp/system/core/init/import_parser.cpp`  
section_parser->ParseLineSection方法在`action`,`service`中嵌套在里面分析
依次分析这对应的三个`ParseSection`方法:  
***  
**action ParseSection解析**  
ParseSection：
```C++
bool ActionParser::ParseSection(const std::vector<std::string>& args,
                                std::string* err) {
    //将on后面的trigger触发执行条件保存在triggers中
    std::vector<std::string> triggers(args.begin() + 1, args.end());
    //如果一个on后面没有trigger将会报错，必须要有一个
    if (triggers.size() < 1) {
        *err = "actions must have a trigger";
        return false;
    }

    auto action = std::make_unique<Action>(false);
    if (!action->InitTriggers(triggers, err)) {
        return false;
    }

    action_ = std::move(action);
    return true;
}
```
ParseLineSection：
```C++
bool ActionParser::ParseLineSection(const std::vector<std::string>& args,
                                    const std::string& filename, int line,
                                    std::string* err) const {
    return action_ ? action_->AddCommand(args, filename, line, err) : false;
}
```
调用了AddCommand:  
```C++
bool Action::AddCommand(const std::vector<std::string>& args,
                        const std::string& filename, int line, std::string* err) {
    if (!function_map_) {
        *err = "no function map available";
        return false;
    }

    if (args.empty()) {
        *err = "command needed, but not provided";
        return false;
    }

    auto function = function_map_->FindFunction(args[0], args.size() - 1, err);
    if (!function) {
        return false;
    }

    AddCommand(function, args, filename, line);
    return true;
}
```
接着调用了AddCommand(function, args, filename, line):  
```C++
void Action::AddCommand(BuiltinFunction f,
                        const std::vector<std::string>& args,
                        const std::string& filename, int line) {
    commands_.emplace_back(f, args, filename, line);
}
```

**service ParseSection解析**  
ParseSection:
```C++
bool ServiceParser::ParseSection(const std::vector<std::string>& args,
                                 std::string* err) {
    //检查参数个数是否合法
    if (args.size() < 3) {
        *err = "services must have a name and a program";
        return false;
    }

    const std::string& name = args[1];
    //检查定义的Service名字的合法性
    if (!IsValidName(name)) {
        *err = StringPrintf("invalid service name '%s'", name.c_str());
        return false;
    }
    //获取执行文件位置和参数，也就是除了service和service名其他的参数
    std::vector<std::string> str_args(args.begin() + 2, args.end());
    //给service赋值
    service_ = std::make_unique<Service>(name, "default", str_args);
    return true;
}
```
定义的每个service都是一个新的进程，定义service还commands,这些commands和执行他们的方法对应关系定义是:  
```C++
Service::OptionHandlerMap::Map& Service::OptionHandlerMap::map() const {
    constexpr std::size_t kMax = std::numeric_limits<std::size_t>::max();
    static const Map option_handlers = {
        {"class",       {1,     1,    &Service::HandleClass}},
        {"console",     {0,     0,    &Service::HandleConsole}},
        {"critical",    {0,     0,    &Service::HandleCritical}},
        {"disabled",    {0,     0,    &Service::HandleDisabled}},
        {"group",       {1,     NR_SVC_SUPP_GIDS + 1, &Service::HandleGroup}},
        {"ioprio",      {2,     2,    &Service::HandleIoprio}},
        {"keycodes",    {1,     kMax, &Service::HandleKeycodes}},
        {"oneshot",     {0,     0,    &Service::HandleOneshot}},
        {"onrestart",   {1,     kMax, &Service::HandleOnrestart}},
        {"seclabel",    {1,     1,    &Service::HandleSeclabel}},
        {"setenv",      {2,     2,    &Service::HandleSetenv}},
        {"socket",      {3,     6,    &Service::HandleSocket}},
        {"user",        {1,     1,    &Service::HandleUser}},
        {"writepid",    {1,     kMax, &Service::HandleWritepid}},
    };
    return option_handlers;
}
```
ParseLineSection:  
```C++
bool ServiceParser::ParseLineSection(const std::vector<std::string>& args,
                                     const std::string& filename, int line,
                                     std::string* err) const {
    return service_ ? service_->HandleLine(args, err) : false;  //service_为true, 调用HandleLine
}
```
接着调用了HandleLine:  
```C++
bool Service::HandleLine(const std::vector<std::string>& args, std::string* err) {
    if (args.empty()) {
        *err = "option needed, but not provided";
        return false;
    }

    static const OptionHandlerMap handler_map;   //获得option对应的函数表
    auto handler = handler_map.FindFunction(args[0], args.size() - 1, err); //根据option获取对应的函数名

    if (!handler) {
        return false;
    }

    return (this->*handler)(args, err);   
}
```
EndSection:  
```C++
void ServiceParser::EndSection() {
    if (service_) {
        ServiceManager::GetInstance().AddService(std::move(service_));
    }
}
```
```C++
void ServiceManager::AddService(std::unique_ptr<Service> service) {
    Service* old_service = FindServiceByName(service->name());
    if (old_service) {    //service已经被定义过了就抛弃
        ERROR("ignored duplicate definition of service '%s'",
              service->name().c_str());
        return;
    }
    services_.emplace_back(std::move(service));  //将service添加services_列表
}
```
**import ParseSection解析**  
```C++
bool ImportParser::ParseSection(const std::vector<std::string>& args,
                                std::string* err) {
    //import 命令是2参数的，如果参数个数不对就直接报错
    if (args.size() != 2) {
        *err = "single argument needed for import\n";
        return false;
    }

    std::string conf_file;
    //第一个参数都是import，args[1]才是要导入的配置文件conf_file
    bool ret = expand_props(args[1], &conf_file);
    if (!ret) {
        *err = "error while expanding import";
        return false;
    }

    INFO("Added '%s' to import list\n", conf_file.c_str());
    //将所有的conf_file添加到imports_列表
    imports_.emplace_back(std::move(conf_file));
    return true;
}
```
终于把`ParseData`方法粗略的过了一遍，接下来分析`EndFile`，该方法其实就在`import_parser.cpp`中:  
```C++
void ImportParser::EndFile(const std::string& filename) {
    auto current_imports = std::move(imports_);  //获取imports_
    imports_.clear();   //将imports_列表清空
    for (const auto& s : current_imports) {  //遍历列表
        if (!Parser::GetInstance().ParseConfig(s)) {   //调用ParseConfig函数,对其他配置进行解析, 流程遇上面的相同
            ERROR("could not import file '%s' from '%s': %s\n",
                  s.c_str(), filename.c_str(), strerror(errno));
        }
    }
}
```
到此，init.rc文件的解析工作完成，接下来的的工作就是执行这些配置，由于init.rc里面配置了太多，接下来以Zygote这个service为例，分析．  
在`init.rc`里`import /init.${ro.zygote}.rc`,这就引入了不同的zygote配置:  
```
init.zygote32_64.rc  init.zygote32.rc     init.zygote64_32.rc  init.zygote64.rc
```
这里以`init.zygote32.rc`为例:  
```shell
#zygote是进程
service zygote /system/bin/app_process -Xzygote /system/bin --zygote --start-system-server
    #启动组名，同样名字的一起启动
    class main
    socket zygote stream 660 root system
    #onrestart表示zygote重启时需要执行的命令
    onrestart write /sys/android_power/request_state wake
    onrestart write /sys/power/state on
    onrestart restart audioserver
    onrestart restart cameraserver
    onrestart restart media
    onrestart restart netd
    writepid /dev/cpuset/foreground/tasks /dev/stune/foreground/tasks
```
通过对这个叫zygote的service的解析之后,在init.rc配置文件中配置了怎么去启动zygote:  
```
on nonencrypted
    # A/B update verifier that marks a successful boot.
    exec - root -- /system/bin/update_verifier nonencrypted
    #通过class_start方法启动了main(这就是zygote的中配置的)
    class_start main
    class_start late_start
```
找到`class_start`对应执行的函数就可以接着分析了，对应关系就在`aosp/system/core/init/builtins.cpp`:  
```C++
ltinFunctionMap::Map& BuiltinFunctionMap::map() const {
    constexpr std::size_t kMax = std::numeric_limits<std::size_t>::max();
    static const Map builtin_functions = {
        {"bootchart_init",          {0,     0,    do_bootchart_init}},
        {"chmod",                   {2,     2,    do_chmod}},
        {"chown",                   {2,     3,    do_chown}},
        {"class_reset",             {1,     1,    do_class_reset}},
        {"class_start",             {1,     1,    do_class_start}},
        {"class_stop",              {1,     1,    do_class_stop}},
        {"copy",                    {2,     2,    do_copy}},
        {"domainname",              {1,     1,    do_domainname}},
        {"enable",                  {1,     1,    do_enable}},
        {"exec",                    {1,     kMax, do_exec}},
        {"export",                  {2,     2,    do_export}},
        {"hostname",                {1,     1,    do_hostname}},
        {"ifup",                    {1,     1,    do_ifup}},
        {"init_user0",              {0,     0,    do_init_user0}},
        {"insmod",                  {1,     kMax, do_insmod}},
        {"installkey",              {1,     1,    do_installkey}},
        {"load_persist_props",      {0,     0,    do_load_persist_props}},
        {"load_system_props",       {0,     0,    do_load_system_props}},
        {"loglevel",                {1,     1,    do_loglevel}},
        {"mkdir",                   {1,     4,    do_mkdir}},
        {"mount_all",               {1,     kMax, do_mount_all}},
        {"mount",                   {3,     kMax, do_mount}},
        {"powerctl",                {1,     1,    do_powerctl}},
        {"restart",                 {1,     1,    do_restart}},
        {"restorecon",              {1,     kMax, do_restorecon}},
        {"restorecon_recursive",    {1,     kMax, do_restorecon_recursive}},
        {"rm",                      {1,     1,    do_rm}},
        {"rmdir",                   {1,     1,    do_rmdir}},
        {"setprop",                 {2,     2,    do_setprop}},
        {"setrlimit",               {3,     3,    do_setrlimit}},
        {"start",                   {1,     1,    do_start}},
        {"stop",                    {1,     1,    do_stop}},
        {"swapon_all",              {1,     1,    do_swapon_all}},
        {"symlink",                 {2,     2,    do_symlink}},
        {"sysclktz",                {1,     1,    do_sysclktz}},
        {"trigger",                 {1,     1,    do_trigger}},
        {"verity_load_state",       {0,     0,    do_verity_load_state}},
        {"verity_update_state",     {0,     0,    do_verity_update_state}},
        {"wait",                    {1,     2,    do_wait}},
        {"write",                   {2,     2,    do_write}},
    };
    return builtin_functions;
}
```
对于在rc配置文件中的commands都对应一个方法函数，可以通过`grep -nr "<command>" .`在`aosp/system/core/init/`中搜索．
找到需要的对应关系:  
```
        {"class_start",             {1,     1,    do_class_start}},
```
进入do_class_start方法:  
```C++
static int do_class_start(const std::vector<std::string>& args) {
        /* Starting a class does not start services
         * which are explicitly disabled.  They must
         * be started individually.
         */
    ServiceManager::GetInstance().
        ForEachServiceInClass(args[1], [] (Service* s) { s->StartIfNotDisabled(); });
    return 0;
}
```
接着看StartIfNotDisabled(),位置`aosp/system/core/init/service.cpp`:   
```cpp
bool Service::StartIfNotDisabled() {
    if (!(flags_ & SVC_DISABLED)) {
        return Start();
    } else {
        flags_ |= SVC_DISABLED_START;
    }
    return true;
}
```
还调了Start()，接着看吧:  
``cpp
bool Service::Start() {
   ......
   //判断需要启动的Service的对应的执行文件是否存在，不存在则不启动该Service
   struct stat sb;
    if (stat(args_[0].c_str(), &sb) == -1) {
        ERROR("cannot find '%s' (%s), disabling '%s'\n",
              args_[0].c_str(), strerror(errno), name_.c_str());
        flags_ |= SVC_DISABLED;
        return false;
    }
    ......
    //每一个service都是一个新进程，必然需要fork
    pid_t pid = fork();
    if (pid == 0) {
        umask(077);

        for (const auto& ei : envvars_) {
            add_environment(ei.name.c_str(), ei.value.c_str());
        }

        for (const auto& si : sockets_) {
            int socket_type = ((si.type == "stream" ? SOCK_STREAM :
                                (si.type == "dgram" ? SOCK_DGRAM :
                                 SOCK_SEQPACKET)));
            const char* socketcon =
                !si.socketcon.empty() ? si.socketcon.c_str() : scon.c_str();

            int s = create_socket(si.name.c_str(), socket_type, si.perm,
                                  si.uid, si.gid, socketcon);
            if (s >= 0) {
                PublishSocket(si.name, s);
            }
        }
        ......
        //execve执行程序，在`init.zygote32.rc`里写了zygote的进程程序的位置以及参数
       if (execve(args_[0].c_str(), (char**) &strs[0], (char**) ENV) < 0) {
            ERROR("cannot execve('%s'): %s\n", args_[0].c_str(), strerror(errno));
        }

        _exit(127);
    }
    ......
    NotifyStateChange("running");
    return true;
}

```
在fork出来的新的子进程里就会进入java层面，`aosp/frameworks/base/cmds/app_process/app_main.cpp`的main()函数:  
```C++
int main(int argc, char* const argv[])
{
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) {
        // Older kernels don't understand PR_SET_NO_NEW_PRIVS and return
        // EINVAL. Don't die on such kernels.
        if (errno != EINVAL) {
            LOG_ALWAYS_FATAL("PR_SET_NO_NEW_PRIVS failed: %s", strerror(errno));
            return 12;
        }
    }
    AppRuntime runtime(argv[0], computeArgBlockSize(argc, argv));
    // Process command line arguments
    // ignore argv[0]
    argc--;
    argv++;
    ......
    if (!niceName.isEmpty()) {
        runtime.setArgv0(niceName.string());
        //设置进程名为zygote
        set_process_name(niceName.string());
    }

    if (zygote) {//经过一系列的初始化和参数判断，会调用到这里
        runtime.start("com.android.internal.os.ZygoteInit", args, zygote);
    } else if (className) {//与上面的启动方式对比,两者在native层实现相同,区别在java层,ZygoteInit比RuntimeInit多做不少事情
        runtime.start("com.android.internal.os.RuntimeInit", args, zygote);
    } else {
        fprintf(stderr, "Error: no class name or --zygote supplied.\n");
        app_usage();
        LOG_ALWAYS_FATAL("app_process: no class name or --zygote supplied.");
        return 10;
    }
}
```
最终使用runtime.start执行"com.android.internal.os.ZygoteInit",接着分析runtime.start的具体实现.runtime是AppRuntime类,可是AppRuntime
类没有start方法,于是找到AppRuntime的父类AndroidRuntime的start方法:  
```cpp
void AndroidRuntime::start(const char* className, const Vector<String8>& options, bool zygote)
{
    ALOGD(">>>>>> START %s uid %d <<<<<<\n",
            className != NULL ? className : "(unknown)", getuid());


    static const String8 startSystemServer("start-system-server");

    /*
     * 'startSystemServer == true' means runtime is obsolete and not run from
     * init.rc anymore, so we print out the boot start event here.
     */
    for (size_t i = 0; i < options.size(); ++i) {
        if (options[i] == startSystemServer) {
           /* track our progress through the boot sequence */
           const int LOG_BOOT_PROGRESS_START = 3000;
           LOG_EVENT_LONG(LOG_BOOT_PROGRESS_START,  ns2ms(systemTime(SYSTEM_TIME_MONOTONIC)));
        }
    }

    const char* rootDir = getenv("ANDROID_ROOT");
    if (rootDir == NULL) {
        rootDir = "/system";
        if (!hasDir("/system")) {
            LOG_FATAL("No root directory specified, and /android does not exist.");
            return;
        }
        setenv("ANDROID_ROOT", rootDir, 1);
    }

    //const char* kernelHack = getenv("LD_ASSUME_KERNEL");
    //ALOGD("Found LD_ASSUME_KERNEL='%s'\n", kernelHack);

    /* start the virtual machine */
    JniInvocation jni_invocation;
    jni_invocation.Init(NULL);
    JNIEnv* env;
    //启动虚拟机
    if (startVm(&mJavaVM, &env, zygote) != 0) {
        return;
    }
    onVmCreated(env);
    //注册JNI方法到虚拟机
    /*
     * Register android functions.
     */
    if (startReg(env) < 0) {
        ALOGE("Unable to register all android natives\n");
        return;
    }

    /*
     * We want to call main() with a String array with arguments in it.
     * At present we have two arguments, the class name and an option string.
     * Create an array to hold them.
     */
    jclass stringClass;
    jobjectArray strArray;
    jstring classNameStr;

    stringClass = env->FindClass("java/lang/String");
    assert(stringClass != NULL);
    strArray = env->NewObjectArray(options.size() + 1, stringClass, NULL);
    assert(strArray != NULL);
    classNameStr = env->NewStringUTF(className);
    assert(classNameStr != NULL);
    env->SetObjectArrayElement(strArray, 0, classNameStr);

    for (size_t i = 0; i < options.size(); ++i) {
        jstring optionsStr = env->NewStringUTF(options.itemAt(i).string());
        assert(optionsStr != NULL);
        env->SetObjectArrayElement(strArray, i + 1, optionsStr);
    }

    /*
     * Start VM.  This thread becomes the main thread of the VM, and will
     * not return until the VM exits.
     */
    char* slashClassName = toSlashClassName(className);
    jclass startClass = env->FindClass(slashClassName);
    if (startClass == NULL) {
        ALOGE("JavaVM unable to locate class '%s'\n", slashClassName);
        /* keep going */
    } else {
        jmethodID startMeth = env->GetStaticMethodID(startClass, "main",
            "([Ljava/lang/String;)V");
        if (startMeth == NULL) {
            ALOGE("JavaVM unable to find main() in '%s'\n", className);
            /* keep going */
        } else {//启动com.android.internal.os.ZygoteInit
            env->CallStaticVoidMethod(startClass, startMeth, strArray);

#if 0
            if (env->ExceptionCheck())
                threadExitUncaughtException(env);
#endif
        }
    }
    free(slashClassName);

    ALOGD("Shutting down VM\n");
    if (mJavaVM->DetachCurrentThread() != JNI_OK)
        ALOGW("Warning: unable to detach main thread\n");
    mVMShutdown = true;
    if (mJavaVM->DestroyJavaVM() != 0)
        ALOGW("Warning: VM did not shut down cleanly\n");
}
```
该方法做了一下几件事:  

- 启动java虚拟机   
- 将JNI方法注册到java虚拟机  
- 进入到ZygoteInit.java的main()方法  

进入到ZygoteInit.java也就是进入到java层,在分析Zygote的启动过程中再接着分析,这里告一段落.需要注意的是Zygote进程的启动是在解析init.Zygote32.rc开始的,
到这里还没有完成,只是到这,C++层的执行完了.在另一篇介绍Zygote启动的文章中,再接着ZygoteInit.java的main()分析,从java层分析.在C++层只是讲Zygote进程创建,
但是什么活也没干,干活是在java层面,因此文章将zygote进程的分析从此处分成两个部分,同事也是为了让文章内容是以init进程分析为主.  

###总结  
本文主要分析了,init进程的启动,主要分析了一下内容:   

- init进程启动属性服务的过程,分析了属性服务建立过程  
- init进程对rc配置文件的解析,分为对import,action,service,commands的的解析  
- 以zygote进程为例子,分析了作为service被解析之后的执行过程,一直到调用到java层的过程  

###参考blog  
<http://blog.csdn.net/fu_kevin0606/article/details/53339001>  
<http://blog.csdn.net/innost/article/details/47204675>  
<http://blog.csdn.net/luoshengyang/article/details/38102011>  
<http://blog.csdn.net/itachi85/article/details/54783506>  
<http://blog.csdn.net/kc58236582/article/details/52247547>  
<http://blog.csdn.net/fu_kevin0606/article/details/53320515>  
