package io.github.chargercontroller.core

import java.security.MessageDigest
import java.util.Base64

/** Xiaomi cloud RC4-drop1024 protocol. See THIRD_PARTY_NOTICES.md (go2rtc). */
object CloudRc4 {
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun key(security: String, nonce: String) = MessageDigest.getInstance("SHA-256")
        .digest(Base64.getDecoder().decode(security) + Base64.getDecoder().decode(nonce))

    private fun crypt(key: ByteArray, input: ByteArray): ByteArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + state[i] + (key[i % key.size].toInt() and 255)) and 255
            val t = state[i]; state[i] = state[j]; state[j] = t
        }
        var i = 0
        j = 0
        val output = ByteArray(input.size)
        repeat(1024 + input.size) { n ->
            i = (i + 1) and 255
            j = (j + state[i]) and 255
            val t = state[i]; state[i] = state[j]; state[j] = t
            val byte = state[(state[i] + state[j]) and 255]
            if (n >= 1024) output[n - 1024] = (input[n - 1024].toInt() xor byte).toByte()
        }
        return output
    }

    private fun sign(path: String, fields: Map<String, String>, key: ByteArray): String {
        val message = (listOf("POST", path) + fields.map { "${it.key}=${it.value}" } + b64(key)).joinToString("&")
        return b64(MessageDigest.getInstance("SHA-1").digest(message.toByteArray(Charsets.UTF_8)))
    }

    fun fields(path: String, data: String, security: String, nonce: String): Map<String, String> {
        val key = key(security, nonce)
        val fields = linkedMapOf("data" to data)
        fields["rc4_hash__"] = sign(path, fields, key)
        fields.replaceAll { _, value -> b64(crypt(key, value.toByteArray(Charsets.UTF_8))) }
        fields["signature"] = sign(path, fields, key)
        fields["ssecurity"] = security
        fields["_nonce"] = nonce
        return fields
    }

    fun decrypt(response: String, security: String, nonce: String): ByteArray =
        crypt(key(security, nonce), Base64.getDecoder().decode(response))
}
