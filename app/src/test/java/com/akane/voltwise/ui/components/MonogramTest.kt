package com.akane.voltwise.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MonogramTest {
    @Test
    fun takesTheFirstLetterOrDigit() {
        assertEquals("C", monogram("chrome"))
        assertEquals("7", monogram("  7-Zip"))
        assertEquals("Ö", monogram("(özel)"))
    }

    @Test
    fun keepsSurrogatePairsWhole() {
        assertEquals("𝐀", monogram("𝐀pp"))
    }

    @Test
    fun fallsBackWhenThereIsNoLetter() {
        assertEquals("?", monogram(""))
        assertEquals("?", monogram("—…"))
    }
}
