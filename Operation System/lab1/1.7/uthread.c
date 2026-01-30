#include <stdio.h>
#include <stdlib.h>
#include <ucontext.h>
#include "uthread.h"

#define STACK_SIZE (64 * 1024)
#define MAX_THREADS 16

typedef struct uthread {
    ucontext_t context;
    char stack[STACK_SIZE];
    void *(*func)(void *);
    void *arg;
    int finished;
} uthread_struct;

static uthread_struct *threads[MAX_THREADS];
static int num_threads = 0;
static int current = -1;
static ucontext_t main_ctx;

static int find_next_thread(void) {
    if (num_threads == 0) return -1;
    int candidate = (current + 1) % num_threads;
    int start = candidate;

    do {
        if (!threads[candidate]->finished) {
            return candidate;
        }
        candidate = (candidate + 1) % num_threads;
    } while (candidate != start);

    return -1;
}

void uthread_yield(void) {
    swapcontext(&threads[current]->context, &main_ctx);
}

static void thread_entry(void) {
    uthread_struct *t = threads[current];
    t->func(t->arg);
    t->finished = 1;
}

int uthread_create(uthread_t *tid, void *(*start_routine)(void *), void *arg) {
    if (num_threads >= MAX_THREADS) return -1;

    uthread_struct *t = malloc(sizeof(uthread_struct));
    if (!t) return -1;

    t->func = start_routine;
    t->arg = arg;
    t->finished = 0;

    getcontext(&t->context);
    t->context.uc_stack.ss_sp = t->stack;
    t->context.uc_stack.ss_size = STACK_SIZE;
    t->context.uc_stack.ss_flags = 0;
    t->context.uc_link = &main_ctx;

    makecontext(&t->context, thread_entry, 0);

    threads[num_threads++] = t;
    if (tid) *tid = t;
    return 0;
}

void uthread_scheduler(void) {
    getcontext(&main_ctx);

    while (1) {
        int next = find_next_thread();
        if (next == -1) {
            break;
        }

        current = next;
        swapcontext(&main_ctx, &threads[next]->context);
    }
    for (int i = 0; i < num_threads; i++) {
        free(threads[i]);
    }
    num_threads = 0;
    current = -1;
}