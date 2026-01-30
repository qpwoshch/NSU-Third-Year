#define _GNU_SOURCE
#include <stdio.h>
#include <pthread.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <unistd.h>
#include <stdlib.h>

struct Thr {
    int a;
    char* b;
};

void *mythread(void *arg) {
    struct Thr *s1 = (struct Thr *)arg;
    printf("Struct: int = %d, string = %s\n", s1->a, s1->b);
    free(s1);
    return NULL;
}

int main() {
    pthread_t tid;
    int err;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    struct Thr *s1 = malloc(sizeof(struct Thr));
    s1->a = 42;
    s1->b = "hello world";
    err = pthread_create(&tid, &attr, mythread, s1);
    if (err) {
        printf("main: pthread_create() failed: %s\n", strerror(err));
        return -1;
    }
    sleep(1);
    return 0;
}

