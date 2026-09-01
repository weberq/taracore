package dev.taracore.service

import dev.taracore.api.TaraCoreErrors
import dev.taracore.service.model.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refusals matter more than the happy path here, and they are the branches a
 * device test cannot pin down: free memory moves between runs, so "decline because
 * it will not fit" is not reproducible on hardware.
 */
class WarmUpPolicyTest {

    private val GB = 1_000_000_000L

    private fun model(
        id: String = "m",
        downloaded: Boolean = true,
        estRam: Long = 700_000_000L,
    ) = ModelEntity(
        id = id,
        displayName = id,
        family = "test",
        quant = "Q4_K_M",
        url = "https://example.invalid/$id.gguf",
        sizeBytes = 400_000_000L,
        sha256 = "",
        ctxDefault = 4096,
        estRamBytes = estRam,
        license = "Apache-2.0",
        path = if (downloaded) "/data/models/$id.gguf" else null,
    )

    private fun decide(
        resident: String? = null,
        active: String? = "m",
        firstDownloaded: String? = "m",
        candidate: ModelEntity? = model(),
        nonEvictable: Long = 300_000_000L,
        available: Long = 2 * GB,
    ) = WarmUpPolicy.decide(
        resident, active, firstDownloaded, candidate, nonEvictable, available,
    )

    @Test
    fun `a resident model short-circuits`() {
        val d = decide(resident = "already-here")
        assertEquals(WarmUpDecision.AlreadyResident("already-here"), d)
    }

    @Test
    fun `warms the active model when it fits`() {
        assertEquals(WarmUpDecision.Proceed("m"), decide())
    }

    @Test
    fun `falls back to the first downloaded model when none is active`() {
        val d = decide(active = null, firstDownloaded = "fallback",
                       candidate = model(id = "fallback"))
        assertEquals(WarmUpDecision.Proceed("fallback"), d)
    }

    @Test
    fun `declines when nothing is downloaded`() {
        val d = decide(active = null, firstDownloaded = null, candidate = null)
        assertTrue(d is WarmUpDecision.Decline)
        assertEquals(TaraCoreErrors.MODEL_NOT_FOUND, (d as WarmUpDecision.Decline).code)
    }

    @Test
    fun `declines when the active model is registered but not downloaded`() {
        val d = decide(candidate = model(downloaded = false))
        assertTrue(d is WarmUpDecision.Decline)
        assertEquals(TaraCoreErrors.MODEL_NOT_FOUND, (d as WarmUpDecision.Decline).code)
        assertTrue(d.reason, d.reason.contains("not downloaded"))
    }

    @Test
    fun `declines rather than warm when the resident part will not fit`() {
        val d = decide(nonEvictable = 2 * GB, available = 1 * GB)
        assertTrue("expected a decline, got $d", d is WarmUpDecision.Decline)
        d as WarmUpDecision.Decline
        assertEquals(TaraCoreErrors.OUT_OF_MEMORY, d.code)
        assertTrue(d.reason, d.reason.contains("2000 MB"))
        assertTrue(d.reason, d.reason.contains("1000 MB"))
    }

    @Test
    fun `warms a large model when only its weights are large`() {
        // Regression. The check used to compare the whole footprint against free
        // memory, so a 2 GB model on a phone with 700 MB free was declined -- and
        // then loaded and ran perfectly when the user asked for it explicitly,
        // because mmap'd weights need no free memory at all. Only the KV cache and
        // compute buffers do.
        val d = decide(
            candidate = model(estRam = 2_300_000_000L),
            nonEvictable = 500_000_000L,
            available = 700_000_000L,
        )
        assertEquals(WarmUpDecision.Proceed("m"), d)
    }

    @Test
    fun `warms when the resident part exactly equals available memory`() {
        // Boundary: the check is strictly greater-than, so an exact fit proceeds.
        assertEquals(WarmUpDecision.Proceed("m"),
            decide(nonEvictable = 1 * GB, available = 1 * GB))
    }

    @Test
    fun `a resident model wins even when the active model would not fit`() {
        // Nothing to do, so the memory estimate is never consulted.
        val d = decide(resident = "loaded", nonEvictable = 9 * GB, available = 1)
        assertEquals(WarmUpDecision.AlreadyResident("loaded"), d)
    }
}
