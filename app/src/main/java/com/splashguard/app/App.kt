package com.splashguard.app

import android.app.Application
import com.splashguard.app.rules.RuleStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 规则只在本机加载，不做任何网络请求
        RuleStore.reload(this)
    }
}
