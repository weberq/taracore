package dev.taracore.api;

import dev.taracore.api.GenerationResult;

/**
 * Streaming sink handed to {@link ITaraCore#startStream}.
 *
 * Every method is {@code oneway}: the service never blocks its generation loop on a
 * client that is slow to return, and a client that dies mid-stream is detected by the
 * service's DeathRecipient rather than by a stuck transaction.
 */
oneway interface ITokenCallback {

    /** One decoded piece of text. Pieces concatenate to the full completion. */
    void onToken(String requestId, String piece);

    /** Terminal success. {@code result.text} is the full completion. */
    void onDone(String requestId, in GenerationResult result);

    /** Terminal failure. {@code code} is one of the {@code TaraCoreErrors} constants. */
    void onError(String requestId, int code, String message);
}
