package ca.kevtv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseRulesTest {
    private val goodSum = "a".repeat(64)

    private fun metadata(tag: String = "v0.2.0", assetUrl: String =
        "https://github.com/kcote84/KevTv/releases/download/$tag/KevTV.apk", prerelease: Boolean = false,
        sha: String? = "sha256:$goodSum", size: Long = 1234L): JSONObject {
        val asset = JSONObject().put("name", "KevTV.apk").put("state", "uploaded")
            .put("size", size).put("browser_download_url", assetUrl).put("digest", sha ?: JSONObject.NULL)
        return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", prerelease)
            .put("body", "Corrections.").put("html_url", "https://github.com/kcote84/KevTv/releases/tag/$tag")
            .put("assets", JSONArray().put(asset))
    }

    @Test fun readsOnlyNewerPublicStableRelease() {
        val update = ReleaseRules.parse(metadata().toString(), "0.1.9")!!
        assertEquals("0.2.0", update.version)
        assertEquals("Corrections.", update.notes)
        assertEquals(goodSum, update.sha256)
        assertNull(ReleaseRules.parse(metadata().toString(), "0.2.0"))
        assertNull(ReleaseRules.parse(metadata().toString(), "1.0.0"))
        assertNull(ReleaseRules.parse(metadata(prerelease = true).toString(), "0.1.9"))
        assertNull(ReleaseRules.parse(metadata().put("draft", true).toString(), "0.1.9"))
        assertNull(ReleaseRules.parse(metadata(sha = null).toString(), "0.1.9")!!.sha256)
    }

    @Test fun rejectsUntrustedDownloadPathsAndPackageMetadata() {
        val path = "/kcote84/KevTV/releases/download/v0.2.0/KevTV.apk"
        assertTrue(ReleaseRules.initialUrl("https://github.com$path", "v0.2.0"))
        val bad = listOf("http://github.com$path", "https://github.com.evil.test$path",
            "https://github.com@evil.test$path", "https://evil@github.com$path",
            "https://github.com:8443$path", "https://github.com$path?q=1", "https://github.com$path#x",
            "https://github.com/kcote84/VickTV/releases/download/v0.2.0/KevTV.apk",
            "https://github.com/kcote84/KevTV/releases/download/v0.2.1/KevTV.apk",
            "https://github.com/kcote84/KevTV/releases/download/v0.2.0/Other.apk")
        bad.forEach { assertFalse(it, ReleaseRules.initialUrl(it, "v0.2.0")) }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseRules.parse(metadata(sha = "sha256:invalid").toString(), "0.1.9")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseRules.parse(metadata(size = ReleaseRules.MAX_BYTES + 1).toString(), "0.1.9")
        }
    }

    @Test fun followsOnlyOfficialSignedReleaseAssetLocation() {
        val suffix = "/github-production-release-asset-2e65be/1/example?sig=fixture"
        val official = "https://release-assets.githubusercontent.com"
        assertEquals("$official$suffix", ReleaseRules.officialRedirect("$official$suffix"))
        assertNotNull(ReleaseRules.officialRedirect("$official/github-production-release-asset/1/fixture?sig=fixture"))
        assertNotNull(ReleaseRules.officialRedirect("https://objects.githubusercontent.com$suffix"))
        assertNull(ReleaseRules.officialRedirect("http://release-assets.githubusercontent.com$suffix"))
        assertNull(ReleaseRules.officialRedirect("https://release-assets.githubusercontent.com.evil.test$suffix"))
        assertNull(ReleaseRules.officialRedirect("https://someone@release-assets.githubusercontent.com$suffix"))
        assertNull(ReleaseRules.officialRedirect("$official/somewhere-else?sig=fixture"))
        assertNull(ReleaseRules.officialRedirect("$official${suffix.substringBefore('?')}"))
        assertNull(ReleaseRules.officialRedirect("//release-assets.githubusercontent.com$suffix"))
    }

    @Test fun requiresExactCurrentSignersPackageAndHigherVersionCode() {
        val exact = setOf("cert-one", "cert-two")
        assertTrue(ReleaseRules.apkMatches("0.2.0", 9, "ca.kevtv", "0.2.0", 10, exact, exact.reversed().toSet()))
        assertFalse(ReleaseRules.apkMatches("0.2.0", 9, "ca.vicktv", "0.2.0", 10, exact, exact))
        assertFalse(ReleaseRules.apkMatches("0.2.0", 9, "ca.kevtv", "0.2.1", 10, exact, exact))
        assertFalse(ReleaseRules.apkMatches("0.2.0", 9, "ca.kevtv", "0.2.0", 9, exact, exact))
        assertFalse(ReleaseRules.apkMatches("0.2.0", 9, "ca.kevtv", "0.2.0", 10, exact, setOf("cert-one")))
        assertFalse(ReleaseRules.apkMatches("0.2.0", 9, "ca.kevtv", "0.2.0", 10, emptySet(), emptySet()))
    }
}
