package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The four PIN dots: digits typed so far, never shown. */
class PinEntry {
    var digits by mutableStateOf("")
        private set

    /** Adds a digit; true once the PIN is complete and ready to submit. */
    fun type(c: Char): Boolean {
        if (c !in '0'..'9' || digits.length >= LENGTH) return false
        digits += c
        return digits.length == LENGTH
    }

    fun deleteBackward() {
        digits = digits.dropLast(1)
    }

    fun clear() {
        digits = ""
    }

    companion object {
        const val LENGTH = 4
    }
}
