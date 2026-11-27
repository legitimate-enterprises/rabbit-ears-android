package enterprises.legitimate.rabbitears.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WidthClassTest {
    @Test
    fun breakpointsFollowMaterialWindowSizeClasses() {
        val cases =
            mapOf(
                0 to WidthClass.Compact,
                360 to WidthClass.Compact,
                599 to WidthClass.Compact,
                600 to WidthClass.Medium,
                800 to WidthClass.Medium,
                839 to WidthClass.Medium,
                840 to WidthClass.Expanded,
                1280 to WidthClass.Expanded,
            )
        cases.forEach { (width, expected) ->
            assertEquals("$width dp", expected, WidthClass.of(width))
        }
    }

    @Test
    fun onlyCompactUsesTheBottomBar() {
        assertEquals(true, WidthClass.Compact.bottomBar)
        assertEquals(false, WidthClass.Medium.bottomBar)
        assertEquals(false, WidthClass.Expanded.bottomBar)
    }
}
