package enterprises.legitimate.rabbitears.kit

/** A fetch of server bytes: [status] is null when no response came, such as on a timeout. */
class Fetched(val status: Int?, val body: ByteArray?)

/**
 * Whether an artwork outcome is final. An image that decoded and a 404 are; anything else may
 * succeed later, so the next appearance of the tile asks again.
 */
fun remembersArtwork(status: Int?, decoded: Boolean): Boolean =
    status == 404 || (status != null && status in 200..299 && decoded)
