# android添加系统服务

内容: 在system server定义自己的系统服务,提供接口给app侧使用.
平台: Android P

## 1 添加系统接口

### 1.1 定义service name
添加service name.
`frameworks/base/core/java/android/content/Context.java`

```
public static final String DEMO_SERVICE = "demoservice";
```
demoservice 就是要定义的service的name.

### 1.2 定义service

添加`frameworks/base/services/core/java/com/android/server/DemoService.java`新文件

```
package com.android.server;

import android.content.Context;
import android.util.Slog;
import android.app.IDemoManager;

public class DemoService extends IDemoManager.Stub {
    private static final String TAG = "DemoService";
    private Context mContext
    public DemoService(Context context) {
        mContext = context;
        Slog.d(TAG, "DemoService start success");
    }
    public void sayHello(){
        Slog.d(TAG,"hello world");
    }
}
```

这里定义了一个`public void sayHello()`,供app侧调用

### 1.3 添加demo service到system server

首先需要导包
```
import com.android.server.DemoService;
```

将demo service 注册到ServiceManager, 下面的代码添加到 `startOtherServices` 中
```
            try {
                ServiceManager.addService(Context.DEMO_SERVICE, new DemoService(context));
                Slog.i(TAG, "addService demo service is OK!");
            } catch (Exception e) {
                Slog.e(TAG, "DemoService start fail" + e);
            }

```
到这系统服务已经添加完毕, 不过还不能给app侧调用,调用需要使用添加binder通讯模块.

### 1.4 添加aidl文件

添加新文件 `frameworks/base/core/java/android/app/IDemoManager.aidl`,将demoservice的sayHello方法暴露给app侧.

```
package android.app;
interface IDemoManager {
    void sayHello();
}
```

将路径添加到`Android.bp`的 `srcs:`里面
```
	"core/java/android/app/IDemoManager.aidl",
```

### 1.5 添加app侧manager文件

添加新文件 `frameworks/base/core/java/android/app/DemoManager.java`,app可以调用此接口来使用demo service.

```
package android.app;
import android.util.Log;
import android.os.ServiceManager;
import android.content.Context;
import android.annotation.NonNull;
public class DemoManager {
    private static final String TAG = "DemoManager";
    private static DemoManager sInstance;
    private IDemoManager mDemoManagerService;
    private static Context mContext;
    private static IDemoManager mService;
    public DemoManager(@NonNull Context context, @NonNull IDemoManager service) {
        mContext = context;
        mService = service;
    }
    public void sayHello() {
        try {
            mService.sayHello();
        } catch (Exception e) {
            Log.i(TAG, "exception sayHello()");
        }
    }
}
```

注册服务,在 `frameworks/base/core/java/android/app/SystemServiceRegistry.java`中的`static`添加如下代码:

```
        registerService(Context.DEMO_SERVICE, DemoManager.class, new CachedServiceFetcher<DemoManager>() {
                    @Override
                    public DemoManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.DEMO_SERVICE);
                        return new DemoManager(ctx.getOuterContext(), IDemoManager.Stub.asInterface(b));
                    }
            });
```

### 1.6 解决SELinux权限问题

在`service_contexts`中添加:
```
demoservice                             u:object_r:demo_service:s0
```

在`service.te`中添加:
```
type demo_service, app_api_service, system_server_service, service_manager_type;
```

### 1.7 编译
由于添加了public的接口给app侧, 需要使用
```
make update-api
```

到此, 编译,刷机. 开机可以使用`adb shell service list | grep demoservice`, 可以查看到
```
54      demoservice: [android.app.IDemoManager]
```
说明已经添加成功.

## 2 APP 使用
在源码中的app代码中添加一下代码即可使用自己添加的service的api接口.
```
                DemoManager mDemoManager = (DemoManager) getSystemService(Context.DEMO_SERVICE);
                try {
                    mDemoManager.sayHello();
                } catch (Exception e) {
                    e.printStackTrace();
                }

```

