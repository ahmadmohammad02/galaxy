package com.galaxytv.app

import com.google.gson.JsonParser
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal const val SERVER_PRIMARY_HOST = "http://maghrebtv.xyz:2082/iptv"
internal const val SERVER_BACKUP_HOST = "http://maghrebtv.xyz:2082/iptv"
internal const val API_PATH = "/V6/API-V6.php"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 30_000
private const val MAX_API_RESPONSE_BYTES = 4 * 1024 * 1024
private const val TEN_DAYS_MS = 10L * 24L * 60L * 60L * 1000L
private const val PRIMARY_DOWN_UNTIL = "primary_down_until"
private const val SERVER_PREFERENCES = "server_failover"

internal object ServerRuntime {
    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun primaryDownUntil(): Long {
        return appContext?.getSharedPreferences(SERVER_PREFERENCES, Context.MODE_PRIVATE)
            ?.getLong(PRIMARY_DOWN_UNTIL, 0L) ?: 0L
    }

    fun markPrimaryDownUntil(value: Long) {
        appContext?.getSharedPreferences(SERVER_PREFERENCES, Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong(PRIMARY_DOWN_UNTIL, value)
            ?.apply()
    }
}

internal data class MultipartFormBody(
    val contentType: String,
    val bytes: ByteArray
)

internal fun createMultipartFormBody(
    fieldName: String,
    fieldValue: String,
    boundary: String = "GalaxyTv-${UUID.randomUUID()}"
): MultipartFormBody {
    val body = "--$boundary\r\n" +
        "Content-Disposition: form-data; name=\"$fieldName\"\r\n\r\n" +
        "$fieldValue\r\n--$boundary--\r\n"
    return MultipartFormBody("multipart/form-data; boundary=$boundary", body.toByteArray(Charsets.UTF_8))
}

internal fun readApiResponse(input: InputStream, contentEncoding: String? = null): ByteArray {
    val output = ByteArrayOutputStream()
    val decodedInput = when (contentEncoding?.lowercase()?.trim()) {
        "gzip" -> GZIPInputStream(input)
        "deflate" -> InflaterInputStream(input)
        else -> input
    }
    decodedInput.use { stream ->
        val buffer = ByteArray(8192)
        var totalBytes = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            totalBytes += count
            if (totalBytes > MAX_API_RESPONSE_BYTES) {
                throw IllegalStateException("Server response is too large")
            }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

class ActivationResponse(
    val status: Int,
    val message: String,
    val osd: String,
    val expire: String,
    val userAgent: String,
    val codeId: String,
    val rawJson: String = ""
) {
    companion object {
        fun fromJson(jsonText: String): ActivationResponse {
            val json = JsonParser.parseString(jsonText).asJsonObject
            return ActivationResponse(
                status = json.get("status")?.asInt ?: -1,
                message = json.get("message")?.asString ?: "",
                osd = json.get("osd")?.asString ?: "",
                expire = json.get("expire")?.asString ?: "",
                userAgent = json.get("user_agent")?.asString ?: "",
                codeId = json.get("code_id")?.asString ?: "",
                rawJson = jsonText
            )
        }
    }
}

class FailoverHostManager(
    private val host1: String = SERVER_PRIMARY_HOST,
    private val host2: String = SERVER_BACKUP_HOST,
    private val memory: MutableMap<String, Long> = mutableMapOf()
) {
    fun currentHost(): String {
        val primaryDownUntil = memory[PRIMARY_DOWN_UNTIL] ?: ServerRuntime.primaryDownUntil()
        val isPrimaryDown = System.currentTimeMillis() < primaryDownUntil
        return if (isPrimaryDown && host2.isNotBlank()) host2 else host1
    }

    fun markHostDown(host: String) {
        if (host == host1) {
            val downUntil = System.currentTimeMillis() + TEN_DAYS_MS
            memory[PRIMARY_DOWN_UNTIL] = downUntil
            ServerRuntime.markPrimaryDownUntil(downUntil)
        }
    }
}

object XorCrypto {
    private const val XOR_KEY = "y]w(7r+W3e>@wEiM0a9"

    fun encrypt(plainText: String): String {
        val textBytes = plainText.toByteArray(Charsets.UTF_8)
        val keyBytes = XOR_KEY.toByteArray(Charsets.UTF_8)
        val output = ByteArray(textBytes.size)

        for (i in textBytes.indices) {
            output[i] = (textBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }

        return Base64.getEncoder().encodeToString(output)
    }

    fun decrypt(base64Text: String): String {
        val encrypted = Base64.getDecoder().decode(base64Text)
        return String(xorBytes(encrypted), Charsets.UTF_8)
    }

    fun decryptResponse(responseBytes: ByteArray): String {
        val candidates = mutableListOf(responseBytes)
        val responseText = String(responseBytes, Charsets.US_ASCII).trim()
        val base64Candidate = runCatching { Base64.getDecoder().decode(responseText) }.getOrNull()
        if (base64Candidate != null) candidates.add(0, base64Candidate)

        for (candidate in candidates) {
            val decoded = String(xorBytes(candidate), Charsets.UTF_8).trim()
            if (decoded.startsWith("{") || decoded.startsWith("[")) {
                if (runCatching { JsonParser.parseString(decoded) }.isSuccess) return decoded
            }
        }
        throw IllegalStateException("Server returned an invalid JSON response")
    }

    private fun xorBytes(input: ByteArray): ByteArray {
        val keyBytes = XOR_KEY.toByteArray(Charsets.UTF_8)
        val output = ByteArray(input.size)

        for (i in input.indices) {
            output[i] = (input[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return output
    }
}

class ActivationService(
    private val hostManager: FailoverHostManager = FailoverHostManager()
) {
    fun activate(
        code: String,
        mac: String,
        sn: String,
        chipId: String = "",
        model: String,
        firmwareVersion: String,
        osdTest: String? = null
    ): ActivationResponse? {
        val payload = buildPayload(code, mac, sn, chipId, model, firmwareVersion, osdTest)
        val encrypted = XorCrypto.encrypt(payload)
        val host = hostManager.currentHost()

        return try {
            val responseText = postEncryptedData(host, encrypted)
            val decrypted = XorCrypto.decryptResponse(responseText)
            ActivationResponse.fromJson(decrypted)
        } catch (e: Exception) {
            hostManager.markHostDown(host)
            try {
                val backupHost = hostManager.currentHost()
                if (backupHost == host) return null
                val responseText = postEncryptedData(backupHost, encrypted)
                val decrypted = XorCrypto.decryptResponse(responseText)
                ActivationResponse.fromJson(decrypted)
            } catch (backupError: Exception) {
                null
            }
        }
    }

    private fun buildPayload(
        code: String,
        mac: String,
        sn: String,
        chipId: String,
        model: String,
        firmwareVersion: String,
        osdTest: String?
    ): String {
        val values = linkedMapOf<String, String>(
            "mode" to "active",
            "code" to code,
            "mac" to mac,
            "sn" to sn,
            "chipid" to chipId,
            "model" to model,
            "firmware_ver" to firmwareVersion
        )

        if (!osdTest.isNullOrBlank()) {
            values["osd_test"] = osdTest
        }

        return values.entries.joinToString(",", prefix = "{", postfix = "}") { "\"${it.key}\":\"${it.value.replace("\\","\\\\").replace("\"","\\\"")}\"" }
    }

    private fun postEncryptedData(host: String, encryptedJson: String): ByteArray {
        val url = URL("${host.trimEnd('/')}$API_PATH")
        val connection = url.openConnection() as HttpURLConnection
        try {
            val multipart = createMultipartFormBody("json", encryptedJson)
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
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
