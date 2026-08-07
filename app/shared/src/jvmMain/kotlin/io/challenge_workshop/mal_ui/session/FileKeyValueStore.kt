package io.challenge_workshop.mal_ui.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Desktop [KeyValueStore]: one JSON file, `0600`, under `$XDG_STATE_HOME`.
 *
 * No OS keychain — see `docs/adr/0002-hand-rolled-key-value-store.md`. Every candidate library
 * looked dormant, pulled JNA plus native bits that complicate `jpackage`, and headless Linux
 * has no Secret Service anyway, so this fallback would have been needed regardless.
 *
 * Reads and writes are whole-file and serialised behind a [Mutex], which is affordable because
 * the file holds two records. Writes go to a sibling temp file and are then moved into place,
 * so a crash mid-write cannot leave a truncated store that reads as corrupt.
 */
class FileKeyValueStore(
    private val file: Path,
    private val json: Json = Json,
) : KeyValueStore {

    private val mutex = Mutex()

    override suspend fun read(key: String): String? = mutex.withLock { load()[key] }

    override suspend fun write(key: String, value: String) = mutex.withLock {
        save(load() + (key to value))
    }

    override suspend fun remove(key: String) = mutex.withLock {
        val current = load()
        if (key in current) save(current - key)
    }

    private suspend fun load(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext emptyMap()
        try {
            json.decodeFromString<Map<String, String>>(Files.readString(file))
        } catch (_: Exception) {
            // A corrupt file is discarded rather than thrown: the only thing in it is a
            // credential that can be obtained again by signing in. JsonTokenStore applies the
            // same policy per record; this is the same argument one level down.
            emptyMap()
        }
    }

    private suspend fun save(entries: Map<String, String>) = withContext(Dispatchers.IO) {
        val parent = file.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
            restrictTo(parent, OWNER_ONLY_DIR)
        }
        val temp = Files.createTempFile(parent, ".${file.fileName}", ".tmp")
        restrictTo(temp, OWNER_ONLY_FILE)
        Files.writeString(temp, json.encodeToString(entries))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        // Re-asserted after the move: REPLACE_EXISTING keeps the source's permissions on most
        // filesystems, but a store written by an older build may have been wider.
        restrictTo(file, OWNER_ONLY_FILE)
    }

    /** No-op where POSIX permissions do not apply, which is Windows. */
    private fun restrictTo(path: Path, permissions: Set<PosixFilePermission>) {
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }

    companion object {
        private val OWNER_ONLY_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
        private val OWNER_ONLY_DIR: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")

        /**
         * `$XDG_STATE_HOME/<namespace>/store.json`, falling back to the spec's
         * `~/.local/state`, and to `%APPDATA%` on Windows.
         */
        fun defaultPathFor(
            namespace: String,
            env: (String) -> String? = System::getenv,
            osName: String = System.getProperty("os.name").orEmpty(),
        ): Path {
            val home = env("HOME") ?: System.getProperty("user.home").orEmpty()
            val base = if (osName.startsWith("Windows", ignoreCase = true)) {
                env("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming"
            } else {
                env("XDG_STATE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/state"
            }
            return Paths.get(base, namespace, "store.json")
        }

        fun defaultFor(namespace: String): FileKeyValueStore = FileKeyValueStore(defaultPathFor(namespace))
    }
}
