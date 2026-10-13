// Ponte JNI tra l'app Kotlin e whisper.cpp
#include <jni.h>
#include <atomic>
#include <cstring>
#include <string>

#include "ggml-backend.h"
#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "Trascrittore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

static std::atomic<bool> g_abort{false};
static std::atomic<bool> g_backends_loaded{false};

namespace {

struct CallbackCtx {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
    jmethodID onSegment;
    int lastProgress;
};

void log_cb(enum ggml_log_level level, const char *text, void * /*user*/) {
    if (level >= GGML_LOG_LEVEL_WARN) {
        LOGE("%s", text);
    }
}

void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *c = static_cast<CallbackCtx *>(user);
    if (progress == c->lastProgress) return;
    c->lastProgress = progress;
    c->env->CallVoidMethod(c->listener, c->onProgress, (jint) progress);
    if (c->env->ExceptionCheck()) c->env->ExceptionClear();
}

void segment_cb(struct whisper_context *, struct whisper_state *state, int n_new, void *user) {
    auto *c = static_cast<CallbackCtx *>(user);
    JNIEnv *env = c->env;
    const int n = whisper_full_n_segments_from_state(state);
    for (int i = n - n_new; i < n; ++i) {
        if (i < 0) continue;
        const char *text = whisper_full_get_segment_text_from_state(state, i);
        const int64_t t0 = whisper_full_get_segment_t0_from_state(state, i); // centesimi di secondo
        const int64_t t1 = whisper_full_get_segment_t1_from_state(state, i);
        const jsize len = text ? (jsize) strlen(text) : 0;
        // Passiamo i byte grezzi: un token puo contenere UTF-8 spezzato,
        // NewStringUTF in quel caso manderebbe in crash l'app.
        jbyteArray bytes = env->NewByteArray(len);
        if (bytes == nullptr) { env->ExceptionClear(); continue; }
        if (len > 0) env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));
        env->CallVoidMethod(c->listener, c->onSegment, (jlong) (t0 * 10), (jlong) (t1 * 10), bytes);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(bytes);
    }
}

bool abort_cb(void *) {
    return g_abort.load();
}

} // namespace

extern "C" {

// Carica la variante del backend CPU piu veloce per questo processore
// (libggml-cpu-android_*.so nella cartella delle librerie native dell'app).
// Ritorna false se non e stato trovato nessun backend utilizzabile.
JNIEXPORT jboolean JNICALL
Java_it_donato_trascrittore_WhisperLib_nativeLoadBackends(JNIEnv *env, jobject, jstring jdir) {
    if (!g_backends_loaded.load()) {
        whisper_log_set(log_cb, nullptr);
        const char *dir = env->GetStringUTFChars(jdir, nullptr);
        ggml_backend_load_all_from_path(dir);
        env->ReleaseStringUTFChars(jdir, dir);
        g_backends_loaded.store(true);
    }
    const bool ok = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) != nullptr;
    if (ok) LOGI("%s", whisper_print_system_info());
    else LOGE("Nessun backend CPU caricato");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_it_donato_trascrittore_WhisperLib_nativeInit(JNIEnv *env, jobject, jstring jpath) {
    whisper_log_set(log_cb, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == nullptr) LOGE("Impossibile caricare il modello: %s", path);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_it_donato_trascrittore_WhisperLib_nativeFree(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

JNIEXPORT void JNICALL
Java_it_donato_trascrittore_WhisperLib_nativeSetAbort(JNIEnv *, jobject, jboolean value) {
    g_abort.store(value == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_it_donato_trascrittore_WhisperLib_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

// Lingua usata nell'ultima trascrizione (quella rilevata, se era "auto").
JNIEXPORT jstring JNICALL
Java_it_donato_trascrittore_WhisperLib_nativeDetectedLanguage(JNIEnv *env, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    const int id = ctx ? whisper_full_lang_id(ctx) : -1;
    const char *lang = id >= 0 ? whisper_lang_str(id) : nullptr;
    return env->NewStringUTF(lang ? lang : "");
}

// Ritorna 0 se ok, 1 se interrotto, altro valore in caso di errore.
// prompt: coda del testo del blocco precedente, per non perdere il filo
// (nomi, stile, punteggiatura) quando si passa da un blocco all'altro.
JNIEXPORT jint JNICALL
Java_it_donato_trascrittore_WhisperLib_nativeTranscribe(
        JNIEnv *env, jobject,
        jlong ptr, jfloatArray jsamples, jstring jlang, jstring jprompt,
        jboolean translate, jint threads, jobject listener) {

    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) return -1;

    jclass cls = env->GetObjectClass(listener);
    CallbackCtx cb{};
    cb.env = env;
    cb.listener = listener;
    cb.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    cb.onSegment = env->GetMethodID(cls, "onSegment", "(JJ[B)V");
    cb.lastProgress = -1;
    if (cb.onProgress == nullptr || cb.onSegment == nullptr) return -2;

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);

    const char *pr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(pr);
    env->ReleaseStringUTFChars(jprompt, pr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = language.c_str();
    p.detect_language = false;
    p.translate = translate == JNI_TRUE;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    // Niente token "non parlato" (musica, applausi...): riduce le frasi
    // inventate nei tratti senza voce.
    p.suppress_nst = true;
    p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    p.progress_callback = progress_cb;
    p.progress_callback_user_data = &cb;
    p.new_segment_callback = segment_cb;
    p.new_segment_callback_user_data = &cb;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = nullptr;

    const jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    if (samples == nullptr) return -3;

    const int rc = whisper_full(ctx, p, samples, (int) n);

    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    env->DeleteLocalRef(cls);

    if (g_abort.load()) return 1;
    if (rc != 0) LOGE("whisper_full ha restituito %d", rc);
    return rc == 0 ? 0 : 2;
}

} // extern "C"
