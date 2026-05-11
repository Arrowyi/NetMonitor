package com.telenav.netmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class KnownHostsLoaderTest {

    private fun load(content: String): List<String> =
        KnownHostsLoader.loadFromStream { ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) }

    @Test
    fun `empty content returns empty list`() {
        assertTrue(load("").isEmpty())
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val list = load(
            """
            # Comment 1
            
            # Comment 2
              
            api.example.com
            """.trimIndent(),
        )
        assertEquals(listOf("api.example.com"), list)
    }

    @Test
    fun `multiple domains preserve declaration order`() {
        val list = load("a.example.com\nb.example.com\nc.example.com\n")
        assertEquals(listOf("a.example.com", "b.example.com", "c.example.com"), list)
    }

    @Test
    fun `lines are trimmed before validation`() {
        val list = load("   api.example.com   \n\t cdn.example.com\t\n")
        assertEquals(listOf("api.example.com", "cdn.example.com"), list)
    }

    @Test
    fun `invalid domain shapes are skipped`() {
        val list = load(
            """
            api.example.com
            not a domain
            valid-name.com
            http://example.com
            another!.com
            ok.example.com
            """.trimIndent(),
        )
        assertEquals(listOf("api.example.com", "valid-name.com", "ok.example.com"), list)
    }

    @Test
    fun `case is normalized to lower case`() {
        val list = load("API.Example.COM\nCdn.Example.com\n")
        assertEquals(listOf("api.example.com", "cdn.example.com"), list)
    }

    @Test
    fun `duplicates are removed (case-insensitive)`() {
        val list = load("api.example.com\nAPI.example.com\napi.example.com\n")
        assertEquals(listOf("api.example.com"), list)
    }

    @Test
    fun `excess entries beyond MAX_HOSTS are truncated`() {
        val content = buildString {
            for (i in 0 until KnownHostsLoader.MAX_HOSTS + 10) append("h$i.example.com\n")
        }
        val list = load(content)
        assertEquals(KnownHostsLoader.MAX_HOSTS, list.size)
        assertEquals("h0.example.com", list.first())
        assertEquals("h${KnownHostsLoader.MAX_HOSTS - 1}.example.com", list.last())
    }

    @Test
    fun `FileNotFoundException returns empty list (asset missing case)`() {
        val list = KnownHostsLoader.loadFromStream {
            throw FileNotFoundException("known_hosts.txt")
        }
        assertTrue(list.isEmpty())
    }

    @Test
    fun `unexpected IOException returns empty list (defensive)`() {
        val list = KnownHostsLoader.loadFromStream {
            throw IOException("disk on fire")
        }
        assertTrue(list.isEmpty())
    }

    @Test
    fun `unexpected RuntimeException returns empty list (paranoid catch)`() {
        val list = KnownHostsLoader.loadFromStream {
            throw RuntimeException("ART glitch")
        }
        assertTrue(list.isEmpty())
    }

    @Test
    fun `stream is closed even when parse loop completes normally`() {
        var closed = false
        val stream: InputStream = object : ByteArrayInputStream("api.example.com\n".toByteArray()) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        KnownHostsLoader.loadFromStream { stream }
        assertTrue("stream must be closed", closed)
    }
}
