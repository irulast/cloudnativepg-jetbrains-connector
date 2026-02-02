package com.irulast.cloudnativepg.intellij.kubernetes

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SecretDecoderTest {

    @Test
    fun `test decodes base64 value correctly`() {
        // "testpassword" encoded in base64
        val encoded = "dGVzdHBhc3N3b3Jk"
        val decoded = SecretDecoder.decodeValue(encoded)
        assertEquals("testpassword", decoded)
    }

    @Test
    fun `test decodes empty string`() {
        val encoded = ""
        val decoded = SecretDecoder.decodeValue(encoded)
        assertEquals("", decoded)
    }

    @Test
    fun `test decodes database name`() {
        // "mydb" encoded in base64
        val encoded = "bXlkYg=="
        val decoded = SecretDecoder.decodeValue(encoded)
        assertEquals("mydb", decoded)
    }

    @Test
    fun `test decodes username`() {
        // "postgres" encoded in base64
        val encoded = "cG9zdGdyZXM="
        val decoded = SecretDecoder.decodeValue(encoded)
        assertEquals("postgres", decoded)
    }

    @Test
    fun `test decodes port number`() {
        // "5432" encoded in base64
        val encoded = "NTQzMg=="
        val decoded = SecretDecoder.decodeValue(encoded)
        assertEquals("5432", decoded)
    }
}
