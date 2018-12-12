# JobScheduler机制(一)

** Android P **

在android p 上, google 提出了应用待机分组, 分析其代码,涉及到了JobScheduler, 对其实习想学习一下,
故有`JobScheduler机制` 系列.

## 1 JobScheduler 使用
为了学习JobScheduler机制,首先要了解JobScheduler的使用,本节就简单的介绍JobScheduler的使用.
提供一个简单使用的demo : [JobScheduler demo](https://github.com/lqktz/TctApplication) 仅供参考.

JobScheduler 程序首先要继承`JobService`对象, 并必须实现其中的两个抽象方法 `onStartJob` 和 `onStopJob`:

```
public class MyJobService extends JobService {
    private static final String TAG = "MyJobService";
 
    /**
     * false: 该系统假设任何任务运行不需要很长时间并且到方法返回时已经完成。
     * true: 该系统假设任务是需要一些时间并且当任务完成时需要调用jobFinished()告知系统。
     */
    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Totally and completely working on job " + params.getJobId());
        Log.d(TAG,"onStartJob");
        return true;
    }
 
    /**
     * 当收到取消请求时，该方法是系统用来取消挂起的任务的。
     * 如果onStartJob()返回false，则系统会假设没有当前运行的任务，故不会调用该方法。
     */
    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "stop job " + params.getJobId());
        return false;
    }

}
```

定义 Job ,并调用JobScheduler的schedule方法去执行:

```
        jobService = new ComponentName(this, MyJobService.class);
        Intent service = new Intent(this, MyJobService.class);
        startService(service);

        JobScheduler scheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);

        JobInfo jobInfo = new JobInfo.Builder(10087, jobService) //任务Id等于123
                .setMinimumLatency(12345)// 任务最少延迟时间
                .setOverrideDeadline(60000)// 任务deadline，当到期没达到指定条件也会开始执行
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)// 网络条件，默认值NETWORK_TYPE_NONE
                .setRequiresCharging(true)// 是否充电
                .setRequiresDeviceIdle(false)// 设备是否空闲
                .setPersisted(true) //设备重启后是否继续执行 需要权限android.permission.RECEIVE_BOOT_COMPLETED
                .setBackoffCriteria(3000,JobInfo.BACKOFF_POLICY_LINEAR) //设置退避/重试策略
                .build(); // build 才是真正的执行创建

        Log.d(TAG,"scheduler.schedule");
        scheduler.schedule(jobInfo);

```

为了绑定`JobService` , 在AndroidManifest.xml中要添加service,并且定义权限`android.permission.BIND_JOB_SERVICE`
```
        <service android:name=".MyJobService"
            android:permission="android.permission.BIND_JOB_SERVICE"
            android:exported="true"/>
```

这里梳理一下流程:
 - 通过getSystemService获取JobSchedulerService的代理端
 - `new Intent(this, MyJobService.class)` 创建服务, 启动服务
 - 采用builder模式创建JobInfo对象
 - 调用`JobScheduler.schedule(JobInfo)` 启动`JobInfo`

## 2 查看定义的jobscheduler

当app设置的jobscheduler 之后,使用`adb shell dumpsys jobscheduler` 可以查看注册的jobscheduler,
dumpsys 命令不需要使用root命令 : 

```
       ......
  JOB #u0a174/10087: 86d97f7 com.lq.tct.tctapplication/.MyJobService
    u0a174 tag=*job*/com.lq.tct.tctapplication/.MyJobService
    Source: uid=u0a174 user=0 pkg=com.lq.tct.tctapplication
    JobInfo:
      Service: com.lq.tct.tctapplication/.MyJobService
      PERSISTED
      Requires: charging=true batteryNotLow=false deviceIdle=false
      Network type: 2
      Minimum latency: +12s345ms
      Max execution delay: +1m0s0ms
      Backoff: policy=0 initial=+10s0ms
      Has early constraint
      Has late constraint
    Required constraints: CHARGING TIMING_DELAY DEADLINE UNMETERED
    Satisfied constraints: CHARGING BATTERY_NOT_LOW APP_NOT_IDLE DEVICE_NOT_DOZING
    Unsatisfied constraints: TIMING_DELAY DEADLINE UNMETERED
    Tracking: BATTERY CONNECTIVITY TIME
    Enqueue time: -1s976ms
    Run time: earliest=+10s369ms, latest=+58s24ms
    Ready: false (job=false user=true !pending=true !active=true !backingup=true comp=true)
    ......
```

如果能获取root权限, 还可以查看`/data/system/job/jobs.xml` 文件: 

```
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<job-info version="0">
    <job jobid="10087" package="com.lq.tct.tctapplication" class="com.lq.tct.tctapplication.MyJobService" sourcePackageName="com.lq.tct.tctapplication" sourceUserId="0" uid="10174" priority="0" flags="0" lastSuccessfulRunTime="0" lastFailedRunTime="0">
        <constraints unmetered="true" charging="true" />
        <one-off deadline="1264103317926" delay="1264103270271" backoff-policy="0" initial-backoff="10000" />
        <extras />
    </job>
    <job jobid="1" package="com.tencent.mobileqq" class="com.tencent.mobileqq.msf.service.MSFAliveJobService" sourcePackageName="com.tencent.mobileqq" sourceUserId="0" uid="10163" priority="0" flags="0" lastSuccessfulRunTime="1262980997656" lastFailedRunTime="0">
        <constraints />
        <periodic period="900000" flex="900000" deadline="1262982797615" delay="1262981897615" />
        <extras />
    </job>
		.....
</job-info>
```

两种查看jobscheduler方式,结果形式比一样,但是显示的内容都是相同的,eg :  jobid: 10087是我们在前面的代码中定义的.
对于在定义job的时候的详细的参数,都在dumpsys或者job.xml中查看到.constraints 就是对应的限制条件,可参考:
[Android省电的秘密（2）之adb解读JobScheduler](https://www.jianshu.com/p/10f9a26081be)

## 参看Blog

 - [Android省电的秘密（2）之adb解读JobScheduler](https://www.jianshu.com/p/10f9a26081be)
 - [【Android Ｐ】 JobScheduler服务源码解析(一) —— 如何使用Ｊob](https://blog.csdn.net/u011311586/article/details/83027007)
