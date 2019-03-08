# Android P 非SDK接口限制分析

涉及的源码路径

```
libcore/ojluni/src/main/java/java/lang/Class.java
art/runtime/native/java_lang_Class.cc
art/runtime/hidden_api.h
art/runtime/runtime.h
art/libdexfile/dex/hidden_api_access_flags.h
art/runtime/hidden_api.cc
art/runtime/art_method-inl.h
```

Google为了限制APP调用非公开的SDK接口,在android P开始添加了相关的限制.[详情](https://developer.android.com/about/versions/pie/restrictions-non-sdk-interfaces?hl=zh-cn)

本文从源码上分析其实现,先上一张大图.

![hidden_API framework](https://raw.githubusercontent.com/lqktz/document/master/res/hidden_API.png)

## 1. java 端调用

反射调用代码:

```java
     Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
     Method currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
```

代码是通过`getDeclaredMethod`调用开始的,接下来代码就从这开始.
`Class.java`

```java
    public Method getDeclaredMethod(String name, Class<?>... parameterTypes)
        throws NoSuchMethodException, SecurityException {
        return getMethod(name, parameterTypes, false); // 进入该函数
    }
```

```java
    private Method getMethod(String name, Class<?>[] parameterTypes, boolean recursivePublicMethods)
            throws NoSuchMethodException {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        if (parameterTypes == null) {
            parameterTypes = EmptyArray.CLASS;
        }
        for (Class<?> c : parameterTypes) {
            if (c == null) {
                throw new NoSuchMethodException("parameter type is null");
            }
        }
	// recursivePublicMethods的值传入的是false,所以走getDeclaredMethodInternal
        Method result = recursivePublicMethods ? getPublicMethodRecursive(name, parameterTypes)
                                               : getDeclaredMethodInternal(name, parameterTypes); 
        // Fail if we didn't find the method or it was expected to be public.
        if (result == null ||
            (recursivePublicMethods && !Modifier.isPublic(result.getAccessFlags()))) {
            throw new NoSuchMethodException(name + " " + Arrays.toString(parameterTypes));
        }
        return result;
    }
```
这里的java方法是一个native方法

```java
    /**
     * Returns the method if it is defined by this class; {@code null} otherwise. This may return a
     * non-public member.
     *
     * @param name the method name
     * @param args the method's parameter types
     */
    @FastNative
    private native Method getDeclaredMethodInternal(String name, Class<?>[] args);
```

通过JNI跳转到接C++方法

## 2. C++ 端的实现

### 2.1 Class_getDeclaredMethodInternal

`java_lang_Class.cc`


```
static jobject Class_getDeclaredMethodInternal(JNIEnv* env, jobject javaThis,
                                               jstring name, jobjectArray args) {
  ScopedFastNativeObjectAccess soa(env);
  StackHandleScope<1> hs(soa.Self());
  DCHECK_EQ(Runtime::Current()->GetClassLinker()->GetImagePointerSize(), kRuntimePointerSize);
  DCHECK(!Runtime::Current()->IsActiveTransaction());
  Handle<mirror::Method> result = hs.NewHandle(
      mirror::Class::GetDeclaredMethodInternal<kRuntimePointerSize, false>(
          soa.Self(),
          DecodeClass(soa, javaThis),
          soa.Decode<mirror::String>(name),
          soa.Decode<mirror::ObjectArray<mirror::Class>>(args)));
  //检测该方法是否允许访问【小节2.4】
  if (result == nullptr || ShouldBlockAccessToMember(result->GetArtMethod(), soa.Self())) {
    return nullptr;
  }
  return soa.AddLocalReference<jobject>(result.Get());
}
```

### 2.2 ShouldBlockAccessToMember

```C++
// Returns true if the first non-ClassClass caller up the stack should not be
// allowed access to `member`.
template<typename T>
ALWAYS_INLINE static bool ShouldBlockAccessToMember(T* member, Thread* self)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  hiddenapi::Action action = hiddenapi::GetMemberAction( // 获取的action的类型是重点
      member, self, IsCallerTrusted, hiddenapi::kReflection);  // kReflection : 反射方式调用
  if (action != hiddenapi::kAllow) {
    hiddenapi::NotifyHiddenApiListener(member); // 当不是kAllow时,则需要警告或者弹窗,则通过此方法通知
  }

  return action == hiddenapi::kDeny; // 返回true则被限制,也就是当action是kAllow/kAllowButWarn/kAllowButWarnAndToast返回false,都是允许accessmember的
}
```

`hidden_api.h`

```C++
enum Action {
  kAllow,  // 允许
  kAllowButWarn,  // 允许+警告
  kAllowButWarnAndToast, // 允许+警告+弹窗
  kDeny // 阻止
};

// 调用方式
enum AccessMethod {
  kNone,  // internal test that does not correspond to an actual access by app
  kReflection,  //反射
  kJNI,  //JNI
  kLinking, //动态链接
};
```

Reflection反射过程：
 - Class_newInstance：对象实例化
 - Class_getDeclaredConstructorInternal：构造方法
 - Class_getDeclaredMethodInternal：获取方法
 - Class_getDeclaredField：获取字段
 - Class_getPublicFieldRecursive：获取字段

kJNI的JNI调用过程：
 - FindMethodID：查找方法
 - FindFieldID：查找字段

kLinking动态链接：
 - UnstartedClassNewInstance
 - UnstartedClassGetDeclaredConstructor
 - UnstartedClassGetDeclaredMethod
 - UnstartedClassGetDeclaredField


### 2.3 GetMemberAction

进入`hiddenapi::GetMemberAction`分析

`hidden_api.h`

```
// Returns true if access to `member` should be denied to the caller of the
// reflective query. The decision is based on whether the caller is trusted or
// not. Because different users of this function determine this in a different
// way, `fn_caller_is_trusted(self)` is called and should return true if the
// caller is allowed to access the platform.
// This function might print warnings into the log if the member is hidden.
template<typename T>
//Action就是允许,警告,弹窗,阻止 
inline Action GetMemberAction(T* member,
                              Thread* self,
                              std::function<bool(Thread*)> fn_caller_is_trusted,
                              AccessMethod access_method)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  DCHECK(member != nullptr);

  // Decode hidden API access flags.
  // NB Multiple threads might try to access (and overwrite) these simultaneously,
  // causing a race. We only do that if access has not been denied, so the race
  // cannot change Java semantics. We should, however, decode the access flags
  // once and use it throughout this function, otherwise we may get inconsistent
  // results, e.g. print whitelist warnings (b/78327881).

  // inline HiddenApiAccessFlags::ApiList ArtMethod::GetHiddenApiAccessFlags()
  HiddenApiAccessFlags::ApiList api_list = member->GetHiddenApiAccessFlags(); // 获取方法的可访问标示符,返回在哪个名单里面 art_method-inl.h
  // HiddenApiAccessFlags 定义在 hidden_api_access_flags.h 白名单 浅灰名单 深灰名单 黑名单

  Action action = GetActionFromAccessFlags(member->GetHiddenApiAccessFlags());  // 1.依据名单类型,获取action类型->允许,警告,弹窗,阻止 
  if (action == kAllow) {
    // Nothing to do.
    return action;
  }

  // Member is hidden. Invoke `fn_caller_in_platform` and find the origin of the access.
  // This can be *very* expensive. Save it for last.
  if (fn_caller_is_trusted(self)) { // 2. 通过函数指针调用到IsCallerTrusted函数
    // Caller is trusted. Exit.
    return kAllow;
  }

  // Member is hidden and caller is not in the platform.
  return detail::GetMemberActionImpl(member, api_list, action, access_method); //3.access_method是kReflection表示是反射调用
}

```

此方法里有三处要分析:

 - GetActionFromAccessFlags(member->GetHiddenApiAccessFlags())
 - fn_caller_is_trusted(self)
 - detail::GetMemberActionImpl

分析上面三个方法之前,先来看看上面的代码用到的

```
class HiddenApiAccessFlags {
 public:
  enum ApiList {
    kWhitelist = 0,  // 白名单
    kLightGreylist, // 浅灰名单
    kDarkGreylist,  // 深灰名单
    kBlacklist,  // 黑名单
  };
```

#### 2.3.1 GetHiddenApiAccessFlags

先看`GetActionFromAccessFlags(member->GetHiddenApiAccessFlags())`,要先分析`member->GetHiddenApiAccessFlags`.

`art_method-inl.h`

```C++
inline HiddenApiAccessFlags::ApiList ArtMethod::GetHiddenApiAccessFlags()
    REQUIRES_SHARED(Locks::mutator_lock_) {
  if (UNLIKELY(IsIntrinsic())) {
    switch (static_cast<Intrinsics>(GetIntrinsic())) {
      case Intrinsics::kSystemArrayCopyChar:
      case Intrinsics::kStringGetCharsNoCheck:
      case Intrinsics::kReferenceGetReferent:
        // These intrinsics are on the light greylist and will fail a DCHECK in
        // SetIntrinsic() if their flags change on the respective dex methods.
        // Note that the DCHECK currently won't fail if the dex methods are
        // whitelisted, e.g. in the core image (b/77733081). As a result, we
        // might print warnings but we won't change the semantics.
        return HiddenApiAccessFlags::kLightGreylist;
      case Intrinsics::kVarHandleFullFence:
      case Intrinsics::kVarHandleAcquireFence:
      case Intrinsics::kVarHandleReleaseFence:
      case Intrinsics::kVarHandleLoadLoadFence:
      case Intrinsics::kVarHandleStoreStoreFence:
      case Intrinsics::kVarHandleCompareAndExchange:
      case Intrinsics::kVarHandleCompareAndExchangeAcquire:
      case Intrinsics::kVarHandleCompareAndExchangeRelease:
      case Intrinsics::kVarHandleCompareAndSet:
      case Intrinsics::kVarHandleGet:
      case Intrinsics::kVarHandleGetAcquire:
      case Intrinsics::kVarHandleGetAndAdd:
      case Intrinsics::kVarHandleGetAndAddAcquire:
      case Intrinsics::kVarHandleGetAndAddRelease:
      case Intrinsics::kVarHandleGetAndBitwiseAnd:
      case Intrinsics::kVarHandleGetAndBitwiseAndAcquire:
      case Intrinsics::kVarHandleGetAndBitwiseAndRelease:
      case Intrinsics::kVarHandleGetAndBitwiseOr:
      case Intrinsics::kVarHandleGetAndBitwiseOrAcquire:
      case Intrinsics::kVarHandleGetAndBitwiseOrRelease:
      case Intrinsics::kVarHandleGetAndBitwiseXor:
      case Intrinsics::kVarHandleGetAndBitwiseXorAcquire:
      case Intrinsics::kVarHandleGetAndBitwiseXorRelease:
      case Intrinsics::kVarHandleGetAndSet:
      case Intrinsics::kVarHandleGetAndSetAcquire:
      case Intrinsics::kVarHandleGetAndSetRelease:
      case Intrinsics::kVarHandleGetOpaque:
      case Intrinsics::kVarHandleGetVolatile:
      case Intrinsics::kVarHandleSet:
      case Intrinsics::kVarHandleSetOpaque:
      case Intrinsics::kVarHandleSetRelease:
      case Intrinsics::kVarHandleSetVolatile:
      case Intrinsics::kVarHandleWeakCompareAndSet:
      case Intrinsics::kVarHandleWeakCompareAndSetAcquire:
      case Intrinsics::kVarHandleWeakCompareAndSetPlain:
      case Intrinsics::kVarHandleWeakCompareAndSetRelease:
        // These intrinsics are on the blacklist and will fail a DCHECK in
        // SetIntrinsic() if their flags change on the respective dex methods.
        // Note that the DCHECK currently won't fail if the dex methods are
        // whitelisted, e.g. in the core image (b/77733081). Given that they are
        // exclusively VarHandle intrinsics, they should not be used outside
        // tests that do not enable hidden API checks.
        return HiddenApiAccessFlags::kBlacklist;
      default:
        // Remaining intrinsics are public API. We DCHECK that in SetIntrinsic().
        return HiddenApiAccessFlags::kWhitelist;
    }
  } else {
    return HiddenApiAccessFlags::DecodeFromRuntime(GetAccessFlags());
  }
}

```

#### 2.3.2 GetActionFromAccessFlags

`GetHiddenApiAccessFlags()`获取相应的flag,接着看`GetActionFromAccessFlags`:

```C++
inline Action GetActionFromAccessFlags(HiddenApiAccessFlags::ApiList api_list) {
  // api_list就是 kWhitelist/kLightGreylist/kDarkGreylist/kBlacklist
  if (api_list == HiddenApiAccessFlags::kWhitelist) {// 白名单直接返回
    return kAllow;
  }

  EnforcementPolicy policy = Runtime::Current()->GetHiddenApiEnforcementPolicy(); // 1 EnforcementPolicy
  if (policy == EnforcementPolicy::kNoChecks) {
    // Exit early. Nothing to enforce.
    return kAllow;
  }

  // if policy is "just warn", always warn. We returned above for whitelist APIs.
  if (policy == EnforcementPolicy::kJustWarn) {
    return kAllowButWarn;
  }
  DCHECK(policy >= EnforcementPolicy::kDarkGreyAndBlackList);
  // The logic below relies on equality of values in the enums EnforcementPolicy and
  // HiddenApiAccessFlags::ApiList, and their ordering. Assertions are in hidden_api.cc.
  if (static_cast<int>(policy) > static_cast<int>(api_list)) {
    return api_list == HiddenApiAccessFlags::kDarkGreylist
        ? kAllowButWarnAndToast
        : kAllowButWarn;
  } else {
    return kDeny;
  }
}

```

```
// Hidden API enforcement policy
// This must be kept in sync with ApplicationInfo.ApiEnforcementPolicy in
// frameworks/base/core/java/android/content/pm/ApplicationInfo.java
enum class EnforcementPolicy {
  kNoChecks             = 0,
  kJustWarn             = 1,  // keep checks enabled, but allow everything (enables logging)
  kDarkGreyAndBlackList = 2,  // ban dark grey & blacklist
  kBlacklistOnly        = 3,  // ban blacklist violations only
  kMax = kBlacklistOnly,
};
```
- kNoChecks：允许调用所有API，不做任何检测
- kJustWarn：允许调用所有API，但是对于私有API的调用会打印警告log
- kDarkGreyAndBlackList：会阻止调用dark grey或black list中的API
- kBlacklistOnly：会阻止调用black list中的API

ApiList,Action,EnforcementPolicy这三者的关系,Google其实是通过EnforcementPolicy的配置，将ApiList转成Action。
![ApiList,Action,EnforcementPolicy关系表](https://raw.githubusercontent.com/lqktz/document/master/res/Hidden_API_02.png)

```
  void SetHiddenApiEnforcementPolicy(hiddenapi::EnforcementPolicy policy) {
    hidden_api_policy_ = policy;
  }

  hiddenapi::EnforcementPolicy GetHiddenApiEnforcementPolicy() const {
    return hidden_api_policy_;
  }
```


#### 2.3.2 IsCallerTrusted

`fn_caller_is_trusted` 其实是通过函数指针调用到IsCallerTrusted函数

```
// Returns true if the first caller outside of the Class class or java.lang.invoke package
// is in a platform DEX file.
static bool IsCallerTrusted(Thread* self) REQUIRES_SHARED(Locks::mutator_lock_) {
  // Walk the stack and find the first frame not from java.lang.Class and not from java.lang.invoke.
  // This is very expensive. Save this till the last.
  struct FirstExternalCallerVisitor : public StackVisitor {
    explicit FirstExternalCallerVisitor(Thread* thread)
        : StackVisitor(thread, nullptr, StackVisitor::StackWalkKind::kIncludeInlinedFrames),
          caller(nullptr) {
    }

    bool VisitFrame() REQUIRES_SHARED(Locks::mutator_lock_) {
      ArtMethod *m = GetMethod();
      if (m == nullptr) {
        // Attached native thread. Assume this is *not* boot class path.
        caller = nullptr;
        return false;
      } else if (m->IsRuntimeMethod()) {
        // Internal runtime method, continue walking the stack.
        return true;
      }

      ObjPtr<mirror::Class> declaring_class = m->GetDeclaringClass();
      if (declaring_class->IsBootStrapClassLoaded()) {
        if (declaring_class->IsClassClass()) {
          return true;
        }
        // Check classes in the java.lang.invoke package. At the time of writing, the
        // classes of interest are MethodHandles and MethodHandles.Lookup, but this
        // is subject to change so conservatively cover the entire package.
        // NB Static initializers within java.lang.invoke are permitted and do not
        // need further stack inspection.
        ObjPtr<mirror::Class> lookup_class = mirror::MethodHandlesLookup::StaticClass();
        if ((declaring_class == lookup_class || declaring_class->IsInSamePackage(lookup_class))
            && !m->IsClassInitializer()) {
          return true;
        }
      }

      caller = m;
      return false;
    }
    ArtMethod* caller;
  };

  FirstExternalCallerVisitor visitor(self);
  visitor.WalkStack();
  return visitor.caller != nullptr &&
         hiddenapi::IsCallerTrusted(visitor.caller->GetDeclaringClass()); // 调用到hidden_api.h 里的IsCallerTrusted函数
}
```

`hiddenapi::IsCallerTrusted` ,是调用到了

`hidden_api.h`

```C++
// Returns true if the caller is either loaded by the boot strap class loader or comes from
// a dex file located in ${ANDROID_ROOT}/framework/.
ALWAYS_INLINE
inline bool IsCallerTrusted(ObjPtr<mirror::Class> caller,
                            ObjPtr<mirror::ClassLoader> caller_class_loader,
                            ObjPtr<mirror::DexCache> caller_dex_cache)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  if (caller_class_loader.IsNull()) {
    // Boot class loader. Boot classloader
    return true;
  }

  if (!caller_dex_cache.IsNull()) {
    const DexFile* caller_dex_file = caller_dex_cache->GetDexFile();
    if (caller_dex_file != nullptr && caller_dex_file->IsPlatformDexFile()) {
      // Caller is in a platform dex file. caller是平台dex文件
      return true;
    }
  }

  if (!caller.IsNull() &&
      caller->ShouldSkipHiddenApiChecks() &&
      Runtime::Current()->IsJavaDebuggable()) {
    // We are in debuggable mode and this caller has been marked trusted. 处于debuggable调试模式且caller已被标记可信任
    return true;
  }

  return false; // 除了以上三种情况都是false
}

```
所以`IsCallerTrusted`就是在将信任的caller排除在外.

#### 2.3.3 GetMemberActionImpl

`hidden_api.cc`

```C++
template<typename T>
Action GetMemberActionImpl(T* member,
                           HiddenApiAccessFlags::ApiList api_list,
                           Action action,
                           AccessMethod access_method) {
  DCHECK_NE(action, kAllow);

  // Get the signature, we need it later.
  MemberSignature member_signature(member); // 获取签名

  Runtime* runtime = Runtime::Current();

  // Check for an exemption first. Exempted APIs are treated as white list.
  // We only do this if we're about to deny, or if the app is debuggable. This is because:
  // - we only print a warning for light greylist violations for debuggable apps
  // - for non-debuggable apps, there is no distinction between light grey & whitelisted APIs.
  // - we want to avoid the overhead of checking for exemptions for light greylisted APIs whenever
  //   possible.
  const bool shouldWarn = kLogAllAccesses || runtime->IsJavaDebuggable();
  if (shouldWarn || action == kDeny) {
    if (member_signature.IsExempte(runtime->GetHiddenApiExemptions())) { // 判断是否可以豁免
      action = kAllow;
      // Avoid re-examining the exemption list next time.
      // Note this results in no warning for the member, which seems like what one would expect.
      // Exemptions effectively adds new members to the whitelist.
      MaybeWhitelistMember(runtime, member);
      return kAllow;
    }

    if (access_method != kNone) {
      // Print a log message with information about this class member access.
      // We do this if we're about to block access, or the app is debuggable.
      member_signature.WarnAboutAccess(access_method, api_list); // 判断是否通知警告
    }
  }

  if (kIsTargetBuild) {
    uint32_t eventLogSampleRate = runtime->GetHiddenApiEventLogSampleRate();
    // Assert that RAND_MAX is big enough, to ensure sampling below works as expected.
    static_assert(RAND_MAX >= 0xffff, "RAND_MAX too small");
    if (eventLogSampleRate != 0 &&
        (static_cast<uint32_t>(std::rand()) & 0xffff) < eventLogSampleRate) {
      member_signature.LogAccessToEventLog(access_method, action); // 输出EventLog
    }
  }

  if (action == kDeny) {
    // Block access
    return action;
  }

  // Allow access to this member but print a warning.
  DCHECK(action == kAllowButWarn || action == kAllowButWarnAndToast);

  if (access_method != kNone) {
    // Depending on a runtime flag, we might move the member into whitelist and
    // skip the warning the next time the member is accessed.
    MaybeWhitelistMember(runtime, member);

    // If this action requires a UI warning, set the appropriate flag.
    if (shouldWarn &&
        (action == kAllowButWarnAndToast || runtime->ShouldAlwaysSetHiddenApiWarningFlag())) {
      runtime->SetPendingHiddenApiWarning(true);
    }
  }

  return action;
}
```

`WarnAboutAccess` 用来输出log

`hidden_api.cc`

```
void MemberSignature::WarnAboutAccess(AccessMethod access_method,
                                      HiddenApiAccessFlags::ApiList list) {
  LOG(WARNING) << "Accessing hidden " << (type_ == kField ? "field " : "method ")
               << Dumpable<MemberSignature>(*this) << " (" << list << ", " << access_method << ")";
```

在代码介绍过程中有白名单,灰名单等,在源码中的目录如下

```
out/target/common/obj/PACKAGING/hiddenapi-light-greylist.txt
out/target/common/obj/PACKAGING/hiddenapi-dark-greylist.txt
out/target/common/obj/PACKAGING/hiddenapi-blacklist.txt

frameworks/base/config/hiddenapi-force-blacklist.txt
frameworks/base/config/hiddenapi-light-greylist.txt
frameworks/base/config/hiddenapi-private-dex.txt
frameworks/base/config/hiddenapi-public-dex.txt
frameworks/base/config/hiddenapi-removed-dex.txt
frameworks/base/config/hiddenapi-vendor-list.txt


out/target/product/product_name/system/etc/sysconfig/hiddenapi-package-whitelist.xml
frameworks/base/data/etc/hiddenapi-package-whitelist.xml
```

# 参考资料

- [对非 SDK 接口的限制 | Google 文档](https://developer.android.com/about/versions/pie/restrictions-non-sdk-interfaces?hl=zh-cn)
- [理解Android P内部API的限制调用机制](http://gityuan.com/2019/01/26/hidden_api/)
- [深入源码分析non-sdk并绕过Android 9.0反射限制](https://blog.csdn.net/XXOOYC/article/details/81585655)
