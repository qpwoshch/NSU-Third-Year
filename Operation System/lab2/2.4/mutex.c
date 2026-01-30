#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <linux/futex.h>
#include <stdatomic.h>
#include <errno.h>
#include <string.h>

atomic_int lock = 0;

int futex_wait(atomic_int *futex, int expected_value) {
    int ret = syscall(SYS_futex, futex, FUTEX_WAIT_PRIVATE, expected_value, NULL, NULL, 0);
    if (ret == -1) {
        if (errno != EAGAIN && errno != EINTR) {
            fprintf(stderr, "futex_wait ошибка: %s\n", strerror(errno));
        }
    }
    return ret;
}

int futex_wake(atomic_int *futex, int wake_count) {
    int ret = syscall(SYS_futex, futex, FUTEX_WAKE_PRIVATE, wake_count, NULL, NULL, 0);
    if (ret == -1) {
        fprintf(stderr, "futex_wake ошибка: %s\n", strerror(errno));
    }
    return ret;
}

void mutex_lock() {
    while (1) {
        if (atomic_compare_exchange_strong(&lock, &(int){0}, 1)) {
            return;
        }
        futex_wait(&lock, 1);
    }
}

void mutex_unlock() {
    atomic_store(&lock, 0);
    futex_wake(&lock, 1);
}

void *worker(void *arg) {
    long id = (long)arg;

    for (int i = 1; i <= 5; i++) {
        printf("[Поток %ld] Попытка %d: жду мьютекс...\n", id, i);
        fflush(stdout);
        mutex_lock();
        printf(">>> [Поток %ld] Попытка %d: ЗАХВАТИЛ ресурс!\n", id, i);
        fflush(stdout);
        sleep(1);
        printf("<<< [Поток %ld] Попытка %d: освобождаю мьютекс\n", id, i);
        fflush(stdout);
        mutex_unlock();
        sleep(1);
    }

    printf("[Поток %ld] Завершил все итерации.\n", id);
    return NULL;
}

int main() {
    pthread_t t1, t2;

    printf("Запуск двух потоков. Каждый выполнит 5 захватов мьютекса по очереди.\n\n");

    if (pthread_create(&t1, NULL, worker, (void *)1) != 0) {
        perror("pthread_create t1");
        return 1;
    }
    if (pthread_create(&t2, NULL, worker, (void *)2) != 0) {
        perror("pthread_create t2");
        return 1;
    }

    if (pthread_join(t1, NULL) != 0) {
        perror("pthread_join t1");
    }
    if (pthread_join(t2, NULL) != 0) {
        perror("pthread_join t2");
    }

    printf("\nОба потока завершились.\n");
    return 0;
}