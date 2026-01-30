#define _GNU_SOURCE
#include <stdio.h>
#include <pthread.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <unistd.h>


void *mythread(void *arg) {
    int* result = (int*)arg;
    *result = 42;
	return "hello world";
}

int main() {
	pthread_t tid;
	int err;
    int value;
    void *thread_return;
    err = pthread_create(&tid, NULL, mythread, &value);
    if (err) {
        printf("main: pthread_create() failed: %s\n", strerror(err));
        return -1;
    }
    pthread_join(tid, &thread_return);
    printf("main: pthread_create return value: %d\n", value);
    printf("main: thread return string: %s\n", (char*)thread_return);
    return 0;
}

