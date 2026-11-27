package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.AccountDeletion
import enterprises.legitimate.rabbitears.api.models.AccountDeletion.Outcome
import enterprises.legitimate.rabbitears.api.models.PasswordConfirmation

/** The server's only admin keeps their account until another is made an admin. */
val AccountDeletion.allowed: Boolean
    get() = outcome != Outcome.refused

/** What the confirmation says deleting the account takes with it. */
val AccountDeletion.summary: String
    get() {
        if (outcome == Outcome.refused)
            return "You are the server’s only admin, so your account stays until you make another " +
                "account an admin in the admin console."
        val what =
            "This deletes your account for good, with your watch history, your playlists and the " +
                "stations that play them, and your stations, and signs out every device signed in " +
                "as you. It cannot be undone."
        return when (outcome) {
            Outcome.transfer ->
                "$what The household stays, with its kids, and ${newOwnerName ?: "another adult"} " +
                    "becomes its owner."
            Outcome.household -> {
                val kids =
                    if (kidNames.isEmpty()) ""
                    else ", with ${joined(kidNames)} and everything of theirs"
                "$what You are the household’s only adult, so the household goes too$kids."
            }
            else -> "$what The household stays as it is."
        }
    }

/** "Mia", "Mia and Leo", "Mia, Leo and Ada". */
private fun joined(names: List<String>) =
    if (names.size < 2) names.firstOrNull().orEmpty()
    else "${names.dropLast(1).joinToString(", ")} and ${names.last()}"

/** What deleting the active adult's own account takes with it, for the confirmation to say. */
suspend fun AppModel.accountDeletion(): AccountDeletion {
    val api = signedIn()
    return try {
        api.call { auth.getAccountDeletion() }
    } catch (e: Exception) {
        throw AppError.from(e, AppError.SignedOut)
    }
}

/** Deletes the active adult's own account with its password, then signs this device out. */
suspend fun AppModel.deleteAccount(password: String) {
    val api = signedIn()
    try {
        api.call { auth.deleteOwnAccount(PasswordConfirmation(password)) }
    } catch (e: Exception) {
        if (ApiError.from(e)?.code == "forbidden") throw AppError.WrongPassword
        throw AppError.from(e, AppError.SignedOut)
    }
    signOut()
}
