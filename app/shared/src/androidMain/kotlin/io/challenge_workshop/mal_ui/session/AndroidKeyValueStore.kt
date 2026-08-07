package io.challenge_workshop.mal_ui.session

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [KeyValueStore]: plain `SharedPreferences`, `MODE_PRIVATE`.
 *
 * Deliberately **not** `androidx.security.crypto`, whose 1.1.0 release deprecated every API and
 * whose Javadoc reads "Use SharedPreferences instead". Android 10 enforces file-based encryption
 * device-wide and the app sandbox already blocks other apps, so the security delta was about zero
 * while adding StrictMode violations and Tink keyset-corruption crashes.
 *
 * What does matter is **keeping this out of Auto Backup**, which would otherwise copy a refresh
 * token to the cloud — see the manifest in `:app:androidApp`.
 *
 * Writes use `commit()` on an IO dispatcher rather than `apply()`, so a suspending `write` that
 * has returned really is on disk. `apply()` would let a process death immediately after sign-in
 * lose the Session it just persisted.
 */
class AndroidKeyValueStore(
    context: Context,
    namespace: String,
) : KeyValueStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)

    override suspend fun read(key: String): String? =
        withContext(Dispatchers.IO) { prefs.getString(key, null) }

    override suspend fun write(key: String, value: String) {
        withContext(Dispatchers.IO) { prefs.edit().putString(key, value).commit() }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { prefs.edit().remove(key).commit() }
    }
}
