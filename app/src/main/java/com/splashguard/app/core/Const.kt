package com.splashguard.app.core

/** 全局常量。虚拟地址段 10.111.222.0/24 是私网保留段，不会与真实网络冲突。 */
object Const {
    const val VIRTUAL_TUN_ADDR = "10.111.222.1"
    const val VIRTUAL_DNS = "10.111.222.2"
    const val MTU = 1500

    /** 常见硬编码 DNS，让不查系统解析器的 App 也把查询交给我们 */
    val COMMON_DNS = listOf(
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1",
        "9.9.9.9",
        "114.114.114.114",
        "223.5.5.5", "223.6.6.6",
        "119.29.29.29"
    )

    const val UPSTREAM_TIMEOUT_MS = 2500
    const val DNS_PORT = 53
    const val MAX_LOG = 200

    const val CHANNEL_ID = "splashguard_vpn"
    const val NOTIF_ID = 1001

    const val ACTION_START = "com.splashguard.app.action.START"
    const val ACTION_STOP = "com.splashguard.app.action.STOP"
    const val ACTION_RESTART = "com.splashguard.app.action.RESTART"
}
