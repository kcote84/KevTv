package ca.kevtv
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkPrivacyTest {
    @Test fun credentialsStayOutOfArtworkAndPersistentMetadata() {
        val origin = "https://provider.invalid"
        listOf("/poster.jpg?token=demo", "https://name:secret@cdn.invalid/p.jpg",
            "/li%76e/demo/secret/12.ts", "/movie/demo/secret/12.mp4", "/poster.jpg#secret",
            "http://cdn.invalid/poster.jpg").forEach { assertEquals("", ArtworkRules.url(it, origin)) }
        assertEquals("https://provider.invalid/posters/cover.jpg", ArtworkRules.url("/posters/cover.jpg", origin))
        assertEquals("https://cdn.invalid/series/images/cover.jpg", ArtworkRules.url("https://cdn.invalid/series/images/cover.jpg", origin))
    }
}
