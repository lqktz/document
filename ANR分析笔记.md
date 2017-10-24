分析的第一个ANR问题,trace:  
```
"Binder:876_B" prio=5 tid=100 Runnable
  | group="main" sCount=0 dsCount=0 obj=0x13d36f70 self=0x97c62b00
  | sysTid=1958 nice=0 cgrp=default sched=0/0 handle=0x94213920
  | state=R schedstat=( 50485139390 8705967032 26706 ) utm=4415 stm=633 core=2 HZ=100
  | stack=0x94117000-0x94119000 stackSize=1014KB
  | held mutexes= "mutator lock"(shared held)
  native: #00 pc 0034f0ad  /system/lib/libart.so (_ZN3art15DumpNativeStackERNSt3__113basic_ostreamIcNS0_11char_traitsIcEEEEiP12BacktraceMapPKcPNS_9ArtMethodEPv+128)
  native: #01 pc 0032f96d  /system/lib/libart.so (_ZNK3art6Thread9DumpStackERNSt3__113basic_ostreamIcNS1_11char_traitsIcEEEEbP12BacktraceMap+632)
  native: #02 pc 003418cd  /system/lib/libart.so (_ZN3art14DumpCheckpoint3RunEPNS_6ThreadE+620)
  native: #03 pc 003304fb  /system/lib/libart.so (_ZN3art6Thread21RunCheckpointFunctionEv+146)
  native: #04 pc 003f24c1  /system/lib/libart.so (_ZN3artL12GoToRunnableEPNS_6ThreadE+52)
  native: #05 pc 003f27e1  /system/lib/libart.so (_ZN3art25JniMethodEndWithReferenceEP8_jobjectjPNS_6ThreadE+12)
  native: #06 pc 00015d7f  /system/framework/arm/boot.oat (Java_java_lang_Throwable_nativeFillInStackTrace__+82)
  at java.lang.Throwable.nativeFillInStackTrace!(Native method)
  at java.lang.Throwable.fillInStackTrace(Throwable.java:774)
  - locked <0x09c5860b> (a java.lang.NumberFormatException)
  at java.lang.Throwable.<init>(Throwable.java:257)
  at java.lang.Exception.<init>(Exception.java:66)
  at java.lang.RuntimeException.<init>(RuntimeException.java:62)
  at java.lang.IllegalArgumentException.<init>(IllegalArgumentException.java:53)
  at java.lang.NumberFormatException.<init>(NumberFormatException.java:55)
  at java.lang.Integer.parseInt(Integer.java:483)
  at java.lang.Integer.parseInt(Integer.java:556)
  at com.android.internal.util.XmlUtils.readIntAttribute(XmlUtils.java:1526)
  at android.content.res.Configuration.readXmlAttrs(Configuration.java:1948)
  at com.android.server.usage.UsageStatsXmlV1.loadEvent(UsageStatsXmlV1.java:111)
  at com.android.server.usage.UsageStatsXmlV1.read(UsageStatsXmlV1.java:214)
  at com.android.server.usage.UsageStatsXml.read(UsageStatsXml.java:99)
  at com.android.server.usage.UsageStatsXml.read(UsageStatsXml.java:63)
  at com.android.server.usage.UsageStatsDatabase.queryUsageStats(UsageStatsDatabase.java:449)
  - locked <0x0178af01> (a java.lang.Object)
  at com.android.server.usage.UserUsageStatsService.queryStats(UserUsageStatsService.java:275)//UserUsageStatsService用于搜集用户的使用app的个人信息
  at com.android.server.usage.UserUsageStatsService.queryUsageStats(UserUsageStatsService.java:302)
  at com.android.server.usage.UsageStatsService.queryUsageStats(UsageStatsService.java:722)
  - locked <0x017b01a6> (a java.lang.Object)//没有释放该锁导致别的线程ANR
  at com.android.server.usage.UsageStatsService$BinderService.queryUsageStats(UsageStatsService.java:1270)
  at android.app.usage.IUsageStatsManager$Stub.onTransact(IUsageStatsManager.java:61)
  at android.os.Binder.execTransact(Binder.java:570)
```
是一个native方法导致的线程超时,而没有释放锁<0x017b01a6>:  
```
  at java.lang.Throwable.nativeFillInStackTrace!(Native method)
```
通过addr2line工具分析;  
```
  native: #00 pc 0034f0ad  /system/lib/libart.so (_ZN3art15DumpNativeStackERNSt3__113basic_ostreamIcNS0_11char_traitsIcEEEEiP12BacktraceMapPKcPNS_9ArtMethodEPv+128)
  native: #01 pc 0032f96d  /system/lib/libart.so (_ZNK3art6Thread9DumpStackERNSt3__113basic_ostreamIcNS1_11char_traitsIcEEEEbP12BacktraceMap+632)
  native: #02 pc 003418cd  /system/lib/libart.so (_ZN3art14DumpCheckpoint3RunEPNS_6ThreadE+620)
  native: #03 pc 003304fb  /system/lib/libart.so (_ZN3art6Thread21RunCheckpointFunctionEv+146)
  native: #04 pc 003f24c1  /system/lib/libart.so (_ZN3artL12GoToRunnableEPNS_6ThreadE+52)
  native: #05 pc 003f27e1  /system/lib/libart.so (_ZN3art25JniMethodEndWithReferenceEP8_jobjectjPNS_6ThreadE+12)
  native: #06 pc 00015d7f  /system/framework/arm/boot.oat (Java_java_lang_Throwable_nativeFillInStackTrace__+82)
```
找到`libart.so`的位置:  
```shell
qli3@tabletbuild13:~/code/A3AXL3G/out/target$ find -name "libart.so"
./product/a3a63g_gmo/obj/lib/libart.so
./product/a3a63g_gmo/obj/SHARED_LIBRARIES/libart_intermediates/PACKED/libart.so
./product/a3a63g_gmo/obj/SHARED_LIBRARIES/libart_intermediates/LINKED/libart.so
./product/a3a63g_gmo/symbols/system/fake-libs/libart.so
./product/a3a63g_gmo/symbols/system/lib/libart.so
./product/a3a63g_gmo/system/lib/libart.so
```
选取symbols下面的对应的:  
```
./product/a3a63g_gmo/symbols/system/lib/libart.so
```
使用命令解析;  
```shell
qli3@tabletbuild13:~/code/A3AXL3G/out/target$ addr2line -e ./product/a3a63g_gmo/symbols/system/lib/libart.so 003f27e1
/proc/self/cwd/art/runtime/entrypoints/quick/quick_jni_entrypoints.cc:106
```
找到对应的代码位置quick_jni_entrypoints.cc:  
```
extern mirror::Object* JniMethodEndWithReference(jobject result, uint32_t saved_local_ref_cookie,
                                                 Thread* self) {
  GoToRunnable(self);                                                                                                                                                    
  return JniMethodEndWithReferenceHandleResult(result, saved_local_ref_cookie, self);
}
```
接着分析`  native: #04 pc 003f24c1  /system/lib/libart.so (_ZN3artL12GoToRunnableEPNS_6ThreadE+52)`:  
```
qli3@tabletbuild13:~/code/A3AXL3G$ addr2line -e ./out/target/product/a3a63g_gmo/symbols/system/lib/libart.so 003f24c1
/proc/self/cwd/art/runtime/thread-inl.h:68
```



***
如果没有addr2line工具,在android源码中可以找使用`find -name "addr2line"`:  
```shell
./prebuilts/go/darwin-x86/src/cmd/addr2line
./prebuilts/go/darwin-x86/pkg/tool/darwin_amd64/addr2line
./prebuilts/go/linux-x86/src/cmd/addr2line
./prebuilts/go/linux-x86/pkg/tool/linux_amd64/addr2line
./prebuilts/tools/gcc-sdk/addr2line
```
任意选取其中一个即可.
***
