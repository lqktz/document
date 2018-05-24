#Launcher启动  

流程:  
systemserver.java->main()->run()->startOtherService  
    mActivityManagerService.systemReady(Runnable)  
        RunnablemSupervisor.scheduleResumeTopActivities()  
    ActivityManagerService.systemReady(final Runnable goingCallback)  
        startHomeActivityLocked(mCurrentUserId, "systemReady")  
            getHomeIntent()  
            mActivityStarter.startHomeActivityLocked(intent, aInfo, reason)  
            ActivityStarter.java->void startHomeActivityLocked(Intent intent, ActivityInfo aInfo, String reason)  
                mSupervisor.scheduleResumeTopActivities()  
                ActivityStackSupervisor.java->scheduleResumeTopActivities()
                    mHandler.sendEmptyMessage(RESUME_TOP_ACTIVITY_MSG)
                        resumeFocusedStackTopActivityLocked()




