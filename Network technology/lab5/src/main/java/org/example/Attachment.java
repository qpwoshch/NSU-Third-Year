package org.example;

import java.nio.channels.SelectionKey;

public interface Attachment {
    void handle(SelectionKey key) throws Exception;
}
