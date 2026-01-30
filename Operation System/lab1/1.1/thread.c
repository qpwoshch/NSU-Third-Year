#define _GNU_SOURCE
#include <stdio.h>
#include <pthread.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <unistd.h>

int global;

void *mythread(void *arg) {
    int local = 0;
    static int static_local;
    const int const_local = 5;
    for (int i = 0; i < 3; i++) {
        local++;
        global++;
    }
    printf("-----------------------------------------\n");
	printf("mythread [%d %d %d]: Hello from mythread!\n", getpid(), getppid(), gettid());
    printf("pthread_self: %p\n", (void*)pthread_self());
    printf("global address: %p\n", (void*)&global);
    printf("local address: %p\n", (void*)&local);
    printf("static local address: %p\n", (void*)&static_local);
    printf("const local address: %p\n", (void*)&const_local);
    printf("global: %d\nlocal: %d\n", global, local);
    sleep(15);
	return NULL;
}

int main() {
	pthread_t tid[5];
	int err;
	printf("main [%d %d %d]: Hello from main!\n", getpid(), getppid(), gettid());
    for (int i = 0; i < 5; i++) {
        err = pthread_create(&tid[i], NULL, mythread, NULL);
        if (err) {
            printf("main: pthread_create() failed: %s\n", strerror(err));
            return -1;
        }
        printf("main: pthread_create return tid: %p\n", (void*)tid[i]);

    }
    sleep(15);
    for (int i = 0; i < 5; i++) {
        pthread_join(tid[i], NULL);
    }

    return 0;
}

