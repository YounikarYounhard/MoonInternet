// The one piece of native code that always ships with the app: it loads the engines that do not.
//
// Since Android 10 an app may not exec a file it has written into its own data directory, which is
// exactly where a downloaded engine lives. It may, however, map one executable — so a downloadable
// engine is a shared library rather than a program, and this is what picks it up. Both halves were
// measured on a device before any of this was written: dlopen of a downloaded .so succeeds with no
// SELinux denial, exec of the same file fails with EACCES.
//
// Every engine exports the same two symbols, so this file knows nothing about which one it holds:
//
//     int  moon_core_main(const char *args);   // argv joined by '\n'; blocks until stopped
//     void moon_core_stop(void);               // makes the above return
//
// moon_core_main blocks for as long as the engine runs, so it gets a thread of its own and the
// caller gets control back immediately. Whether the engine actually came up is answered by knocking
// on its port, the way the app has always answered it — a thread that started and died a moment
// later looks identical from here.
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

typedef int (*core_main_fn)(const char *);
typedef void (*core_stop_fn)(void);

// One slot per engine. Four because there are four engines and never two copies of one: starting
// an engine stops the previous instance first, which is the app's rule everywhere else too.
#define SLOTS 4
#define ID_MAX 32

static struct slot {
    char id[ID_MAX];
    void *handle;
    core_stop_fn stop;
    char *args;          // owned by the thread, freed when it ends
    pthread_t thread;
    int running;
} slots[SLOTS];

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

/** The slot holding this id, or a free one, or NULL when all four are taken. Call under lock. */
static struct slot *slot_for(const char *id) {
    struct slot *free_slot = NULL;
    for (int i = 0; i < SLOTS; i++) {
        if (slots[i].id[0] && strcmp(slots[i].id, id) == 0) return &slots[i];
        if (!slots[i].id[0] && !free_slot) free_slot = &slots[i];
    }
    return free_slot;
}

static void *run_core(void *arg) {
    struct slot *s = (struct slot *) arg;
    core_main_fn entry = (core_main_fn) dlsym(s->handle, "moon_core_main");
    if (entry) entry(s->args);
    pthread_mutex_lock(&lock);
    free(s->args);
    s->args = NULL;
    s->running = 0;
    pthread_mutex_unlock(&lock);
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_cc_moon_internet_core_CoreLib_nativeStart(JNIEnv *env, jclass clazz,
                                               jstring jid, jstring jpath, jstring jargs) {
    const char *id = (*env)->GetStringUTFChars(env, jid, NULL);
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *args = (*env)->GetStringUTFChars(env, jargs, NULL);
    const char *problem = NULL;

    pthread_mutex_lock(&lock);
    struct slot *s = slot_for(id);
    if (!s) {
        problem = "нет свободного места под ядро";
    } else if (s->running) {
        problem = "ядро уже работает";
    } else {
        // RTLD_NOW so a missing symbol is an error here rather than a crash later, in a thread,
        // with nobody to report it to.
        if (!s->handle) s->handle = dlopen(path, RTLD_NOW);
        if (!s->handle) {
            problem = dlerror();
            if (!problem) problem = "ядро не загрузилось";
        } else if (!dlsym(s->handle, "moon_core_main")) {
            problem = "в ядре нет точки входа";
        } else {
            snprintf(s->id, ID_MAX, "%s", id);
            s->stop = (core_stop_fn) dlsym(s->handle, "moon_core_stop");
            s->args = strdup(args);
            s->running = 1;
            if (pthread_create(&s->thread, NULL, run_core, s) != 0) {
                free(s->args);
                s->args = NULL;
                s->running = 0;
                problem = "не удалось создать поток ядра";
            } else {
                pthread_detach(s->thread);
            }
        }
    }
    pthread_mutex_unlock(&lock);

    (*env)->ReleaseStringUTFChars(env, jid, id);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    (*env)->ReleaseStringUTFChars(env, jargs, args);
    return problem ? (*env)->NewStringUTF(env, problem) : NULL;
}

JNIEXPORT void JNICALL
Java_cc_moon_internet_core_CoreLib_nativeStop(JNIEnv *env, jclass clazz, jstring jid) {
    const char *id = (*env)->GetStringUTFChars(env, jid, NULL);
    pthread_mutex_lock(&lock);
    struct slot *s = slot_for(id);
    // The handle is deliberately NOT closed. A Go runtime cannot be unloaded and loaded again in
    // one process; keeping the library mapped costs a few megabytes of address space and makes a
    // second start a plain function call.
    core_stop_fn stop = (s && s->id[0] && s->running) ? s->stop : NULL;
    pthread_mutex_unlock(&lock);
    if (stop) stop();
    (*env)->ReleaseStringUTFChars(env, jid, id);
}

JNIEXPORT jboolean JNICALL
Java_cc_moon_internet_core_CoreLib_nativeRunning(JNIEnv *env, jclass clazz, jstring jid) {
    const char *id = (*env)->GetStringUTFChars(env, jid, NULL);
    pthread_mutex_lock(&lock);
    struct slot *s = slot_for(id);
    jboolean running = (s && s->id[0] && s->running) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&lock);
    (*env)->ReleaseStringUTFChars(env, jid, id);
    return running;
}
