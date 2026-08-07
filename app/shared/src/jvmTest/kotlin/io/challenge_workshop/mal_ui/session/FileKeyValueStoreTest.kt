package io.challenge_workshop.mal_ui.session

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKeyValueStoreTest {

    private val dir: Path = createTempDirectory("mal-store-test")
    private val file: Path = dir.resolve("nested").resolve("store.json")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun satisfies_the_key_value_store_contract() = runTest {
        assertKeyValueStoreRoundTrip(FileKeyValueStore(file))
    }

    @Test
    fun the_file_and_its_directory_are_owner_only() = runTest {
        FileKeyValueStore(file).write("k", "v")

        if (!file.fileSystem.supportedFileAttributeViews().contains("posix")) return@runTest
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(file),
        )
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(file.parent),
        )
    }

    @Test
    fun a_pre_existing_world_readable_file_is_narrowed_on_the_next_write() = runTest {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}")
        if (!file.fileSystem.supportedFileAttributeViews().contains("posix")) return@runTest
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))

        FileKeyValueStore(file).write("k", "v")

        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(file),
        )
    }

    @Test
    fun survives_a_reopen() = runTest {
        FileKeyValueStore(file).write("mal.session.v1", """{"tokens":1}""")

        assertEquals("""{"tokens":1}""", FileKeyValueStore(file).read("mal.session.v1"))
    }

    @Test
    fun a_corrupt_file_reads_as_empty_rather_than_throwing() = runTest {
        Files.createDirectories(file.parent)
        Files.writeString(file, "this is not json")

        val store = FileKeyValueStore(file)
        assertNull(store.read("anything"))
        // ...and the store is still usable afterwards.
        store.write("k", "v")
        assertEquals("v", store.read("k"))
    }

    @Test
    fun no_temp_files_are_left_behind() = runTest {
        val store = FileKeyValueStore(file)
        store.write("a", "1")
        store.write("b", "2")
        store.remove("a")

        val leftovers = Files.list(file.parent).use { paths ->
            paths.filter { it.fileName.toString() != "store.json" }.toList()
        }
        assertTrue(leftovers.isEmpty(), "leftover files: $leftovers")
    }

    @Test
    fun the_default_path_follows_xdg_state_home() {
        val env = mapOf("XDG_STATE_HOME" to "/tmp/state", "HOME" to "/home/someone")
        assertEquals(
            Path.of("/tmp/state", "ns", "store.json"),
            FileKeyValueStore.defaultPathFor("ns", env::get, osName = "Linux"),
        )
    }

    @Test
    fun the_default_path_falls_back_to_dot_local_state() {
        val env = mapOf("HOME" to "/home/someone")
        assertEquals(
            Path.of("/home/someone/.local/state", "ns", "store.json"),
            FileKeyValueStore.defaultPathFor("ns", env::get, osName = "Linux"),
        )
    }

    @Test
    fun the_default_path_uses_appdata_on_windows() {
        val env = mapOf("APPDATA" to "C:\\Users\\someone\\AppData\\Roaming", "HOME" to "C:\\Users\\someone")
        val path = FileKeyValueStore.defaultPathFor("ns", env::get, osName = "Windows 11")

        assertTrue(path.toString().contains("Roaming"), "was $path")
        assertTrue(path.toString().endsWith("store.json"), "was $path")
    }
}
