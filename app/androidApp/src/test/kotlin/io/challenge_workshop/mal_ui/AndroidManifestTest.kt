package io.challenge_workshop.mal_ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import io.challenge_workshop.mal_ui.mal.ANDROID_REDIRECT_URI
import io.challenge_workshop.mal_ui.session.MAL_STORE_NAMESPACE
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the manifest against silent drift.
 *
 * Every fact asserted here fails silently in production if it regresses: a redirect that resolves
 * nowhere, a second activity holding a different `ViewModelStore`, a refresh token in a cloud
 * backup. None of them throws, and none of them shows up in a normal sign-in on a dev machine.
 *
 * Robolectric resolves against the **merged** manifest as Android itself parses it, so these assert
 * what ships rather than what `src/main/AndroidManifest.xml` happens to say. The one exception is
 * `<queries>`, which no `PackageManager` API exposes — see [sourceManifest].
 */
@RunWith(RobolectricTestRunner::class)
class AndroidManifestTest {

    private val context = RuntimeEnvironment.getApplication()

    // Robolectric honours the manifest's `android:name`, so every test here boots MalUiApplication
    // and with it Koin's global context — which is static, and would refuse to start a second time.
    // Incidentally proves the class named in the manifest exists and its onCreate runs.
    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun the_android_redirect_uri_resolves_to_main_activity() {
        // The one assertion that ties the intent filter's scheme/host/path back to the single string
        // registered on the MAL app. Split across three attributes in the manifest, so a typo in any
        // of them is invisible until a redirect lands on nothing.
        assertResolvesToMainActivity(ANDROID_REDIRECT_URI)
    }

    @Test
    fun a_redirect_carrying_a_code_still_resolves() {
        // What actually arrives. A `path` filter matches the path only, but an `android:path` that
        // was mistakenly written as a full URI would pass the test above and fail here.
        assertResolvesToMainActivity("$ANDROID_REDIRECT_URI?code=a-code&state=a-state")
    }

    @Test
    fun main_activity_is_single_top() {
        // `standard` stacks a second MainActivity with its own ViewModelStore, so the instance that
        // receives the redirect is not the one holding the Pending Authorization in memory.
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activity.launchMode)
    }

    @Test
    fun the_app_does_not_participate_in_backup() {
        val flags = context.applicationInfo.flags

        assertEquals(
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
            "allowBackup is on, which lets Auto Backup copy the refresh token to the cloud.",
        )
    }

    @Test
    fun the_token_store_is_excluded_from_every_extraction_path() {
        // `allowBackup="false"` stops cloud backup, but device-to-device transfer on API 31+ is
        // governed by the extraction rules alone — and below API 31 those rules are ignored and
        // `fullBackupContent` is what counts. So the store is named in all three places.
        val store = "sharedpref:$MAL_STORE_NAMESPACE.xml"

        assertEquals(
            setOf("cloud-backup" to store, "device-transfer" to store),
            excludesIn(R.xml.data_extraction_rules),
        )
        assertEquals(
            setOf("full-backup-content" to store),
            excludesIn(R.xml.backup_rules),
        )
    }

    @Test
    fun browsers_are_visible_through_a_queries_element() {
        // The API 30+ package-visibility declaration — see CLAUDE.md for what breaks without it.
        val queried = queriedIntents()

        assertTrue(
            setOf("action=android.support.customtabs.action.CustomTabsService") in queried,
            "no <queries> entry for the Custom Tabs service: $queried",
        )
        assertTrue(
            setOf(
                "action=android.intent.action.VIEW",
                "category=android.intent.category.BROWSABLE",
                "data:scheme=https",
            ) in queried,
            "no <queries> entry for an https-capable browser: $queried",
        )
    }

    private fun assertResolvesToMainActivity(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)

        val matched = context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.name }

        assertEquals(listOf(MainActivity::class.java.name), matched, "for $uri")
    }

    /** Every `<exclude>` in a backup rules resource, as `enclosing element to "domain:path"`. */
    private fun excludesIn(rules: Int): Set<Pair<String, String>> {
        val parser = context.resources.getXml(rules)
        val excluded = mutableSetOf<Pair<String, String>>()
        var section = ""

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name != "exclude") {
                // These files are two elements deep at most, so the last tag opened before an
                // <exclude> is the section it belongs to.
                section = parser.name
                continue
            }
            // Backup rules use unprefixed attributes, not the android namespace.
            val domain = parser.getAttributeValue(null, "domain")
            val path = parser.getAttributeValue(null, "path")
            excluded += section to "$domain:$path"
        }
        return excluded
    }

    /**
     * Each `<intent>` under `<queries>`, as the set of its children — `action=…`, `category=…`, and
     * one `data:<attribute>=…` per attribute of a `<data>`, so a stray `host` or `path` cannot hide
     * inside a match.
     *
     * Read out of `src/main/AndroidManifest.xml` rather than through the `PackageManager`, because
     * package visibility lives in the system server: no public API exposes it and Robolectric models
     * none of it. Gradle runs a `Test` task with the project directory as its working directory, which
     * is what makes the relative path dependable.
     */
    private fun queriedIntents(): Set<Set<String>> {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "no manifest at ${manifest.absolutePath}")

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)

        return document.documentElement.children()
            .single { it.nodeName == "queries" }
            .children()
            .filter { it.nodeName == "intent" }
            .map { intent -> intent.children().flatMap(::describe).toSet() }
            .toSet()
    }

    private fun Node.children(): List<Node> = childNodes.let { List(it.length, it::item) }

    /** An `<action>`, `<category>` or `<data>` child as `tag=value` pairs; nothing for other nodes. */
    private fun describe(node: Node): List<String> {
        val attributes = node.attributes ?: return emptyList()
        val named = List(attributes.length, attributes::item)

        return when (node.nodeName) {
            "action", "category" -> named.map { "${node.nodeName}=${it.nodeValue}" }
            // `android:scheme` and friends, each kept separate: `<data android:scheme="https"/>` and
            // `<data android:scheme="https" android:host="example.com"/>` are different queries.
            "data" -> named.map { "data:${it.nodeName.removePrefix("android:")}=${it.nodeValue}" }
            else -> emptyList()
        }
    }
}
