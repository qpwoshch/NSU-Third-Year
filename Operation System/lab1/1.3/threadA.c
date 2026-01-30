#define _GNU_SOURCE
#include <stdio.h>
#include <pthread.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <unistd.h>

struct Thr {
    int a;
    char* b;
};

void *mythread(void *arg) {
    struct Thr *s1 = (struct Thr *)arg;
    printf("Struct: int = %d, string = %s\n", s1->a, s1->b);
    return NULL;
}

int main() {
    pthread_t tid;
    int err;
    struct Thr s1 = {42, "hello world"};
    err = pthread_create(&tid, NULL, mythread, &s1);
    if (err) {
        printf("main: pthread_create() failed: %s\n", strerror(err));
        return -1;
    }
    pthread_join(tid, NULL);
    return 0;
}

