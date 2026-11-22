package com.cameroncurrin.camzone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Talks to the PYXIS over Bluetooth Low Energy using Blackmagic's published
 * Bluetooth Camera Control service. Messages use the Blackmagic SDI Camera Control Protocol.
 */
@SuppressLint("MissingPermission")
class PyxisBle(private val context: Context) : CameraLink {

    companion object {
        val SERVICE: UUID = UUID.fromString("291D567A-6D75-11E6-8B77-86F30CA893D3")
        val OUTGOING: UUID = UUID.fromString("5DD3465F-1AEE-4299-8493-D2ECA2F8E1BB")
        val INCOMING: UUID = UUID.fromString("B864E140-76A0-416A-BF30-5876504537D9")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Builds one "change configuration" packet, padded to a 32-bit boundary. */
        fun packet(category: Int, parameter: Int, type: Int, data: ByteArray): ByteArray {
            val len = 4 + data.size
            val total = 4 + len
            val padded = (total + 3) / 4 * 4
            val out = ByteArray(padded)
            out[0] = 255.toByte()          // broadcast
            out[1] = len.toByte()          // command length (no header, no padding)
            out[2] = 0                     // command 0: change configuration
            out[3] = 0
            out[4] = category.toByte()
            out[5] = parameter.toByte()
            out[6] = type.toByte()
            out[7] = 0                     // assign value
            data.copyInto(out, 8)
            return out
        }

        fun fixed16(v: Double): ByteArray {
            val raw = (v * 2048).roundToInt().coerceIn(-32768, 32767)
            return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(raw.toShort()).array()
        }

        fun int32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

        fun irisPacket(t: Double) = packet(0, 2, 128, fixed16(2 * Expo.log2(t)))   // aperture value AV, f = sqrt(2^AV)
        fun isoPacket(iso: Int) = packet(1, 14, 3, int32(iso))
        fun shutterPacket(angle: Double) = packet(1, 11, 3, int32((angle * 100).roundToInt()))
        fun lutPacket(enabled: Boolean) = packet(1, 15, 1, byteArrayOf(1, if (enabled) 1 else 0)) // custom LUT
    }

    data class Found(val device: BluetoothDevice, val name: String)

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var outgoing: BluetoothGattCharacteristic? = null
    private val writes = LinkedList<ByteArray>()
    private var writing = false
    private var scanCallback: ScanCallback? = null
    private var retries = 0

    val available get() = adapter?.isEnabled == true

    fun scan(onFound: (Found) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            status("Bluetooth is off on the phone")
            return
        }
        stopScan()
        val seen = mutableSetOf<String>()
        fun looksLikeCamera(name: String?) = name != null &&
            listOf("pyxis", "blackmagic", "pocket", "cinema", "ursa", "bmpcc").any { name.lowercase().contains(it) }
        // Cameras already paired with this phone show up right away (a paired camera may not advertise again)
        adapter?.bondedDevices?.forEach { d ->
            if (looksLikeCamera(d.name) && seen.add(d.address)) onFound(Found(d, (d.name ?: d.address) + " (paired)"))
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = d.name ?: result.scanRecord?.deviceName
                val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true
                if ((hasService || looksLikeCamera(name)) && seen.add(d.address)) onFound(Found(d, name ?: d.address))
            }
        }
        scanCallback = cb
        // No filter: some cameras don't put the service ID in their advert, so match by name too
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        status("Searching for the PYXIS…")
        handler.postDelayed({ stopScan() }, 15000)
    }

    fun stopScan() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            if (state == BluetoothDevice.BOND_BONDED) {
                status("Paired. Finishing connection…")
                gatt?.let { enableNotifications(it) }
            } else if (state == BluetoothDevice.BOND_NONE) {
                status("Pairing didn't finish. Check the 6-digit code on the PYXIS and try again.")
            }
        }
    }

    fun connect(found: Found) {
        stopScan()
        disconnect()
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                context, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (_: Exception) { }
        retries = 0
        AppState.link.value = LinkState(LinkType.BLUETOOTH, false, found.name, "Connecting…")
        gatt = found.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        outgoing = null
        writes.clear()
        writing = false
        try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) { }
        AppState.link.value = LinkState()
        AppState.settingsFromCamera.value = false
    }

    private fun status(s: String) {
        AppState.link.value = AppState.link.value.copy(status = s)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected. Reading the camera…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!AppState.link.value.connected && st != 0 && retries < 2) {
                    // Android often fails the first connection (code 133); try again
                    retries++
                    status("Retrying connection…")
                    g.close()
                    handler.postDelayed({ gatt = g.device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) }, 800)
                    return
                }
                AppState.link.value = AppState.link.value.copy(connected = false,
                    status = if (st != 0) "Couldn't connect (code $st). On the PYXIS, open Setup > Bluetooth and make sure it's on, then tap Find again." else "Disconnected")
                AppState.settingsFromCamera.value = false
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) {
                status("This device isn't a Blackmagic camera")
                return
            }
            outgoing = svc.getCharacteristic(OUTGOING)
            if (g.device.bondState != BluetoothDevice.BOND_BONDED) {
                status("Enter the 6-digit code shown on the PYXIS screen")
                g.device.createBond()
            } else {
                enableNotifications(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            if (st == BluetoothGatt.GATT_SUCCESS) {
                AppState.link.value = AppState.link.value.copy(connected = true, status = "Connected over Bluetooth")
            } else {
                status("Connected, but the camera didn't share its settings (code $st). Try pairing again.")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) c.value?.let { parseIncoming(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            parseIncoming(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            writing = false
            if (st != BluetoothGatt.GATT_SUCCESS) status("The camera refused a change (code $st)")
            pump()
        }
    }

    private fun enableNotifications(g: BluetoothGatt) {
        val inc = g.getService(SERVICE)?.getCharacteristic(INCOMING) ?: return
        g.setCharacteristicNotification(inc, true)
        val d = inc.getDescriptor(CCCD) ?: return
        val v = if (inc.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v)
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    /** Reads settings the camera reports, so the app always knows the real values. */
    fun parseIncoming(b: ByteArray) {
        var o = 0
        while (o + 8 <= b.size) {
            val len = b[o + 1].toInt() and 0xFF
            val cmd = b[o + 2].toInt() and 0xFF
            if (cmd == 0 && len >= 4 && o + 4 + len <= b.size) {
                val cat = b[o + 4].toInt() and 0xFF
                val par = b[o + 5].toInt() and 0xFF
                val data = ByteBuffer.wrap(b, o + 8, len - 4).order(ByteOrder.LITTLE_ENDIAN)
                when {
                    cat == 0 && par == 2 && len - 4 >= 2 -> {
                        val av = data.short / 2048.0
                        AppState.fromCamera(t = sqrt(2.0.pow(av)))
                    }
                    cat == 1 && par == 14 && len - 4 >= 4 -> AppState.fromCamera(iso = data.int)
                    cat == 1 && par == 11 && len - 4 >= 4 -> AppState.fromCamera(angle = data.int / 100.0)
                    cat == 1 && par == 9 && len - 4 >= 2 -> {
                        val fps = data.short.toInt()
                        if (fps in 1..240) AppState.fromCamera(fps = fps.toDouble())
                    }
                }
            }
            val total = 4 + len
            o += maxOf(4, (total + 3) / 4 * 4)
        }
    }

    private fun queue(p: ByteArray) {
        writes.add(p)
        pump()
    }

    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val c = outgoing ?: return
        val p = writes.poll() ?: return
        writing = true
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, p, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.value = p
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    override val isConnected get() = AppState.link.value.type == LinkType.BLUETOOTH && AppState.link.value.connected

    override fun setIris(t: Double) = queue(irisPacket(t))
    override fun setIso(iso: Int) = queue(isoPacket(iso))
    override fun setShutterAngle(angle: Double) = queue(shutterPacket(angle))
    fun setCustomLut(enabled: Boolean) = queue(lutPacket(enabled))
}

/** What the Apply buttons need from either connection type. */
interface CameraLink {
    val isConnected: Boolean
    fun setIris(t: Double)
    fun setIso(iso: Int)
    fun setShutterAngle(angle: Double)
    fun disconnect()
}
