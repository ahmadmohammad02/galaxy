package com.galaxytv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ActivationProtocolTest {
    @Test
    fun twelveDigitCodeIsAccepted() {
        assertTrue(LoginCodeValidator.isValidCode("123456789012"))
        assertTrue(!LoginCodeValidator.isValidCode("12345678901"))
        assertTrue(!LoginCodeValidator.isValidCode("GALAXY"))
    }

    @Test
    fun userAgentFromActiveJsonIsUsed() {
        val response = ActivationResponse.fromJson("""
            {
              "status": 100,
              "message": "The Code is active",
              "osd": "Hello",
              "expire": "538 Days",
              "user_agent": "REV 2000plus 2.1.1",
              "code_id": "1642"
            }
        """.trimIndent())

        assertEquals("REV 2000plus 2.1.1", response.userAgent)
    }

    @Test
    fun failoverHostSwitchesAfterPrimaryFailure() {
        val manager = FailoverHostManager(host1 = "host1.example.com", host2 = "host2.example.com")
        manager.markHostDown("host1.example.com")
        assertEquals("host2.example.com", manager.currentHost())
    }

    @Test
    fun xorEncryptAndDecryptRoundTripReturnsOriginalJson() {
        val raw = "{\"mode\":\"active\",\"code\":\"111111111111\"}"
        val encrypted = XorCrypto.encrypt(raw)
        val decrypted = XorCrypto.decrypt(encrypted)
        assertTrue(raw == decrypted)
    }

    @Test
    fun rawBinaryXorServerResponseDecodesToUtf8Json() {
        val raw = "{\"status\":100,\"message\":\"active\"}"
        val binaryXor = Base64.getDecoder().decode(XorCrypto.encrypt(raw))

        assertEquals(raw, XorCrypto.decryptResponse(binaryXor))
    }

    @Test
    fun malformedDecryptedResponseIsRejected() {
        val malformed = Base64.getDecoder().decode(XorCrypto.encrypt("{not-json"))

        assertThrows(IllegalStateException::class.java) {
            XorCrypto.decryptResponse(malformed)
        }
    }

    @Test
    fun multipartRequestUsesJsonFieldAndBoundary() {
        val multipart = createMultipartFormBody("json", "ciphertext", "test-boundary")
        val body = String(multipart.bytes, Charsets.UTF_8)

        assertEquals("multipart/form-data; boundary=test-boundary", multipart.contentType)
        assertTrue(body.contains("Content-Disposition: form-data; name=\"json\""))
        assertTrue(body.contains("\r\n\r\nciphertext\r\n"))
        assertTrue(body.endsWith("--test-boundary--\r\n"))
    }

    @Test
    fun gzipApiResponseIsDecodedBeforeXorProcessing() {
        val expected = "encrypted-response"
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(expected.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()

        assertEquals(
            expected,
            String(readApiResponse(ByteArrayInputStream(compressed), "gzip"), Charsets.UTF_8)
        )
    }

    @Test
    fun automaticDeviceProfileSelectsLiteForLowResources() {
        assertTrue(DeviceProfile.shouldUseLite(1L * 1024L * 1024L * 1024L, 8))
        assertTrue(DeviceProfile.shouldUseLite(2L * 1024L * 1024L * 1024L, 4))
        assertTrue(DeviceProfile.shouldUseLite(4L * 1024L * 1024L * 1024L, 2))
        assertTrue(!DeviceProfile.shouldUseLite(4L * 1024L * 1024L * 1024L, 4))
    }
}
