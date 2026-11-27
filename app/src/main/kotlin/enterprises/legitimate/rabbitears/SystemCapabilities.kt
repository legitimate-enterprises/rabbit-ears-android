package enterprises.legitimate.rabbitears

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import enterprises.legitimate.rabbitears.kit.CapabilityGroup
import enterprises.legitimate.rabbitears.kit.CapabilitySource
import enterprises.legitimate.rabbitears.kit.CodecFacts
import enterprises.legitimate.rabbitears.kit.DeviceFacts
import enterprises.legitimate.rabbitears.kit.OutputDevice
import enterprises.legitimate.rabbitears.kit.PathFacts
import enterprises.legitimate.rabbitears.kit.ScreenFacts

/**
 * Reads capability facts from MediaCodecList, the activity's display, AudioManager and
 * ConnectivityManager, and reports display, output and path changes on the main thread until
 * [close]. Metered is the default network's missing NOT_METERED, low data is Data Saver.
 */
class SystemCapabilities(private val context: Context) : CapabilitySource {
    override var onChange: ((CapabilityGroup) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var network: NetworkCapabilities? =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    private var path = pathFacts()

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                network = caps
                repath()
            }

            override fun onLost(n: Network) {
                network = null
                repath()
            }
        }

    private val dataSaver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = repath()
        }

    private val outputs =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) =
                changed(CapabilityGroup.AudioRoute)

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) =
                changed(CapabilityGroup.AudioRoute)
        }

    private val display =
        object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) {
                if (id == ContextCompat.getDisplayOrDefault(context).displayId)
                    changed(CapabilityGroup.Display)
            }

            override fun onDisplayAdded(id: Int) = Unit

            override fun onDisplayRemoved(id: Int) = Unit
        }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback, main)
        ContextCompat.registerReceiver(
            context,
            dataSaver,
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        audio.registerAudioDeviceCallback(outputs, main)
        displays.registerDisplayListener(display, main)
    }

    fun close() {
        onChange = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(dataSaver) }
        audio.unregisterAudioDeviceCallback(outputs)
        displays.unregisterDisplayListener(display)
    }

    override fun facts(): DeviceFacts =
        DeviceFacts(
            codecs,
            screen(),
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
                OutputDevice(
                    it.type,
                    it.channelCounts.toList(),
                    it.encodings.toList(),
                    it.sampleRates.toList(),
                )
            },
            path,
        )

    private fun changed(group: CapabilityGroup) {
        onChange?.invoke(group)
    }

    private fun repath() {
        val now = pathFacts()
        if (now == path) return
        path = now
        changed(CapabilityGroup.Network)
    }

    private fun pathFacts(): PathFacts {
        val caps = network ?: return PathFacts()
        val unmetered =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                Build.VERSION.SDK_INT >= 30 &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        return PathFacts.of(
            unmetered = unmetered,
            dataSaver =
                connectivity.restrictBackgroundStatus ==
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
    }

    private fun screen(): ScreenFacts {
        val display = ContextCompat.getDisplayOrDefault(context)
        val mode = display.mode
        val hdr =
            if (Build.VERSION.SDK_INT >= 34) mode.supportedHdrTypes
            else @Suppress("DEPRECATION") display.hdrCapabilities?.supportedHdrTypes
        return ScreenFacts(
            mode.physicalWidth,
            mode.physicalHeight,
            mode.refreshRate.toDouble(),
            hdr?.toList().orEmpty(),
        )
    }

    private companion object {
        /** Group A, probed once per process; a system update restarts the app anyway. */
        val codecs: List<CodecFacts> by lazy {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filter { !it.isEncoder && !it.name.endsWith(".secure") }
                .flatMap { info -> info.supportedTypes.mapNotNull { facts(info, it) } }
        }

        fun facts(info: MediaCodecInfo, mime: String): CodecFacts? {
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return null
            val video = caps.videoCapabilities
            val width = video?.supportedWidths?.upper ?: 0
            val height = video?.supportedHeights?.upper ?: 0
            // The rate at 4K, or the largest size below it, rather than the rate at 8K.
            val rate =
                video?.let {
                    runCatching {
                        it.getSupportedFrameRatesFor(minOf(width, 3840), minOf(height, 2160)).upper
                    }
                        .getOrNull() ?: it.supportedFrameRates.upper.toDouble()
                } ?: 0.0
            return CodecFacts(
                mime.lowercase(),
                hardware(info),
                caps.profileLevels.map { it.profile to it.level },
                width,
                height,
                rate,
            )
        }

        fun hardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
            val name = info.name.lowercase()
            return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }
    }
}
