#define _POSIX_C_SOURCE 200809L
#define _GNU_SOURCE
#define _DEFAULT_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <pthread.h>
#include <unistd.h>

void sigint_handler(int signo) {
    printf("Thread 2: SIGINT (%d)\n", signo);
}

void* thread_block_all(void* arg) {
    sigset_t set;
    sigfillset(&set);
    pthread_sigmask(SIG_BLOCK, &set, NULL);
    printf("Thread 1\n");
    while (1)  {
        sleep(1);
    }
    return NULL;
}

void* thread_handle_sigint(void* arg) {
    struct sigaction sa;
    sa.sa_handler = sigint_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    if (sigaction(SIGINT, &sa, NULL) == -1) {
        exit(1);
    }
//    if (signal(SIGINT, sigint_handler) == SIG_ERR) {
//        exit(1);
//    }
//    if (sigset(SIGINT, sigint_handler) == SIG_ERR) {
//        exit(1);
//    }
//    if (bsd_signal(SIGINT, sigint_handler) == SIG_ERR) {
//        exit(1);
//    }
//    struct sigvec sv;
//    sv.sv_handler = sigint_handler;
//    sigemptyset(&sv.sv_mask);
//    sv.sv_flags = 0;
//
//    if (sigvec(SIGINT, &sv, NULL) < 0) {
//        exit(1);
//    }
    printf("Thread 2\n");
    return NULL;
}

void* thread_wait_sigquit(void* arg) {
    sigset_t set;
    int sig;
    sigemptyset(&set);
    sigaddset(&set, SIGQUIT);
    pthread_sigmask(SIG_BLOCK, &set, NULL);
    printf("Thread 3:\n");
    while (1) {
        if (sigwait(&set, &sig) == 0) {
            printf("\nThread 3: SIGQUIT (%d)\n", sig);
        } else {
            perror("sigwait");
        }
    }
    return NULL;
}

int main() {
    pthread_t t1, t2, t3;

    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGQUIT);
    pthread_sigmask(SIG_BLOCK, &set, NULL);

    pthread_create(&t1, NULL, thread_block_all, NULL);
    pthread_create(&t2, NULL, thread_handle_sigint, NULL);
    pthread_create(&t3, NULL, thread_wait_sigquit, NULL);

    pthread_join(t1, NULL);
    pthread_join(t2, NULL);
    pthread_join(t3, NULL);
    return 0;
}
