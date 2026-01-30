#ifndef UTHREAD_H
#define UTHREAD_H

typedef struct uthread* uthread_t;

int uthread_create(uthread_t *tid, void *(*start_routine)(void *), void *arg);

void uthread_yield(void);

void uthread_scheduler(void);

#endif