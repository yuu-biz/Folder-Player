package com.wing.folderplayer.cast

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jupnp.DefaultUpnpServiceConfiguration
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.controlpoint.ActionCallback
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.StreamRequestMessage
import org.jupnp.model.message.StreamResponseMessage
import org.jupnp.model.message.UpnpHeaders
import org.jupnp.model.message.UpnpMessage
import org.jupnp.model.message.UpnpRequest
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.message.header.UDADeviceTypeHeader
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.Service
import org.jupnp.model.types.UDADeviceType
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import org.jupnp.support.avtransport.callback.GetPositionInfo
import org.jupnp.support.avtransport.callback.GetTransportInfo
import org.jupnp.support.avtransport.callback.Pause
import org.jupnp.support.avtransport.callback.Play
import org.jupnp.support.avtransport.callback.Seek
import org.jupnp.support.avtransport.callback.SetAVTransportURI
import org.jupnp.support.avtransport.callback.Stop
import org.jupnp.support.model.PositionInfo
import org.jupnp.support.model.TransportInfo
import org.jupnp.transport.Router
import org.jupnp.transport.spi.AbstractStreamClient
import org.jupnp.transport.spi.AbstractStreamClientConfiguration
import org.jupnp.transport.spi.NetworkAddressFactory
import org.jupnp.transport.spi.StreamClient
import org.jupnp.transport.spi.StreamServer
import org.jupnp.transport.spi.StreamServerConfiguration
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** jUPnP StreamClient on OkHttp (jUPnP's Android configuration would pull in a Jetty client). */
class OkHttpStreamClient(private val conf: Config) : AbstractStreamClient<OkHttpStreamClient.Config, okhttp3.Call>() {
    class Config(executor: ExecutorService) : AbstractStreamClientConfiguration(executor, 10)

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    override fun getConfiguration(): Config = conf

    override fun createRequest(msg: StreamRequestMessage): okhttp3.Call {
        val op = msg.operation
        val b = Request.Builder().url(op.uri.toString())
        for ((k, values) in msg.headers) for (v in values) b.addHeader(k, v)
        if (!msg.headers.containsKey("User-Agent")) b.header("User-Agent", conf.getUserAgentValue(msg.udaMajorVersion, msg.udaMinorVersion))
        val body = if (msg.hasBody()) {
            val type = (msg.contentTypeHeader?.string ?: "text/xml; charset=\"utf-8\"").toMediaTypeOrNull()
            if (msg.bodyType == UpnpMessage.BodyType.STRING) msg.bodyString.toByteArray(Charsets.UTF_8).toRequestBody(type)
            else msg.bodyBytes.toRequestBody(type)
        } else null
        val method = op.httpMethodName
        b.method(method, body ?: if (method == "POST" || method == "NOTIFY") ByteArray(0).toRequestBody(null) else null)
        return http.newCall(b.build())
    }

    override fun createCallable(msg: StreamRequestMessage, call: okhttp3.Call): Callable<StreamResponseMessage> = Callable {
        call.execute().use { r ->
            val response = StreamResponseMessage(UpnpResponse(r.code, r.message))
            val headers = UpnpHeaders()
            for ((k, v) in r.headers) headers.add(k, v)
            response.headers = headers
            val bytes = r.body?.bytes()
            if (bytes != null && bytes.isNotEmpty()) {
                if (response.isContentTypeMissingOrText) response.setBodyCharacters(bytes)
                else response.setBody(UpnpMessage.BodyType.BYTES, bytes)
            }
            response
        }
    }

    override fun abort(call: okhttp3.Call) = call.cancel()
    override fun logExecutionException(t: Throwable): Boolean = false
    override fun stop() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

/** Control point only: GENA event callbacks are not used (positions are polled), so no HTTP server is needed. */
class NoopStreamServer : StreamServer<StreamServerConfiguration> {
    private val conf = StreamServerConfiguration { 0 }
    override fun init(bindAddress: InetAddress?, router: Router?) = Unit
    override fun getPort(): Int = 0
    override fun stop() = Unit
    override fun getConfiguration(): StreamServerConfiguration = conf
    override fun run() = Unit
}

class ControlPointConfiguration : DefaultUpnpServiceConfiguration(0, 0, false) {
    override fun createStreamClient(): StreamClient<*> = OkHttpStreamClient(OkHttpStreamClient.Config(syncProtocolExecutorService))
    override fun createStreamServer(networkAddressFactory: NetworkAddressFactory?): StreamServer<*> = NoopStreamServer()
}

data class Renderer(val udn: String, val name: String, val model: String, val address: String)

/**
 * DLNA/UPnP MediaRenderer discovery and AVTransport control on jUPnP. Pure JVM; the Android wrapper adds Wi-Fi
 * multicast/wake locks and the UI state.
 */
class DlnaControlPoint {
    private var service: UpnpService? = null
    private val renderers = java.util.concurrent.ConcurrentHashMap<String, RemoteDevice>()
    @Volatile var onChange: (() -> Unit)? = null

    private val listener = object : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
            if (device.findService(AV_TRANSPORT) != null) {
                renderers[device.identity.udn.identifierString] = device
                onChange?.invoke()
            }
        }
        override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
            if (renderers.remove(device.identity.udn.identifierString) != null) onChange?.invoke()
        }
    }

    @Synchronized
    fun start() {
        if (service != null) return
        val s = UpnpServiceImpl(ControlPointConfiguration())
        s.startup()
        s.registry.addListener(listener)
        service = s
        search()
    }

    fun search() {
        service?.controlPoint?.search(UDADeviceTypeHeader(UDADeviceType("MediaRenderer", 1)))
    }

    fun renderers(): List<Renderer> = renderers.values.map {
        Renderer(it.identity.udn.identifierString, it.details.friendlyName ?: it.displayString,
            it.details.modelDetails?.modelName ?: "", it.identity.descriptorURL.host)
    }.sortedBy { it.name }

    private fun avt(udn: String): Service<*, *> =
        renderers[udn]?.findService(AV_TRANSPORT) ?: throw IllegalStateException("renderer $udn not available")

    private fun <T> run(make: (CompletableFuture<T>) -> ActionCallback, timeoutSec: Long = 10): T {
        val cp = service?.controlPoint ?: throw IllegalStateException("UPnP not started")
        val f = CompletableFuture<T>()
        cp.execute(make(f))
        return f.get(timeoutSec, TimeUnit.SECONDS)
    }

    private fun fail(f: CompletableFuture<*>, invocation: ActionInvocation<*>?, msg: String?) {
        f.completeExceptionally(IllegalStateException("UPnP action ${invocation?.action?.name}: $msg"))
    }

    fun setUri(udn: String, url: String, didl: String) = run<Unit>({ f ->
        object : SetAVTransportURI(avt(udn), url, didl) {
            override fun success(invocation: ActionInvocation<*>?) { f.complete(Unit) }
            override fun failure(invocation: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) = fail(f, invocation, msg)
        }
    })

    fun play(udn: String) = run<Unit>({ f ->
        object : Play(avt(udn)) {
            override fun success(invocation: ActionInvocation<*>?) { f.complete(Unit) }
            override fun failure(invocation: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) = fail(f, invocation, msg)
        }
    })

    fun pause(udn: String) = run<Unit>({ f ->
        object : Pause(avt(udn)) {
            override fun success(invocation: ActionInvocation<*>?) { f.complete(Unit) }
            override fun failure(invocation: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) = fail(f, invocation, msg)
        }
    })

    fun stop(udn: String) = run<Unit>({ f ->
        object : Stop(avt(udn)) {
            override fun success(invocation: ActionInvocation<*>?) { f.complete(Unit) }
            override fun failure(invocation: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) = fail(f, invocation, msg)
        }
    })

    fun seek(udn: String, positionMs: Long) = run<Unit>({ f ->
        object : Seek(avt(udn), formatTime(positionMs)) {
            override fun success(invocation: ActionInvocation<*>?) { f.complete(Unit) }
            override fun failure(invocation: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) = fail(f, invocation, msg)
        }
    })

    fun position(udn: String): PositionInfo = run({ f ->
        object : GetPositionInfo(avt(udn)) {
            override fun received(invocation: ActionInvocation<*>?, info: PositionInfo) { f.complete(info) }
            override fun failure(invocation: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) = fail(f, invocation, msg)
        }
    })

    fun transport(udn: String): TransportInfo = run({ f ->
        object : GetTransportInfo(avt(udn)) {
            override fun received(invocation: ActionInvocation<*>?, info: TransportInfo) { f.complete(info) }
            override fun failure(invocation: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) = fail(f, invocation, msg)
        }
    })

    @Synchronized
    fun shutdown() {
        service?.shutdown()
        service = null
        renderers.clear()
    }

    companion object {
        val AV_TRANSPORT = UDAServiceType("AVTransport", 1)

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        }

        fun parseTime(t: String?): Long {
            if (t.isNullOrBlank() || t == "NOT_IMPLEMENTED") return -1
            val parts = t.substringBefore('.').split(':').mapNotNull { it.toLongOrNull() }
            if (parts.size != 3) return -1
            return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
        }

        fun didl(title: String, artist: String?, url: String, mime: String, size: Long): String {
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
                """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1">""" +
                "<dc:title>${esc(title)}</dc:title>" + (artist?.takeIf { it.isNotBlank() }?.let { "<upnp:artist>${esc(it)}</upnp:artist>" } ?: "") +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                """<res protocolInfo="http-get:*:$mime:*" size="$size">${esc(url)}</res></item></DIDL-Lite>"""
        }
    }
}
