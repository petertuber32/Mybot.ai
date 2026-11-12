package com.mybotai.bacboanalyzer.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun detectsNewerPatch() {
        assertTrue(UpdateChecker.isNewer("v1.0.1", "1.0.0"))
    }

    @Test
    fun detectsNewerMinorAndMajor() {
        assertTrue(UpdateChecker.isNewer("v1.1.0", "1.0.9"))
        assertTrue(UpdateChecker.isNewer("v2.0", "1.9.9"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("v1.0.0", "v1.0.0"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("v0.9.0", "1.0.0"))
    }

    @Test
    fun numericComparisonNotLexicographic() {
        assertTrue(UpdateChecker.isNewer("v1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("v1.2.0", "1.10.0"))
    }

    @Test
    fun missingComponentsTreatedAsZero() {
        assertTrue(UpdateChecker.isNewer("v1.1", "1.0.5"))
        assertFalse(UpdateChecker.isNewer("v1.0", "1.0.5"))
    }
}
