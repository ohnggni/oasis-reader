package com.oasisreader.app

import com.oasisreader.app.remote.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** remote://<서버>/<경로> 는 받은 책의 출처로 DB에 남으므로 모양이 바뀌면 안 된다. */
class RemoteAddressTest {
    @Test fun paths() {
        assertEquals("/", Remote.norm(""))
        assertEquals("/a/b", Remote.norm("/a/b/"))
        assertEquals("/a/b", Remote.norm("a\\.\\b"))
        assertEquals("원피스 1권.cbz", Remote.nameOf("/만화/원피스 1권.cbz/"))
        assertEquals("", Remote.nameOf("/"))
    }

    @Test fun links() {
        assertEquals("remote://s1/만화/a b.cbz", Remote.url("s1", "만화/a b.cbz"))
        assertEquals("remote://s1/", Remote.url("s1", ""))
        assertEquals("s1" to "/만화/a b.cbz", Remote.parseUrl("remote://s1/만화/a b.cbz"))
        assertEquals("s1" to "/", Remote.parseUrl("remote://s1/"))
        assertNull(Remote.parseUrl("remote://s1"))
        assertNull(Remote.parseUrl("remote:///a"))
        assertNull(Remote.parseUrl("https://h/a"))
        assertTrue(Remote.isUrl("remote://x"))
        assertFalse(Remote.isUrl(null))
    }

    @Test fun protocolNames() {
        assertEquals(listOf("WebDAV", "SFTP", "SMB", "FTPS", "FTP"), Remote.ALL.map { Remote.label(it) })
        assertEquals("nfs", Remote.label("nfs"))
    }
}
