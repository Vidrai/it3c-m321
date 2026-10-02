package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Macht aus einer rohen AMQP-Nachricht eine ChatMessage und prüft, ob die
 * Datenbank sie annehmen wird.
 *
 * Warum wir das selbst machen, statt Spring den Body umwandeln zu lassen:
 * Spring würde dafür den Header __TypeId__ lesen. Der nennt eine Klasse
 * aus dem chat-service, die es hier nicht gibt, und in Szenario S5 fehlt
 * er ganz. Bei uns steht der Zieltyp im Code, nicht im Header (Spec 2.3).
 */
@Component
@RequiredArgsConstructor
public class MessageParser {

    /** So lang dürfen sender_id und sender_name höchstens sein: VARCHAR(255). */
    private static final int MAX_SENDER_LENGTH = 255;

    /** Der ObjectMapper von Spring Boot. Er kann Instant aus Text und aus Zahlen lesen. */
    private final ObjectMapper objectMapper;

    /**
     * Liest den Body als JSON und prüft die Regeln aus Spec 2.4.
     *
     * Header werden absichtlich nicht angeschaut: content_type und
     * __TypeId__ dürfen fehlen, der Body allein entscheidet.
     *
     * @throws InvalidMessageException wenn sich die Nachricht nie speichern lassen wird
     */
    public ChatMessage parse(Message delivery) throws InvalidMessageException {
        byte[] body = delivery.getBody();
        ChatMessage message = readJson(body);

        checkRequiredFields(message);
        checkMaxLength("senderId", message.senderId());
        checkMaxLength("senderName", message.senderName());

        return message;
    }

    /**
     * Wandelt den Body in eine ChatMessage um.
     *
     * Jackson wirft eine IOException, wenn der Body kein JSON ist oder ein
     * Feld den falschen Typ hat, zum Beispiel eine id, die keine UUID ist.
     */
    private ChatMessage readJson(byte[] body) throws InvalidMessageException {
        ChatMessage message;
        try {
            message = objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            throw new InvalidMessageException("body is not a valid chat message: " + exception.getMessage());
        }

        // Der Body "null" ist gültiges JSON, ergibt aber kein Objekt.
        if (message == null) {
            throw new InvalidMessageException("body is the JSON value null");
        }
        return message;
    }

    /** Jede Spalte der Tabelle message ist NOT NULL, also muss jedes Feld da sein. */
    private void checkRequiredFields(ChatMessage message) throws InvalidMessageException {
        checkPresent("id", message.id());
        checkPresent("roomId", message.roomId());
        checkPresent("senderId", message.senderId());
        checkPresent("senderName", message.senderName());
        checkPresent("content", message.content());
        checkPresent("sentAt", message.sentAt());
    }

    /** Ein einzelnes Feld prüfen. Der Feldname steht in der Meldung, damit man im Log sieht, was fehlt. */
    private void checkPresent(String fieldName, Object value) throws InvalidMessageException {
        if (value == null) {
            throw new InvalidMessageException("field " + fieldName + " is missing");
        }
    }

    /**
     * Zu lange Texte würde PostgreSQL ablehnen. Wir merken das lieber hier,
     * bei der einzelnen Nachricht, als später beim ganzen Stapel.
     *
     * codePointCount statt length(): PostgreSQL zählt Zeichen, Java zählt
     * bei length() UTF-16-Einheiten. Ein Emoji wäre sonst zwei "Zeichen".
     */
    private void checkMaxLength(String fieldName, String value) throws InvalidMessageException {
        int length = value.codePointCount(0, value.length());
        if (length > MAX_SENDER_LENGTH) {
            throw new InvalidMessageException(
                    "field " + fieldName + " has " + length + " characters, maximum is " + MAX_SENDER_LENGTH);
        }
    }
}
