package com.linxi.diary.data

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 网易云 Web API 所需的本地加密。密码/Cookie 不经过林曦服务端。 */
internal object NeteaseCrypto {
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val EAPI_FORMAT = "%s-36cd479b6b5-%s-36cd479b6b5-%s"
    private const val EAPI_SALT = "nobody%suse%smd5forencrypt"
    private const val PUBLIC_KEY_PEM = """
        -----BEGIN PUBLIC KEY-----
        MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFb
        t7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZ
        MldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB
        -----END PUBLIC KEY-----
    """
    private val random = SecureRandom()

    fun weApi(payload: JSONObject): Map<String, String> {
        val secret = buildString(16) { repeat(16) { append(BASE62[random.nextInt(BASE62.length)]) } }
        val first = aes(payload.toString(), PRESET_KEY, IV, cbc = true, base64 = true)
        val params = aes(first, secret, IV, cbc = true, base64 = true)
        return mapOf("params" to params, "encSecKey" to rsa(reverse(secret)))
    }

    fun eApi(path: String, payload: JSONObject): Map<String, String> {
        val apiPath = path.replace("/eapi", "/api")
        val data = payload.toString()
        val message = EAPI_FORMAT.format(apiPath, data, md5(EAPI_SALT.format(apiPath, data)))
        return mapOf("params" to aes(message, EAPI_KEY, "", cbc = false, base64 = false).uppercase(Locale.ROOT))
    }

    private fun reverse(value: String): String = value.reversed()

    private fun aes(text: String, key: String, iv: String, cbc: Boolean, base64: Boolean): String {
        val cipher = Cipher.getInstance(if (cbc) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding")
        val secret = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
        if (cbc) cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8)))
        else cipher.init(Cipher.ENCRYPT_MODE, secret)
        val result = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return if (base64) Base64.encodeToString(result, Base64.NO_WRAP)
        else result.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun rsa(text: String): String {
        val key = PUBLIC_KEY_PEM
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)),
        ) as java.security.interfaces.RSAPublicKey
        val input = BigInteger(1, text.toByteArray(StandardCharsets.UTF_8))
        val output = input.modPow(publicKey.publicExponent, publicKey.modulus)
        val size = (publicKey.modulus.bitLength() + 7) / 8
        var bytes = output.toByteArray()
        if (bytes.size > size) bytes = bytes.copyOfRange(bytes.size - size, bytes.size)
        if (bytes.size < size) bytes = ByteArray(size - bytes.size) + bytes
        return bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
