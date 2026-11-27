package enterprises.legitimate.rabbitears.kit

import java.io.File

/**
 * What this device keeps from the server besides its session. A release that cannot read what an
 * earlier one kept raises [SCHEMA], and at launch, before anything opens it, the kept data goes
 * whole. The server and token stay for the server to accept, or refuse with unauthorized, which
 * signs out.
 */
object LocalData {
    const val SCHEMA = 2

    fun migrate(store: CredentialStore, kept: List<File>) {
        if (store.dataSchema == SCHEMA) return
        kept.forEach { it.deleteRecursively() }
        store.dataSchema = SCHEMA
    }
}
