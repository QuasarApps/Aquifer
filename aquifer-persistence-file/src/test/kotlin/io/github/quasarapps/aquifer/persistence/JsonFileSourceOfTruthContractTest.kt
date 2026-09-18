package io.github.quasarapps.aquifer.persistence

import io.github.quasarapps.aquifer.SourceOfTruth
import io.github.quasarapps.aquifer.test.AbstractSourceOfTruthContractTest
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/**
 * Runs the published [AbstractSourceOfTruthContractTest] against [JsonFileSourceOfTruth].
 *
 * The interesting subject for the *opted-out* half of the enumeration contract: this store cannot
 * list its keys, because each filename is a one-way SHA-256 of the key, so the suite's
 * `keys()`/`keysWhere()` cases assert it returns `null` rather than an empty set.
 */
class JsonFileSourceOfTruthContractTest : AbstractSourceOfTruthContractTest() {

    @TempDir
    lateinit var root: Path

    private lateinit var storeDirectory: Path

    /** Its filenames are a one-way hash of the key, so it keeps the SPI's `null` default. */
    override val isEnumerable: Boolean = false

    /** Each test gets its own directory under the per-test temp root, so stores never share state. */
    override fun createStore(): SourceOfTruth<String, String> {
        storeDirectory = Files.createTempDirectory(root, "store")
        return JsonFileSourceOfTruth(storeDirectory, String.serializer())
    }

    /**
     * Corrupts the envelope this store just wrote. Overwriting the file directly — rather than
     * storing a value some other serializer produced — is what makes the "undecodable" state
     * unambiguous: the bytes are not JSON at all, so no lenient decode can quietly succeed.
     */
    override suspend fun writeUndecodableEntry(store: SourceOfTruth<String, String>, key: String): Boolean {
        store.write(key, entry("placeholder"))
        val envelope = storeDirectory.listDirectoryEntries().singleOrNull { it.isRegularFile() } ?: return false
        envelope.writeText("this is not json")
        return true
    }
}
