package tachiyomi.domain.manga.model

enum class ReadingStatus(val value: Long) {
    PLAN_TO_READ(1L),
    READING(2L),
    COMPLETED(3L),
    DROPPED(4L);

    companion object {
        fun fromInt(value: Long): ReadingStatus {
            return entries.find { it.value == value } ?: PLAN_TO_READ
        }
    }
}

val ReadingStatus.categoryId: Long
    get() = when (this) {
        ReadingStatus.READING -> -101L
        ReadingStatus.PLAN_TO_READ -> -102L
        ReadingStatus.COMPLETED -> -103L
        ReadingStatus.DROPPED -> -104L
    }
