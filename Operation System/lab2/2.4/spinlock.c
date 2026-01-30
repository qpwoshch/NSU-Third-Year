#include <stdio.h>
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>

atomic_int lock = 0;

int compare_and_swap(atomic_int *atom, int expected, int new_val) {
    int prev = expected;
    int success = atomic_compare_exchange_strong(atom, &prev, new_val);
    if (!success && prev != 1) {
        fprintf(stderr, "[ОШИБКА] Неожиданное значение lock после неудачного CAS: %d (ожидалось 1)\n", prev);
    }
    return success;
}

void spin_lock() {
    int expected = 0;
    while (!compare_and_swap(&lock, expected, 1)) {
        expected = 0;
    }
}

void spin_unlock() {
    atomic_store(&lock, 0);
}

void *worker(void *arg) {
    long id = (long)arg;

    for (int i = 1; i <= 5; i++) {
        printf("[Поток %ld] Попытка %d: жду спинлок...\n", id, i);
        fflush(stdout);

        spin_lock();

        printf(">>> [Поток %ld] Попытка %d: ЗАХВАТИЛ ресурс!\n", id, i);
        fflush(stdout);

        sleep(1);

        printf("<<< [Поток %ld] Попытка %d: освобождаю спинлок\n", id, i);
        fflush(stdout);

        spin_unlock();

        sleep(1);
    }

    printf("[Поток %ld] Завершил все итерации.\n", id);
    return NULL;
}

int main() {
    pthread_t t1, t2;

    printf("Запуск двух потоков. Каждый выполнит 5 захватов спинлока по очереди.\n\n");

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

    printf("\nОба потока завершились. Твой спинлок работает корректно!\n");
    return 0;
}