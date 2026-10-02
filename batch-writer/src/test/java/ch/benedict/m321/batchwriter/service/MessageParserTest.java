package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den Vertrag aus Spec 2.2 bis 2.4, ganz ohne Docker.
 *
 * Der ObjectMapper kommt aus demselben Builder, den auch Spring Boot
 * benutzt. Zum Lesen hat er damit dieselben Voreinstellungen wie im
 * laufenden Dienst.
 */
class MessageParserTest {

    /** Echte Ausgabe des chat-service, siehe Spec 2.2. */
    private static final String CHAT_SERVICE_JSON = """
            {"id":"49079faf-750a-463e-a8d7-f42d0235a307",
             "roomId":"b9956566-5ff2-483b-a258-e1b49b4e776f",
             "senderId":"anna",
             "senderName":"Anna Muster",
             "content":"Hallo",
             "sentAt":"2026-10-02T12:53:49.852631700Z"}
            """;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private final MessageParser messageParser = new MessageParser(objectMapper);

    /** Der wichtigste Fall: genau das, was der chat-service schickt, inklusive Nanosekunden. */
    @Test
    void readsMessageInChatServiceFormat() throws InvalidMessageException {
        Message delivery = createDelivery(CHAT_SERVICE_JSON);

        ChatMessage message = messageParser.parse(delivery);

        UUID expectedId = UUID.fromString("49079faf-750a-463e-a8d7-f42d0235a307");
        UUID expectedRoomId = UUID.fromString("b9956566-5ff2-483b-a258-e1b49b4e776f");
        Instant expectedSentAt = Instant.parse("2026-10-02T12:53:49.852631700Z");
        assertEquals(expectedId, message.id());
        assertEquals(expectedRoomId, message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("Hallo", message.content());
        assertEquals(expectedSentAt, message.sentAt());
    }

    /**
     * Ein ObjectMapper ohne Spring Boot schreibt Instant als Zahl
     * (Sekunden.Nanosekunden). Auch das muss gehen (Spec 2.2, Punkt 2).
     */
    @Test
    void readsSentAtWrittenAsNumber() throws InvalidMessageException {
        String json = CHAT_SERVICE_JSON.replace("\"2026-10-02T12:53:49.852631700Z\"", "1790945611.436501400");
        Message delivery = createDelivery(json);

        ChatMessage message = messageParser.parse(delivery);

        Instant expectedSentAt = Instant.ofEpochSecond(1790945611L, 436501400L);
        assertEquals(expectedSentAt, message.sentAt());
    }

    /**
     * So legt das Prüfskript in S5 die Nachricht in die Queue: nur
     * content_type, kein __TypeId__. Das darf keinen Unterschied machen.
     */
    @Test
    void readsMessageWithoutTypeIdHeader() throws InvalidMessageException {
        Message delivery = createDelivery(CHAT_SERVICE_JSON);
        Object typeIdHeader = delivery.getMessageProperties().getHeader("__TypeId__");
        assertNull(typeIdHeader);

        ChatMessage message = messageParser.parse(delivery);

        assertEquals("Hallo", message.content());
    }

    /** Ein neues Feld im chat-service darf den batch-writer nicht umwerfen. */
    @Test
    void ignoresUnknownField() throws InvalidMessageException {
        String json = CHAT_SERVICE_JSON.replace("\"content\":\"Hallo\",", "\"content\":\"Hallo\",\"priority\":\"high\",");
        Message delivery = createDelivery(json);

        ChatMessage message = messageParser.parse(delivery);

        assertEquals("Hallo", message.content());
    }

    /** Kein JSON: daraus wird nie eine Zeile, also ungültig. */
    @Test
    void rejectsBodyThatIsNotJson() {
        Message delivery = createDelivery("das ist kein JSON");

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(delivery));
    }

    /** Der JSON-Wert null ist gültiges JSON, aber keine Nachricht. */
    @Test
    void rejectsJsonNull() {
        Message delivery = createDelivery("null");

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(delivery));
    }

    /** Fehlt ein Feld, nennt die Meldung genau dieses Feld. So findet man den Fehler im Log. */
    @Test
    void rejectsMissingFieldAndNamesIt() {
        String json = CHAT_SERVICE_JSON.replace("\"senderName\":\"Anna Muster\",", "");
        Message delivery = createDelivery(json);

        InvalidMessageException exception =
                assertThrows(InvalidMessageException.class, () -> messageParser.parse(delivery));

        String reason = exception.getMessage();
        assertTrue(reason.contains("senderName"), "Meldung nennt das Feld nicht: " + reason);
    }

    /** Die Spalte id ist vom Typ UUID. Eine andere Zeichenkette passt da nicht hinein. */
    @Test
    void rejectsIdThatIsNotUuid() {
        String json = CHAT_SERVICE_JSON.replace("49079faf-750a-463e-a8d7-f42d0235a307", "nachricht-1");
        Message delivery = createDelivery(json);

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(delivery));
    }

    /** sender_name ist VARCHAR(255). 256 Zeichen sind eins zu viel. */
    @Test
    void rejectsSenderNameLongerThan255Characters() {
        String tooLongName = "a".repeat(256);
        String json = CHAT_SERVICE_JSON.replace("Anna Muster", tooLongName);
        Message delivery = createDelivery(json);

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(delivery));
    }

    /** Genau 255 Zeichen passen noch, die Grenze selbst ist erlaubt. */
    @Test
    void acceptsSenderNameWithExactly255Characters() throws InvalidMessageException {
        String longestName = "a".repeat(255);
        String json = CHAT_SERVICE_JSON.replace("Anna Muster", longestName);
        Message delivery = createDelivery(json);

        ChatMessage message = messageParser.parse(delivery);

        assertEquals(longestName, message.senderName());
    }

    /** Baut eine AMQP-Nachricht wie im Prüfskript: nur mit content_type. */
    private Message createDelivery(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return new Message(body, properties);
    }
}
