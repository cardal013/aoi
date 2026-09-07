package tachiyomi.domain.manga.model

enum class ReadingStatus(val value: Long) {
    READING(2L),
    COMPLETED(3L),
    DROPPED(4L),
    PLAN_TO_READ(1L);

    companion object {
        fun fromInt(value: Long): ReadingStatus {
            return entries.find { it.value == value } ?: READING
        }
    }
}

val ReadingStatus.categoryId: Long
    get() = when (this) {
        ReadingStatus.READING -> -101L
        ReadingStatus.COMPLETED -> -102L
        ReadingStatus.DROPPED -> -103L
        ReadingStatus.PLAN_TO_READ -> -104L
    }
