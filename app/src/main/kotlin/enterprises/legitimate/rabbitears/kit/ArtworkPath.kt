package enterprises.legitimate.rabbitears.kit

/**
 * Where an image is asked for: the address the object's data carries, which names one image and
 * never another, so it is also the image's cache key. Without one, as from an older server or data
 * kept before an upgrade, the id address, which the server revalidates daily; an object with no
 * image carries none either, and the id address answers 404.
 */
object ArtworkPath {
    fun item(id: String, url: String?, kind: String = "poster") =
        url ?: "/api/v2/items/$id/artwork/$kind"

    fun collection(id: String, url: String?, kind: String = "poster") =
        url ?: "/api/v2/collections/$id/artwork/$kind"

    fun person(id: String, url: String?) = url ?: "/api/v2/people/$id/artwork"
}
