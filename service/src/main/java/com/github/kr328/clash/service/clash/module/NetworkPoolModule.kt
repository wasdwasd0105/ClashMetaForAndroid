package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.FileDescriptor

class NetworkPoolModule(service: Service) : Module<Unit>(service) {
    companion object {
        // Singleton reference so TunModule (in the same :background process)
        // can call bind() regardless of which service installed the pool.
        @Volatile
        var instance: NetworkPoolModule? = null
            private set
    }

    private val connectivity = service.getSystemService<ConnectivityManager>()!!

    @Volatile private var wifi: Network? = null
    @Volatile private var cellular: Network? = null

    private val wifiCallback = transportCallback("wifi") { wifi = it }
    private val cellularCallback = transportCallback("cellular") { cellular = it }

    fun bind(fd: Int, tag: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val net = when (tag) {
            "wifi" -> wifi
            "cellular", "cellular-only" -> cellular
            else -> null
        }
        if (net == null) {
            Log.w("NetworkPool bind skip fd=$fd tag=$tag (no network handle yet)")
            return false
        }
        return try {
            net.bindSocket(toFileDescriptor(fd))
            Log.i("NetworkPool bind ok fd=$fd tag=$tag net=$net")
            true
        } catch (e: Exception) {
            Log.w("NetworkPool bind failed fd=$fd tag=$tag", e)
            false
        }
    }

    private fun transportCallback(
        name: String,
        onChange: (Network?) -> Unit,
    ) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("NetworkPool onAvailable $name=$network")
            onChange(network)
        }

        override fun onLost(network: Network) {
            Log.i("NetworkPool onLost $name=$network")
            onChange(null)
        }
    }

    private fun request(transport: Int): NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addTransportType(transport)
        .build()

    private fun toFileDescriptor(fd: Int): FileDescriptor {
        val jfd = FileDescriptor()
        val setInt = FileDescriptor::class.java.getDeclaredMethod(
            "setInt\$", Int::class.javaPrimitiveType,
        )
        setInt.invoke(jfd, fd)
        return jfd
    }

    override suspend fun run() {
        instance = this
        connectivity.requestNetwork(request(NetworkCapabilities.TRANSPORT_WIFI), wifiCallback)
        connectivity.requestNetwork(request(NetworkCapabilities.TRANSPORT_CELLULAR), cellularCallback)

        try {
            suspendCancellableCoroutine<Unit> { /* park until cancel */ }
        } finally {
            withContext(NonCancellable) {
                runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
                runCatching { connectivity.unregisterNetworkCallback(cellularCallback) }
                if (instance === this@NetworkPoolModule) instance = null
            }
        }
    }
}
