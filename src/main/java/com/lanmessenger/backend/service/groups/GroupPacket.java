
package com.lanmessenger.backend.service.groups;

import java.io.*;
import java.util.*;

public record GroupPacket(
        String id,
        String type,
        String from,
        String to,
        String groupId,
        long timestamp,
        byte[] payload
) {
    private static final int MAGIC = 0x4c4d4750;
    private static final int VERSION = 1;
    private static final int LIMIT = 2 * 1024 * 1024;

    public GroupPacket {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(groupId);
        Objects.requireNonNull(payload);

        if (id.length() > 100 ||
                from.length() > 100 ||
                to.length() > 100 ||
                groupId.length() > 100 ||
                !type.matches(
                        "ROSTER|CHAT|DELETE_GLOBAL|LEAVE") ||
                payload.length > LIMIT) {

            throw new IllegalArgumentException(
                    "Invalid group packet");
        }

        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static GroupPacket create(
            String eventId,
            String type,
            String from,
            String to,
            String groupId,
            long time,
            byte[] bytes) {

        return new GroupPacket(
                eventId, type, from, to,
                groupId, time, bytes);
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream();

        DataOutputStream out =
                new DataOutputStream(buffer);

        write(out);
        out.flush();

        return buffer.toByteArray();
    }

    public void write(DataOutputStream out)
            throws IOException {

        out.writeInt(MAGIC);
        out.writeInt(VERSION);

        out.writeUTF(id);
        out.writeUTF(type);
        out.writeUTF(from);
        out.writeUTF(to);
        out.writeUTF(groupId);

        out.writeLong(timestamp);
        out.writeInt(payload.length);
        out.write(payload);
    }

    public static GroupPacket read(
            DataInputStream in) throws IOException {

        if (in.readInt() != MAGIC ||
                in.readInt() != VERSION) {

            throw new IOException(
                    "Unsupported group protocol");
        }

        String id = in.readUTF();
        String type = in.readUTF();
        String from = in.readUTF();
        String to = in.readUTF();
        String groupId = in.readUTF();

        long time = in.readLong();
        int size = in.readInt();

        if (size < 0 || size > LIMIT) {
            throw new IOException(
                    "Group packet too large");
        }

        byte[] content = new byte[size];
        in.readFully(content);

        try {
            return new GroupPacket(
                    id, type, from, to,
                    groupId, time, content);

        } catch (IllegalArgumentException e) {
            throw new IOException(
                    "Malformed group packet", e);
        }
    }
}
