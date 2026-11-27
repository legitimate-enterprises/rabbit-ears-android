package enterprises.legitimate.rabbitears.kit

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataTest {
    private val root = Files.createTempDirectory("local").toFile()
    private val downloads = root.resolve("downloads")
    private val responses = root.resolve("responses")

    private fun keepSomething() {
        downloads.resolve("media").mkdirs()
        downloads.resolve("state.json").writeText("""{"requests":[{"id":"1","profileId":"7"}]}""")
        downloads.resolve("media/7.mkv").writeText("movie")
        responses.mkdirs()
        responses.resolve("kept").writeText("answer")
    }

    @Test
    fun anOlderDevicesKeptDataGoesAndItsSessionStays() {
        keepSomething()
        val store =
            MemoryCredentialStore().apply {
                server = "http://192.168.1.20:8477"
                token = "t"
            }
        LocalData.migrate(store, listOf(downloads, responses))
        assertFalse(downloads.exists())
        assertFalse(responses.exists())
        assertEquals("http://192.168.1.20:8477", store.server)
        assertEquals("t", store.token)
        assertEquals(LocalData.SCHEMA, store.dataSchema)
    }

    @Test
    fun whatThisSchemaKeptStays() {
        val store = MemoryCredentialStore()
        LocalData.migrate(store, listOf(downloads, responses))
        keepSomething()
        LocalData.migrate(store, listOf(downloads, responses))
        assertTrue(downloads.resolve("media/7.mkv").exists())
        assertTrue(responses.resolve("kept").exists())
    }
}
