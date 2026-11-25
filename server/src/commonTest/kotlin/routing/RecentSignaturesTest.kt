package routing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecentSignaturesTest : FunSpec({
    val window = 5 * 60 * 1000L
    val now = 1_700_000_000_000L

    test("the first delivery of a signature is accepted") {
        RecentSignatures(window).isFirstDelivery("abc", now, now) shouldBe true
    }

    test("the same signature delivered again is rejected") {
        val recent = RecentSignatures(window)
        recent.isFirstDelivery("abc", now, now) shouldBe true
        recent.isFirstDelivery("abc", now, now + 1_000) shouldBe false
    }

    test("the same signature in different letter case counts as a repeat") {
        val recent = RecentSignatures(window)
        recent.isFirstDelivery("abcdef", now, now) shouldBe true
        recent.isFirstDelivery("ABCDEF", now, now) shouldBe false
    }

    test("different signatures are both accepted") {
        val recent = RecentSignatures(window)
        recent.isFirstDelivery("abc", now, now) shouldBe true
        recent.isFirstDelivery("def", now, now) shouldBe true
    }

    test("signatures older than the window are dropped so memory stays small") {
        val recent = RecentSignatures(window)
        recent.isFirstDelivery("old", now, now)
        recent.isFirstDelivery("new", now + window + 1, now + window + 1)
        recent.size shouldBe 1
    }

    test("a signature is kept until its signing time leaves the window") {
        val recent = RecentSignatures(window)
        recent.isFirstDelivery("abc", now, now)
        recent.isFirstDelivery("abc", now, now + window) shouldBe false
    }
})
