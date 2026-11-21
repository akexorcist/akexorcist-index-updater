package routing

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun ghostSignatureHeader(body: String, secret: String, timestamp: Long): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    val hex = mac.doFinal("$body$timestamp".toByteArray()).joinToString("") { "%02x".format(it) }
    return "sha256=$hex, t=$timestamp"
}
