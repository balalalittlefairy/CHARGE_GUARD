package io.github.chargercontroller.core

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CloudSigning {
    fun signature(path: String, data: String, security: String, nonce: String): String {
        val decoder = Base64.getDecoder()
        val encoder = Base64.getEncoder()
        val signedNonce = MessageDigest.getInstance("SHA-256").digest(decoder.decode(security) + decoder.decode(nonce))
        val message = "$path&${encoder.encodeToString(signedNonce)}&$nonce&data=$data"
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signedNonce, "HmacSHA256"))
            encoder.encodeToString(doFinal(message.toByteArray(Charsets.UTF_8)))
        }
    }
}
