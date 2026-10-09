package com.sysbot32.robotmc.installer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InstallerVersionDecisionTest {

    @Test
    fun untaggedAndNonExactRefsStayAtOneZeroZero() {
        assertEquals("1.0.0", InstallerVersion.versionFor(null))
        assertEquals("1.0.0", InstallerVersion.versionFor(""))
        assertEquals("1.0.0", InstallerVersion.versionFor("main"))
        assertEquals("1.0.0", InstallerVersion.versionFor("v1.2.0-3-gabcdef"))
        assertEquals("1.0.0", InstallerVersion.versionFor("refs/tags/v1.2.0"))
        assertEquals("1.0.0", InstallerVersion.versionFor("v1.2.0-beta"))
        assertEquals("1.0.0", InstallerVersion.versionFor("V1.2.0"))
    }

    @Test
    fun exactReleaseTagDropsTheLeadingV() {
        assertEquals("1.2.0", InstallerVersion.versionFor("v1.2.0"))
    }
}
