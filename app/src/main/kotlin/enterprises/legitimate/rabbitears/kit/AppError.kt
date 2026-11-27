package enterprises.legitimate.rabbitears.kit

import java.io.IOException

/** What went wrong, in the words the account boards use. */
open class AppError(override val message: String) : Exception(message) {
    object BadServer : AppError("Enter a server address, such as https://media.example.com.")

    object Unreachable : AppError("Can't reach that server.")

    object InsecureServer : AppError("Use https:// for a server outside your home network.")

    object WrongCredentials : AppError("Email or password is wrong.")

    object WrongPin : AppError("Wrong PIN.")

    object PinLocked : AppError("Too many tries. Try again in 15 minutes.")

    object PinRequired : AppError("This profile needs its PIN.")

    object SignedOut : AppError("Signed out.")

    object WrongPassword : AppError("The password is wrong.")

    object InviteInvalid : AppError("This invite is no longer valid. Ask for a new one.")

    object EmailTaken : AppError("That email already has an account.")

    object PinNeedsServer : AppError("A profile with a PIN opens once your server is reachable.")

    object TokenNotKept : AppError("This device couldn't keep the sign-in. Try again.")

    class TooManyAttempts(minutes: Int) :
        AppError(
            "Too many attempts. Try again in $minutes ${if (minutes == 1) "minute" else "minutes"}.",
        )

    companion object {
        /** Maps a thrown error; [unauthorized] names what a 401 means where it was made. */
        fun from(error: Throwable, unauthorized: AppError = WrongCredentials): AppError {
            if (error is AppError) return error
            val api =
                ApiError.from(error)
                    ?: return if (error is IOException) Unreachable
                    else AppError(error.message ?: "Something went wrong.")
            return when (api.code) {
                "unauthorized" -> unauthorized
                "pin_locked" -> PinLocked
                "pin_required" -> PinRequired
                "too_many_requests" -> TooManyAttempts(maxOf(1, ((api.retryAfter ?: 60) + 59) / 60))
                else -> AppError(api.message)
            }
        }
    }
}
