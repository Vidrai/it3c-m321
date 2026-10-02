package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der ganze Weg im Kleinen: Nachricht in chat.persist legen, warten,
 * Zeile in der Tabelle finden.
 *
 * Die Nachrichten werden ROH in die Queue gelegt, als JSON-Text mit
 * content_type, genau wie das Prüfskript der Bewertung es tut. So prüft
 * der Test den Vertrag und nicht unsere eigene Java-Klasse.
 */
@SpringBootTest
@Testcontainers
class PersistQueueListenerIntegrationTest {

    /** Zeitpunkt aus dem echten Beispiel in Spec 2.2. */
    private static final String SENT_AT = "2026-10-02T12:53:49.852631700Z";

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Die Dead-Letter-Queue überlebt die einzelne Testmethode. Ohne Leeren
     * würde ein Test die abgelehnte Nachricht des vorherigen mitzählen.
     */
    @BeforeEach
    void emptyDeadLetterQueue() {
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE);
    }

    /**
     * S3 im Kleinen: 50 Nachrichten im Format des chat-service, mit dem
     * fremden Header __TypeId__. Alle landen in der Tabelle, die Queue ist
     * danach leer.
     */
    @Test
    void storesMessagesInChatServiceFormat() {
        UUID roomId = UUID.randomUUID();
        for (int i = 1; i <= 50; i++) {
            UUID messageId = UUID.randomUUID();
            String json = createJson(messageId, roomId, "Nachricht " + i);
            Message delivery = createDelivery(json);
            MessageProperties properties = delivery.getMessageProperties();
            properties.setHeader("__TypeId__", "ch.benedict.m321.chatservice.dto.ChatMessage");
            rabbitTemplate.send(QueueNames.PERSIST_QUEUE, delivery);
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> countMessagesInRoom(roomId) == 50);
        await().atMost(Duration.ofSeconds(10)).until(() -> countMessagesInQueue(QueueNames.PERSIST_QUEUE) == 0);
    }

    /**
     * S5 genau wie in der Bewertung: dieselbe Nachricht zweimal, nur mit
     * content_type. Ergebnis: eine Zeile, und nichts in chat.dlq. Ein
     * Duplikat ist kein Fehler, die Nachricht ist ja gespeichert.
     */
    @Test
    void storesDuplicateOnlyOnceAndDoesNotDeadLetterIt() {
        UUID roomId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String json = createJson(messageId, roomId, "zweimal");

        Message firstDelivery = createDelivery(json);
        Message secondDelivery = createDelivery(json);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, firstDelivery);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, secondDelivery);

        await().atMost(Duration.ofSeconds(30)).until(() -> countMessagesInRoom(roomId) == 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> countMessagesInQueue(QueueNames.PERSIST_QUEUE) == 0);
        assertEquals(1, countMessagesInRoom(roomId));
        assertEquals(0, countMessagesInQueue(QueueNames.DEAD_LETTER_QUEUE));
    }

    /**
     * Eine kaputte Nachricht zwischen zwei gültigen: Die gültigen werden
     * gespeichert, die kaputte landet in chat.dlq, und zwar unverändert.
     */
    @Test
    void sendsInvalidMessageToDeadLetterQueueAndStoresTheOthers() {
        UUID roomId = UUID.randomUUID();
        String firstJson = createJson(UUID.randomUUID(), roomId, "gültig vorher");
        String secondJson = createJson(UUID.randomUUID(), roomId, "gültig nachher");
        String brokenJson = "{ das ist kein JSON";

        Message firstDelivery = createDelivery(firstJson);
        Message brokenDelivery = createDelivery(brokenJson);
        Message secondDelivery = createDelivery(secondJson);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, firstDelivery);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, brokenDelivery);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, secondDelivery);

        await().atMost(Duration.ofSeconds(30)).until(() -> countMessagesInRoom(roomId) == 2);
        await().atMost(Duration.ofSeconds(10)).until(() -> countMessagesInQueue(QueueNames.DEAD_LETTER_QUEUE) == 1);

        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, 5000);
        assertNotNull(deadLetter);
        String deadLetterBody = new String(deadLetter.getBody(), StandardCharsets.UTF_8);
        assertEquals(brokenJson, deadLetterBody);
    }

    /**
     * Gültiges JSON, das die Datenbank trotzdem ablehnt: ein Nullzeichen
     * im Text. PostgreSQL erlaubt das in TEXT nicht. Der Stapel wird dann
     * einzeln geschrieben, nur diese eine Nachricht geht in die DLQ
     * (Spec 3.6).
     *
     * Im JSON steht das Nullzeichen als Escape-Folge aus sechs Zeichen,
     * Backslash, u, 0, 0, 0, 0. Jackson macht daraus beim Lesen das
     * Zeichen mit dem Code 0.
     */
    @Test
    void sendsMessageRefusedByDatabaseToDeadLetterQueueAndStoresTheOthers() {
        UUID roomId = UUID.randomUUID();
        UUID refusedMessageId = UUID.randomUUID();
        String firstJson = createJson(UUID.randomUUID(), roomId, "gültig vorher");
        String refusedJson = createJson(refusedMessageId, roomId, "kaputt\\u0000");
        String secondJson = createJson(UUID.randomUUID(), roomId, "gültig nachher");

        Message firstDelivery = createDelivery(firstJson);
        Message refusedDelivery = createDelivery(refusedJson);
        Message secondDelivery = createDelivery(secondJson);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, firstDelivery);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, refusedDelivery);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, secondDelivery);

        await().atMost(Duration.ofSeconds(30)).until(() -> countMessagesInRoom(roomId) == 2);
        await().atMost(Duration.ofSeconds(10)).until(() -> countMessagesInQueue(QueueNames.DEAD_LETTER_QUEUE) == 1);

        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, 5000);
        assertNotNull(deadLetter);
        String deadLetterBody = new String(deadLetter.getBody(), StandardCharsets.UTF_8);
        String refusedId = refusedMessageId.toString();
        assertTrue(deadLetterBody.contains(refusedId), "Falsche Nachricht in der DLQ: " + deadLetterBody);
    }

    /** Baut den JSON-Text, wie ihn der chat-service in die Queue legt (Spec 2.2). */
    private String createJson(UUID messageId, UUID roomId, String content) {
        return """
                {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna Muster","content":"%s","sentAt":"%s"}
                """.formatted(messageId, roomId, content, SENT_AT);
    }

    /** Eine rohe AMQP-Nachricht, nur mit content_type, wie im Prüfskript. */
    private Message createDelivery(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return new Message(body, properties);
    }

    /** Zählt die Zeilen eines Raums. */
    private int countMessagesInRoom(UUID roomId) {
        String sql = "SELECT count(*) FROM message WHERE room_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, roomId);
        return count;
    }

    /** Wie viele Nachrichten bereit in einer Queue liegen, so wie rabbitmqctl sie zählt. */
    private int countMessagesInQueue(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        return information.getMessageCount();
    }
}
