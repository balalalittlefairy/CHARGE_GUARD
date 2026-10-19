package io.github.chargercontroller.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** miIO wire format: 16-byte header, 16-byte checksum, AES-CBC payload. */
object MiioCodec {
    val hello: ByteArray get() = hex("21310020" + "ff".repeat(28))
    fun hex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]+"))) { "十六进制格式无效" }
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun token(value: String): ByteArray {
        require(value.matches(Regex("[0-9a-fA-F]{32}"))) { "Token 应为 32 位十六进制字符" }
        require(value != "0".repeat(32) && !value.equals("f".repeat(32), true)) { "Token 是占位值，请重新获取" }
        return hex(value)
    }
    private fun md5(value: ByteArray) = MessageDigest.getInstance("MD5").digest(value)
    private fun cipher(mode: Int, token: ByteArray): Cipher {
        require(token.size == 16)
        val key = md5(token)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(md5(key + token)))
        }
    }
    data class Header(val deviceId: Int, val timestamp: Int)
    fun header(packet: ByteArray): Header {
        require(packet.size >= 32) { "miIO 响应过短" }
        val input = ByteBuffer.wrap(packet)
        require(input.short.toInt() and 0xffff == 0x2131) { "miIO 魔数错误" }
        require(input.short.toInt() and 0xffff == packet.size) { "miIO 报文长度错误" }
        require(input.int == 0) { "miIO 报文头无效" }
        return Header(input.int, input.int)
    }
    fun encode(json: String, token: ByteArray, deviceId: Int, timestamp: Int): ByteArray {
        val encrypted = cipher(Cipher.ENCRYPT_MODE, token).doFinal((json + "\u0000").toByteArray(Charsets.UTF_8))
        val prefix = ByteBuffer.allocate(16).putShort(0x2131).putShort((32 + encrypted.size).toShort())
            .putInt(0).putInt(deviceId).putInt(timestamp).array()
        return prefix + md5(prefix + token + encrypted) + encrypted
    }
    fun decode(packet: ByteArray, token: ByteArray, deviceId: Int): String {
        val h = header(packet)
        require(h.deviceId == deviceId) { "响应设备 ID 不匹配" }
        require(packet.size > 32 && (packet.size - 32) % 16 == 0) { "miIO 密文长度错误" }
        val encrypted = packet.copyOfRange(32, packet.size)
        val checksum = md5(packet.copyOfRange(0, 16) + token + encrypted)
        require(MessageDigest.isEqual(checksum, packet.copyOfRange(16, 32))) { "校验失败：Token 错误或响应损坏" }
        return cipher(Cipher.DECRYPT_MODE, token).doFinal(encrypted).toString(Charsets.UTF_8).trimEnd('\u0000')
    }
}
