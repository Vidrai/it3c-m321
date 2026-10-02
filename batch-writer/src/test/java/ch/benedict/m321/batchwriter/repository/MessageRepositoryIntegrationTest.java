package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft den Schreibweg gegen ein echtes PostgreSQL, ohne RabbitMQ-Verkehr.
 *
 * Jeder Test benutzt eine eigene Raum-ID. So zählt jeder Test nur seine
 * eigenen Zeilen, obwohl alle Tests dieselbe Datenbank teilen.
 */
@SpringBootTest
@Testcontainers
class MessageRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Drei Nachrichten, ein Aufruf, drei Zeilen. Der Normalfall. */
    @Test
    void insertsAllMessagesOfBatch() {
        UUID roomId = UUID.randomUUID();
        ChatMessage first = createMessage(roomId, "eins");
        ChatMessage second = createMessage(roomId, "zwei");
        ChatMessage third = createMessage(roomId, "drei");
        List<ChatMessage> batch = List.of(first, second, third);

        int insertedCount = messageRepository.insertAll(batch);

        assertEquals(3, insertedCount);
        assertEquals(3, countMessagesInRoom(roomId));
    }

    /** Was hineingeht, kommt unverändert wieder heraus, auch der Zeitpunkt. */
    @Test
    void storesEveryFieldUnchanged() {
        UUID roomId = UUID.randomUUID();
        // PostgreSQL speichert Mikrosekunden. Feiner würde gerundet (Spec 4.2).
        Instant now = Instant.now();
        Instant sentAt = now.truncatedTo(ChronoUnit.MICROS);
        UUID messageId = UUID.randomUUID();
        ChatMessage message = new ChatMessage(messageId, roomId, "anna", "Anna Muster", "Grüezi 👋", sentAt);
        List<ChatMessage> batch = List.of(message);

        messageRepository.insertAll(batch);

        String sql = "SELECT * FROM message WHERE id = ?";
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, messageId);
        assertEquals(roomId, row.get("room_id"));
        assertEquals("anna", row.get("sender_id"));
        assertEquals("Anna Muster", row.get("sender_name"));
        assertEquals("Grüezi 👋", row.get("content"));
        OffsetDateTime storedSentAt = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM message WHERE id = ?", OffsetDateTime.class, messageId);
        Instant storedInstant = storedSentAt.toInstant();
        assertEquals(sentAt, storedInstant);
    }

    /**
     * S5 auf der Datenbankseite: dieselbe Nachricht in zwei Stapeln.
     * Der zweite Aufruf darf keinen Fehler werfen und fügt nichts ein.
     */
    @Test
    void ignoresDuplicateFromEarlierBatch() {
        UUID roomId = UUID.randomUUID();
        ChatMessage message = createMessage(roomId, "zweimal geschickt");
        List<ChatMessage> batch = List.of(message);

        int firstInsertedCount = messageRepository.insertAll(batch);
        int secondInsertedCount = messageRepository.insertAll(batch);

        assertEquals(1, firstInsertedCount);
        assertEquals(0, secondInsertedCount);
        assertEquals(1, countMessagesInRoom(roomId));
    }

    /**
     * Beide Kopien im SELBEN Stapel: die zweite Anweisung derselben
     * Transaktion sieht die erste Zeile und lässt sich weg.
     */
    @Test
    void ignoresDuplicateWithinSameBatch() {
        UUID roomId = UUID.randomUUID();
        ChatMessage message = createMessage(roomId, "doppelt im Stapel");
        List<ChatMessage> batch = List.of(message, message);

        int insertedCount = messageRepository.insertAll(batch);

        assertEquals(1, insertedCount);
        assertEquals(1, countMessagesInRoom(roomId));
    }

    /** Der Einzelweg für den Rückfall verhält sich gleich: 1 neu, 0 Duplikat. */
    @Test
    void insertOneIgnoresDuplicate() {
        UUID roomId = UUID.randomUUID();
        ChatMessage message = createMessage(roomId, "einzeln");

        int firstInsertedCount = messageRepository.insertOne(message);
        int secondInsertedCount = messageRepository.insertOne(message);

        assertEquals(1, firstInsertedCount);
        assertEquals(0, secondInsertedCount);
    }

    /** Zählt die Zeilen eines Raums. */
    private int countMessagesInRoom(UUID roomId) {
        String sql = "SELECT count(*) FROM message WHERE room_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, roomId);
        return count;
    }

    /** Baut eine vollständige Nachricht, damit die Tests kurz bleiben. */
    private ChatMessage createMessage(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", content, sentAt);
    }
}
