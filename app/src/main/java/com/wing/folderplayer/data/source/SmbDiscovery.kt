package com.wing.folderplayer.data.source

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/** Where a server's name came from; the better source wins when the same address is found twice. */
enum class SmbNameSource { NONE, NETBIOS, MDNS }

/** An SMB server found on the local network. [address] is what the source stores: names rarely resolve on Android. */
data class SmbHost(val address: String, val port: Int = SmbDiscoveryLogic.SMB_PORT, val name: String? = null, val nameSource: SmbNameSource = SmbNameSource.NONE) {
    val displayName: String get() = name ?: address
}

/** What the search has found so far. [noLocalNetwork]: no private Wi-Fi / Ethernet address, so only announcing servers were looked for. */
data class SmbDiscoveryState(val hosts: List<SmbHost> = emptyList(), val finished: Boolean = false, val noLocalNetwork: Boolean = false)

/** The parts of the search that need no Android: which addresses may be probed, NetBIOS packets, merging. */
object SmbDiscoveryLogic {
    const val SMB_PORT = 445

    /** RFC 1918 only: a cellular or public IPv4 address is never port-scanned. */
    fun isPrivate(a: ByteArray): Boolean {
        if (a.size != 4) return false
        val b0 = a[0].toInt() and 0xff
        val b1 = a[1].toInt() and 0xff
        return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168)
    }

    /**
     * The addresses of the subnet of [address] to probe: the whole /24 containing it even when the network is wider,
     * the real subnet when it is narrower; without the device itself, the network and the broadcast address.
     * Empty when [address] is not a private IPv4 address.
     */
    fun candidates(address: ByteArray, prefixLength: Int): List<String> {
        if (!isPrivate(address)) return emptyList()
        val prefix = prefixLength.coerceIn(24, 30)
        val ip = ((address[0].toInt() and 0xff) shl 24) or ((address[1].toInt() and 0xff) shl 16) or
            ((address[2].toInt() and 0xff) shl 8) or (address[3].toInt() and 0xff)
        val mask = -1 shl (32 - prefix)
        val network = ip and mask
        val broadcast = network or mask.inv()
        return ((network + 1) until broadcast).filter { it != ip }.map {
            "${(it ushr 24) and 0xff}.${(it ushr 16) and 0xff}.${(it ushr 8) and 0xff}.${it and 0xff}"
        }
    }

    /** One entry per address: the best name (mDNS, then NetBIOS), the mDNS port when there is one; sorted by name, then address. */
    fun merge(found: Collection<SmbHost>): List<SmbHost> {
        val byAddress = LinkedHashMap<String, SmbHost>()
        for (h in found) {
            val old = byAddress[h.address]
            byAddress[h.address] = when {
                old == null -> h
                h.nameSource > old.nameSource -> h.copy(port = if (h.nameSource == SmbNameSource.MDNS) h.port else old.port)
                else -> old
            }
        }
        return byAddress.values.sortedWith(compareBy({ it.name == null }, { it.name?.lowercase() }, { it.address }))
    }

    /** NetBIOS node status request (UDP 137, name "*"): the server answers with the names it holds. */
    fun netBiosRequest(id: Int = 0x4650): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v ushr 8).toByte()); out.add(v.toByte()) }
        u16(id); u16(0); u16(1); u16(0); u16(0); u16(0)
        out.add(0x20)
        "CK".forEach { out.add(it.code.toByte()) }
        repeat(30) { out.add('A'.code.toByte()) }
        out.add(0)
        u16(0x0021); u16(0x0001)
        return out.toByteArray()
    }

    /** The computer name in a node status response (a unique 0x00 name, else a 0x20 one), or null if there is none. */
    fun parseNetBiosName(r: ByteArray, length: Int = r.size): String? {
        fun u16(i: Int) = ((r[i].toInt() and 0xff) shl 8) or (r[i + 1].toInt() and 0xff)
        if (length < 12) return null
        var i = 12
        repeat(u16(4)) { // questions, if the answer repeats them
            while (i < length && r[i].toInt() != 0) i += (r[i].toInt() and 0xff) + 1
            i += 5
        }
        if (u16(6) < 1 || i >= length) return null
        // Answer name: a compression pointer or a label sequence.
        if ((r[i].toInt() and 0xc0) == 0xc0) i += 2 else {
            while (i < length && r[i].toInt() != 0) i += (r[i].toInt() and 0xff) + 1
            i += 1
        }
        i += 10 // type, class, ttl, rdlength
        if (i >= length) return null
        val count = r[i].toInt() and 0xff
        i += 1
        var fallback: String? = null
        for (n in 0 until count) {
            if (i + 18 > length) break
            val name = String(r, i, 15, Charsets.ISO_8859_1).trim()
            val suffix = r[i + 15].toInt() and 0xff
            val group = (u16(i + 16) and 0x8000) != 0
            i += 18
            if (group || name.isEmpty() || name.any { it.code < 0x20 || it.code > 0x7e }) continue
            if (suffix == 0x00) return name
            if (suffix == 0x20 && fallback == null) fallback = name
        }
        return fallback
    }
}

/** Probes used by the search; separate so they can be run against a local socket. */
object SmbProbe {
    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    /** The NetBIOS name of [host], or null (not answered, no usable name). */
    fun netBiosName(host: String, timeoutMs: Int): String? = try {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val req = SmbDiscoveryLogic.netBiosRequest()
            s.send(DatagramPacket(req, req.size, InetAddress.getByName(host), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            SmbDiscoveryLogic.parseNetBiosName(buf, p.length)
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Searches the local network for SMB servers when the user asks (never in the background): servers that announce
 * themselves through mDNS (`_smb._tcp`: NAS boxes, Macs, Samba with Avahi), and TCP port 445 on the subnet of the
 * Wi-Fi / Ethernet connection (hosts that do not announce, e.g. Windows PCs). Names come from mDNS, else NetBIOS.
 */
class SmbDiscovery(private val context: Context) {
    fun discover(): Flow<SmbDiscoveryState> = channelFlow {
        val found = ArrayList<SmbHost>()
        val lock = Mutex()
        val net = localNetwork()
        var last = SmbDiscoveryState(noLocalNetwork = net == null)
        send(last)
        suspend fun report(h: SmbHost) {
            val state = lock.withLock { found.add(h); last.copy(hosts = SmbDiscoveryLogic.merge(found)) }
            last = state
            send(state)
        }
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            coroutineScope {
                // Without mDNS (no service, refused) the subnet probe still runs.
                launch { try { announced { report(it) } } catch (e: CancellationException) { throw e } catch (e: Exception) { } }
                if (net != null) launch { sweep(net.first, net.second) { report(it) } }
            }
        }
        send(last.copy(finished = true))
    }

    /** A private IPv4 address (with prefix length) of a Wi-Fi / Ethernet connection that is not a VPN. */
    @Suppress("DEPRECATION")
    private fun localNetwork(): Pair<ByteArray, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val la = cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address && SmbDiscoveryLogic.isPrivate(it.address.address) } ?: continue
            return la.address.address to la.prefixLength
        }
        return null
    }

    private suspend fun sweep(address: ByteArray, prefix: Int, report: suspend (SmbHost) -> Unit) = coroutineScope {
        val permits = Semaphore(PROBES_AT_ONCE)
        SmbDiscoveryLogic.candidates(address, prefix).map { ip ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    if (SmbProbe.isOpen(ip, SmbDiscoveryLogic.SMB_PORT, PROBE_TIMEOUT_MS)) {
                        val name = SmbProbe.netBiosName(ip, PROBE_TIMEOUT_MS)
                        report(SmbHost(ip, SmbDiscoveryLogic.SMB_PORT, name, if (name != null) SmbNameSource.NETBIOS else SmbNameSource.NONE))
                    }
                }
            }
        }.awaitAll()
    }

    /** mDNS. Services found are resolved one after the other: Android before 14 refuses a second resolve while one runs. */
    @Suppress("DEPRECATION")
    private suspend fun announced(report: suspend (SmbHost) -> Unit) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val seen = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) { seen.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { seen.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            for (info in seen) {
                val r = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(nsd, info) } ?: continue
                val host = r.host ?: continue
                val address = (host as? Inet4Address)?.hostAddress ?: continue
                report(SmbHost(address, r.port.takeIf { it > 0 } ?: SmbDiscoveryLogic.SMB_PORT, r.serviceName, SmbNameSource.MDNS))
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { c: CancellableContinuation<NsdServiceInfo?> ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (c.isActive) c.resume(null) }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (c.isActive) c.resume(serviceInfo) }
        })
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 12_000L
        const val RESOLVE_TIMEOUT_MS = 3_000L
        const val PROBE_TIMEOUT_MS = 500
        const val PROBES_AT_ONCE = 48
    }
}
