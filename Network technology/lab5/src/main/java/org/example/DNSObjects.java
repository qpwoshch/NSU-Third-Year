package org.example;

import java.nio.channels.SelectionKey;

public record DNSObjects(
        Connect connect,
        SelectionKey key,
        int port
) {}
