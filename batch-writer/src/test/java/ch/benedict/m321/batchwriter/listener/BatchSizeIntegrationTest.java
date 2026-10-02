package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
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
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4: Der batch-writer war weg, 1000 Nachrichten warten. Beim Start
 * müssen sie in wenigen grossen Stapeln geschrieben werden, nicht in
 * 1000 einzelnen Transaktionen.
 *
 * Gezählt wird mit xmin. Das ist eine versteckte Spalte, die PostgreSQL
 * in jeder Zeile führt: die Nummer der Transaktion, die die Zeile
 * geschrieben hat. Wie viele verschiedene xmin-Werte die 1000 Zeilen
 * haben, ist also genau die Zahl der Schreib-Transaktionen.
 */
@SpringBootTest
@Testcontainers
class BatchSizeIntegrationTest {

    /** Rechnerisch sind es 2 (1000 / 500). Etwas Luft, falls RabbitMQ langsamer liefert als gesammelt wird. */
    private static final int MAX_WRITING_TRANSACTIONS = 10;

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
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Listener anhalten (wie "docker compose stop batch-writer"),
     * 1000 Nachrichten einreihen, Listener starten, zählen.
     */
    @Test
    void stores1000WaitingMessagesInFewTransactions() {
        UUID roomId = UUID.randomUUID();
        rabbitListenerEndpointRegistry.stop();

        for (int i = 1; i <= 1000; i++) {
            Message delivery = createDelivery(roomId, "wartet " + i);
            rabbitTemplate.send(QueueNames.PERSIST_QUEUE, delivery);
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> countMessagesInQueue(QueueNames.PERSIST_QUEUE) == 1000);

        rabbitListenerEndpointRegistry.start();

        await().atMost(Duration.ofSeconds(60)).until(() -> countMessagesInRoom(roomId) == 1000);

        int writingTransactions = countWritingTransactions(roomId);
        assertTrue(writingTransactions <= MAX_WRITING_TRANSACTIONS,
                "1000 Nachrichten brauchten " + writingTransactions + " Transaktionen");
    }

    /** Eine rohe Nachricht im Format des chat-service, nur mit content_type. */
    private Message createDelivery(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        String json = """
                {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna Muster","content":"%s","sentAt":"2026-10-02T12:53:49.852631700Z"}
                """.formatted(messageId, roomId, content);
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

    /**
     * Zählt die verschiedenen Transaktionen, die die Zeilen eines Raums
     * geschrieben haben. Der Typ von xmin hat keinen Sortieroperator, den DISTINCT braucht,
     * darum der Umweg über Text.
     */
    private int countWritingTransactions(UUID roomId) {
        String sql = "SELECT count(DISTINCT xmin::text) FROM message WHERE room_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, roomId);
        return count;
    }

    /** Wie viele Nachrichten bereit in einer Queue liegen. */
    private int countMessagesInQueue(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        return information.getMessageCount();
    }
}
