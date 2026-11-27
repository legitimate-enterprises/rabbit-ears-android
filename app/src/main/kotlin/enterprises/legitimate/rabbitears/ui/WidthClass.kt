package enterprises.legitimate.rabbitears.ui

/** Material window width size class; layouts switch on it, never on device type. */
enum class WidthClass(val bottomBar: Boolean) {
    Compact(bottomBar = true),
    Medium(bottomBar = false),
    Expanded(bottomBar = false);

    companion object {
        fun of(widthDp: Int): WidthClass =
            when {
                widthDp < 600 -> Compact
                widthDp < 840 -> Medium
                else -> Expanded
            }
    }
}
