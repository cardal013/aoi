package tachiyomi.domain.library.model

enum class LibraryGrouping {
    BY_CATEGORY,
    BY_STATUS;

    companion object {
        val default = BY_STATUS
    }
}
