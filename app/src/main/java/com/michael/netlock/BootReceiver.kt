package com.michael.netlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/**
 * 开机兜底: service.d 脚本已能生效, 这里再补一次,
 * 主要用于应用更新后或脚本被清理的场景。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = RuleStore(context)
        if (!store.enabled || !store.autoBoot) return
        val pending = goAsync()
        Executors.newSingleThreadExecutor().execute {
            try {
                Firewall.apply(context.applicationContext, store)
            } finally {
                pending.finish()
            }
        }
    }
}
