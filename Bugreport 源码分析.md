＃Bugreport 源码分析

时间: 2018/04/28

**平台Android 8.1**

## 1. adb 端的代码

使用bugreport,'abd bugreport > bugreport.log',在adb的代码查找入口:
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
会调用`adb_connect`,位置在`system/core/adb/adb_client.cpp`:
```










## 2 bugreport
### 2.1 bugreport.cpp



















