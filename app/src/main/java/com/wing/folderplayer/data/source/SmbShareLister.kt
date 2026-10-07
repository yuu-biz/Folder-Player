package com.wing.folderplayer.data.source

import com.hierynomus.mserref.NtStatus
import com.hierynomus.smbj.SMBClient
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.rapid7.client.dcerpc.transport.exceptions.RPCFaultException
import com.rapid7.helper.smbj.io.SMB2Exception

/** A disk share a server offers (the name to put in the source's "Share" field). */
data class SmbShare(val name: String, val remark: String = "")

enum class SmbShareFailure { AUTH_FAILED, NOT_ALLOWED, UNREACHABLE, ERROR }

sealed class SmbShareResult {
    data class Ok(val shares: List<SmbShare>) : SmbShareResult()
    data class Failed(val reason: SmbShareFailure, val detail: String = "") : SmbShareResult()
}

/**
 * Asks an SMB server which shares it offers (srvsvc NetShareEnumAll over the `IPC$` pipe). Servers answer a logged-in
 * user; some Samba setups and routers also answer a guest / anonymous one, most NAS boxes and Windows do not.
 * Nothing is stored: the credentials are used for this one question.
 */
object SmbShareLister {
    private const val TYPE_MASK = 0xFFFF
    private const val TYPE_DISK = 0
    private const val TYPE_SPECIAL = 0x80000000.toInt()
    private const val TYPE_TEMPORARY = 0x40000000

    /** Disk shares a user would pick: no printers, pipes, `IPC$`, `C$`-style administrative or temporary shares. Sorted by name. */
    fun visible(all: List<Triple<String, Int, String>>): List<SmbShare> =
        all.filter { (name, type, _) ->
            name.isNotBlank() && !name.endsWith("$") &&
                (type and TYPE_MASK) == TYPE_DISK && (type and TYPE_SPECIAL) == 0 && (type and TYPE_TEMPORARY) == 0
        }.map { (name, _, remark) -> SmbShare(name, remark.trim()) }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }

    fun list(host: String, port: Int, username: String, password: String?, domain: String, anonymous: Boolean): SmbShareResult = try {
        SMBClient(smbClientConfig(anonymous, timeoutSeconds = 10, soTimeoutSeconds = 15)).use { client ->
            client.connect(host.trim(), port).use { connection ->
                connection.authenticate(smbAuthentication(username, password, domain, anonymous)).use { session ->
                    val rows = ServerService(SMBTransportFactories.SRVSVC.getTransport(session)).getShares1()
                        .map { Triple(it.netName.orEmpty(), it.type, it.remark.orEmpty()) }
                    SmbShareResult.Ok(visible(rows))
                }
            }
        }
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        failure(e)
    }

    /** What a server that lists shares to nobody logged in answers: anonymous first, then the "Guest" account. */
    fun listAsGuest(host: String, port: Int): SmbShareResult {
        val first = list(host, port, "", null, "", anonymous = true)
        if (first is SmbShareResult.Ok) return first
        val second = runCatching { list(host, port, "Guest", null, "", anonymous = true) }.getOrNull()
        return if (second is SmbShareResult.Ok) second else first
    }

    internal fun failure(e: Throwable): SmbShareResult.Failed {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is RPCFaultException -> return SmbShareResult.Failed(SmbShareFailure.NOT_ALLOWED, t.message.orEmpty())
                is SMB2Exception -> return SmbShareResult.Failed(statusFailure(t.status), t.message.orEmpty())
            }
            t = t.cause
        }
        return when (val mapped = SmbErrors.map(e, "list shares")) {
            is SourceException.AuthFailed -> SmbShareResult.Failed(SmbShareFailure.AUTH_FAILED, mapped.message.orEmpty())
            is SourceException.PermissionDenied -> SmbShareResult.Failed(SmbShareFailure.NOT_ALLOWED, mapped.message.orEmpty())
            is SourceException.Unreachable -> SmbShareResult.Failed(SmbShareFailure.UNREACHABLE, mapped.message.orEmpty())
            else -> SmbShareResult.Failed(SmbShareFailure.ERROR, mapped.message ?: mapped.javaClass.simpleName)
        }
    }

    private fun statusFailure(s: NtStatus?): SmbShareFailure = when (s) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED -> SmbShareFailure.AUTH_FAILED
        NtStatus.STATUS_ACCESS_DENIED -> SmbShareFailure.NOT_ALLOWED
        else -> SmbShareFailure.ERROR
    }
}
