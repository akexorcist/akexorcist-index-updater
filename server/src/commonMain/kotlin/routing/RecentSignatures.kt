package routing

import java.util.concurrent.ConcurrentHashMap

internal class RecentSignatures(private val windowMillis: Long) {
    private val seen = ConcurrentHashMap<String, Long>()

    internal val size: Int get() = seen.size

    fun isFirstDelivery(signature: String, signedAtMillis: Long, nowMillis: Long): Boolean {
        seen.entries.removeIf { (_, signedAt) -> nowMillis - signedAt > windowMillis }
        return seen.putIfAbsent(signature.lowercase(), signedAtMillis) == null
    }
}
