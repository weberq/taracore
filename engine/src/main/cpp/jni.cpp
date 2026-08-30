// Tara Core -- JNI bridge between LlamaEngine.kt and the native Engine.
// Copyright 2026 The Tara Core Authors. Licensed under the Apache License 2.0.
#include <jni.h>

#include <android/log.h>

#include <exception>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "engine.h"

#define TAG "TaraCore/Engine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

#define EXPORT extern "C" JNIEXPORT

namespace {

JavaVM *g_vm = nullptr;

// Classes and methods resolved once in JNI_OnLoad. Local refs returned by
// FindClass are promoted to globals because they are used from later calls.
jclass    g_loadResultCls = nullptr;
jmethodID g_loadResultCtor = nullptr;
jclass    g_genStatsCls   = nullptr;
jmethodID g_genStatsCtor  = nullptr;
jmethodID g_onTokenMid    = nullptr;

taracore::Engine *asEngine(jlong handle) {
    return reinterpret_cast<taracore::Engine *>(handle);
}

std::string toStdString(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::vector<std::string> toStdStrings(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto s = reinterpret_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(toStdString(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

/**
 * Build a java.lang.String. Engine::generate already guarantees every piece it emits
 * ends on a UTF-8 codepoint boundary (see utf8SafeEnd), so NewStringUTF is safe here.
 */
jstring newUtf8(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

}  // namespace

// ---------------------------------------------------------------------------

jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    auto bind = [&](const char *name) -> jclass {
        jclass local = env->FindClass(name);
        if (local == nullptr) {
            LOGE("JNI_OnLoad: class not found: %s", name);
            return nullptr;
        }
        auto global = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return global;
    };

    g_loadResultCls = bind("dev/taracore/engine/NativeLoadResult");
    g_genStatsCls   = bind("dev/taracore/engine/GenStats");
    jclass listener = env->FindClass("dev/taracore/engine/TokenListener");
    if (g_loadResultCls == nullptr || g_genStatsCls == nullptr || listener == nullptr) {
        return JNI_ERR;
    }

    g_loadResultCtor = env->GetMethodID(
        g_loadResultCls, "<init>",
        "(ZLjava/lang/String;JIILjava/lang/String;Ljava/lang/String;)V");
    g_genStatsCtor = env->GetMethodID(
        g_genStatsCls, "<init>", "(IIJJZZZLjava/lang/String;)V");
    g_onTokenMid = env->GetMethodID(listener, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(listener);

    if (g_loadResultCtor == nullptr || g_genStatsCtor == nullptr || g_onTokenMid == nullptr) {
        LOGE("JNI_OnLoad: failed to resolve one or more method ids");
        return JNI_ERR;
    }

    taracore::global_init();
    LOGI("JNI_OnLoad complete");
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------

EXPORT jlong JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeCreate(JNIEnv *, jobject) {
    taracore::global_init();
    return reinterpret_cast<jlong>(new taracore::Engine());
}

EXPORT void JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete asEngine(handle);
}

EXPORT jobject JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeLoad(JNIEnv *env, jobject, jlong handle,
                                                jstring path, jint nCtx, jint nThreads,
                                                jint nGpuLayers, jint nBatch,
                                                jboolean useMmap, jboolean useMlock) {
    taracore::Engine *engine = asEngine(handle);
    taracore::LoadResult r;
    if (engine == nullptr) {
        r.error = "engine handle is null";
    } else {
        r = engine->load(toStdString(env, path), nCtx, nThreads, nGpuLayers, nBatch,
                         useMmap == JNI_TRUE, useMlock == JNI_TRUE);
    }

    jstring jerr  = newUtf8(env, r.error);
    jstring jback = newUtf8(env, r.backendName);
    jstring jdesc = newUtf8(env, r.description);
    jobject obj = env->NewObject(g_loadResultCls, g_loadResultCtor,
                                 static_cast<jboolean>(r.ok ? JNI_TRUE : JNI_FALSE),
                                 jerr,
                                 static_cast<jlong>(r.modelSizeBytes),
                                 static_cast<jint>(r.vocabSize),
                                 static_cast<jint>(r.nCtx),
                                 jback, jdesc);
    env->DeleteLocalRef(jerr);
    env->DeleteLocalRef(jback);
    env->DeleteLocalRef(jdesc);
    return obj;
}

EXPORT void JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeUnload(JNIEnv *, jobject, jlong handle) {
    if (auto *e = asEngine(handle)) e->unload();
}

EXPORT jboolean JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeIsLoaded(JNIEnv *, jobject, jlong handle) {
    auto *e = asEngine(handle);
    return (e != nullptr && e->isLoaded()) ? JNI_TRUE : JNI_FALSE;
}

EXPORT void JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeCancel(JNIEnv *, jobject, jlong handle) {
    if (auto *e = asEngine(handle)) e->requestCancel();
}

EXPORT jstring JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeFormatChat(JNIEnv *env, jobject, jlong handle,
                                                      jobjectArray roles,
                                                      jobjectArray contents) {
    auto *e = asEngine(handle);
    if (e == nullptr) return env->NewStringUTF("");

    const std::vector<std::string> r = toStdStrings(env, roles);
    const std::vector<std::string> c = toStdStrings(env, contents);
    std::vector<taracore::ChatMsg> msgs;
    msgs.reserve(r.size());
    for (size_t i = 0; i < r.size() && i < c.size(); ++i) msgs.push_back({r[i], c[i]});

    return newUtf8(env, e->formatChat(msgs));
}

EXPORT jobject JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeGenerate(JNIEnv *env, jobject, jlong handle,
                                                    jstring prompt, jint maxTokens,
                                                    jfloat temperature, jfloat topP,
                                                    jint topK, jfloat repeatPenalty,
                                                    jint repeatLastN, jlong seed,
                                                    jobjectArray stop, jstring grammar,
                                                    jobject listener) {
    auto *e = asEngine(handle);
    taracore::GenStats stats;

    if (e == nullptr) {
        stats.ok = false;
        stats.error = "engine handle is null";
    } else {
        taracore::GenParams p;
        p.maxTokens     = maxTokens;
        p.temperature   = temperature;
        p.topP          = topP;
        p.topK          = topK;
        p.repeatPenalty = repeatPenalty;
        p.repeatLastN   = repeatLastN;
        p.seed          = static_cast<uint32_t>(seed);
        p.stop          = toStdStrings(env, stop);
        p.grammar       = toStdString(env, grammar);

        // The sink runs on this same thread -- Engine::generate is synchronous and
        // never spawns a callback thread -- so `env` stays valid and no
        // AttachCurrentThread is required. If that ever changes, attach here.
        taracore::TokenSink sink = [&](const std::string &piece) -> bool {
            if (listener == nullptr || piece.empty()) return true;
            jstring js = newUtf8(env, piece);
            const jboolean keepGoing = env->CallBooleanMethod(listener, g_onTokenMid, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) {
                // A throwing listener must not be allowed to unwind through llama.cpp.
                env->ExceptionDescribe();
                env->ExceptionClear();
                return false;
            }
            return keepGoing == JNI_TRUE;
        };

        // llama.cpp throws on several recoverable conditions -- a grammar that
        // rejects a token is one. This is a shared service: an uncaught exception
        // here aborts the :engine process and takes down inference for every app on
        // the device, because of one malformed request from one client. Never let
        // that happen; turn it into a failed request instead.
        try {
            stats = e->generate(toStdString(env, prompt), p, sink);
        } catch (const std::exception &ex) {
            stats.ok = false;
            stats.error = std::string("engine exception: ") + ex.what();
            LOGE("generate threw: %s", ex.what());
        } catch (...) {
            stats.ok = false;
            stats.error = "engine threw a non-standard exception";
            LOGE("generate threw a non-standard exception");
        }
    }

    // NOTE: the engine mutex is released by the time we get here, so the Kotlin
    // side is free to call back into the engine from onDone without deadlocking.
    jstring jerr = newUtf8(env, stats.error);
    jobject obj  = env->NewObject(g_genStatsCls, g_genStatsCtor,
                                  static_cast<jint>(stats.promptTokens),
                                  static_cast<jint>(stats.genTokens),
                                  static_cast<jlong>(stats.promptMs),
                                  static_cast<jlong>(stats.genMs),
                                  stats.cancelled ? JNI_TRUE : JNI_FALSE,
                                  stats.stopped   ? JNI_TRUE : JNI_FALSE,
                                  stats.ok        ? JNI_TRUE : JNI_FALSE,
                                  jerr);
    env->DeleteLocalRef(jerr);
    return obj;
}

EXPORT jstring JNICALL
Java_dev_taracore_engine_LlamaEngine_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_version());
}
