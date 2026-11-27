package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.HouseholdAdultInput
import enterprises.legitimate.rabbitears.api.models.PasswordChange

/** The server's admin console, offered while an admin is browsing. */
val AppModel.adminUrl: String?
    get() = activeProfile?.takeIf { it.isAdmin }?.let { api?.endpoint?.resolve("/admin/") }

/** Replaces the signed-in adult's password; their other devices are signed out. */
suspend fun AppModel.changePassword(current: String, new: String) {
    try {
        signedIn().call { auth.changePassword(PasswordChange(current, new)) }
    } catch (e: Exception) {
        if (ApiError.from(e)?.code == "forbidden") throw AppError.WrongPassword
        throw AppError.from(e, AppError.SignedOut)
    }
}

/** Adds a pending adult to the household and returns what to hand them. */
suspend fun AppModel.addAdult(name: String, email: String): Invite {
    val added =
        try {
            signedIn().call { profiles.addHouseholdAdult(HouseholdAdultInput(name, email)) }
        } catch (e: Exception) {
            if (ApiError.from(e)?.code == "conflict") throw AppError.EmailTaken
            throw AppError.from(e, AppError.SignedOut)
        }
    reload()
    return invite(added)
}
