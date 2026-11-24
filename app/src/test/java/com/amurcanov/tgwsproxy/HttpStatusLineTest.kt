package com.amurcanov.tgwsproxy

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpStatusLineTest {
    @Test
    fun parsesSuccessfulResponse() {
        assertEquals(200, HttpStatusLine.parse("HTTP/1.1 200 OK"))
    }

    @Test
    fun parsesClientErrorResponseAsReachableHttp() {
        assertEquals(404, HttpStatusLine.parse("HTTP/1.1 404 Not Found"))
    }

    @Test(expected = IOException::class)
    fun rejectsMalformedStatusLine() {
        HttpStatusLine.parse("not an HTTP response")
    }

    @Test(expected = IOException::class)
    fun rejectsOutOfRangeStatusCode() {
        HttpStatusLine.parse("HTTP/1.1 999 Unknown")
    }
}
