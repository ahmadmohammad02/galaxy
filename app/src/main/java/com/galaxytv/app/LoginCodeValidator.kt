package com.galaxytv.app

object LoginCodeValidator {
    fun isValidCode(rawCode: String?): Boolean {
        val code = rawCode?.trim() ?: return false

        if (code.isEmpty() || code.length != 12) {
            return false
        }

        return code.all { it.isDigit() }
    }
}
