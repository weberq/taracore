package dev.taracore.api;

import dev.taracore.api.GenerationRequest;
import dev.taracore.api.GenerationResult;
import dev.taracore.api.IModelCallback;
import dev.taracore.api.ITokenCallback;
import dev.taracore.api.ModelInfo;
import dev.taracore.api.ServiceStatus;

/**
 * The Tara Core inference contract.
 *
 * COMPATIBILITY POLICY: methods are append-only. An existing method's signature,
 * argument order, or position in this file must never change, because the ordinal
 * position is the on-wire transaction code. New capability is added by appending a
 * new method and bumping API_VERSION. See docs/API.md.
 *
 * Callers must hold dev.taracore.permission.BIND_INFERENCE; the service enforces it
 * on bind and again inside every method.
 */
interface ITaraCore {

    /** Contract version implemented by this service. Compare against API_VERSION. */
    int getApiVersion();

    /** Every model in the registry, downloaded or not. */
    List<ModelInfo> listModels();

    /**
     * Load a model into memory, replacing any currently loaded one. Asynchronous:
     * returns as soon as the request is queued, results arrive on {@code cb}.
     */
    void loadModel(String modelId, IModelCallback cb);

    /** Free the resident model and its KV cache. Safe to call when nothing is loaded. */
    void unloadModel();

    /** Current service, model and server state. Cheap; safe to poll. */
    ServiceStatus getStatus();

    /**
     * Blocking generation. The calling thread is parked for the whole completion, so
     * never call this from the main thread. Prefer startStream for interactive use.
     */
    GenerationResult generate(in GenerationRequest req);

    /**
     * Streaming generation. Returns immediately with the request id used to correlate
     * callbacks and to cancel.
     */
    String startStream(in GenerationRequest req, ITokenCallback cb);

    /**
     * Cancel a queued or running request. Cancelling a running generation stops it
     * within one token; the stream still terminates with onDone carrying the partial
     * text and {@code cancelled = true}.
     */
    void cancel(String requestId);
}
