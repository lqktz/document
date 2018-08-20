# tctlog功能文档总结

Date: 2018/08/17 Author: qli3@tcl.com

tctlog主要是针对系统性能,提供系统接口,抓取系统性能相关的log信息. 并统一汇总到指定文件夹.该功能也可以扩展到系统性能之外的log信息的抓取.
目前只是提供一个基础版本.

# 1 收集的主要log信息

- bugreport
- blockinfo
- system_server maps 信息
- vmstat, cpu ,uptime 等信息
- mtklog 信息
- dropbox 信息
- anr 信息

# 2 log信息搜集

## 2.1 bugreport信息搜集
通过AMS的接口,调用:

```
    requestBugReport(ActivityManager.BUGREPORT_OPTION_FULL);
```

该接口通过修改属性值:

```
    SystemProperties.set("ctl.start", "bugreport");
```

启动`bugreport`bin程序,开始抓取bugreport文件.这个服务定义在init.rc文件中:

```
# bugreport is triggered by holding down volume down, volume up and power                                                                                           
service bugreport /system/bin/dumpstate -d -p -B -z \                                                                                                       
-o /data/user_de/0/com.android.shell/files/bugreports/bugreport                                                                                                  
class late_start                                                                                                                                                     
disabled                                                                                                                                                             
oneshot                                                                                                                                                              
keycodes 114 115 116                                                                                                                                                 

```

但是这样抓取的bugreport的文件并没有在指定的文件夹下.
最开始的想法是看bugreport的实现,发现可以指定bugreport抓取的位置.但是在AMS里面使用了带参数的`/system/bin/dumpstate`bin程序,
由于有太多的SELinux权限问题需要解决,有一些权限问题得不到很好的解决方案.所以找了其他方式.抓取的`bugreport`是在`com.android.shell`下,
能否考虑抓取`bugreport`完成后,从该apk的data目录下`copy`到指定的目录.最后发现该方案可行.

`com.android.shell`在`frameworks/basepackages/Shell`下,查看代码,在接收到`INTENT_BUGREPORT_FINISHED`消息,即bgreport完成的消息,会调用
`onBugreportFinished`方法,在`onBugreportFinished`方法中添加copy操作就可以:
```
    File f_input = new File("/bugreports");                                                                                                                  
    File f_output = new File("/data/tctlogs/bugreports_" + timeStamp + ".zip");                                                                              
    zipDir(f_input,f_output);
```

这样可以将`bugreport`文件,抓取到指定的文件下面了.

# 3 blockinfo信息的搜集

## 3.1 主要工作

- 在blockinfo原有的功能中添加搜集binder信息,用于分析binder导致的问题
- 将`/data/blockinfo`打包到`/data/tctlogs`

## 3.2 搜集binder信息

分两步:

- 在framework添加接口`get_binderinfo`,搜集binder信息的接口;
- 在blockinfo的功能中,卡顿打印log时,调用`get_binderinfo`.

```
String input_path = "/sys/kernel/debug/binder/";
String output_path = "/data/blockinfo/";
String [] fileList = {"failed_transaction_log",                                                                                                                 
    "state",
    "stats",                                                                                                
    "transaction_log",
    "transactions"};                                                                                                                         
```

binder信息就是读取fileList中的指定的节点数据.然后将其保存在`/data/blockinfo`中.

在blockinfo功能的`LogWriter.java`中的`save`方法中调用该接口:
```
    ActivityManager mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);                                                 
    mActivityManager.get_binderinfo(FILE_NAME_FORMATTER.format(time));                                                                                       
```
每次当应用出现卡顿的时候,就搜集binder信息.

## 3.3 system_server maps信息搜集

```
/**                                                                                                                                                                  
 * for get system_server maps.                                                                                                                                       
 */                                                                                                                                                                  
public void get_maps(String output_path){                                                                                                                            
    String input_path = "/proc/self/maps";                                                                                                                           

    try {                                                                                                                                                            
        Files.copy(Paths.get(input_path), Paths.get(output_path), StandardCopyOption.REPLACE_EXISTING);                                                              
        String shellComm = "chmod 755 " + output_path;                                                                                                               
        Runtime.getRuntime().exec(shellComm);                                                                                                                        
    } catch (IOException e) {                                                                                                                                        
        e.printStackTrace();                                                                                                                                         
    }                                                                                                                                                                
}                                                                                                                                                                    
```

为了搜集的log能够在shell权限下能够被copy,需要用`chmod`修改权限.

## 3.4 其他log信息的搜集
其他的log信息是以下几种情况:

- copy
- compress
- shell commmand

这集中情况,都比较简单,这里不再赘述.至于用到的java语言的copy compress 以及调用shell command都是通用的方式没有什么特别之处.

## 3.5 权限

- sdcard 读写权限
- SELinux 权限
