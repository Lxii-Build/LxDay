package com.linxi.diary.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.ProfileSyncPolicy
import com.linxi.diary.sync.SharingRuntimePolicy
import com.linxi.diary.util.Logs

/**
 * 网络可用性监听：恢复网络时立刻重连 WebSocket 并重新采集上报。
 *
 * 取代原 `NetworkReceiver`：那个类在 AndroidManifest 里静态注册
 * `android.net.conn.CONNECTIVITY_CHANGE`，而该隐式广播**自 API 24 起不再投递给清单接收器**
 * （本项目 minSdk=33），所以它 100% 不生效——断网恢复后既不重连也不重推，
 * 只能等 WS 退避重连碰运气。这里改用 ConnectivityManager.NetworkCallback 动态注册。
 *
 * 生命周期：由 [StatusForegroundService] 在 onCreate 注册、onDestroy 注销，
 * 与其它动态注册的接收器（屏幕状态等）保持一致。
 */
object NetworkWatcher {

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null

    /** 上一次已知的"有可用网络"状态，用于只在 false→true 的跳变上触发重连。 */
    @Volatile
    private var wasAvailable = false

    @Synchronized
    fun register(context: Context) {
        if (callback != null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: run {
                Logs.w("Net", "ConnectivityManager unavailable; network watch disabled")
                return
            }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkUp("available")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // 已连上但尚未验证连通（如登录门户）时不算恢复，避免无效重连风暴。
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (validated) onNetworkUp("validated")
            }

            override fun onLost(network: Network) {
                wasAvailable = false
                Logs.i("Net", "Network lost")
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(req, cb)
            cm = manager
            callback = cb
            appContext = context.applicationContext
            wasAvailable = hasInternet(manager)
            Logs.i("Net", "Network watcher registered")
        } catch (t: Throwable) {
            Logs.w("Net", "Failed to register network callback", t)
        }
    }

    @Synchronized
    fun unregister() {
        val manager = cm
        val cb = callback
        callback = null
        cm = null
        appContext = null
        if (manager != null && cb != null) {
            runCatching { manager.unregisterNetworkCallback(cb) }
                .onFailure { Logs.w("Net", "Failed to unregister network callback", it) }
        }
    }

    /**
     * 网络恢复：重连 WS 并重新采集上报。
     * 只在 false→true 跳变时动作——onAvailable 与 onCapabilitiesChanged 会重复回调。
     */
    private fun onNetworkUp(reason: String) {
        if (wasAvailable) return
        wasAvailable = true
        Logs.i("Net", "Network restored ($reason); reconnecting")
        if (!ProfileSyncPolicy.canConnectNow()) return
        ProfileRuntime.connectAndRefreshIfEligible()
        if (SharingRuntimePolicy.canRunNow()) {
            // 走 syncNow 而非 pushNow：必须先重新采集，否则推的是断网前的旧快照。
            StatusForegroundService.syncNow(appContext)
        }
    }

    private fun hasInternet(manager: ConnectivityManager): Boolean {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
