package com.friday.ai.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.ai.core.modes.PhoneLinks
import com.friday.ai.core.modes.Trigger
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** [PhoneLinks] on a real phone: paired and connected Bluetooth devices, the current Wi-Fi network. */
class AndroidPhoneLinks(private val context: Context) : PhoneLinks {

    private companion object {
        const val PROXY_TIMEOUT_MS = 2_000L
        const val UNKNOWN_SSID = "<unknown ssid>"
    }

    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    @SuppressLint("MissingPermission") // checked by permitted()
    override suspend fun bluetooth(): PhoneLinks.Bluetooth {
        if (!permitted()) return PhoneLinks.Bluetooth(false, emptyList(), emptyList())
        val adapter = manager.adapter ?: return PhoneLinks.Bluetooth(true, emptyList(), emptyList())
        val paired = runCatching { adapter.bondedDevices.mapNotNull { it.name } }.getOrDefault(emptyList())
        val connected =
            (connectedOn(adapter, BluetoothProfile.A2DP) + connectedOn(adapter, BluetoothProfile.HEADSET)).distinct()
        return PhoneLinks.Bluetooth(true, paired, connected)
    }

    /** Devices connected over [profile]: car audio and headphones are on A2DP or the headset profile. */
    @SuppressLint("MissingPermission")
    private suspend fun connectedOn(adapter: BluetoothAdapter, profile: Int): List<String> =
        withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine { done ->
                val asked = adapter.getProfileProxy(
                    context,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            val names = runCatching { proxy.connectedDevices.mapNotNull { it.name } }
                                .getOrDefault(emptyList())
                            adapter.closeProfileProxy(p, proxy)
                            if (done.isActive) done.resume(names)
                        }

                        override fun onServiceDisconnected(p: Int) = Unit
                    },
                    profile
                )
                if (!asked && done.isActive) done.resume(emptyList())
            }
        }.orEmpty()

    @Suppress("DEPRECATION") // connectionInfo is still how an app reads the SSID, with location granted
    override suspend fun wifi(): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = runCatching { wifi.connectionInfo?.ssid }.getOrNull()?.trim('"')
        return ssid?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
    }

    fun permitted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Watches the phone's links while Friday's service runs: a Bluetooth device
 * connecting or going, the charger, the Wi-Fi network. Each change goes to
 * [onChange] once — Bluetooth reconnects in bursts, so a repeat of the same
 * change within a short time is dropped.
 */
class LinkWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val links: AndroidPhoneLinks,
    private val onChange: suspend (trigger: Trigger, value: String, connected: Boolean) -> Unit
) {

    private companion object {
        const val TAG = "LinkWatcher"
        const val REPEAT_MS = 30_000L

        /** The SSID isn't readable the instant the network appears. */
        const val WIFI_SETTLE_MS = 1_500L
    }

    private var last: Pair<String, Long>? = null
    private var ssid: String? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> emit(Trigger.CHARGER, "", true)
                Intent.ACTION_POWER_DISCONNECTED -> emit(Trigger.CHARGER, "", false)
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (!links.permitted()) return
                    @Suppress("DEPRECATION")
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val name = runCatching { device?.name }.getOrNull() ?: return
                    emit(Trigger.BLUETOOTH, name, intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)
                }
            }
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                delay(WIFI_SETTLE_MS)
                links.wifi()?.let { name ->
                    ssid = name
                    onChangeOnce(Trigger.WIFI, name, true)
                }
            }
        }

        override fun onLost(network: Network) {
            ssid?.let { name ->
                ssid = null
                emit(Trigger.WIFI, name, false)
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // System broadcasts reach a not-exported receiver; nothing else needs to.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching {
            connectivity().registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback
            )
        }.onFailure { Log.w(TAG, "No Wi-Fi callback: ${it.message}") }
        scope.launch { ssid = links.wifi() }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { connectivity().unregisterNetworkCallback(wifiCallback) }
    }

    private fun emit(trigger: Trigger, value: String, connected: Boolean) {
        scope.launch { onChangeOnce(trigger, value, connected) }
    }

    private suspend fun onChangeOnce(trigger: Trigger, value: String, connected: Boolean) {
        val key = "${trigger.key}|$value|$connected"
        val now = System.currentTimeMillis()
        last?.let { (k, at) -> if (k == key && now - at < REPEAT_MS) return }
        last = key to now
        Log.i(TAG, "${trigger.key} ${if (connected) "connected" else "disconnected"}: $value")
        runCatching { onChange(trigger, value, connected) }.onFailure { Log.e(TAG, "Mode event failed: ${it.message}") }
    }

    private fun connectivity() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
}
