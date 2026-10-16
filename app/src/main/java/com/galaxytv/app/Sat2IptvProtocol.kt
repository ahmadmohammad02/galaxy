package com.galaxytv.app

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val SAT2IPTV_CONNECT_TIMEOUT_MS = 30_000
private const val SAT2IPTV_READ_TIMEOUT_MS = 30_000

data class Sat2IptvChannel(
    val channelName: String,
    val angle: String = "",
    val tp: Int? = null,
    val pol: String = "",
    val sid: Int? = null,
    val streamUrl: String = ""
)

data class LitePackage(
    val id: String,
    val pkgName: String,
    val pkgIcon: String,
    val channels: List<LiteChannel> = emptyList(),
    val usesLiteChannels: Boolean = false
)

data class LiteChannel(
    val id: Long,
    val streamDisplayName: String,
    val categoryId: String,
    val streamIcon: String,
    val viewOrder: Int,
    val streamUrl: String
)

internal object ChannelPackageResponseParser {
    fun parse(jsonText: String, useLite: Boolean): List<LitePackage> {
        return try {
            val response = JsonParser.parseString(jsonText)
            val packages = when {
                response.isJsonArray -> response.asJsonArray
                response.isJsonObject -> response.asJsonObject.getAsJsonArray("packages")
                else -> null
            } ?: return emptyList()

            packages.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val packageType = obj.primitiveString("pkg_type")
                if (!useLite && !packageType.isNullOrBlank() &&
                    packageType != "0" && !packageType.equals("live", ignoreCase = true)
                ) {
                    return@mapNotNull null
                }
                val id = obj.primitiveString(if (useLite) "id" else "pkg_id")
                    ?: obj.primitiveString(if (useLite) "pkg_id" else "id")
                    ?: return@mapNotNull null
                LitePackage(
                    id = id,
                    pkgName = obj.primitiveString("pkg_name").orEmpty(),
                    pkgIcon = obj.primitiveString("pkg_icon").orEmpty(),
                    channels = if (useLite) emptyList() else {
                        obj.getAsJsonArray("pkg_channels")?.mapNotNull channelLoop@{ channelElement ->
                            if (!channelElement.isJsonObject) return@channelLoop null
                            val channel = channelElement.asJsonObject
                            val channelId = channel.primitiveString("stream_id")?.toLongOrNull()
                                ?: return@channelLoop null
                            LiteChannel(
                                id = channelId,
                                streamDisplayName = channel.primitiveString("stream_name").orEmpty(),
                                categoryId = channel.primitiveString("stream_pkg_id").orEmpty(),
                                streamIcon = channel.primitiveString("stream_icon").orEmpty(),
                                viewOrder = channel.primitiveString("stream_order")?.toIntOrNull() ?: 0,
                                streamUrl = channel.primitiveString("stream_url").orEmpty()
                            )
                        }.orEmpty()
                    },
                    usesLiteChannels = useLite
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun com.google.gson.JsonObject.primitiveString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return value.asString
    }
}

object LitePackageCategory {
    private val sportsKeywords = listOf("sport", "football", "soccer", "bein", "espn", "dazn", "رياض")

    fun sportsOnly(packages: List<LitePackage>): List<LitePackage> {
        return packages.filter { pkg ->
            sportsKeywords.any { keyword -> pkg.pkgName.contains(keyword, ignoreCase = true) }
        }
    }
}

object Sat2IptvChannelMatcher {
    fun findMatch(scrambledName: String?, channels: List<Sat2IptvChannel>): Sat2IptvChannel? {
        if (scrambledName.isNullOrBlank()) return null

        val target = normalize(scrambledName)
        if (target.isEmpty()) return null

        channels.firstOrNull { normalize(it.channelName) == target }?.let { return it }
        if (target.size < 2) return null

        return channels.firstOrNull { channel ->
            val candidate = normalize(channel.channelName)
            containsTokenSequence(candidate, target) || containsTokenSequence(target, candidate)
        }
    }

    private fun normalize(value: String): List<String> {
        return value.lowercase(Locale.ROOT)
            .split("[^\\p{L}\\p{N}]+".toRegex())
            .filter { it.isNotBlank() }
    }

    private fun containsTokenSequence(tokens: List<String>, sequence: List<String>): Boolean {
        if (sequence.isEmpty() || sequence.size > tokens.size) return false
        return tokens.windowed(sequence.size).any { it == sequence }
    }
}

class Sat2IptvService(
    private val hostManager: FailoverHostManager = FailoverHostManager()
) {
    fun fetchChannels(
        code: String,
        mac: String,
        sn: String,
        chipId: String = "",
        model: String,
        firmwareVersion: String
    ): List<Sat2IptvChannel> {
        val payload = buildPayload(
            mode = "sat2iptv",
            code = code,
            mac = mac,
            sn = sn,
            chipId = chipId,
            model = model,
            firmwareVersion = firmwareVersion
        )
        val response = safeRequest(payload) ?: return emptyList()
        return try {
            val arr = JsonParser.parseString(response).asJsonArray
            arr.mapNotNull { element ->
                val obj = element.asJsonObject
                val name = obj.get("channel_name")?.asString ?: return@mapNotNull null
                Sat2IptvChannel(
                    channelName = name,
                    angle = obj.get("angle")?.asString ?: "",
                    tp = obj.get("tp")?.takeIf { !it.isJsonNull }?.asInt,
                    pol = obj.get("pol")?.asString ?: "",
                    sid = obj.get("sid")?.takeIf { !it.isJsonNull }?.asInt,
                    streamUrl = obj.get("stream_url")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetchLitePackages(
        code: String,
        mac: String,
        sn: String,
        chipId: String = "",
        model: String,
        firmwareVersion: String
    ): List<LitePackage> {
        return fetchPackagesForMode(code, mac, sn, chipId, model, firmwareVersion, useLite = true)
    }

    fun fetchPackages(
        code: String,
        mac: String,
        sn: String,
        chipId: String = "",
        model: String,
        firmwareVersion: String
    ): List<LitePackage> {
        val packages = fetchPackagesForMode(code, mac, sn, chipId, model, firmwareVersion, useLite = false)
        if (packages.isNotEmpty()) return packages

        return fetchPackagesForMode(
            code, mac, sn, chipId, model, firmwareVersion, useLite = true
        )
    }

    private fun fetchPackagesForMode(
        code: String,
        mac: String,
        sn: String,
        chipId: String,
        model: String,
        firmwareVersion: String,
        useLite: Boolean
    ): List<LitePackage> {
        val payload = buildPayload(
            mode = if (useLite) "lite_packages" else "packages",
            code = code,
            mac = mac,
            sn = sn,
            chipId = chipId,
            model = model,
            firmwareVersion = firmwareVersion
        )
        val response = safeRequest(payload) ?: return emptyList()
        return ChannelPackageResponseParser.parse(response, useLite)
    }

    fun fetchLiteChannels(
        code: String,
        mac: String,
        sn: String,
        chipId: String = "",
        model: String,
        firmwareVersion: String,
        pkgId: String
    ): List<LiteChannel> {
        val payload = buildPayload(
            mode = "lite_channels",
            code = code,
            mac = mac,
            sn = sn,
            chipId = chipId,
            model = model,
            firmwareVersion = firmwareVersion,
            extras = mapOf("pkg_id" to pkgId)
        )
        val response = safeRequest(payload) ?: return emptyList()
        return try {
            val arr = JsonParser.parseString(response).asJsonArray
            arr.mapNotNull { element ->
                val obj = element.asJsonObject
                val id = obj.get("id")?.asLong ?: return@mapNotNull null
                LiteChannel(
                    id = id,
                    streamDisplayName = obj.get("stream_display_name")?.asString ?: "",
                    categoryId = obj.get("category_id")?.asString ?: "",
                    streamIcon = obj.get("stream_icon")?.asString ?: "",
                    viewOrder = obj.get("view_order")?.asInt ?: 0,
                    streamUrl = obj.get("stream_url")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun findMatchingIptvChannel(
        scrambledChannelName: String,
        code: String,
        mac: String,
        sn: String,
        chipId: String = "",
        model: String,
        firmwareVersion: String
    ): Sat2IptvChannel? {
        val channels = fetchChannels(code, mac, sn, chipId, model, firmwareVersion)
        if (channels.isEmpty()) return null
        return Sat2IptvChannelMatcher.findMatch(scrambledChannelName, channels)
    }

    private fun buildPayload(
        mode: String,
        code: String,
        mac: String,
        sn: String,
        chipId: String,
        model: String,
        firmwareVersion: String,
        extras: Map<String, String> = emptyMap()
    ): String {
        val values = linkedMapOf(
            "mode" to mode,
            "code" to code,
            "mac" to mac,
            "sn" to sn,
            "chipid" to chipId,
            "model" to model,
            "firmware_ver" to firmwareVersion
        )
        values.putAll(extras)
        return Gson().toJson(values)
    }

    private fun safeRequest(payload: String): String? {
        val encrypted = XorCrypto.encrypt(payload)
        val host = hostManager.currentHost()

        return try {
            val response = postEncryptedData(host, encrypted)
            val decrypted = XorCrypto.decryptResponse(response)
            if (decrypted.isBlank()) null else decrypted
        } catch (e: Exception) {
            hostManager.markHostDown(host)
            try {
                val fallbackHost = hostManager.currentHost()
                if (fallbackHost == host) return null
                val fallbackResponse = postEncryptedData(fallbackHost, encrypted)
                val fallbackDecrypted = XorCrypto.decryptResponse(fallbackResponse)
                if (fallbackDecrypted.isBlank()) null else fallbackDecrypted
            } catch (ignored: Exception) {
                null
            }
        }
    }

    private fun postEncryptedData(host: String, encryptedJson: String): ByteArray {
        val url = URL("${host.trimEnd('/')}$API_PATH")
        val connection = url.openConnection() as HttpURLConnection
        try {
            val multipart = createMultipartFormBody("json", encryptedJson)
            connection.requestMethod = "POST"
            connection.connectTimeout = SAT2IPTV_CONNECT_TIMEOUT_MS
            connection.readTimeout = SAT2IPTV_READ_TIMEOUT_MS
            connection.doOutput = true
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(multipart.bytes.size)
            connection.setRequestProperty("Content-Type", multipart.contentType)
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
            connection.setRequestProperty("User-Agent", DeviceProfile.currentIdentity().activationUserAgent)

            connection.outputStream.use { it.write(multipart.bytes) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Server response: $responseCode")
            }
            return readApiResponse(connection.inputStream, connection.getHeaderField("Content-Encoding"))
        } finally {
            connection.disconnect()
        }

    }
}
