package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft, dass der Spring-Kontext mit echtem RabbitMQ und echtem
 * PostgreSQL hochfährt.
 *
 * Anders als beim chat-service braucht es hier beide Container schon für
 * den Start: die Datenbank-Verbindung wird beim Hochfahren geprüft.
 */
@SpringBootTest
@Testcontainers
class BatchWriterApplicationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Fährt der Kontext nicht hoch, wirft Spring eine Exception und der Test wird rot. */
    @Test
    void contextLoads() {
        // Kein Assert nötig.
    }
}
