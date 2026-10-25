
package com.lanmessenger.backend.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record WirePacket(
        String id,
        String type,
        String senderId,
        String recipientId,
        long timestamp,
        byte[] payload
) {
    private static final int MAGIC = 0x4C4D5032;
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    public WirePacket {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(senderId);
        Objects.requireNonNull(recipientId);
        Objects.requireNonNull(payload);

        if (id.length() > 128 ||
                senderId.length() > 128 ||
                recipientId.length() > 128 ||
                !type.matches("[A-Z_]{2,40}") ||
                payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                    "Invalid packet fields or size");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static WirePacket create(
            String type, String from,
            String to, byte[] content) {
        return new WirePacket(
                UUID.randomUUID().toString(),
                type, from, to,
                System.currentTimeMillis(), content);
    }

    public static WirePacket text(
            String type, String from,
            String to, String content) {
        return create(type, from, to,
                content.getBytes(StandardCharsets.UTF_8));
    }

    public String text() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public void write(DataOutputStream out)
            throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        writeString(out, id);
        writeString(out, type);
        writeString(out, senderId);
        writeString(out, recipientId);
        out.writeLong(timestamp);
        out.writeInt(payload.length);
        out.write(payload);
    }

    public static WirePacket read(DataInputStream in)
            throws IOException {
        if (in.readInt() != MAGIC ||
                in.readInt() != VERSION) {
            throw new IOException(
                    "Unsupported LAN Messenger protocol");
        }

        String id = readString(in);
        String type = readString(in);
        String from = readString(in);
        String to = readString(in);
        long time = in.readLong();

        int size = in.readInt();
        if (size < 0 || size > MAX_PAYLOAD) {
            throw new IOException("Packet payload too large");
        }

        byte[] bytes = new byte[size];
        in.readFully(bytes);

        try {
            return new WirePacket(
                    id, type, from, to, time, bytes);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed packet", e);
        }
    }

    private static void writeString(
            DataOutputStream out, String value)
            throws IOException {
        byte[] bytes =
                value.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > 1024) {
            throw new IOException("Packet field too long");
        }

        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in)
            throws IOException {
        int size = in.readInt();

        if (size < 0 || size > 1024) {
            throw new IOException(
                    "Invalid packet field length");
        }

        byte[] bytes = new byte[size];
        in.readFully(bytes);

        return new String(
                bytes, StandardCharsets.UTF_8);
    }
}
