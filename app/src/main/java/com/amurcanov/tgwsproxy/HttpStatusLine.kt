package com.amurcanov.tgwsproxy

import java.io.IOException

internal object HttpStatusLine {
    private val statusPattern = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s|$)")

    fun parse(line: String): Int {
        val match = statusPattern.find(line) ?: throw IOException("Invalid HTTP status line")
        val status = match.groupValues[1].toInt()
        if (status !in 100..599) throw IOException("Invalid HTTP status code")
        return status
    }
}
