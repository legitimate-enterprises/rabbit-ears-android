package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.ItemCredits

/**
 * The Details lines the boards draw from credits: who directed and who wrote, named as a movie
 * names them ("Director", "Writers") or as an episode does ("Directed by", "Written by").
 */
fun crewLines(credits: ItemCredits, episode: Boolean): List<Pair<String, String>> {
    fun names(jobs: Set<String>) = credits.crew.filter { it.job in jobs }.map { it.name }.distinct()
    val directors = names(setOf("Director"))
    val writers = names(setOf("Writer", "Screenplay", "Teleplay", "Story", "Novel"))
    fun label(names: List<String>, one: String, many: String, by: String) =
        if (episode) by else if (names.size == 1) one else many
    return listOfNotNull(
        directors
            .takeIf { it.isNotEmpty() }
            ?.let { label(it, "Director", "Directors", "Directed by") to it.joinToString(", ") },
        writers
            .takeIf { it.isNotEmpty() }
            ?.let { label(it, "Writer", "Writers", "Written by") to it.joinToString(", ") },
    )
}
