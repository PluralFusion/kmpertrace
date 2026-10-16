package benchmark

import kotlinx.coroutines.*
import kotlin.test.Test

// S5: 24 concurrent checkout flows. Order 17 fails: the payment retry uses a new idempotency key.
class BenchSimLarge {
    @Test
    fun s5() = runBlocking {
        val r = Run("s5")
        val skus = listOf("S10", "S11", "S12", "S13", "S14", "S15", "S16", "S17", "S18", "S19")

        suspend fun checkout(orderId: Int) {
            val items = skus.shuffled().take(6 + orderId % 4)
            val oid = "o$orderId"
            r.span("Checkout", "run", mapOf("orderId" to oid, "items" to "${items.size}")) {
                r.span("CartService", "validate", mapOf("orderId" to oid)) {
                    items.forEach { r.log("DEBUG", "CartService", "order=$oid item $it price ok"); delay((2..8).random().toLong()) }
                }
                r.span("Inventory", "reserve", mapOf("orderId" to oid)) {
                    items.forEach { r.log("DEBUG", "Inventory", "order=$oid reserved $it"); delay((2..8).random().toLong()) }
                    if (orderId == 9) r.log("WARN", "Inventory", "order=$oid stock for ${items.first()} below threshold")
                }
                r.span("PaymentService", "charge", mapOf("orderId" to oid, "amountCents" to "${4000 + orderId * 137}")) {
                    var attempt = 1
                    var key = "k$orderId-1"
                    while (true) {
                        r.log("DEBUG", "PaymentService", "order=$oid charge attempt $attempt idempotencyKey=$key")
                        delay((20..60).random().toLong())
                        val timeout = (orderId == 17 && attempt == 1) || (orderId == 4 && attempt == 1)
                        if (timeout) {
                            r.log("WARN", "PaymentService", "order=$oid gateway timeout (504) on attempt $attempt")
                            attempt++
                            key = if (orderId == 17) "k$orderId-$attempt" else key
                            continue
                        }
                        if (orderId == 17) {
                            r.log("ERROR", "PaymentService", "order=$oid charge rejected: duplicate charge for card, key=$key")
                            error("charge rejected for order $oid: duplicate charge")
                        }
                        r.log("DEBUG", "PaymentService", "order=$oid charged ok on attempt $attempt")
                        break
                    }
                }
                r.span("Shipping", "createLabel", mapOf("orderId" to oid)) {
                    repeat(3) { r.log("DEBUG", "Shipping", "order=$oid label step ${it + 1}/3"); delay((3..9).random().toLong()) }
                }
                r.span("Receipt", "send", mapOf("orderId" to oid)) {
                    r.log("DEBUG", "Receipt", "order=$oid receipt queued"); delay((3..9).random().toLong())
                }
            }
        }

        val jobs = (1..24).map { n ->
            async(Dispatchers.Default) {
                delay(n * 6L)
                runCatching { checkout(n) }.onFailure { r.log("ERROR", "Checkout", "checkout failed for order=o$n: ${it.message}") }
            }
        }
        jobs.awaitAll()
        r.log("INFO", "CheckoutBatch", "batch finished: 23 ok, 1 failed")
    }
}
