package dev.taracore.api;

/**
 * Progress sink for {@link ITaraCore#loadModel}. Model loading reads several GB from
 * storage and can take tens of seconds, so it is asynchronous with progress.
 */
oneway interface IModelCallback {

    /** @param progress 0.0f..1.0f, monotonically non-decreasing. */
    void onProgress(String modelId, float progress);

    /** Terminal success: the model is resident and ready to generate. */
    void onLoaded(String modelId, long ramBytes, String backend);

    /** Terminal failure. {@code code} is one of the {@code TaraCoreErrors} constants. */
    void onError(String modelId, int code, String message);
}
