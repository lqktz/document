
`java_lang_Class.cc`
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

Reflection反射过程：
        Class_newInstance：对象实例化
        Class_getDeclaredConstructorInternal：构造方法
        Class_getDeclaredMethodInternal：获取方法
        Class_getDeclaredField：获取字段
        Class_getPublicFieldRecursive：获取字段
    kJNI的JNI调用过程：
        FindMethodID：查找方法
        FindFieldID：查找字段
    kLinking动态链接：
        UnstartedClassNewInstance
        UnstartedClassGetDeclaredConstructor
        UnstartedClassGetDeclaredMethod
        UnstartedClassGetDeclaredField

```

进入`hiddenapi::GetMemberAction`分析其返回值

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

  Action action = GetActionFromAccessFlags(member->GetHiddenApiAccessFlags());  // 依据名单类型,获取action类型->允许,警告,弹窗,阻止 
  if (action == kAllow) {
    // Nothing to do.
    return action;
  }

  // Member is hidden. Invoke `fn_caller_in_platform` and find the origin of the access.
  // This can be *very* expensive. Save it for last.
  if (fn_caller_is_trusted(self)) { // 通过函数指针调用到IsCallerTrusted函数
    // Caller is trusted. Exit.
    return kAllow;
  }

  // Member is hidden and caller is not in the platform.
  return detail::GetMemberActionImpl(member, api_list, action, access_method); //access_method是kReflection表示是反射调用
}

```

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









