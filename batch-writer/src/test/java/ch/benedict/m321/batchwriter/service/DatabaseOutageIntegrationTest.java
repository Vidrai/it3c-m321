package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.QueueNames;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7: Postgres wird gestoppt, Nachrichten kommen trotzdem, Postgres kommt
 * zurück. Danach muss jede Nachricht in der Tabelle sein, ohne dass jemand
 * den batch-writer neu startet.
 *
 * Postgres wird hier WIRKLICH gestoppt und wieder gestartet, über den
 * Docker-Client. Damit der Container danach unter derselben Adresse
 * erreichbar ist, bekommt er einen festen Host-Port. Sonst würde Docker
 * beim Neustart einen neuen Port vergeben, und die Verbindungsdaten der
 * Anwendung zeigten ins Leere.
 *
 * Die Wartezeit zwischen zwei Versuchen ist im Test auf 500 ms verkürzt,
 * damit der Test nicht unnötig lange läuft.
 */
@SpringBootTest(properties = "batch-writer.db-retry-delay-ms=500")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DatabaseOutageIntegrationTest {

    /** Muss VOR dem Container stehen: Java füllt statische Felder von oben nach unten. */
    private static final int POSTGRES_HOST_PORT = findFreePort();

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCreateContainerCmdModifier(DatabaseOutageIntegrationTest::bindFixedHostPort);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Der eigentliche Test. Prüft drei Dinge:
     * alle 30 Nachrichten sind in der Tabelle, nichts liegt in der DLQ,
     * und der Weg dorthin war "warten und wiederholen", nicht die heisse
     * Schleife über basicNack.
     */
    @Test
    void storesAllMessagesAfterDatabaseComesBack(CapturedOutput output) throws InterruptedException {
        UUID roomId = UUID.randomUUID();
        DockerClient dockerClient = DockerClientFactory.instance().client();
        String containerId = postgres.getContainerId();

        dockerClient.stopContainerCmd(containerId).exec();

        for (int i = 1; i <= 30; i++) {
            Message delivery = createDelivery(roomId, "während des Ausfalls " + i);
            rabbitTemplate.send(QueueNames.PERSIST_QUEUE, delivery);
        }

        // Postgres bleibt eine Weile weg. In dieser Zeit muss der
        // batch-writer warten, statt Nachrichten zu verlieren.
        Thread.sleep(5000);
        dockerClient.startContainerCmd(containerId).exec();

        // ignoreExceptions: Solange Postgres hochfährt, scheitert auch
        // unsere Zählabfrage. Das ist hier erwartet, nicht ein Fehler.
        await().atMost(Duration.ofSeconds(90))
                .ignoreExceptions()
                .until(() -> countMessagesInRoom(roomId) == 30);

        assertEquals(0, countMessagesInQueue(QueueNames.DEAD_LETTER_QUEUE));

        String log = output.getOut();
        assertTrue(log.contains("Database not available"), "Der Wiederholungspfad ist nie gelaufen");
        assertFalse(log.contains("Unexpected error"), "Der Stapel ist über basicNack im Kreis gelaufen");
    }

    /**
     * Ersetzt die zufällige Port-Zuordnung von Testcontainers durch eine
     * feste. Wird aufgerufen, kurz bevor Docker den Container anlegt.
     */
    private static void bindFixedHostPort(CreateContainerCmd command) {
        ExposedPort containerPort = ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT);
        Ports.Binding hostPort = Ports.Binding.bindPort(POSTGRES_HOST_PORT);
        PortBinding portBinding = new PortBinding(hostPort, containerPort);
        HostConfig hostConfig = command.getHostConfig();
        hostConfig.withPortBindings(portBinding);
    }

    /** Fragt das Betriebssystem nach einem gerade freien Port. */
    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
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

    /** Wie viele Nachrichten bereit in einer Queue liegen. */
    private int countMessagesInQueue(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        return information.getMessageCount();
    }
}
