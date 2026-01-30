#define _GNU_SOURCE
#include <pthread.h>
#include <assert.h>
#include <semaphore.h>
#include "queue.h"

sem_t sem_add;
sem_t sem_get;
pthread_mutex_t queue_mutex;

void *qmonitor(void *arg) {
    queue_t *q = (queue_t *)arg;

    printf("qmonitor: [%d %d %ld]\n", getpid(), getppid(), (long)gettid());

    pthread_setcancelstate(PTHREAD_CANCEL_ENABLE, NULL);
    pthread_setcanceltype(PTHREAD_CANCEL_DEFERRED, NULL);

    while (1) {
        queue_print_stats(q);
        pthread_testcancel();
        sleep(1);
    }

    return NULL;
}

queue_t* queue_init(int max_count) {
    int err;

    queue_t *q = malloc(sizeof(queue_t));
    if (!q) {
        printf("Cannot allocate memory for a queue\n");
        abort();
    }

    q->first = NULL;
    q->last = NULL;
    q->max_count = max_count;
    q->count = 0;

    q->add_attempts = q->get_attempts = 0;
    q->add_count = q->get_count = 0;


    err = sem_init(&sem_add, 0, q->max_count);
    if (err) {
        printf("queue_init: sem_init(sem_add) failed\n");
        abort();
    }

    err = sem_init(&sem_get, 0, 0);
    if (err) {
        printf("queue_init: sem_init(sem_get) failed\n");
        abort();
    }
    err = pthread_mutex_init(&queue_mutex, NULL);
    err = pthread_create(&q->qmonitor_tid, NULL, qmonitor, q);
    if (err) {
        printf("queue_init: pthread_create() failed: %s\n", strerror(err));
        abort();
    }

    return q;
}

void queue_destroy(queue_t *q) {
    if (!q) return;

    pthread_cancel(q->qmonitor_tid);
    pthread_join(q->qmonitor_tid, NULL);

    qnode_t *cur = q->first;
    while (cur) {
        qnode_t *tmp = cur;
        cur = cur->next;
        free(tmp);
    }
    free(q);
    pthread_mutex_unlock(&queue_mutex);

    pthread_mutex_destroy(&queue_mutex);
    sem_destroy(&sem_add);
    sem_destroy(&sem_get);
    printf("queue_destroy: queue destroyed successfully\n");
}

int queue_add(queue_t *q, int val) {
    q->add_attempts++;

    assert(q->count <= q->max_count);

    if (q->count == q->max_count)
        return 0;


    sem_wait(&sem_add);
    pthread_mutex_lock(&queue_mutex);

    qnode_t *new = malloc(sizeof(qnode_t));
    if (!new) {
        printf("Cannot allocate memory for new node\n");
        abort();
    }

    new->val = val;
    new->next = NULL;

    if (!q->first)
        q->first = q->last = new;
    else {
        q->last->next = new;
        q->last = q->last->next;
    }

    q->count++;
    q->add_count++;
    pthread_mutex_unlock(&queue_mutex);
    sem_post(&sem_get);

    return 1;
}

int queue_get(queue_t *q, int *val) {
    q->get_attempts++;

    assert(q->count >= 0);

    if (q->count == 0)
        return 0;
    sem_wait(&sem_get);
    pthread_mutex_lock(&queue_mutex);
    qnode_t *tmp = q->first;
    *val = tmp->val;
    q->first = q->first->next;

    free(tmp);
    q->count--;
    q->get_count++;
    pthread_mutex_unlock(&queue_mutex);
    sem_post(&sem_add);
    return 1;
}

void queue_print_stats(queue_t *q) {
    printf("queue stats: current size %d; attempts: (%ld %ld %ld); counts (%ld %ld %ld)\n",
           q->count,
           q->add_attempts, q->get_attempts, q->add_attempts - q->get_attempts,
           q->add_count, q->get_count, q->add_count - q->get_count);
}
