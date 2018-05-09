#adb bugreport 命令源码分析

时间: 2018/04/28

**平台Android 8.1**

## 1. adb 端的代码

使用bugreport,`abd bugreport > bugreport.log`,在adb的代码查找入口:
### 1.1 command 检测
`system/core/adb/commandline.cpp`
```
if (!strcmp(argv[0], "bugreport")) {
        Bugreport bugreport;
        return bugreport.DoIt(argc, argv);
}
```
上面的`bugreport.DoIt`方法在`system/core/adb/bugreport.cpp`:  
```
int Bugreport::DoIt(int argc, const char** argv) {
    if (argc > 2) return syntax_error("adb bugreport [PATH]");

    // Gets bugreportz version.
    std::string bugz_stdout, bugz_stderr;
    DefaultStandardStreamsCallback version_callback(&bugz_stdout, &bugz_stderr);
    int status = SendShellCommand("bugreportz -v", false, &version_callback);
    std::string bugz_version = android::base::Trim(bugz_stderr);
    std::string bugz_output = android::base::Trim(bugz_stdout);

    if (status != 0 || bugz_version.empty()) {
        D("'bugreportz' -v results: status=%d, stdout='%s', stderr='%s'", status,
          bugz_output.c_str(), bugz_version.c_str());
        if (argc == 1) {
            // Device does not support bugreportz: if called as 'adb bugreport', just falls out to
            // the flat-file version.
            fprintf(stderr,
                    "Failed to get bugreportz version, which is only available on devices "
                    "running Android 7.0 or later.\nTrying a plain-text bug report instead.\n");
            return SendShellCommand("bugreport", false);
        }

        // But if user explicitly asked for a zipped bug report, fails instead (otherwise calling
        // 'bugreport' would generate a lot of output the user might not be prepared to handle).
        fprintf(stderr,
                "Failed to get bugreportz version: 'bugreportz -v' returned '%s' (code %d).\n"
                "If the device does not run Android 7.0 or above, try 'adb bugreport' instead.\n",
                bugz_output.c_str(), status);
        return status != 0 ? status : -1;
    }

    std::string dest_file, dest_dir;

    // 设置bugreport的生成文件存储位置
    if (argc == 1) {
        // No args - use current directory
        if (!getcwd(&dest_dir)) {
            perror("adb: getcwd failed");
            return 1;
        }
    } else {
        // Check whether argument is a directory or file
        if (directory_exists(argv[1])) {
            dest_dir = argv[1];
        } else {
            dest_file = argv[1];
        }
    }
    // 设置bugreport的生成文件的名称
    if (dest_file.empty()) {
        // Uses a default value until device provides the proper name
        dest_file = "bugreport.zip";
    } else {
        if (!android::base::EndsWithIgnoreCase(dest_file, ".zip")) {
            dest_file += ".zip";
        }
    }

    bool show_progress = true;
    std::string bugz_command = "bugreportz -p";

    // bugreportz 的版本使用bugreportz -v查看,android8.1上是1.1版本
    if (bugz_version == "1.0") {
        // 1.0 does not support progress notifications, so print a disclaimer
        // message instead.
        fprintf(stderr,
                "Bugreport is in progress and it could take minutes to complete.\n"
                "Please be patient and do not cancel or disconnect your device "
                "until it completes.\n");
        show_progress = false;
        bugz_command = "bugreportz";
    }
    BugreportStandardStreamsCallback bugz_callback(dest_dir, dest_file, show_progress, this);
    return SendShellCommand(bugz_command, false, &bugz_callback);
}
```
上面的代码进行了命令合法性的检测和默认设置,最后是使用了SendShellCommand命令,
`SendShellCommand("bugreportz -v", false, &version_callback)`和`SendShellCommand(bugz_command, false, &bugz_callback)`
接下来跟代码:
```
int Bugreport::SendShellCommand(const std::string& command, bool disable_shell_protocol,
                                StandardStreamsCallbackInterface* callback) {
    return send_shell_command(command, disable_shell_protocol, callback);
}
```
### 1.2 send_shell_command
`system/core/adb/commandline.cpp`
```
int send_shell_command(const std::string& command, bool disable_shell_protocol,
                       StandardStreamsCallbackInterface* callback) {
    int fd;
    bool use_shell_protocol = false;

    while (true) {
        bool attempt_connection = true;

        // Use shell protocol if it's supported and the caller doesn't explicitly
        // disable it.
        if (!disable_shell_protocol) {
            FeatureSet features;
            std::string error;
            if (adb_get_feature_set(&features, &error)) {
                use_shell_protocol = CanUseFeature(features, kFeatureShell2);
            } else {
                // Device was unreachable.
                attempt_connection = false;
            }
        }

        if (attempt_connection) {
            std::string error;
            // 将命令和参数转换成shell命令
            std::string service_string = ShellServiceString(use_shell_protocol, "", command);

            // 此方法的重点!!!adb 只是一个代理端,
            fd = adb_connect(service_string, &error);
            if (fd >= 0) {
                break;
            }
        }

        // 没有链接设备就会打出这句话,并且等待设备的链接
        fprintf(stderr, "- waiting for device -\n");
        if (!wait_for_device("wait-for-device")) {
            return 1;
        }
    }

    int exit_code = read_and_dump(fd, use_shell_protocol, callback);

    if (adb_close(fd) < 0) {
        PLOG(ERROR) << "failure closing FD " << fd;
    }

    return exit_code;
}
```
分析`adb_connect`方法:
```
static int adb_connect_command(const std::string& command) {
    std::string error;
    int fd = adb_connect(command, &error);
    if (fd < 0) {
        fprintf(stderr, "error: %s\n", error.c_str());
        return 1;
    }
    read_and_dump(fd);
    adb_close(fd);
    return 0;
}
```
此处是从电脑链接到手机,并向手机发送shell命令,并由手机端的adbd去执行相关的操作.过程很复杂,没有弄清楚,不过不影响对`bugreport`的分析.
就是能够调用到手机里面的可执行.

## 2 bugreport 源码分析
### 2.1 Android.bp
在`framework/native/cmds/bugreport`有一个Android.bp,该模块是被编译成一个二进制可执行文件bugreport,位于手机`/system/bin/bugreport`
```
cc_binary {
    name: "bugreport",
    srcs: ["bugreport.cpp"],
    cflags: ["-Wall"],
    shared_libs: ["libcutils"],
}
```
### 2.2 bugreport.cpp
```
int main() {

// 说明一下,现在的bugreport报告使用zip文件替代了,为了显得不那么大
  fprintf(stderr, "=============================================================================\n");
  fprintf(stderr, "WARNING: flat bugreports are deprecated, use adb bugreport <zip_file> instead\n");
  fprintf(stderr, "=============================================================================\n\n\n");

  // Start the dumpstate service.
  // 启动dumpstate服务,该服务才是干活的服务,在init.rc中定义,启动的是system/bin/dumpstate进程
  property_set("ctl.start", "dumpstate");

  // Socket will not be available until service starts.
  // 不停的去尝试与dumpstate建立socket通信,直到链接成功
  int s;
  for (int i = 0; i < 20; i++) {
    s = socket_local_client("dumpstate", ANDROID_SOCKET_NAMESPACE_RESERVED,
                            SOCK_STREAM);
    if (s >= 0)
      break;
    // Try again in 1 second.
    sleep(1);
  }

  if (s == -1) {
    printf("Failed to connect to dumpstate service: %s\n", strerror(errno));
    return 1;
  }

  // Set a timeout so that if nothing is read in 3 minutes, we'll stop
  // reading and quit. No timeout in dumpstate is longer than 60 seconds,
  // so this gives lots of leeway in case of unforeseen time outs.
  // 设置timeout,在3分钟里没有任何数据读取就退出
  struct timeval tv;
  tv.tv_sec = 3 * 60;
  tv.tv_usec = 0;
  if (setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) == -1) {
    printf("WARNING: Cannot set socket timeout: %s\n", strerror(errno));
  }

  while (1) {
    char buffer[65536];
    ssize_t bytes_read = TEMP_FAILURE_RETRY(read(s, buffer, sizeof(buffer)));
    if (bytes_read == 0) {
      break;
    } else if (bytes_read == -1) {
      // EAGAIN really means time out, so change the errno.
      if (errno == EAGAIN) {
        errno = ETIMEDOUT;
      }
      printf("\nBugreport read terminated abnormally (%s).\n", strerror(errno));
      break;
    }

    ssize_t bytes_to_send = bytes_read;
    ssize_t bytes_written;
    // 不断的将数据读取到STDOUT(屏幕)
    do {
      bytes_written = TEMP_FAILURE_RETRY(write(STDOUT_FILENO,
                                               buffer + bytes_read - bytes_to_send,
                                               bytes_to_send));
      if (bytes_written == -1) {
        printf("Failed to write data to stdout: read %zd, trying to send %zd (%s)\n",
               bytes_read, bytes_to_send, strerror(errno));
        return 1;
      }
      bytes_to_send -= bytes_written;
    } while (bytes_written != 0 && bytes_to_send > 0);
  }

  close(s);
  return 0;
}
```
`bugreport`的代码其实就是启动dumpstate服务进程,建立与其的socket通信,读取数据,输出数据(屏幕或者重定向).

## 2 dumpstate 源码分析

### 2.1 dumpstate.rc
`frameworks/native/cmds/dumpstate/dumpstate.rc`:
```
on boot
    # Allow bugreports access to eMMC 5.0 stats
    chown root mount /sys/kernel/debug/mmc0/mmc0:0001/ext_csd
    chmod 0440 /sys/kernel/debug/mmc0/mmc0:0001/ext_csd
# 这就是在init.rc文件中定义的dumpstate服务
service dumpstate /system/bin/dumpstate -s
    class main
    socket dumpstate stream 0660 shell log
    disabled
    oneshot

# dumpstatez generates a zipped bugreport but also uses a socket to print the file location once
# it is finished.
service dumpstatez /system/bin/dumpstate -S -d -z \
        -o /data/user_de/0/com.android.shell/files/bugreports/bugreport
    socket dumpstate stream 0660 shell log
    class main
    disabled
    oneshot
```
在rc文件中定义了会用到的服务.

### 2.2 dumpstate.cpp
`frameworks/native/cmds/dumpstate/dumpstate.cpp`,分析其main函数:
```
int main(int argc, char *argv[]) {
    int do_add_date = 0;
    int do_zip_file = 0;
    int do_vibrate = 1;
    char* use_outfile = 0;
    int use_socket = 0;
    int use_control_socket = 0;
    int do_fb = 0;
    int do_broadcast = 0;
    int is_remote_mode = 0;
    bool show_header_only = false;
    bool do_start_service = false;
    bool telephony_only = false;

    /* set as high priority, and protect from OOM killer */
    setpriority(PRIO_PROCESS, 0, -20);

    // 设置高的进程优先级,防止被OOM_killer杀
    FILE* oom_adj = fopen("/proc/self/oom_score_adj", "we");
    if (oom_adj) {
        fputs("-1000", oom_adj);
        fclose(oom_adj);
    } else {
        /* fallback to kernels <= 2.6.35 */
        oom_adj = fopen("/proc/self/oom_adj", "we");
        if (oom_adj) {
            fputs("-17", oom_adj);
            fclose(oom_adj);
        }
    }

    /* parse arguments */
    // 解析服务进程的参数,在前面介绍的rc中也有用到
    int c;
    while ((c = getopt(argc, argv, "dho:svqzpPBRSV:")) != -1) {
        switch (c) {
            // clang-format off
            case 'd': do_add_date = 1;            break;
            case 'z': do_zip_file = 1;            break;
            case 'o': use_outfile = optarg;       break;
            case 's': use_socket = 1;             break;
            case 'S': use_control_socket = 1;     break;
            case 'v': show_header_only = true;    break;
            case 'q': do_vibrate = 0;             break;
            case 'p': do_fb = 1;                  break;
            case 'P': ds.update_progress_ = true; break;
            case 'R': is_remote_mode = 1;         break;
            case 'B': do_broadcast = 1;           break;
            case 'V':                             break; // compatibility no-op
            case 'h':
                ShowUsageAndExit(0);
                break;
            default:
                fprintf(stderr, "Invalid option: %c\n", c);
                ShowUsageAndExit();
                // clang-format on
        }
    }

    // TODO: use helper function to convert argv into a string
    // 参数转换成string类型数据
    for (int i = 0; i < argc; i++) {
        // ds 是class Dumpstate ,定义在dumpstate.h中
        ds.args_ += argv[i];
        if (i < argc - 1) {
            // args_是一个string类型
            ds.args_ += " ";
        }
    }

    // extra_options_ 是指一些系统属性,PROPERTY_EXTRA_OPTIONS属性在ActivityManagerService.java中设置
    ds.extra_options_ = android::base::GetProperty(PROPERTY_EXTRA_OPTIONS, "");
    if (!ds.extra_options_.empty()) {
        // Framework uses a system property to override some command-line args.
        // Currently, it contains the type of the requested bugreport.
        if (ds.extra_options_ == "bugreportplus") {
            // Currently, the dumpstate binder is only used by Shell to update progress.
            do_start_service = true;
            ds.update_progress_ = true;
            do_fb = 0;
        } else if (ds.extra_options_ == "bugreportremote") {
            do_vibrate = 0;
            is_remote_mode = 1;
            do_fb = 0;
        } else if (ds.extra_options_ == "bugreportwear") {
            ds.update_progress_ = true;
        } else if (ds.extra_options_ == "bugreporttelephony") {
            telephony_only = true;
        } else {
            MYLOGE("Unknown extra option: %s\n", ds.extra_options_.c_str());
        }
        // Reset the property
        android::base::SetProperty(PROPERTY_EXTRA_OPTIONS, "");
    }

    // PROPERTY_EXTRA_TITLE,PROPERTY_EXTRA_DESCRIPTION都是在ActivityManagerService.java中设置的系统属性
    ds.notification_title = android::base::GetProperty(PROPERTY_EXTRA_TITLE, "");
    if (!ds.notification_title.empty()) {
        // Reset the property
        android::base::SetProperty(PROPERTY_EXTRA_TITLE, "");

        ds.notification_description = android::base::GetProperty(PROPERTY_EXTRA_DESCRIPTION, "");
        if (!ds.notification_description.empty()) {
            // Reset the property
            android::base::SetProperty(PROPERTY_EXTRA_DESCRIPTION, "");
        }
        MYLOGD("notification (title:  %s, description: %s)\n",
               ds.notification_title.c_str(), ds.notification_description.c_str());
    }

    // 符合以下条件,退出,并打印印,原因是一些参数是需要有其他参数的,不能单独使用,在用法说明中都有详细的说明
    if ((do_zip_file || do_add_date || ds.update_progress_ || do_broadcast) && !use_outfile) {
        ExitOnInvalidArgs();
    }

    if (use_control_socket && !do_zip_file) {
        ExitOnInvalidArgs();
    }

    if (ds.update_progress_ && !do_broadcast) {
        ExitOnInvalidArgs();
    }

    if (is_remote_mode && (ds.update_progress_ || !do_broadcast || !do_zip_file || !do_add_date)) {
        ExitOnInvalidArgs();
    }
    // VERSION_DEFAULT 指 Bugreport format version
    if (ds.version_ == VERSION_DEFAULT) {
        ds.version_ = VERSION_CURRENT;
    }
    // VERSION_CURRENT = "1.0" , VERSION_SPLIT_ANR = "2.0-dev-1"
    if (ds.version_ != VERSION_CURRENT && ds.version_ != VERSION_SPLIT_ANR) {
        MYLOGE("invalid version requested ('%s'); suppported values are: ('%s', '%s', '%s')\n",
               ds.version_.c_str(), VERSION_DEFAULT.c_str(), VERSION_CURRENT.c_str(),
               VERSION_SPLIT_ANR.c_str());
        exit(1);
    }

    // dumpstate -v 就只打印头
    if (show_header_only) {
        ds.PrintHeader();
        exit(0);
    }

    /* redirect output if needed */
    // 使用重定向
    bool is_redirecting = !use_socket && use_outfile;

    // TODO: temporarily set progress until it's part of the Dumpstate constructor
    std::string stats_path =
        is_redirecting ? android::base::StringPrintf("%s/dumpstate-stats.txt", dirname(use_outfile))
                       : "";
    ds.progress_.reset(new Progress(stats_path));

    /* gets the sequential id */
    uint32_t last_id = android::base::GetIntProperty(PROPERTY_LAST_ID, 0);
    ds.id_ = ++last_id;
    android::base::SetProperty(PROPERTY_LAST_ID, std::to_string(last_id));

    MYLOGI("begin\n");

    register_sig_handler();

    if (do_start_service) {
        MYLOGI("Starting 'dumpstate' service\n");
        android::status_t ret;
        if ((ret = android::os::DumpstateService::Start()) != android::OK) {
            MYLOGE("Unable to start DumpstateService: %d\n", ret);
        }
    }

    if (PropertiesHelper::IsDryRun()) {
        MYLOGI("Running on dry-run mode (to disable it, call 'setprop dumpstate.dry_run false')\n");
    }

    MYLOGI("dumpstate info: id=%d, args='%s', extra_options= %s)\n", ds.id_, ds.args_.c_str(),
           ds.extra_options_.c_str());

    MYLOGI("bugreport format version: %s\n", ds.version_.c_str());

    ds.do_early_screenshot_ = ds.update_progress_;

    // If we are going to use a socket, do it as early as possible
    // to avoid timeouts from bugreport.
    if (use_socket) {
        redirect_to_socket(stdout, "dumpstate");
    }

    if (use_control_socket) {
        MYLOGD("Opening control socket\n");
        ds.control_socket_fd_ = open_socket("dumpstate");
        ds.update_progress_ = 1;
    }

    // 如果是重定向了,要走此内容
    if (is_redirecting) {
        ds.bugreport_dir_ = dirname(use_outfile);
        std::string build_id = android::base::GetProperty("ro.build.id", "UNKNOWN_BUILD");
        std::string device_name = android::base::GetProperty("ro.product.name", "UNKNOWN_DEVICE");
        ds.base_name_ = android::base::StringPrintf("%s-%s-%s", basename(use_outfile),
                                                    device_name.c_str(), build_id.c_str());
        if (do_add_date) {
            char date[80];
            strftime(date, sizeof(date), "%Y-%m-%d-%H-%M-%S", localtime(&ds.now_));
            ds.name_ = date;
        } else {
            ds.name_ = "undated";
        }

        if (telephony_only) {
            ds.base_name_ += "-telephony";
        }

        if (do_fb) {
            ds.screenshot_path_ = ds.GetPath(".png");
        }
        ds.tmp_path_ = ds.GetPath(".tmp");
        ds.log_path_ = ds.GetPath("-dumpstate_log-" + std::to_string(ds.pid_) + ".txt");

        MYLOGD(
            "Bugreport dir: %s\n"
            "Base name: %s\n"
            "Suffix: %s\n"
            "Log path: %s\n"
            "Temporary path: %s\n"
            "Screenshot path: %s\n",
            ds.bugreport_dir_.c_str(), ds.base_name_.c_str(), ds.name_.c_str(),
            ds.log_path_.c_str(), ds.tmp_path_.c_str(), ds.screenshot_path_.c_str());

        if (do_zip_file) {
            ds.path_ = ds.GetPath(".zip");
            MYLOGD("Creating initial .zip file (%s)\n", ds.path_.c_str());
            create_parent_dirs(ds.path_.c_str());
            ds.zip_file.reset(fopen(ds.path_.c_str(), "wb"));
            if (ds.zip_file == nullptr) {
                MYLOGE("fopen(%s, 'wb'): %s\n", ds.path_.c_str(), strerror(errno));
                do_zip_file = 0;
            } else {
                ds.zip_writer_.reset(new ZipWriter(ds.zip_file.get()));
            }
            ds.AddTextZipEntry("version.txt", ds.version_);
        }

        if (ds.update_progress_) {
            if (do_broadcast) {
                // clang-format off

                std::vector<std::string> am_args = {
                     "--receiver-permission", "android.permission.DUMP",
                     "--es", "android.intent.extra.NAME", ds.name_,
                     "--ei", "android.intent.extra.ID", std::to_string(ds.id_),
                     "--ei", "android.intent.extra.PID", std::to_string(ds.pid_),
                     "--ei", "android.intent.extra.MAX", std::to_string(ds.progress_->GetMax()),
                };
                // clang-format on
                SendBroadcast("com.android.internal.intent.action.BUGREPORT_STARTED", am_args);
            }
            if (use_control_socket) {
                dprintf(ds.control_socket_fd_, "BEGIN:%s\n", ds.path_.c_str());
            }
        }
    }

    /* read /proc/cmdline before dropping root */
    FILE *cmdline = fopen("/proc/cmdline", "re");
    if (cmdline) {
        fgets(cmdline_buf, sizeof(cmdline_buf), cmdline);
        fclose(cmdline);
    }

    if (do_vibrate) {
        Vibrate(150);
    }

    if (do_fb && ds.do_early_screenshot_) {
        if (ds.screenshot_path_.empty()) {
            // should not have happened
            MYLOGE("INTERNAL ERROR: skipping early screenshot because path was not set\n");
        } else {
            MYLOGI("taking early screenshot\n");
            ds.TakeScreenshot();
        }
    }

    if (do_zip_file) {
        if (chown(ds.path_.c_str(), AID_SHELL, AID_SHELL)) {
            MYLOGE("Unable to change ownership of zip file %s: %s\n", ds.path_.c_str(),
                   strerror(errno));
        }
    }

    if (is_redirecting) {
        redirect_to_file(stderr, const_cast<char*>(ds.log_path_.c_str()));
        if (chown(ds.log_path_.c_str(), AID_SHELL, AID_SHELL)) {
            MYLOGE("Unable to change ownership of dumpstate log file %s: %s\n",
                   ds.log_path_.c_str(), strerror(errno));
        }
        /* TODO: rather than generating a text file now and zipping it later,
           it would be more efficient to redirect stdout to the zip entry
           directly, but the libziparchive doesn't support that option yet. */
        redirect_to_file(stdout, const_cast<char*>(ds.tmp_path_.c_str()));
        if (chown(ds.tmp_path_.c_str(), AID_SHELL, AID_SHELL)) {
            MYLOGE("Unable to change ownership of temporary bugreport file %s: %s\n",
                   ds.tmp_path_.c_str(), strerror(errno));
        }
    }

    // Don't buffer stdout
    setvbuf(stdout, nullptr, _IONBF, 0);

    // NOTE: there should be no stdout output until now, otherwise it would break the header.
    // In particular, DurationReport objects should be created passing 'title, NULL', so their
    // duration is logged into MYLOG instead.
    ds.PrintHeader();

    if (telephony_only) {
        DumpIpTables();
        if (!DropRootUser()) {
            return -1;
        }
        do_dmesg();
        DoLogcat();
        DoKmsg();
        ds.DumpstateBoard();
        DumpModemLogs();
    } else {
        // Dumps systrace right away, otherwise it will be filled with unnecessary events.
        // First try to dump anrd trace if the daemon is running. Otherwise, dump
        // the raw trace.
        if (!dump_anrd_trace()) {
            dump_systrace();
        }

        // Invoking the following dumpsys calls before dump_traces() to try and
        // keep the system stats as close to its initial state as possible.
        RunDumpsys("DUMPSYS MEMINFO", {"meminfo", "-a"},
                   CommandOptions::WithTimeout(90).DropRoot().Build());
        RunDumpsys("DUMPSYS CPUINFO", {"cpuinfo", "-a"},
                   CommandOptions::WithTimeout(10).DropRoot().Build());

        // TODO: Drop root user and move into dumpstate() once b/28633932 is fixed.
        dump_raft();

        /* collect stack traces from Dalvik and native processes (needs root) */
        dump_traces_path = dump_traces();

        /* Run some operations that require root. */
        get_tombstone_fds(tombstone_data);
        ds.AddDir(RECOVERY_DIR, true);
        ds.AddDir(RECOVERY_DATA_DIR, true);
        ds.AddDir(LOGPERSIST_DATA_DIR, false);
        if (!PropertiesHelper::IsUserBuild()) {
            ds.AddDir(PROFILE_DATA_DIR_CUR, true);
            ds.AddDir(PROFILE_DATA_DIR_REF, true);
        }
        add_mountinfo();
        DumpIpTables();

        // Capture any IPSec policies in play.  No keys are exposed here.
        RunCommand("IP XFRM POLICY", {"ip", "xfrm", "policy"},
                   CommandOptions::WithTimeout(10).Build());

        // Run ss as root so we can see socket marks.
        RunCommand("DETAILED SOCKET STATE", {"ss", "-eionptu"},
                   CommandOptions::WithTimeout(10).Build());

        if (!DropRootUser()) {
            return -1;
        }

        dumpstate();
    }

    /* close output if needed */
    if (is_redirecting) {
        fclose(stdout);
    }

    /* rename or zip the (now complete) .tmp file to its final location */
    if (use_outfile) {

        /* check if user changed the suffix using system properties */
        std::string name = android::base::GetProperty(
            android::base::StringPrintf("dumpstate.%d.name", ds.pid_), "");
        bool change_suffix= false;
        if (!name.empty()) {
            /* must whitelist which characters are allowed, otherwise it could cross directories */
            std::regex valid_regex("^[-_a-zA-Z0-9]+$");
            if (std::regex_match(name.c_str(), valid_regex)) {
                change_suffix = true;
            } else {
                MYLOGE("invalid suffix provided by user: %s\n", name.c_str());
            }
        }
        if (change_suffix) {
            MYLOGI("changing suffix from %s to %s\n", ds.name_.c_str(), name.c_str());
            ds.name_ = name;
            if (!ds.screenshot_path_.empty()) {
                std::string new_screenshot_path = ds.GetPath(".png");
                if (rename(ds.screenshot_path_.c_str(), new_screenshot_path.c_str())) {
                    MYLOGE("rename(%s, %s): %s\n", ds.screenshot_path_.c_str(),
                           new_screenshot_path.c_str(), strerror(errno));
                } else {
                    ds.screenshot_path_ = new_screenshot_path;
                }
            }
        }

        bool do_text_file = true;
        if (do_zip_file) {
            if (!ds.FinishZipFile()) {
                MYLOGE("Failed to finish zip file; sending text bugreport instead\n");
                do_text_file = true;
            } else {
                do_text_file = false;
                // Since zip file is already created, it needs to be renamed.
                std::string new_path = ds.GetPath(".zip");
                if (ds.path_ != new_path) {
                    MYLOGD("Renaming zip file from %s to %s\n", ds.path_.c_str(), new_path.c_str());
                    if (rename(ds.path_.c_str(), new_path.c_str())) {
                        MYLOGE("rename(%s, %s): %s\n", ds.path_.c_str(), new_path.c_str(),
                               strerror(errno));
                    } else {
                        ds.path_ = new_path;
                    }
                }
            }
        }
        if (do_text_file) {
            ds.path_ = ds.GetPath(".txt");
            MYLOGD("Generating .txt bugreport at %s from %s\n", ds.path_.c_str(),
                   ds.tmp_path_.c_str());
            if (rename(ds.tmp_path_.c_str(), ds.path_.c_str())) {
                MYLOGE("rename(%s, %s): %s\n", ds.tmp_path_.c_str(), ds.path_.c_str(),
                       strerror(errno));
                ds.path_.clear();
            }
        }
        if (use_control_socket) {
            if (do_text_file) {
                dprintf(ds.control_socket_fd_,
                        "FAIL:could not create zip file, check %s "
                        "for more details\n",
                        ds.log_path_.c_str());
            } else {
                dprintf(ds.control_socket_fd_, "OK:%s\n", ds.path_.c_str());
            }
        }
    }

    /* vibrate a few but shortly times to let user know it's finished */
    for (int i = 0; i < 3; i++) {
        Vibrate(75);
        usleep((75 + 50) * 1000);
    }

    /* tell activity manager we're done */
    if (do_broadcast) {
        if (!ds.path_.empty()) {
            MYLOGI("Final bugreport path: %s\n", ds.path_.c_str());
            // clang-format off

            std::vector<std::string> am_args = {
                 "--receiver-permission", "android.permission.DUMP",
                 "--ei", "android.intent.extra.ID", std::to_string(ds.id_),
                 "--ei", "android.intent.extra.PID", std::to_string(ds.pid_),
                 "--ei", "android.intent.extra.MAX", std::to_string(ds.progress_->GetMax()),
                 "--es", "android.intent.extra.BUGREPORT", ds.path_,
                 "--es", "android.intent.extra.DUMPSTATE_LOG", ds.log_path_
            };
            // clang-format on
            if (do_fb) {
                am_args.push_back("--es");
                am_args.push_back("android.intent.extra.SCREENSHOT");
                am_args.push_back(ds.screenshot_path_);
            }
            if (!ds.notification_title.empty()) {
                am_args.push_back("--es");
                am_args.push_back("android.intent.extra.TITLE");
                am_args.push_back(ds.notification_title);
                if (!ds.notification_description.empty()) {
                    am_args.push_back("--es");
                    am_args.push_back("android.intent.extra.DESCRIPTION");
                    am_args.push_back(ds.notification_description);
                }
            }
            if (is_remote_mode) {
                am_args.push_back("--es");
                am_args.push_back("android.intent.extra.REMOTE_BUGREPORT_HASH");
                am_args.push_back(SHA256_file_hash(ds.path_));
                SendBroadcast("com.android.internal.intent.action.REMOTE_BUGREPORT_FINISHED",
                              am_args);
            } else {
                SendBroadcast("com.android.internal.intent.action.BUGREPORT_FINISHED", am_args);
            }
        } else {
            MYLOGE("Skipping finished broadcast because bugreport could not be generated\n");
        }
    }

    MYLOGD("Final progress: %d/%d (estimated %d)\n", ds.progress_->Get(), ds.progress_->GetMax(),
           ds.progress_->GetInitialMax());
    ds.progress_->Save();
    MYLOGI("done (id %d)\n", ds.id_);

    if (is_redirecting) {
        fclose(stderr);
    }

    if (use_control_socket && ds.control_socket_fd_ != -1) {
        MYLOGD("Closing control socket\n");
        close(ds.control_socket_fd_);
    }

    return 0;
}
```

















