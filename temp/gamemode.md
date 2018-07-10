# gamemode 实现

该功能主要分由框架端实现了相关的功能接口,app端进行调用.

## 1. 结构

### 1.1 框架app实现
```
device/mediateksample/a3a84g  |
                              |--full_a3a84g.mk
                              |--BoardConfig.mk

frameworks/base/core/java/com/tct/gamemode |
                                           |--GameMode.aidl
                                           |--IGameModeChangeListener.aidl
                                           |--IGameModeManager.aidl
                                           |--GameMode.java
                                           |--GameModeManagerGlobal.java

vendor/jrdcom/system/gamemode |
                              |--gamemode.mk
                              |/core |
                                     |--Android.mk
                                     |/java/com/tct/gamemode/service/GameModeManagerService.java
```

### 1.2 app端调用

```
vendor/mediatek/proprietary/packages/apps/MtkSettings
```









