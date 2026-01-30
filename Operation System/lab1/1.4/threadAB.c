#define _GNU_SOURCE
#include <stdio.h>
#include <pthread.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <unistd.h>


void *mythread(void *arg) {
    long long counter = 0;
    while (1) {
        counter++;
//        if (counter % 1000000 == 0) {
//            pthread_testcancel();
//        }
//        printf("%llu\n", counter);
    }
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

