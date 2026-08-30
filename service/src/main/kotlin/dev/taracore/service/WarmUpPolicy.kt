package dev.taracore.service

import dev.taracore.api.TaraCoreErrors
import dev.taracore.service.model.ModelEntity

/** What a speculative warm-up should do. */
sealed interface WarmUpDecision {

    /** Something is already loaded; the cheapest possible answer. */
    data class AlreadyResident(val modelId: String) : WarmUpDecision

    /** Load [modelId]. */
    data class Proceed(val modelId: String) : WarmUpDecision

    /** Do nothing, and say why. [code] is a [TaraCoreErrors] constant. */
    data class Decline(val code: Int, val reason: String) : WarmUpDecision
}

/**
 * Decides whether to honour a client's warm-up request.
 *
 * Extracted from the service because the decision is the interesting part and it is
 * worth testing without an Android runtime — particularly the refusals, which are
 * hard to reproduce on a device whose free memory moves between runs.
 *
 * The asymmetry with an explicit load is deliberate: when a *user* picks a model in
 * the Models screen it loads even if the estimate says it will be tight, because they
 * asked for it and the screen already warned them. A warm-up is speculative, on
 * behalf of an app the user may not even be looking at, so it defers instead.
 */
object WarmUpPolicy {

    fun decide(
        residentModelId: String?,
        activeModelId: String?,
        firstDownloadedId: String?,
        candidate: ModelEntity?,
        availableMemoryBytes: Long,
    ): WarmUpDecision {
        if (residentModelId != null) return WarmUpDecision.AlreadyResident(residentModelId)

        val wanted = activeModelId ?: firstDownloadedId
            ?: return WarmUpDecision.Decline(
                TaraCoreErrors.MODEL_NOT_FOUND,
                "no model is downloaded, so there is nothing to warm",
            )

        if (candidate == null) {
            return WarmUpDecision.Decline(
                TaraCoreErrors.MODEL_NOT_FOUND, "unknown model: $wanted",
            )
        }
        if (candidate.path == null) {
            return WarmUpDecision.Decline(
                TaraCoreErrors.MODEL_NOT_FOUND, "model $wanted is not downloaded",
            )
        }

        // The refusal that matters. Warming is an optimisation, and it must never be
        // the thing that pushes the device into reclaiming another app's pages.
        if (candidate.estRamBytes > availableMemoryBytes) {
            return WarmUpDecision.Decline(
                TaraCoreErrors.OUT_OF_MEMORY,
                "declined to warm $wanted: it needs about " +
                    "${candidate.estRamBytes / 1_000_000} MB and only " +
                    "${availableMemoryBytes / 1_000_000} MB is available",
            )
        }

        return WarmUpDecision.Proceed(wanted)
    }
}
