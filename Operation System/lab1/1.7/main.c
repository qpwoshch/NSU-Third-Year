#include <stdio.h>
#include "uthread.h"

static double pi = 3.0;
static long long current_n = 2;
static int current_sign = 1;

void *worker(void *arg) {
    int id = *(int *)arg;

    printf("Поток %d: старт вычисления Пи\n", id);

    for (int i = 0; i < 5; i++) {
        long long n;
        int sign;

        n = current_n;
        sign = current_sign;

        current_n += 2;
        current_sign = -current_sign;

        double term = sign * 4.0 / (n * (n + 1) * (n + 2));
        pi += term;

        printf("Поток %d: итерация %d, член %d: %c4/(%lld·%lld·%lld) = %.12f, pi ≈ %.12f\n",
               id, i + 1, i + 1, sign > 0 ? '+' : '-', n, n+1, n+2, term, pi);
        fflush(stdout);

        uthread_yield();
    }

    printf("Поток %d: завершение\n", id);
    return NULL;
}

int main(void) {
    int id1 = 1, id2 = 2, id3 = 3;

    printf("Main: создаём потоки\n");
    uthread_create(NULL, worker, &id1);
    uthread_create(NULL, worker, &id2);
    uthread_create(NULL, worker, &id3);

    printf("Main: запускаем шедулер\n");
    uthread_scheduler();

    printf("\nMain: все потоки завершились\n");
    printf("Итоговое приближение Пи: %.12f\n", pi);
    printf("Main: программа завершена\n");

    return 0;
}