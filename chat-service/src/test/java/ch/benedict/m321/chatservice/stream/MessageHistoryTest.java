package ch.benedict.m321.chatservice.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft das Gedächtnis der Instanz ohne Docker: was nach einer ID kommt,
 * was bei einer unbekannten ID passiert, und dass höchstens 1000 pro Raum
 * gemerkt werden.
 */
class MessageHistoryTest {

    private final MessageHistory messageHistory = new MessageHistory();

    /** Nach einer bekannten ID kommen genau die späteren Nachrichten, in ihrer Reihenfolge. */
    @Test
    void returnsMessagesAfterKnownIdInOrder() {
        UUID roomId = UUID.randomUUID();
        List<UUID> ids = rememberMessages(roomId, 5);
        String lastEventId = ids.get(1).toString();

        List<StoredMessage> after = messageHistory.messagesAfter(roomId, lastEventId);

        assertEquals(3, after.size());
        assertEquals(ids.get(2), after.get(0).messageId());
        assertEquals(ids.get(3), after.get(1).messageId());
        assertEquals(ids.get(4), after.get(2).messageId());
    }

    /** Ist die letzte Nachricht schon die neueste, gibt es nichts nachzuholen. */
    @Test
    void returnsNothingAfterNewestId() {
        UUID roomId = UUID.randomUUID();
        List<UUID> ids = rememberMessages(roomId, 3);
        String lastEventId = ids.get(2).toString();

        List<StoredMessage> after = messageHistory.messagesAfter(roomId, lastEventId);

        assertEquals(0, after.size());
    }

    /** Unbekannte ID: alle gemerkten Nachrichten, so entschieden in der Spezifikation (S7). */
    @Test
    void returnsAllMessagesForUnknownId() {
        UUID roomId = UUID.randomUUID();
        rememberMessages(roomId, 4);
        String unknownId = UUID.randomUUID().toString();

        List<StoredMessage> after = messageHistory.messagesAfter(roomId, unknownId);

        assertEquals(4, after.size());
    }

    /** Nachrichten anderer Räume tauchen nie auf. */
    @Test
    void keepsRoomsSeparate() {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        rememberMessages(roomA, 3);
        String unknownId = UUID.randomUUID().toString();

        List<StoredMessage> inRoomB = messageHistory.messagesAfter(roomB, unknownId);

        assertEquals(0, inRoomB.size());
    }

    /** Höchstens 1000 pro Raum: bei 1005 Nachrichten sind die ersten 5 vergessen. */
    @Test
    void forgetsOldestBeyondLimit() {
        UUID roomId = UUID.randomUUID();
        List<UUID> ids = rememberMessages(roomId, MessageHistory.MAX_MESSAGES_PER_ROOM + 5);
        String unknownId = UUID.randomUUID().toString();

        List<StoredMessage> all = messageHistory.messagesAfter(roomId, unknownId);

        assertEquals(MessageHistory.MAX_MESSAGES_PER_ROOM, all.size());
        assertEquals(ids.get(5), all.get(0).messageId());
    }

    /** Merkt sich count Nachrichten und gibt ihre IDs in der Reihenfolge zurück. */
    private List<UUID> rememberMessages(UUID roomId, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID messageId = UUID.randomUUID();
            messageHistory.remember(roomId, messageId, "{\"content\":\"" + i + "\"}");
            ids.add(messageId);
        }
        return ids;
    }
}
