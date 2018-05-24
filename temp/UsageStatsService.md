#UsageStatsService 入门
***  
最近分析一个ANR问题,原因是卡在了UsageStatsService的一把锁(mLock)上,项目是N平台,经过与O平台的对比,发现在O平台上已经增加了一把新锁,来解决UsageStatsService读取数据慢的问题,
同时发现google为了让UsageStatsService尽可能的不要耗时,把里面的log打印代码全部取消了.说明这块的代码确实是可能会有较长的耗时,会导致ANR(我遇到的问题),也有可能会使得屏幕
响应慢.  
google patch:  
<https://android.googlesource.com/platform/frameworks/base/+/61d5fd7fee3250bdf4b6ddfbccbd6bceae9436c6>

google 给该patch的说明是:Reduce screen on delay during UsageStats rollover.
***
基于上面问题的解决过程,所以有了这篇学习笔记.

##1. UsageStatsService简介
UsageStatsService是Android私有service，主要作用是收集用户使用每一个APP的频率、使用时长.google说是为了给用户更好的使用体验(确实有该作用),但是由于google自家的app
不是开源的,所以google有没有去搜集用户的隐私数据,不得而知.  
UsageStatsServicede 使用:  
1.
2.




从源码的角度分析该服务,先从该服务的启动开始,


