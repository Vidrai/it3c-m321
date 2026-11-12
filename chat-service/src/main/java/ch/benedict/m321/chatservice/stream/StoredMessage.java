package ch.benedict.m321.chatservice.stream;

import java.util.UUID;

/**
 * Eine Nachricht im Gedächtnis der Instanz: ihre ID und das JSON genau
 * so, wie es an die Clients ging. Mehr braucht es nicht, um sie einem
 * Client nach einem Abbruch noch einmal zu schicken (S7).
 *
 * @param messageId die Nachrichten-ID, die der Client als Last-Event-ID zurückschickt
 * @param json      die Nachricht als JSON, wie in chat.persist
 */
public record StoredMessage(UUID messageId, String json) {
}
