#define _GNU_SOURCE
#include <stdio.h>
#include <pthread.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <unistd.h>
#include <stdlib.h>

void clean(void* arg) {
    free(arg);
}


void *mythread(void *arg) {
    char* str = malloc(20);
    strcpy(str, "Hello world!");
    pthread_cleanup_push(clean, str);
    while (1) {
        printf("%s\n", str);
    }
    pthread_cleanup_pop(1);
    return NULL;
}

int main() {
    pthread_t tid;
    int err;
    void *thread_return;
    err = pthread_create(&tid, NULL, mythread, NULL);
    if (err) {
        printf("main: pthread_create() failed: %s\n", strerror(err));
        return -1;
    }
    sleep(1);
    pthread_cancel(tid);
    pthread_join(tid, &thread_return);
    return 0;
}

