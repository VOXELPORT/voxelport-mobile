// Starts the bundled Java runtime inside this (the app's ":jvm") process.
//
// Android won't let an app execute files it wrote (W^X), but it may dlopen
// them. So instead of running bin/java we load the runtime's launcher library
// (libjli.so) and call JLI_Launch, the same entry point bin/java uses. The JVM
// finds its home from where libjli.so was loaded (<home>/lib).
//
// stdout/stderr go to a log file the UI tails; stdin is a pipe the app writes
// console commands into.

#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "VoxelPortJVM"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef int (*JLI_Launch_t)(int argc, char **argv, int jargc, const char **jargv,
                            int appclassc, const char **appclassv,
                            const char *fullversion, const char *dotversion,
                            const char *pname, const char *lname,
                            jboolean javaargs, jboolean cpwildcard, jboolean javaw, jint ergo);

static int stdin_fd = -1;

// Libraries other runtime libraries name in DT_NEEDED. Loading them first, by
// full path, lets the linker resolve those names (it matches loaded sonames).
static const char *PRELOAD[] = {
    "lib/server/libjvm.so", "lib/libjli.so", "lib/libjava.so", "lib/libjimage.so",
    "lib/libzip.so", "lib/libnet.so", "lib/libnio.so", NULL,
};

JNIEXPORT jint JNICALL
Java_com_voxelport_android_Jvm_launch(JNIEnv *env, jclass cls, jstring jhome, jstring jcwd, jobjectArray jargs, jstring jlog) {
    (void)cls;
    const char *home = (*env)->GetStringUTFChars(env, jhome, NULL);
    const char *log = (*env)->GetStringUTFChars(env, jlog, NULL);
    const char *cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    // Paper resolves eula.txt, world/ and plugins/ against the working directory.
    if (chdir(cwd) != 0) LOGE("chdir %s failed", cwd);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwd);

    int fd = open(log, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    int p[2];
    if (pipe(p) == 0) {
        dup2(p[0], STDIN_FILENO);
        close(p[0]);
        stdin_fd = p[1];
    }

    // The launcher re-executes itself when LD_LIBRARY_PATH looks wrong; in an
    // app process that would exec app_process, so make sure it never does.
    unsetenv("LD_LIBRARY_PATH");
    setenv("JAVA_HOME", home, 1);

    char path[PATH_MAX];
    void *jli = NULL;
    for (int i = 0; PRELOAD[i]; i++) {
        snprintf(path, sizeof path, "%s/%s", home, PRELOAD[i]);
        void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (!h) {
            LOGE("dlopen %s: %s", path, dlerror());
            printf("[VoxelPort] Couldn't load %s: %s\n", PRELOAD[i], dlerror());
        }
        if (strcmp(PRELOAD[i], "lib/libjli.so") == 0) jli = h;
    }
    JLI_Launch_t launch = jli ? (JLI_Launch_t)dlsym(jli, "JLI_Launch") : NULL;
    if (!launch) {
        printf("[VoxelPort] The Java runtime is damaged (no JLI_Launch). Reinstall the app.\n");
        return -1;
    }

    int n = (*env)->GetArrayLength(env, jargs);
    char **argv = calloc((size_t)n + 2, sizeof(char *));
    snprintf(path, sizeof path, "%s/bin/java", home);
    argv[0] = strdup(path);
    for (int i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i + 1] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
    }
    (*env)->ReleaseStringUTFChars(env, jlog, log);

    LOGI("JLI_Launch with %d args, home %s", n, home);
    printf("[VoxelPort] Starting Java from %s\n", home);
    // Blocks until the server's main thread ends. System.exit() ends the whole
    // process, which is fine: this process exists only for the server.
    int rc = launch(n + 1, argv, 0, NULL, 0, NULL, "25", "25", "java", "openjdk",
                    JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
    printf("[VoxelPort] Java exited with code %d\n", rc);
    (*env)->ReleaseStringUTFChars(env, jhome, home);
    return rc;
}

JNIEXPORT jboolean JNICALL
Java_com_voxelport_android_Jvm_command(JNIEnv *env, jclass cls, jstring jline) {
    (void)cls;
    if (stdin_fd < 0) return JNI_FALSE;
    const char *line = (*env)->GetStringUTFChars(env, jline, NULL);
    size_t len = strlen(line);
    char *buf = malloc(len + 2);
    memcpy(buf, line, len);
    buf[len] = '\n';
    ssize_t w = write(stdin_fd, buf, len + 1);
    free(buf);
    (*env)->ReleaseStringUTFChars(env, jline, line);
    return w == (ssize_t)(len + 1) ? JNI_TRUE : JNI_FALSE;
}
