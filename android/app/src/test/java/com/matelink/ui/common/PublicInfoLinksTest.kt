package com.matelink.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicInfoLinksTest {

    @Test fun emptyOrInvalidBaseUrl_hasNoLink() {
        listOf("", "http://info.matelink.local", "https://info.matelink.local/path", "https://user:pass@info.matelink.local", "https://info.matelink.local?x=1", "https://info.matelink.local#x")
            .forEach { assertNull(PublicInfoLinks.url(it, PublicInfoLinks.Page.HELP)) }
    }

    @Test fun validHttpsRoot_buildsThreeFixedPathsWithoutDoubleSlash() {
        assertEquals("https://info.matelink.local/help/", PublicInfoLinks.url("https://info.matelink.local", PublicInfoLinks.Page.HELP))
        assertEquals("https://info.matelink.local/legal/", PublicInfoLinks.url("https://info.matelink.local/", PublicInfoLinks.Page.LEGAL))
        assertEquals("https://info.matelink.local/changelog/", PublicInfoLinks.url("https://info.matelink.local/", PublicInfoLinks.Page.CHANGELOG))
    }
}
