package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.LibraryKind

class MemoryCredentialStore : CredentialStore {
    override var server: String? = null
    override var serverName: String? = null
    override var token: String? = null
    private val memories = mutableMapOf<String, ProfileMemory>()

    override fun profileMemory(key: String) = memories[key] ?: ProfileMemory()

    override fun setProfileMemory(key: String, memory: ProfileMemory) {
        memories[key] = memory
    }

    private val kinds = mutableMapOf<String, Set<LibraryKind>>()

    override fun libraryKinds(server: String) = kinds[server]

    override fun setLibraryKinds(server: String, kinds: Set<LibraryKind>) {
        this.kinds[server] = kinds
    }
}
