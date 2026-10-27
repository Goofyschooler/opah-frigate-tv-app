package app.opah.tv.data.update

import app.opah.tv.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class TestVariantIdentityTest {
    @Test
    fun forkFeedIsRestrictedToTheExistingTestPackage() {
        val isTest = BuildConfig.BUILD_TYPE == "darknodeTest"
        assertEquals(
            if (isTest) "Goofyschooler" else "VibeCodingAntagonist",
            GitHubReleaseApiClient.OPAH_REPOSITORY_OWNER,
        )
        if (isTest) {
            assertEquals("app.opah.tv.darknode.test", BuildConfig.APPLICATION_ID)
            assertEquals(6002, BuildConfig.VERSION_CODE)
            assertEquals("0.6.0-darknode-test", BuildConfig.VERSION_NAME)
        }
    }
}
