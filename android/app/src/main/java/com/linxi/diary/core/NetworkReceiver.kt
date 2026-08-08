package com.linxi.diary.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.linxi.diary.sync.StatusSyncManager

/** 网络变化监听：恢复网络时重连 WebSocket + 立即上报一次状态 */
class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.net.ConnectivityManager.CONNECTIVITY_ACTION) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val hasNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (hasNet) {
            StatusSyncManager.connect()
            StatusSyncManager.pushNow()
        }
    }
}
