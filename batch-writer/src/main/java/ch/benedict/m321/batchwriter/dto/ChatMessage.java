package ch.benedict.m321.batchwriter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie in chat.persist liegt.
 *
 * Das ist die EIGENE Kopie des batch-writer, mit denselben Feldnamen wie
 * ChatMessage im chat-service. Der Vertrag zwischen den beiden Diensten
 * ist das JSON, nicht eine gemeinsame Java-Klasse (Spec 2.2).
 *
 * ignoreUnknown: Schickt der chat-service später ein zusätzliches Feld,
 * wird es übersprungen, statt die Nachricht abzulehnen. Das macht den
 * batch-writer zu einem toleranten Leser (Spec 2.4).
 *
 * @param id         die vom chat-service vergebene Nachrichten-ID, unser Primärschlüssel
 * @param roomId     der Raum, in den die Nachricht gehört
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename, damit die Historie lesbar bleibt
 * @param content    der Text der Nachricht
 * @param sentAt     der Zeitpunkt, den der chat-service gesetzt hat
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
