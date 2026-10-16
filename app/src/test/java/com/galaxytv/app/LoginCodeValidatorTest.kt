package com.galaxytv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCodeValidatorTest {
    @Test
    fun validCodesAreAccepted() {
        assertTrue(LoginCodeValidator.isValidCode("123456789012"))
    }

    @Test
    fun invalidCodesAreRejected() {
        assertFalse(LoginCodeValidator.isValidCode(""))
        assertFalse(LoginCodeValidator.isValidCode("   "))
        assertFalse(LoginCodeValidator.isValidCode("1234"))
        assertFalse(LoginCodeValidator.isValidCode("GALAXY"))
        assertFalse(LoginCodeValidator.isValidCode("abc"))
    }
}
