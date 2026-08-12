package com.nexterm.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A licence document that ships in the APK under `assets/licenses`.
 *
 * These are not decoration. NEXTERM bundles PRoot, which is GPL-2.0, and talloc, which
 * is LGPL-3.0 — and both licences require the licence text and a source offer to travel
 * with the binary, so the text has to be readable from the installed app, not only from
 * the source repository. [NOTICES] carries the attribution and the written offer of
 * source.
 */
enum class LicenseDocument(val title: String, val subtitle: String, val asset: String) {
    NOTICES(
        title = "Third-party notices",
        subtitle = "What NEXTERM bundles, and where to get its source",
        asset = "licenses/NOTICE.txt",
    ),
    GPL_2_0(
        title = "GNU General Public License v2",
        subtitle = "Applies to the bundled PRoot binaries",
        asset = "licenses/GPL-2.0.txt",
    ),
    LGPL_3_0(
        title = "GNU Lesser General Public License v3",
        subtitle = "Applies to the bundled talloc, which PRoot links against",
        asset = "licenses/LGPL-3.0.txt",
    ),
    GPL_3_0(
        title = "GNU General Public License v3",
        subtitle = "Referenced by the LGPL v3, which is written as a set of additional permissions over it",
        asset = "licenses/GPL-3.0.txt",
    ),
    BSD_3_CLAUSE(
        title = "BSD 3-Clause License",
        subtitle = "Applies to the bundled libandroid-shmem",
        asset = "licenses/BSD-3-Clause-libandroid-shmem.txt",
    ),
    APACHE_2_0(
        title = "Apache License 2.0",
        subtitle = "Applies to AndroidX, Compose, Kotlin, Hilt and the Termux terminal engine",
        asset = "licenses/Apache-2.0.txt",
    ),
}

/** A licence document and its text, or the reason the text could not be read. */
data class LicenseText(val document: LicenseDocument, val body: String)

/** Reads the bundled licence documents off the main thread. */
@Singleton
class LicenseRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun read(document: LicenseDocument): LicenseText = withContext(Dispatchers.IO) {
        val body = runCatching {
            context.assets.open(document.asset).bufferedReader().use { it.readText() }
        }.getOrElse { failure ->
            // Saying so is better than showing an empty sheet that looks like the
            // licence simply has nothing in it.
            "${document.title} could not be read from this build.\n\n" +
                "Expected asset: ${document.asset}\n" +
                "Error: ${failure.message ?: failure.javaClass.simpleName}"
        }
        LicenseText(document, body)
    }
}
