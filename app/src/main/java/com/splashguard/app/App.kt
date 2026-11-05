package com.splashguard.app

import android.app.Application
import com.splashguard.app.diag.CpuSampler
import com.splashguard.app.log.BlockLog
import com.splashguard.app.rules.RuleStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 规则只在本机加载，不做任何网络请求
        RuleStore.reload(this)
        // 日志落盘初始化：让进程被回收后仍能看到历史拦截记录
        BlockLog.init(this)
        // 诊断报告里的进程 CPU% 从进程启动开始算
        CpuSampler.markStart()
    }
}
