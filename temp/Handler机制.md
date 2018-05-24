#Handler机制  
**android N**

对Handler的分析从java层和native层两方面来分析.  
***
##Handler java层 
主要使用到四个类:  
```
aosp/frameworks/base/core/java/android/os/Handler.java
aosp/frameworks/base/core/java/android/os/Message.java
aosp/frameworks/base/core/java/android/os/Looper.java
aosp/frameworks/base/core/java/android/os/MessageQueue.java
```
下面分析这四个主要的类:  
###Looper
####Looper的creat
```java
     /** Initialize the current thread as a looper.
      * This gives you a chance to create handlers that then reference
      * this looper, before actually starting the loop. Be sure to call
      * {@link #loop()} after calling this method, and end it by calling
      * {@link #quit()}.
      */
    public static void prepare() {
        prepare(true);//true表示允许退出,非主线程的Looper都可以设置成true
    }

    private static void prepare(boolean quitAllowed) {
        if (sThreadLocal.get() != null) {//已经有Looper就不能再此创建
            throw new RuntimeException("Only one Looper may be created per thread");
        }
        //sThreadLocal是一个ThreadLocal类型,每一个线程都有一个线程本地存储区域(Thread Local Storage,TLs),
        //ThreadLocal的get和set方法可以操作该区域,这里的Looper就是存储在该区域.
        sThreadLocal.set(new Looper(quitAllowed));
    }

    /**
     * Initialize the current thread as a looper, marking it as an
     * application's main looper. The main looper for your application
     * is created by the Android environment, so you should never need
     * to call this function yourself.  See also: {@link #prepare()}
     */
    public static void prepareMainLooper() {//创建主线程里面的Loop
        prepare(false);//主线程的Looper不允许退出
        synchronized (Looper.class) {
            if (sMainLooper != null) {
                throw new IllegalStateException("The main Looper has already been prepared.");
            }
            sMainLooper = myLooper();
        }
    }
```
每个线程中Loop都是用以上三种方法的其中一种方法创建的.在`prepare(boolean)`方法里new了一个Looper:  
```java
    private Looper(boolean quitAllowed) {
        mQueue = new MessageQueue(quitAllowed);//每个Looper中都用一个MessageQueue
        mThread = Thread.currentThread();
    }
```
####Looper进入loop  
```java
    /**
     * Run the message queue in this thread. Be sure to call
     * {@link #quit()} to end the loop.
     */
    public static void loop() {
        final Looper me = myLooper();//从TLS获取Looper对象
        if (me == null) {
            throw new RuntimeException("No Looper; Looper.prepare() wasn't called on this thread.");
        }
        final MessageQueue queue = me.mQueue;

        // Make sure the identity of this thread is that of the local process,
        // and keep track of what that identity token actually is.
        Binder.clearCallingIdentity();
        final long ident = Binder.clearCallingIdentity();

        for (;;) {//进入loop的循环方法
            //1.读取queue中的下一条message
            Message msg = queue.next(); // might block可能会阻塞
            if (msg == null) {//没有消息退出
                // No message indicates that the message queue is quitting.
                return;
            }

            // This must be in a local variable, in case a UI event sets the logger
            final Printer logging = me.mLogging;
            if (logging != null) {
                logging.println(">>>>> Dispatching to " + msg.target + " " +
                        msg.callback + ": " + msg.what);
            }

            ///省略 M: ANR mechanism for Message Monitor Service代码
            final long traceTag = me.mTraceTag;
            if (traceTag != 0) {
                Trace.traceBegin(traceTag, msg.target.getTraceName(msg));
            }
            try {
            //2.将message 分发给对应的target,该target就是一个handle对象
                msg.target.dispatchMessage(msg);//分发消息
            } finally {
                if (traceTag != 0) {
                    Trace.traceEnd(traceTag);
                }
            }

            if (logging != null) {
                logging.println("<<<<< Finished to " + msg.target + " " + msg.callback);
            }

            ///省略 M: ANR mechanism for Message Monitor Service 代码
            // Make sure that during the course of dispatching the
            // identity of the thread wasn't corrupted.
            final long newIdent = Binder.clearCallingIdentity();
            if (ident != newIdent) {
                Log.wtf(TAG, "Thread identity changed from 0x"
                        + Long.toHexString(ident) + " to 0x"
                        + Long.toHexString(newIdent) + " while dispatching to "
                        + msg.target.getClass().getName() + " "
                        + msg.callback + " what=" + msg.what);
            }
            //3.将使用完的Message放回message池
            msg.recycleUnchecked();//使用消息池,可以避免重复的创建message对象消耗资源,message池大小是50
        }
    }
```
在Looper.java中loop方法是一个核心方法,其他方法都是在围绕该方法.  
####Looper的quit  
Looper的创建时,有一个quitAllowed的boolean值,接下来分析该值的使用:  
```java
    public void quit() {
        mQueue.quit(false);
    }

    public void quitSafely() {
        mQueue.quit(true);
    }
```
两个方法都调用了MessageQueue的quit方法:  
```java
    void quit(boolean safe) {
        if (!mQuitAllowed) {
            throw new IllegalStateException("Main thread not allowed to quit.");
        }

        synchronized (this) {
            if (mQuitting) {
                return;
            }
            mQuitting = true;

            if (safe) {
                removeAllFutureMessagesLocked();//移除所有还没有触发的消息
            } else {
                removeAllMessagesLocked();//移除所有的消息
            }

            // We can assume mPtr != 0 because mQuitting was previously false.
            nativeWake(mPtr);
        }
    }
```
####常用方法  
#####getMainLooper  
获取所属进程主线程的Looper  
```java
    /**
     * Returns the application's main looper, which lives in the main thread of the application.
     */
    public static Looper getMainLooper() {
        synchronized (Looper.class) {
            return sMainLooper;
        }
    }
```
#####myLooper()  
获取当前线程的Looper  
```java
    /**
     * Return the Looper object associated with the current thread.  Returns
     * null if the calling thread is not associated with a Looper.
     */
    public static @Nullable Looper myLooper() {
        return sThreadLocal.get();
    }
```
#####isCurrentThread()
判断当前线程是不是looper所属的线程
```java
    /**
     * Returns true if the current thread is this looper's thread.
     */
    public boolean isCurrentThread() {
        return Thread.currentThread() == mThread;
    }
```
###Handler  
####Handler的creat  
```java
    public Handler(Looper looper, Callback callback, boolean async) {
        mLooper = looper;
        mQueue = looper.mQueue;
        mCallback = callback;
        mAsynchronous = async;
    }
```
Handler最多有3个参数,这三个参数都可有可无,所以排列组合一下,总共有7中Handler的构造方法:  
```java
public Handler();
public Handler(Callback callback);
public Handler(boolean async);
public Handler(Callback callback, boolean async);
public Handler(Looper looper);
public Handler(Looper looper, Callback callback);
public Handler(Looper looper, Callback callback, boolean async);
```
前四种未指定Looper,表示是使用的当前线程TLS里存储的Looper;  
未指定消息的处理方式是同步还是异步的(即async),默认是同步;  
callback没有设置,默认是null;  
####Handler的dispatchMessage
在Looper.loop()中调用`msg.target.dispatchMessage(msg);`target就是一个Handler
```java
    /**
     * Handle system messages here.
     */
    public void dispatchMessage(Message msg) {
        if (msg.callback != null) {
        //1.message有回调方法方法,调用message的callback.run()处理消息
            handleCallback(msg);
        } else {
            if (mCallback != null) {
        //2.Handler存在回调方法,调用handler的callback的handleMessage(msg)
                if (mCallback.handleMessage(msg)) {
                    return;
                }
            }
        //3.调用Handler的callback方法
            handleMessage(msg);
        }
    }
```
大多是都是使用3方法,在Handler的Handler的handleMessage方法中实现自己的逻辑.  
```java
    /**
     * Subclasses must implement this to receive messages.
     */
    public void handleMessage(Message msg) {
    }
```
handleMessage方法是空方法,Handler的子类要继承该类去在handleMessage里面实现自己的逻辑.  
####Handler消息的发送  
#####sendMessage  
```
    public final boolean sendMessage(Message msg)
    {
        return sendMessageDelayed(msg, 0);
    }
```
```
    public final boolean sendEmptyMessage(int what)
    {
        return sendEmptyMessageDelayed(what, 0);
    }
```
```
    public final boolean sendEmptyMessageDelayed(int what, long delayMillis) {
        Message msg = Message.obtain();
        msg.what = what;
        return sendMessageDelayed(msg, delayMillis);
    }
```
```

    public final boolean sendEmptyMessageAtTime(int what, long uptimeMillis) {
        Message msg = Message.obtain();
        msg.what = what;
        return sendMessageAtTime(msg, uptimeMillis);
    }
```
```
    public final boolean sendMessageDelayed(Message msg, long delayMillis)
    {
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        return sendMessageAtTime(msg, SystemClock.uptimeMillis() + delayMillis);
    }
```
```
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        MessageQueue queue = mQueue;
        if (queue == null) {
            RuntimeException e = new RuntimeException(
                    this + " sendMessageAtTime() called with no mQueue");
            Log.w("Looper", e.getMessage(), e);
            return false;
        }
        return enqueueMessage(queue, msg, uptimeMillis);
    }
```
```
    public final boolean sendMessageAtFrontOfQueue(Message msg) {
        MessageQueue queue = mQueue;
        if (queue == null) {
            RuntimeException e = new RuntimeException(
                this + " sendMessageAtTime() called with no mQueue");
            Log.w("Looper", e.getMessage(), e);
            return false;
        }
        return enqueueMessage(queue, msg, 0);
    }
```
















***
##Handler native层 





