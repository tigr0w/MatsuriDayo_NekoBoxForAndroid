package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

class SagerConnection(private var listenForDeath: Boolean = false) : ServiceConnection,
    IBinder.DeathRecipient {

    companion object {
        val serviceClass
            get() = when (DataStore.serviceMode) {
                Key.MODE_PROXY -> ProxyService::class
                Key.MODE_VPN -> VpnService::class //   Key.MODE_TRANS -> TransproxyService::class
                else -> throw UnknownError()
            }.java
    }

    interface Callback {
        // smaller ISagerNetServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        fun cbTrafficUpdate(data: TrafficData) {}

        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)

        fun missingPlugin(profileName: String, pluginName: String) {}
        fun routeAlert(type: Int, routeName: String) {}

        fun onServiceConnected(service: ISagerNetService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    private var connectionActive = false
    private var callbackRegistered = false
    private var callback: Callback? = null
    private val serviceCallback = object : ISagerNetServiceCallback.Stub() {

        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            val s = BaseService.State.values()[state]
            DataStore.serviceState = s
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.stateChanged(s, profileName, msg)
            }
        }

        override fun cbSpeedUpdate(stats: SpeedDisplayData) {
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.cbSpeedUpdate(stats)
            }
        }

        override fun cbTrafficUpdate(stats: TrafficData) {
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.cbTrafficUpdate(stats)
            }
        }

        override fun missingPlugin(profileName: String, pluginName: String) {
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.missingPlugin(profileName, pluginName)
            }
        }

        override fun routeAlert(type: Int, routeName: String) {
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.routeAlert(type, routeName)
            }
        }

        override fun updateWakeLockStatus(acquired: Boolean) {
        }

        override fun cbLogUpdate(str: String?) {
            DataStore.postLogListener?.let {
                if (str != null) {
                    it(str)
                }
            }
        }

    }

    private var binder: IBinder? = null

    var service: ISagerNetService? = null

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        this.binder = binder
        val service = ISagerNetService.Stub.asInterface(binder)!!
        this.service = service
        try {
            if (listenForDeath) binder.linkToDeath(this, 0)
            check(!callbackRegistered)
            service.registerCallback(serviceCallback)
            callbackRegistered = true
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        callback!!.onServiceConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        unregisterCallback()
        callback?.onServiceDisconnected()
        service = null
        binder = null
    }

    override fun binderDied() {
        service = null
        callbackRegistered = false
        callback?.also { runOnMainDispatcher { it.onBinderDied() } }
    }

    private fun unregisterCallback() {
        val service = service
        if (service != null && callbackRegistered) try {
            service.unregisterCallback(serviceCallback)
        } catch (_: RemoteException) {
        }
        callbackRegistered = false
    }

    fun connect(context: Context, callback: Callback) {
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        this.callback = callback
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        if (connectionActive) try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }   // ignore
        connectionActive = false
        if (listenForDeath) try {
            binder?.unlinkToDeath(this, 0)
        } catch (_: NoSuchElementException) {
        }
        binder = null
        service = null
        callback = null
    }
}
