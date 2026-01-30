#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>
#include <time.h>

#define LIST_SIZE 100

typedef struct _Node {
    char value[100];
    struct _Node* next;
    pthread_mutex_t sync;
} Node;

typedef struct _Storage {
    Node *first;
} Storage;

volatile long long asc_iterations = 0;
volatile long long desc_iterations = 0;
volatile long long eq_iterations = 0;
volatile long long swaps = 0;

Storage storage = { NULL };

void init_list() {
    storage.first = NULL;
    Node* prev = NULL;
    for (int i = 0; i < LIST_SIZE; i++) {
        Node* node = malloc(sizeof(Node));
        int len = 10 + rand() % 81;
        memset(node->value, 'a' + (i % 26), len);
        node->value[len] = '\0';
        pthread_mutex_init(&node->sync, NULL);
        node->next = NULL;

        if (storage.first == NULL) {
            storage.first = node;
        }
        if (prev != NULL) {
            prev->next = node;
        }
        prev = node;
    }
}

void* ascending_thread(void* arg) {
    while (1) {
        Node* cur = storage.first;

        Node* prev_node = cur;
        pthread_mutex_lock(&prev_node->sync);
        cur = cur->next;
        pthread_mutex_lock(&cur->sync);

        while (cur->next != NULL) {
            Node* next = cur->next;
            pthread_mutex_lock(&next->sync);

            if (strlen(prev_node->value) < strlen(cur->value)) {

            }

            pthread_mutex_unlock(&prev_node->sync);
            prev_node = cur;
            cur = next;
        }
        pthread_mutex_unlock(&prev_node->sync);
        pthread_mutex_unlock(&cur->sync);

        __sync_fetch_and_add(&asc_iterations, 1);
    }
    return NULL;
}

void* descending_thread(void* arg) {
    while (1) {
        Node* cur = storage.first;


        Node* prev_node = cur;
        pthread_mutex_lock(&prev_node->sync);
        cur = cur->next;
        pthread_mutex_lock(&cur->sync);

        while (cur->next != NULL) {
            Node* next = cur->next;
            pthread_mutex_lock(&next->sync);

            if (strlen(prev_node->value) > strlen(cur->value)) {

            }

            pthread_mutex_unlock(&prev_node->sync);
            prev_node = cur;
            cur = next;
        }
        pthread_mutex_unlock(&prev_node->sync);
        pthread_mutex_unlock(&cur->sync);

        __sync_fetch_and_add(&desc_iterations, 1);
    }
    return NULL;
}

void* equal_thread(void* arg) {
    while (1) {
        Node* cur = storage.first;


        Node* prev_node = cur;
        pthread_mutex_lock(&prev_node->sync);
        cur = cur->next;
        pthread_mutex_lock(&cur->sync);

        while (cur->next != NULL) {
            Node* next = cur->next;
            pthread_mutex_lock(&next->sync);

            if (strlen(prev_node->value) == strlen(cur->value)) {

            }

            pthread_mutex_unlock(&prev_node->sync);
            prev_node = cur;
            cur = next;
        }
        pthread_mutex_unlock(&prev_node->sync);
        pthread_mutex_unlock(&cur->sync);

        __sync_fetch_and_add(&eq_iterations, 1);
    }
    return NULL;
}

void* swap_thread(void* arg) {
    while (1) {
        usleep(10000);

        Node *prev = storage.first;
        Node *cur = prev->next;


        pthread_mutex_lock(&prev->sync);
        pthread_mutex_lock(&cur->sync);

        while (cur) {
            Node *next = cur->next;
            if (!next) {
                break;
            }
            pthread_mutex_lock(&next->sync);
            if (rand() % 2) {
                Node *a = next->next;
                prev->next = next;
                next->next = cur;
                cur->next = a;
                Node *b = cur;
                cur = next;
                next = b;
                __sync_fetch_and_add(&swaps, 1);
            }
            pthread_mutex_unlock(&prev->sync);
            prev = cur;
            cur = next;
        }
        pthread_mutex_unlock(&prev->sync);
        pthread_mutex_unlock(&cur->sync);
    }
    return NULL;
}



void* printer_thread(void* arg) {
    while (1) {
        sleep(5);
        printf("\n=== Статистика ===\n");
        printf("Итерации (возрастание): %lld\n", asc_iterations);
        printf("Итерации (убывание):    %lld\n", desc_iterations);
        printf("Итерации (равные):      %lld\n", eq_iterations);
        printf("Успешные перестановки:  %lld\n", swaps);
        printf("==================\n");
    }
    return NULL;
}

int main() {
    srand(time(NULL));
    init_list();

    pthread_t t_asc, t_desc, t_eq;
    pthread_t t_swap1, t_swap2, t_swap3;
    pthread_t t_printer;

    pthread_create(&t_asc, NULL, ascending_thread, NULL);
    pthread_create(&t_desc, NULL, descending_thread, NULL);
    pthread_create(&t_eq, NULL, equal_thread, NULL);
    pthread_create(&t_swap1, NULL, swap_thread, NULL);
    pthread_create(&t_swap2, NULL, swap_thread, NULL);
    pthread_create(&t_swap3, NULL, swap_thread, NULL);
    pthread_create(&t_printer, NULL, printer_thread, NULL);

    pthread_join(t_asc, NULL);

    return 0;
}