
package com.lanmessenger.core;

import java.nio.file.Path;

public interface MessengerEvents extends MessengerApi.Listener {

    default void onFileReceived(
            String senderId,
            String filename,
            Path file
    ) {}

    default void onMyProfileChanged(
            MessengerApi.Profile profile
    ) {}

    default void onConversationCleared(
            String contactId
    ) {}
}
