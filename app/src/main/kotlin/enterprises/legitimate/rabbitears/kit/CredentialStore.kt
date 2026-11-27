package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.LibraryKind

/**
 * Where the device token, the last server with its name and each adult's profile memory live
 * between launches.
 */
interface CredentialStore {
    var server: String?
    /** What [server] last called itself. */
    var serverName: String?
    var token: String?

    /** The [LocalData.SCHEMA] of what this device keeps; null before any was recorded. */
    var dataSchema: Int?

    /** [key] names one signed-in adult on one server. */
    fun profileMemory(key: String): ProfileMemory

    fun setProfileMemory(key: String, memory: ProfileMemory)

    /** The library kinds [server] last answered with; null before it has. */
    fun libraryKinds(server: String): Set<LibraryKind>?

    fun setLibraryKinds(server: String, kinds: Set<LibraryKind>)
}

/**
 * Whether launch continues as the profile last active on this device and, for one with a PIN,
 * without asking it.
 */
data class ProfileMemory(val remember: Boolean = true, val skipPin: Boolean = true)
