
package com.lanmessenger.backend.service.groups;

import java.util.Objects;

public final class GroupModels {

    private GroupModels() {}

    public enum Role {
        OWNER, ADMIN, MEMBER
    }

    public record Group(
            String id,
            String name,
            String creatorId,
            long revision,
            long createdAt,
            byte[] avatar
    ) {
        public Group {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(creatorId);

            if (id.isBlank() ||
                    name.isBlank() ||
                    name.length() > 80 ||
                    creatorId.isBlank() ||
                    revision < 1) {
                throw new IllegalArgumentException("Invalid group");
            }

            avatar = avatar == null
                    ? new byte[0] : avatar.clone();

            if (avatar.length > 1024 * 1024) {
                throw new IllegalArgumentException(
                        "Group avatar too large");
            }
        }

        @Override
        public byte[] avatar() {
            return avatar.clone();
        }
    }

    public record Member(
            String groupId,
            String userId,
            String username,
            Role role,
            long joinedAt
    ) {
        public Member {
            Objects.requireNonNull(groupId);
            Objects.requireNonNull(userId);
            Objects.requireNonNull(username);
            Objects.requireNonNull(role);

            if (groupId.isBlank() ||
                    userId.isBlank() ||
                    username.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid group member");
            }
        }
    }

    public record Message(
            String id,
            String groupId,
            String senderId,
            String text,
            long timestamp,
            boolean deletedForEveryone
    ) {
        public Message {
            Objects.requireNonNull(id);
            Objects.requireNonNull(groupId);
            Objects.requireNonNull(senderId);
            Objects.requireNonNull(text);

            if (id.isBlank() ||
                    groupId.isBlank() ||
                    senderId.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid group message");
            }
        }
    }
}
