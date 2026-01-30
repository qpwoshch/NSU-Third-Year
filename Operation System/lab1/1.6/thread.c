#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <string.h>
#include <sched.h>


typedef int mythread_t;

typedef struct {
    void *(*start_routine)(void *);
    void *arg;
} mythread_data_t;

static int thread_wrapper(void *data) {
    mythread_data_t *thread_data = (mythread_data_t *)data;
    void *(*start_routine)(void *) = thread_data->start_routine;
    void *arg = thread_data->arg;
    start_routine(arg);
    free(thread_data);
    return 0;
}

int mythread_create(mythread_t *thread, void *(*start_routine)(void *), void *arg) {
    if (thread == NULL || start_routine == NULL) {
        return -1;
    }
    void *stack = mmap(NULL, 1024*1024,
                       PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_STACK,
                       -1, 0);

    if (stack == MAP_FAILED) {
        return -1;
    }
    mythread_data_t *thread_data = malloc(sizeof(mythread_data_t));
    if (thread_data == NULL) {
        munmap(stack, 1024*1024);
        return -1;
    }
    thread_data->start_routine = start_routine;
    thread_data->arg = arg;
    pid_t pid = clone(thread_wrapper,
                      (char *)stack + 1024*1024,
                      SIGCHLD,
                      thread_data);

//    pid_t ptid = 0;
//    pid_t ctid = 0;
//
//    pid_t pid = clone(thread_wrapper,
//                      (char *)stack + 1024*1024,
//                      CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND | CLONE_THREAD |
//                      CLONE_SYSVSEM | CLONE_SETTLS | CLONE_PARENT_SETTID | CLONE_CHILD_CLEARTID |
//                       SIGCHLD,
//                      thread_data,
//                      &ptid,
//                      NULL,
//                      &ctid);

    if (pid == -1) {
        free(thread_data);
        munmap(stack, 1024*1024);
        return -1;
    }
    *thread = pid;
    return 0;
}

int mythread_join(mythread_t thread, void **retval) {
    int status;
    pid_t result = waitpid(thread, &status, 0);
    if (retval != NULL) {
        *retval = (void *)(long)status;
    }
    return 0;
}

void* example_thread_function(void *arg) {
    int *value = (int *)arg;
    printf("%d\n", *value);
    return NULL;
}

int main() {
    mythread_t thread;
    int arg = 42;
    if (mythread_create(&thread, example_thread_function, &arg) == 0) {
        mythread_join(thread, NULL);
    }
    return 0;
}