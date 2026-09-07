package tachiyomi.domain.manga.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingStatusTest {

    @Test
    fun `fromInt should return correct status or default to PLAN_TO_READ`() {
        ReadingStatus.fromInt(1L) shouldBe ReadingStatus.PLAN_TO_READ
        ReadingStatus.fromInt(2L) shouldBe ReadingStatus.READING
        ReadingStatus.fromInt(3L) shouldBe ReadingStatus.COMPLETED
        ReadingStatus.fromInt(4L) shouldBe ReadingStatus.DROPPED
        ReadingStatus.fromInt(0L) shouldBe ReadingStatus.PLAN_TO_READ
        ReadingStatus.fromInt(99L) shouldBe ReadingStatus.PLAN_TO_READ
    }

    @Test
    fun `categoryId should return correct negative IDs`() {
        ReadingStatus.READING.categoryId shouldBe -101L
        ReadingStatus.PLAN_TO_READ.categoryId shouldBe -102L
        ReadingStatus.COMPLETED.categoryId shouldBe -103L
        ReadingStatus.DROPPED.categoryId shouldBe -104L
    }
}
