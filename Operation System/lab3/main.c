#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <errno.h>
#include <signal.h>
#include <time.h>

#define PROXY_PORT 80
#define BUFFER_SIZE 65536
#define MAX_CACHE_ENTRIES 1000
#define MAX_URL_LENGTH 4096
#define MAX_HEADER_SIZE 8192

static volatile sig_atomic_t g_running = 1;


typedef enum {
    CACHE_LOADING,
    CACHE_COMPLETE,
    CACHE_ERROR
} CacheStatus;

typedef struct {
    char url[MAX_URL_LENGTH];
    char *data;
    size_t size;
    size_t capacity;
    CacheStatus status;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int ref_count;
    time_t timestamp;
} CacheEntry;

static CacheEntry *g_cache[MAX_CACHE_ENTRIES];
static int g_cache_count = 0;
static pthread_mutex_t g_cache_mutex = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    CacheEntry *entry;
    char host[256];
    int port;
    char path[MAX_URL_LENGTH];
    char original_request[MAX_HEADER_SIZE];
} LoaderArgs;

void signal_handler(int signum) {
    if (signum == SIGINT || signum == SIGTERM) {
        printf("\n[SIGNAL] Получен сигнал %d, завершаю работу...\n", signum);
        g_running = 0;


    }
}

void cleanup_cache(void) {
    printf("[CLEANUP] Очистка кэша...\n");

    pthread_mutex_lock(&g_cache_mutex);

    for (int i = 0; i < g_cache_count; i++) {
        CacheEntry *entry = g_cache[i];
        if (entry) {
            pthread_mutex_lock(&entry->mutex);
            entry->status = CACHE_ERROR;
            pthread_cond_broadcast(&entry->cond);
            pthread_mutex_unlock(&entry->mutex);

        }
    }

    pthread_mutex_unlock(&g_cache_mutex);

    printf("[CLEANUP] Ожидание завершения потоков...\n");
    sleep(1);

    pthread_mutex_lock(&g_cache_mutex);

    for (int i = 0; i < g_cache_count; i++) {
        CacheEntry *entry = g_cache[i];
        if (entry) {
            pthread_mutex_destroy(&entry->mutex);
            pthread_cond_destroy(&entry->cond);
            free(entry->data);
            free(entry);
            g_cache[i] = NULL;
        }
    }
    g_cache_count = 0;

    pthread_mutex_unlock(&g_cache_mutex);

    printf("[CLEANUP] Кэш очищен.\n");
}


CacheEntry* cache_find(const char *url) {
    for (int i = 0; i < g_cache_count; i++) {
        if (strcmp(g_cache[i]->url, url) == 0) {
            return g_cache[i];
        }
    }
    return NULL;
}

CacheEntry* cache_create(const char *url) {
    if (g_cache_count >= MAX_CACHE_ENTRIES) {
        for (int i = 0; i < g_cache_count; i++) {
            if (g_cache[i]->ref_count == 0 &&
                g_cache[i]->status != CACHE_LOADING) {
                pthread_mutex_destroy(&g_cache[i]->mutex);
                pthread_cond_destroy(&g_cache[i]->cond);
                free(g_cache[i]->data);
                free(g_cache[i]);
                g_cache[i] = g_cache[--g_cache_count];
                break;
            }
        }
        if (g_cache_count >= MAX_CACHE_ENTRIES) {
            return NULL;
        }
    }

    CacheEntry *entry = calloc(1, sizeof(CacheEntry));
    if (!entry) return NULL;

    strncpy(entry->url, url, MAX_URL_LENGTH - 1);
    entry->data = NULL;
    entry->size = 0;
    entry->capacity = 0;
    entry->status = CACHE_LOADING;
    entry->ref_count = 0;
    entry->timestamp = time(NULL);
    pthread_mutex_init(&entry->mutex, NULL);
    pthread_cond_init(&entry->cond, NULL);

    g_cache[g_cache_count++] = entry;
    printf("[CACHE] Создана запись #%d: %s\n", g_cache_count, url);
    return entry;
}

void cache_append(CacheEntry *entry, const char *data, size_t len) {
    pthread_mutex_lock(&entry->mutex);
    if (!g_running) {
        pthread_mutex_unlock(&entry->mutex);
        return;
    }
    if (entry->size + len > entry->capacity) {
        size_t new_cap = entry->capacity == 0 ? BUFFER_SIZE : entry->capacity * 2;
        while (new_cap < entry->size + len) {
            new_cap *= 2;
        }
        char *new_data = realloc(entry->data, new_cap);
        if (!new_data) {
            pthread_mutex_unlock(&entry->mutex);
            return;
        }
        entry->data = new_data;
        entry->capacity = new_cap;
    }

    memcpy(entry->data + entry->size, data, len);
    entry->size += len;
    pthread_cond_broadcast(&entry->cond);
    pthread_mutex_unlock(&entry->mutex);
}


int parse_http_request(const char *request, char *method, char *host,
                       int *port, char *path, char *full_url) {
    *port = 80;

    char url[MAX_URL_LENGTH];
    char version[32];

    if (sscanf(request, "%15s %4095s %31s", method, url, version) != 3) {
        return -1;
    }

    strcpy(full_url, url);

    if (strcasecmp(method, "GET") != 0 &&
        strcasecmp(method, "HEAD") != 0) {
        printf("[PARSE] Неподдерживаемый метод: %s\n", method);
        return -1;
    }

    char *p = url;

    if (strncasecmp(p, "http://", 7) == 0) {
        p += 7;
    }

    char *path_start = strchr(p, '/');
    char *port_start = strchr(p, ':');

    if (port_start && (!path_start || port_start < path_start)) {
        size_t host_len = port_start - p;
        strncpy(host, p, host_len);
        host[host_len] = '\0';
        *port = atoi(port_start + 1);
    } else if (path_start) {
        size_t host_len = path_start - p;
        strncpy(host, p, host_len);
        host[host_len] = '\0';
    } else {
        strcpy(host, p);
    }

    if (path_start) {
        strcpy(path, path_start);
    } else {
        strcpy(path, "/");
    }

    char *path_port = strchr(path, ':');
    if (path_port && path_port < strchr(path, '/')) {
        char *real_path = strchr(path_port, '/');
        if (real_path) {
            memmove(path, real_path, strlen(real_path) + 1);
        } else {
            strcpy(path, "/");
        }
    }

    printf("[PARSE] Метод: %s, Хост: %s, Порт: %d, Путь: %s\n",
           method, host, *port, path);
    return 0;
}


void* loader_thread(void *arg) {
    LoaderArgs *args = (LoaderArgs*)arg;
    CacheEntry *entry = args->entry;

    printf("[LOADER] Начинаю загрузку: %s:%d%s\n",
           args->host, args->port, args->path);
    if (!g_running) {
        free(args);
        return NULL;
    }
    struct hostent *server = gethostbyname(args->host);
    if (!server) {
        printf("[LOADER] Ошибка DNS для %s: %s\n",
               args->host, hstrerror(h_errno));
        char error_response[512];
        snprintf(error_response, sizeof(error_response),
                 "HTTP/1.0 502 Bad Gateway\r\n"
                 "Content-Type: text/html\r\n"
                 "Connection: close\r\n\r\n"
                 "<html><body><h1>502 Bad Gateway</h1>"
                 "<p>DNS resolution failed for %s</p></body></html>",
                 args->host);

        cache_append(entry, error_response, strlen(error_response));

        pthread_mutex_lock(&entry->mutex);
        entry->status = CACHE_ERROR;
        pthread_cond_broadcast(&entry->cond);
        pthread_mutex_unlock(&entry->mutex);

        free(args);
        return NULL;
    }

    printf("[LOADER] DNS resolved: %s -> %s\n",
           args->host, inet_ntoa(*(struct in_addr*)server->h_addr));

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        perror("[LOADER] socket");
        pthread_mutex_lock(&entry->mutex);
        entry->status = CACHE_ERROR;
        pthread_cond_broadcast(&entry->cond);
        pthread_mutex_unlock(&entry->mutex);
        free(args);
        return NULL;
    }

    struct timeval tv;
    tv.tv_sec = 30;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(args->port);
    memcpy(&addr.sin_addr, server->h_addr, server->h_length);

    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        printf("[LOADER] Ошибка подключения к %s:%d: %s\n",
               args->host, args->port, strerror(errno));

        char error_response[512];
        snprintf(error_response, sizeof(error_response),
                 "HTTP/1.0 502 Bad Gateway\r\n"
                 "Content-Type: text/html\r\n"
                 "Connection: close\r\n\r\n"
                 "<html><body><h1>502 Bad Gateway</h1>"
                 "<p>Connection failed to %s:%d</p></body></html>",
                 args->host, args->port);

        cache_append(entry, error_response, strlen(error_response));

        pthread_mutex_lock(&entry->mutex);
        entry->status = CACHE_ERROR;
        pthread_cond_broadcast(&entry->cond);
        pthread_mutex_unlock(&entry->mutex);

        close(sock);
        free(args);
        return NULL;
    }

    printf("[LOADER] Подключено к %s:%d\n", args->host, args->port);

    char request[MAX_HEADER_SIZE];
    int req_len = snprintf(request, sizeof(request),
                           "GET %s HTTP/1.0\r\n"
                           "Host: %s\r\n"
                           "User-Agent: CachingProxy/1.0\r\n"
                           "Accept: */*\r\n"
                           "Connection: close\r\n"
                           "\r\n",
                           args->path, args->host);

    printf("[LOADER] Отправляю запрос:\n%s", request);

    if (send(sock, request, req_len, 0) != req_len) {
        printf("[LOADER] Ошибка отправки запроса: %s\n", strerror(errno));
        pthread_mutex_lock(&entry->mutex);
        entry->status = CACHE_ERROR;
        pthread_cond_broadcast(&entry->cond);
        pthread_mutex_unlock(&entry->mutex);
        close(sock);
        free(args);
        return NULL;
    }

    char buffer[BUFFER_SIZE];
    ssize_t total = 0;
    ssize_t n;

    while (g_running && (n = recv(sock, buffer, sizeof(buffer), 0)) > 0) {
        cache_append(entry, buffer, n);
        total += n;
        printf("[LOADER] Получено %zd байт (всего: %zd)\n", n, total);
    }

    if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        printf("[LOADER] Ошибка получения данных: %s\n", strerror(errno));
    }

    printf("[LOADER] Загрузка завершена. Всего: %zd байт\n", total);

    pthread_mutex_lock(&entry->mutex);
    entry->status = CACHE_COMPLETE;
    pthread_cond_broadcast(&entry->cond);
    pthread_mutex_unlock(&entry->mutex);

    close(sock);
    free(args);
    return NULL;
}



static void safe_strncpy(char *dest, size_t dest_size, const char *src) {
    if (dest_size == 0) return;
    snprintf(dest, dest_size, "%s", src);
}

void* client_thread(void *arg) {
    int client_fd = *(int*)arg;
    free(arg);

    printf("[CLIENT %d] Новое подключение\n", client_fd);
    if (!g_running) {
        close(client_fd);
        return NULL;
    }
    struct timeval tv;
    tv.tv_sec = 30;
    tv.tv_usec = 0;
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    char request[MAX_HEADER_SIZE];
    ssize_t n = recv(client_fd, request, sizeof(request) - 1, 0);

    if (n <= 0) {
        printf("[CLIENT %d] Ошибка чтения запроса\n", client_fd);
        close(client_fd);
        return NULL;
    }
    request[n] = '\0';

    printf("[CLIENT %d] Получен запрос:\n%.200s...\n", client_fd, request);

    char method[16], host[256], path[MAX_URL_LENGTH], full_url[MAX_URL_LENGTH];
    int port;

    if (parse_http_request(request, method, host, &port, path, full_url) < 0) {
        printf("[CLIENT %d] Ошибка парсинга запроса\n", client_fd);
        const char *bad_request =
                "HTTP/1.0 400 Bad Request\r\n"
                "Content-Type: text/html\r\n"
                "Connection: close\r\n\r\n"
                "<html><body><h1>400 Bad Request</h1></body></html>";
        send(client_fd, bad_request, strlen(bad_request), 0);
        close(client_fd);
        return NULL;
    }

    char cache_key[MAX_URL_LENGTH];
    int key_len = snprintf(cache_key, sizeof(cache_key), "%s:%d%s", host, port, path);

    if (key_len < 0 || (size_t)key_len >= sizeof(cache_key)) {
        printf("[CLIENT %d] Ключ кэша слишком длинный\n", client_fd);
        const char *error =
                "HTTP/1.0 414 URI Too Long\r\n"
                "Connection: close\r\n\r\n";
        send(client_fd, error, strlen(error), 0);
        close(client_fd);
        return NULL;
    }

    printf("[CLIENT %d] Ключ кэша: %s\n", client_fd, cache_key);

    pthread_mutex_lock(&g_cache_mutex);

    CacheEntry *entry = cache_find(cache_key);
    int need_load = 0;

    if (entry) {
        printf("[CLIENT %d] CACHE HIT!\n", client_fd);
    } else {
        printf("[CLIENT %d] CACHE MISS - создаю новую запись\n", client_fd);
        entry = cache_create(cache_key);
        if (!entry) {
            pthread_mutex_unlock(&g_cache_mutex);
            const char *error =
                    "HTTP/1.0 503 Service Unavailable\r\n"
                    "Connection: close\r\n\r\n";
            send(client_fd, error, strlen(error), 0);
            close(client_fd);
            return NULL;
        }
        need_load = 1;
    }

    entry->ref_count++;
    pthread_mutex_unlock(&g_cache_mutex);

    if (need_load) {
        LoaderArgs *args = malloc(sizeof(LoaderArgs));
        if (args) {
            args->entry = entry;
            args->port = port;
            safe_strncpy(args->host, sizeof(args->host), host);
            safe_strncpy(args->path, sizeof(args->path), path);
            safe_strncpy(args->original_request, sizeof(args->original_request), request);

            pthread_t loader;
            if (pthread_create(&loader, NULL, loader_thread, args) == 0) {
                pthread_detach(loader);
            } else {
                free(args);
                pthread_mutex_lock(&entry->mutex);
                entry->status = CACHE_ERROR;
                pthread_cond_broadcast(&entry->cond);
                pthread_mutex_unlock(&entry->mutex);
            }
        }
    }

    size_t sent = 0;

    pthread_mutex_lock(&entry->mutex);

    while (g_running) {
        while (sent < entry->size) {
            pthread_mutex_unlock(&entry->mutex);

            ssize_t w = send(client_fd, entry->data + sent,
                             entry->size - sent, MSG_NOSIGNAL);

            pthread_mutex_lock(&entry->mutex);

            if (w <= 0) {
                printf("[CLIENT %d] Ошибка отправки данных\n", client_fd);
                goto cleanup;
            }
            sent += w;
        }

        if (entry->status == CACHE_COMPLETE || entry->status == CACHE_ERROR) {
            printf("[CLIENT %d] Передача завершена. Отправлено: %zu байт\n",
                   client_fd, sent);
            break;
        }

        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 1;
        pthread_cond_timedwait(&entry->cond, &entry->mutex, &ts);
    }

    cleanup:
    pthread_mutex_unlock(&entry->mutex);

    pthread_mutex_lock(&g_cache_mutex);
    entry->ref_count--;
    pthread_mutex_unlock(&g_cache_mutex);

    close(client_fd);
    printf("[CLIENT %d] Соединение закрыто\n", client_fd);
    return NULL;
}


void print_cache_stats() {
    pthread_mutex_lock(&g_cache_mutex);
    printf("\n=== СТАТИСТИКА КЭША ===\n");
    printf("Записей в кэше: %d\n", g_cache_count);
    for (int i = 0; i < g_cache_count; i++) {
        printf("  [%d] %s (%zu байт, refs=%d, status=%d)\n",
               i, g_cache[i]->url, g_cache[i]->size,
               g_cache[i]->ref_count, g_cache[i]->status);
    }
    printf("========================\n\n");
    pthread_mutex_unlock(&g_cache_mutex);
}

int main(int argc, char *argv[]) {
    int port = PROXY_PORT;

    if (argc > 1) {
        port = atoi(argv[1]);
    }

    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;

    if (sigaction(SIGINT, &sa, NULL) < 0) {
        perror("sigaction SIGINT");
        return 1;
    }
    if (sigaction(SIGTERM, &sa, NULL) < 0) {
        perror("sigaction SIGTERM");
        return 1;
    }
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("socket");
        return 1;
    }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind");
        printf("Попробуйте запустить с sudo или используйте другой порт: ./proxy 8080\n");
        return 1;
    }

    if (listen(server_fd, 128) < 0) {
        perror("listen");
        return 1;
    }

    printf("========================================\n");
    printf("   HTTP CACHING PROXY v1.0\n");
    printf("========================================\n");
    printf("Слушаю на порту: %d\n", port);
    printf("========================================\n\n");

    while (g_running) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);

        int *client_fd = malloc(sizeof(int));
        *client_fd = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);

        if (!g_running) {
            if (client_fd >= 0) {
                close(*client_fd);
            }
            break;
        }

        if (*client_fd < 0) {
            if (errno == EINTR) {
                continue;
            }
            perror("accept");
            continue;
        }

        printf("[MAIN] Новое подключение от %s:%d\n",
               inet_ntoa(client_addr.sin_addr),
               ntohs(client_addr.sin_port));

        pthread_t thread;
        if (pthread_create(&thread, NULL, client_thread, client_fd) != 0) {
            perror("pthread_create");
            close(*client_fd);
            free(client_fd);
            continue;
        }
        pthread_detach(thread);

        static int request_count = 0;
        if (++request_count % 5 == 0) {
            print_cache_stats();
        }
    }

    printf("\n[MAIN] Завершение работы...\n");

    print_cache_stats();

    cleanup_cache();



    printf("[MAIN] Прокси-сервер остановлен.\n");

    close(server_fd);
    return 0;
}