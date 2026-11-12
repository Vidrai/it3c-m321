package ch.benedict.m321.chatservice.stream;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Das Gedächtnis dieser Instanz: pro Raum die letzten 1000 Nachrichten,
 * in der Reihenfolge, in der sie ankamen.
 *
 * Warum im Speicher und nicht aus der Datenbank (Spec, S7): Die
 * Datenbank kann gestoppt sein (S5), und die IDs sind zufällige UUIDs,
 * aus denen sich die Reihenfolge nicht ablesen lässt. Hier ist die
 * Reihenfolge einfach die Reihenfolge der Liste.
 *
 * Jede Instanz bekommt über den Fanout alle Nachrichten. Darum hat jede
 * dasselbe Gedächtnis, und ein Client kann sich an jeder neu verbinden.
 *
 * Die Methoden sind synchronized, weil die Nachrichten auf dem Thread von
 * RabbitMQ hineinkommen und Neuverbindungen auf den Threads des Webservers
 * lesen.
 */
@Component
public class MessageHistory {

    /** So viele Nachrichten pro Raum merkt sich die Instanz höchstens. */
    public static final int MAX_MESSAGES_PER_ROOM = 1000;

    private final Map<UUID, Deque<StoredMessage>> messagesByRoom = new HashMap<>();

    /**
     * Merkt sich eine Nachricht. Ist der Raum schon bei 1000, wird die
     * älteste vergessen, damit der Speicher nicht endlos wächst.
     */
    public synchronized void remember(UUID roomId, UUID messageId, String json) {
        Deque<StoredMessage> messages = messagesByRoom.computeIfAbsent(roomId, key -> new ArrayDeque<>());
        StoredMessage message = new StoredMessage(messageId, json);
        messages.addLast(message);

        if (messages.size() > MAX_MESSAGES_PER_ROOM) {
            messages.removeFirst();
        }
    }

    /**
     * Alle Nachrichten eines Raums, die NACH der genannten ID kamen, in
     * ihrer Reihenfolge.
     *
     * Ist die ID nicht (mehr) im Gedächtnis, weil sie zu alt oder unbekannt
     * ist, kommen alle gemerkten Nachrichten zurück, also die letzten 1000
     * (so entschieden in der Spezifikation, S7). Der Client kann dann
     * einzelne Nachrichten doppelt bekommen, aber es fehlt ihm keine, die
     * die Instanz noch kennt.
     */
    public synchronized List<StoredMessage> messagesAfter(UUID roomId, String lastEventId) {
        Deque<StoredMessage> noMessages = new ArrayDeque<>();
        Deque<StoredMessage> messages = messagesByRoom.getOrDefault(roomId, noMessages);

        List<StoredMessage> after = new ArrayList<>();
        boolean lastEventFound = false;
        for (StoredMessage message : messages) {
            if (lastEventFound) {
                after.add(message);
            }
            String messageId = message.messageId().toString();
            if (messageId.equals(lastEventId)) {
                lastEventFound = true;
            }
        }

        if (!lastEventFound) {
            return new ArrayList<>(messages);
        }
        return after;
    }
}
