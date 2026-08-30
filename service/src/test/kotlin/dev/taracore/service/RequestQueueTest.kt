package dev.taracore.service

import dev.taracore.api.TaraCoreErrors
import dev.taracore.engine.GenRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestQueueTest {

    private fun request(id: String) = GenRequest(requestId = id, messages = emptyList())

    private fun queued(
        id: String,
        run: suspend (RequestQueue.QueuedRequest) -> Unit = {},
        onRejected: (Int, String) -> Unit = { _, _ -> },
    ) = RequestQueue.QueuedRequest(
        request = request(id),
        callerUid = 1000,
        callerPackage = "test",
        run = run,
        onRejected = onRejected,
    )

    @Test
    fun `runs submitted requests in order`() = runTest {
        val executed = CopyOnWriteArrayList<String>()
        val queue = RequestQueue(this)
        queue.start()

        listOf("a", "b", "c").forEach { id ->
            assertTrue(queue.submit(queued(id, run = { executed.add(it.request.requestId) })))
        }
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), executed.toList())
        queue.stop()
    }

    @Test
    fun `rejects with QUEUE_FULL once capacity is reached`() = runTest {
        val gate = CompletableDeferred<Unit>()
        // Capacity 2 with a worker parked on `gate`: the third submission has nowhere
        // to go, which is the condition we want to observe.
        val queue = RequestQueue(this, capacity = 2)
        queue.start()

        assertTrue(queue.submit(queued("first", run = { gate.await() })))
        advanceUntilIdle()   // worker picks up "first" and blocks on the gate

        assertTrue(queue.submit(queued("second")))
        assertTrue(queue.submit(queued("third")))

        var code = -1
        var message = ""
        val accepted = queue.submit(
            queued("fourth", onRejected = { c, m -> code = c; message = m })
        )

        assertFalse("fourth should have been rejected", accepted)
        assertEquals(TaraCoreErrors.QUEUE_FULL, code)
        assertTrue(message.isNotBlank())

        gate.complete(Unit)
        queue.stop()
    }

    @Test
    fun `a request cancelled while queued never executes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val executed = CopyOnWriteArrayList<String>()
        val body: suspend (RequestQueue.QueuedRequest) -> Unit = { q ->
            if (q.request.requestId == "blocker") gate.await()
            executed.add(q.request.requestId)
        }
        val queue = RequestQueue(this)
        queue.start()

        queue.submit(queued("blocker", run = body))
        advanceUntilIdle()

        var rejectedWith = -1
        queue.submit(queued("doomed", run = body, onRejected = { c, _ -> rejectedWith = c }))
        queue.submit(queued("survivor", run = body))

        assertTrue("cancelQueued should report true for a waiting request",
            queue.cancelQueued("doomed"))

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("blocker", "survivor"), executed.toList())
        assertEquals(TaraCoreErrors.CANCELLED, rejectedWith)
        queue.stop()
    }

    @Test
    fun `cancelling the running request is left to the engine`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val queue = RequestQueue(this)
        queue.start()

        queue.submit(queued("running", run = { gate.await() }))
        advanceUntilIdle()

        // false means "not in the queue" -- the caller must cancel the engine instead.
        assertFalse(queue.cancelQueued("running"))

        gate.complete(Unit)
        queue.stop()
    }

    @Test
    fun `a failing request does not kill the worker`() = runTest {
        val executed = CopyOnWriteArrayList<String>()
        val body: suspend (RequestQueue.QueuedRequest) -> Unit = { q ->
            if (q.request.requestId == "bad") error("boom")
            executed.add(q.request.requestId)
        }
        val queue = RequestQueue(this)
        queue.start()

        var code = -1
        queue.submit(queued("bad", run = body, onRejected = { c, _ -> code = c }))
        queue.submit(queued("good", run = body))
        advanceUntilIdle()

        assertEquals(TaraCoreErrors.ENGINE_FAILURE, code)
        assertEquals(listOf("good"), executed.toList())
        queue.stop()
    }

    @Test
    fun `a cancel for a finished request is not remembered`() = runTest {
        // Regression: closing an HTTP response cancels by id after the request has
        // already completed. Recording those would grow a set for the life of the
        // process, one entry per request served.
        val queue = RequestQueue(this)
        queue.start()

        queue.submit(queued("done"))
        advanceUntilIdle()

        assertFalse(
            "a finished request should not be recorded as cancelled",
            queue.cancelQueued("done"),
        )
        assertFalse(
            "an id the queue never saw should not be recorded either",
            queue.cancelQueued("never-submitted"),
        )
        queue.stop()
    }

    @Test
    fun `depth reflects the outstanding backlog`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val queue = RequestQueue(TestScope(StandardTestDispatcher(testScheduler)))
        queue.start()

        assertEquals(0, queue.depth.value)
        queue.submit(queued("one", run = { gate.await() }))
        queue.submit(queued("two", run = { gate.await() }))
        assertEquals(2, queue.depth.value)

        gate.complete(Unit)
        queue.stop()
    }
}
