struct sem {
    uint current_count
    cond c
    mutex m
};

wait(sem) {
    m_lock
    while(sem.current_count <= 0) {
        c_wait()
    }
    sem.current_count-=1
    m_unlock()
}

post(sem) {
    m_lock
    sem.current_count+=1
    c_broaddcast
    m_unlock
}