
package com.lanmessenger.backend.peer;

import java.net.InetAddress;
import java.util.Objects;

public record PeerInfo(
        String id,
        String username,
        InetAddress address,
        int tcpPort,
        long lastSeen
) {
    public PeerInfo {
        Objects.requireNonNull(id);
        Objects.requireNonNull(username);
        Objects.requireNonNull(address);

        if (id.isBlank() ||
                tcpPort < 1 ||
                tcpPort > 65535) {
            throw new IllegalArgumentException(
                    "Invalid LAN peer");
        }
    }
}
