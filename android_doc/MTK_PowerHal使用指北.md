# MTK PowerHal 使用指北

## 0 PowerHal的作用

PowerHal 是一套MTK提供的在用户空间可以调度cpu & gpu资源的接口. 提供java & C++接口.
本文demo是java接口.

## 1 使用demo

### 1.1 导包
```
import com.mediatek.powerhalmgr.PowerHalMgr;
import com.mediatek.powerhalmgr.PowerHalMgrFactory;
```

### 1.2 申明变量

```
private PowerHalMgr mPowerOnHalService;
private int mPowerOnHandle = -1;
```

### 1.3 调用逻辑

```
               if (null == mPowerOnHalService) {
                            mPowerOnHalService = PowerHalMgrFactory.getInstance().makePowerHalMgr();
               }
               if (null != mPowerOnHalService && -1 == mPowerOnHandle) {
                            mPowerOnHandle = mPowerOnHalService.scnReg();
               }
              if (mPowerOnHalService != null && mPowerOnHandle != -1) {
                            //big cluster: 将配置大核心里面的所有core, MTK的设计目前只支持拉一个CLUSTER里面的所有core
                            mPowerOnHalService.scnConfig(mPowerOnHandle,
                                PowerHalMgr.CMD_SET_CLUSTER_CPU_CORE_MIN, 0, 4, 0, 0);
                            //3000000 代表3GHZ,底层会适配,因为没有频率能达到这么高,所以执行结果就是把频率拉倒最高
                            mPowerOnHalService.scnConfig(mPowerOnHandle,
                                PowerHalMgr.CMD_SET_CLUSTER_CPU_FREQ_MIN, 0, 3000000, 0, 0);
                            //little cluster:
                            mPowerOnHalService.scnConfig(mPowerOnHandle,
                                PowerHalMgr.CMD_SET_CLUSTER_CPU_CORE_MIN, 1, 4, 0, 0);
                            mPowerOnHalService.scnConfig(mPowerOnHandle,
                                PowerHalMgr.CMD_SET_CLUSTER_CPU_FREQ_MIN, 1, 3000000, 0, 0);
                            boost timeout(ms):
                            mPowerOnHalService.scnEnable(mPowerOnHandle, 5000); // 5000 是5000ms, 是一个timeout参数.
              }
```
拉频率是有一个开始点, 和结束点. 开始点即`public void scnEnable(int handle, int timeout)`, 结束点即`public void scnDisable(int handle)`. 
将需要拉频率的逻辑代码包含在其中就行.timeout是该调度起作用的最长时间. 也可以值设置开始点,不设置结束点.这样就可以在timeout时间到的时候结束.

## 2 客制化使用总结

- 添加客制化的hint & 配置config
```
PowerHalMgr mPowerOnHalService = PowerHalMgrFactory.getInstance().makePowerHalMgr();
int mPowerOnHandle = mPowerOnHalService.scnReg();
```
- 使能Performance Boost
```
public void scnEnable(int handle, int timeout)
```
- 执行逻辑代码
- 关闭Performance Boost
```
    public void scnDisable(int handle)
```

## 3 验证是否生效

### 3.1 方法一 直接查看cpu的当前频率
将timeout时间设置的长一些,在执行到相关逻辑时(机器需要root权限)

```
adb shell cat /sys/devices/system/cpu/cpu1/cpufreq/cpuinfo_cur_freq   //查看机器的实时频率
adb shell cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq  //如果设置了最低频率,可以使用此方式去查看
```

### 3.2 方法二 log中查看
在log中查看是否类似以下log输出: 
```
11-24 21:25:17.972   361   374 I libPowerHal: 36: legacy set freq: 1105000 -1
```

## 参考文献
- ALPS04863324
- CS6765-BD8E-PGD-V1.0EN_MTK_P0_BSP+_PowerHalService_Programming_Guide_MT6765.pdf
