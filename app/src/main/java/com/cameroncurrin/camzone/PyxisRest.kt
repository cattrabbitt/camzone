package com.cameroncurrin.camzone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Talks to the PYXIS with Blackmagic's Camera Control REST API. Works over the USB-C cable
 * (phone tethered to the camera) or over Ethernet through a router. The camera uses its own
 * certificate, so this connection trusts the camera it was pointed at.
 */
class PyxisRest(private val scope: CoroutineScope) : CameraLink {

    private var base: String? = null
    private var poll: Job? = null
    private var angleScale = 1.0 // some firmware reports shutter angle x100

    override val isConnected get() = AppState.link.value.type == LinkType.NETWORK && AppState.link.value.connected

    private val trustAll: SSLContext by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = trustAll.socketFactory
            c.setHostnameVerifier { _, _ -> true }
        }
        c.requestMethod = method
        c.connectTimeout = 1500
        c.readTimeout = 2000
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun get(root: String, path: String): JSONObject? = try {
        val c = open("$root/control/api/v1$path", "GET")
        if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }

    private fun put(path: String, body: JSONObject): Boolean {
        val root = base ?: return false
        return try {
            val c = open("$root/control/api/v1$path", "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode in 200..299
        } catch (e: Exception) { false }
    }

    /** Connects to a typed address: an IP like 192.168.42.50 or a name like pyxis-6k.local */
    fun connect(address: String) {
        val a = address.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        AppState.link.value = LinkState(LinkType.NETWORK, false, a, "Connecting…")
        scope.launch(Dispatchers.IO) {
            val root = listOf("https://$a", "http://$a").firstOrNull { get(it, "/video/iso") != null }
            if (root == null) {
                AppState.link.value = LinkState(LinkType.NETWORK, false, a,
                    "No answer from $a. Check the cable or address, and that Remote Control is on.")
                return@launch
            }
            base = root
            AppState.link.value = LinkState(LinkType.NETWORK, true, a, "Connected over cable or network")
            startPolling()
        }
    }

    /** Looks for the camera on every local network the phone is on, including a USB-C tether. */
    fun find(onFound: (String) -> Unit, onDone: (Int) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val prefixes = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress!!.substringBeforeLast('.') }
                .distinct()
            AppState.link.value = AppState.link.value.copy(status = "Searching ${prefixes.size} network(s)…")
            val gate = Semaphore(48)
            val found = java.util.concurrent.atomic.AtomicInteger(0)
            prefixes.flatMap { p -> (1..254).map { "$p.$it" } }.map { ip ->
                async {
                    gate.withPermit {
                        if (portOpen(ip, 443) || portOpen(ip, 80)) {
                            val ok = get("https://$ip", "/video/iso") != null || get("http://$ip", "/video/iso") != null
                            if (ok) {
                                found.incrementAndGet()
                                withContext(Dispatchers.Main) { onFound(ip) }
                            }
                        }
                    }
                }
            }.awaitAll()
            withContext(Dispatchers.Main) { onDone(found.get()) }
        }
    }

    private fun portOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 250); true }
    } catch (e: Exception) { false }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch(Dispatchers.IO) {
            var misses = 0
            while (isActive) {
                val root = base ?: break
                val iso = get(root, "/video/iso")
                if (iso == null) {
                    misses++
                    if (misses >= 3) {
                        AppState.link.value = AppState.link.value.copy(connected = false, status = "Lost the camera. Check the cable.")
                        AppState.settingsFromCamera.value = false
                    }
                } else {
                    misses = 0
                    if (!AppState.link.value.connected) AppState.link.value = AppState.link.value.copy(connected = true, status = "Connected over cable or network")
                    val iris = get(root, "/lens/iris")
                    val shutter = get(root, "/video/shutter")
                    val format = get(root, "/system/format")
                    val rawAngle = shutter?.optDouble("shutterAngle", Double.NaN) ?: Double.NaN
                    if (!rawAngle.isNaN()) angleScale = if (rawAngle > 360) 100.0 else 1.0
                    AppState.fromCamera(
                        iso = iso.optInt("iso").takeIf { it > 0 },
                        t = iris?.optDouble("apertureStop", Double.NaN)?.takeIf { !it.isNaN() && it > 0 },
                        angle = if (rawAngle.isNaN()) null else rawAngle / angleScale,
                        fps = format?.optString("frameRate")?.toDoubleOrNull(),
                    )
                }
                delay(1000)
            }
        }
    }

    override fun setIris(t: Double) { scope.launch(Dispatchers.IO) { put("/lens/iris", JSONObject().put("apertureStop", t)) } }
    override fun setIso(iso: Int) { scope.launch(Dispatchers.IO) { put("/video/iso", JSONObject().put("iso", iso)) } }
    override fun setShutterAngle(angle: Double) {
        scope.launch(Dispatchers.IO) { put("/video/shutter", JSONObject().put("shutterAngle", angle * angleScale)) }
    }

    override fun disconnect() {
        poll?.cancel()
        base = null
        AppState.link.value = LinkState()
        AppState.settingsFromCamera.value = false
    }
}
