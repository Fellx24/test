#include <jni.h>
#include <pthread.h>

/* Use ByeDPI's command-line parser without installing process-wide signal handlers. */
#define main youtube_proxy_main
#define run run_youtube_socks
#include "main.c"
#undef run
#undef main

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready = PTHREAD_COND_INITIALIZER;
static pthread_t worker;
static bool worker_started;
static bool running;
static int proxy_port;
static int stop_fd = -1;

int run_youtube_socks(const union sockaddr_u *address)
{
    union sockaddr_u local = *address;
    struct sockaddr_in bound;
    socklen_t size = sizeof(bound);
    local.in.sin_port = 0;

    int server = listen_socket(&local);
    if (server < 0) return -1;

    int shutdown_socket = fcntl(server, F_DUPFD_CLOEXEC, 0);
    if (shutdown_socket < 0 || getsockname(server, (struct sockaddr *)&bound, &size) < 0) {
        if (shutdown_socket >= 0) close(shutdown_socket);
        close(server);
        return -1;
    }

    pthread_mutex_lock(&lock);
    stop_fd = shutdown_socket;
    proxy_port = ntohs(bound.sin_port);
    pthread_cond_signal(&ready);
    pthread_mutex_unlock(&lock);
    return start_event_loop(server);
}

static void *proxy_worker(void *unused)
{
    (void)unused;
    static struct params defaults;
    static bool captured_defaults;
    if (!captured_defaults) {
        defaults = params;
        captured_defaults = true;
    }
    params = defaults;
    optind = 1;
    optreset = 1;

    char *args[] = {
        "ciadpi", "--ip=127.0.0.1", "--proto=t", "--pf=443",
        "--hosts=:youtube.com youtu.be youtube-nocookie.com youtubeeducation.com"
        " googlevideo.com ytimg.com yt3.ggpht.com yt3.googleusercontent.com"
        " youtube.googleapis.com youtubei.googleapis.com youtubeembeddedplayer.googleapis.com"
        " www.googleapis.com",
        "--split=1+s", "--tlsrec=1+s", NULL
    };
    youtube_proxy_main(sizeof(args) / sizeof(args[0]) - 1, args);

    pthread_mutex_lock(&lock);
    running = false;
    pthread_cond_signal(&ready);
    pthread_mutex_unlock(&lock);
    return NULL;
}

static void stop_proxy(void)
{
    pthread_mutex_lock(&lock);
    if (!worker_started) {
        pthread_mutex_unlock(&lock);
        return;
    }
    if (stop_fd >= 0) shutdown(stop_fd, SHUT_RDWR);
    pthread_mutex_unlock(&lock);

    pthread_join(worker, NULL);

    pthread_mutex_lock(&lock);
    if (stop_fd >= 0) close(stop_fd);
    stop_fd = -1;
    worker_started = false;
    proxy_port = 0;
    pthread_mutex_unlock(&lock);
}

JNIEXPORT jint JNICALL
Java_com_amurcanov_tgwsproxy_YoutubeDpi_start(JNIEnv *env, jobject self)
{
    (void)env;
    (void)self;
    pthread_mutex_lock(&lock);
    if (worker_started) {
        pthread_mutex_unlock(&lock);
        return -1;
    }
    running = true;
    if (pthread_create(&worker, NULL, proxy_worker, NULL) != 0) {
        running = false;
        pthread_mutex_unlock(&lock);
        return -1;
    }
    worker_started = true;
    while (running && proxy_port == 0) pthread_cond_wait(&ready, &lock);
    int port = running ? proxy_port : -1;
    pthread_mutex_unlock(&lock);
    if (port < 0) stop_proxy();
    return port;
}

JNIEXPORT jboolean JNICALL
Java_com_amurcanov_tgwsproxy_YoutubeDpi_isRunning(JNIEnv *env, jobject self)
{
    (void)env;
    (void)self;
    pthread_mutex_lock(&lock);
    bool active = running;
    pthread_mutex_unlock(&lock);
    return active ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_amurcanov_tgwsproxy_YoutubeDpi_stop(JNIEnv *env, jobject self)
{
    (void)env;
    (void)self;
    stop_proxy();
}
