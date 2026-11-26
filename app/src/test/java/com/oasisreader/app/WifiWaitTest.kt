package com.oasisreader.app

import com.oasisreader.app.audio.AudioDownloads
import com.oasisreader.app.data.BookQueue
import com.oasisreader.app.data.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 103: what goes on without Wi-Fi and what waits. */
class WifiWaitTest {
    private fun job(key: String, mobile: Boolean = false, quiet: Boolean = false, state: BookQueue.State = BookQueue.State.WAITING) =
        BookQueue.Job(key, "s", "https://x/$key", key, mobile = mobile, quiet = quiet, state = state)

    @Test fun onlyTheOneAskedForGoesOnMobileData() {
        val jobs = listOf(job("wait"), job("once", mobile = true))
        val heldWithoutWifi = { j: BookQueue.Job -> !j.mobile }
        assertEquals("once", BookQueue.next(jobs, emptySet(), heldWithoutWifi)?.key)
        assertNull(BookQueue.next(jobs, setOf("once"), heldWithoutWifi))
    }

    @Test fun whatWasAskedForComesBeforeAPrefetch() {
        val jobs = listOf(job("ahead", quiet = true), job("mine"))
        assertEquals("mine", BookQueue.next(jobs, emptySet()) { false }?.key)
        assertEquals("ahead", BookQueue.next(jobs, setOf("mine")) { false }?.key)
        assertNull(BookQueue.next(listOf(job("p", state = BookQueue.State.PAUSED)), emptySet()) { false })
    }

    @Test fun aQueueFileFromBeforeStillReads() {
        val old = """{"key":"k","serverId":"s","href":"https://x/k","title":"t"}"""
        val j = Http.json.decodeFromString(BookQueue.Job.serializer(), old)
        assertFalse(j.mobile); assertFalse(j.quiet); assertFalse(j.temp)
        val q = Http.json.decodeFromString(AudioDownloads.Queue.serializer(), """{"wanted":[3,4]}""")
        assertFalse(q.onMobile(3))
    }

    @Test fun audioTracksAreMarkedOneByOne() {
        val waiting = AudioDownloads.Queue().asking(listOf(1, 2), mobileOk = false, now = 10)
        val once = waiting.asking(listOf(3), mobileOk = true, now = 20)
        assertEquals(listOf(1, 2, 3), once.wanted)
        assertEquals(10, once.since)
        assertTrue(once.onMobile(3)); assertFalse(once.onMobile(1))
        // asked again to wait for Wi-Fi: the mark goes
        assertFalse(once.asking(listOf(3), mobileOk = false, now = 30).onMobile(3))
        // here: no longer wanted, nor marked
        val done = once.arrived(3, all = false)
        assertEquals(listOf(1, 2), done.wanted)
        assertTrue(done.mobile.isEmpty())
    }

    @Test fun theWholeBookOnMobileData() {
        val all = AudioDownloads.Queue().asking(listOf(AudioDownloads.ALL), mobileOk = true, now = 1)
        assertTrue(all.onMobile(7))
        assertEquals(all.wanted, all.arrived(7, all = true).wanted)
        assertTrue(all.arrived(7, all = true).onMobile(8))
    }
}
