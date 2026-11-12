package ch.benedict.m321.chatservice.stream;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S3 im Kleinen: zwei echte chat-service-Instanzen am selben RabbitMQ.
 *
 * Instanz 1 ist die, die Spring für den Test startet. Instanz 2 starten
 * wir zusätzlich von Hand, mit eigenem Port. Beide legen ihre eigene
 * Queue am Fanout an, genau wie zwei Container im Stack.
 *
 * Geprüft wird: Ein Client in Raum A an jeder Instanz bekommt alle
 * Nachrichten, jede genau einmal, die eines Absenders in seiner
 * Reihenfolge. Ein Client in Raum B bekommt nichts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TwoInstanceDeliveryIntegrationTest {

    /** Pro Instanz so viele Nachrichten. Im Szenario sind es 200, im Test reichen weniger. */
    private static final int MESSAGES_PER_INSTANCE = 20;

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    private static ConfigurableApplicationContext secondInstance;

    @LocalServerPort
    private int firstPort;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Die zweite Instanz wieder beenden, sonst liefe sie nach dem Test weiter. */
    @AfterAll
    static void stopSecondInstance() {
        if (secondInstance != null) {
            secondInstance.close();
        }
    }

    /** Der eigentliche S3-Test. */
    @Test
    void bothClientsInRoomGetEveryMessageOnceAndInOrder() throws Exception {
        secondInstance = startSecondInstance();
        int secondPort = portOf(secondInstance);
        await().atMost(Duration.ofSeconds(30)).until(() -> countDeliveryConsumers() == 2);

        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        SseTestClient clientAtFirst = SseTestClient.connect(firstPort, roomA, null);
        SseTestClient clientAtSecond = SseTestClient.connect(secondPort, roomA, null);
        SseTestClient clientInRoomB = SseTestClient.connect(firstPort, roomB, null);

        for (int i = 0; i < MESSAGES_PER_INSTANCE; i++) {
            sendMessage(firstPort, roomA, "erste " + i);
            sendMessage(secondPort, roomA, "zweite " + i);
        }

        int expectedCount = 2 * MESSAGES_PER_INSTANCE;
        await().atMost(Duration.ofSeconds(30)).until(() -> clientAtFirst.dataLines().size() == expectedCount);
        await().atMost(Duration.ofSeconds(30)).until(() -> clientAtSecond.dataLines().size() == expectedCount);

        assertEquals(0, clientInRoomB.dataLines().size());
        assertEveryMessageOnce(clientAtFirst, expectedCount);
        assertEveryMessageOnce(clientAtSecond, expectedCount);
        assertSenderOrder(clientAtFirst, "erste ");
        assertSenderOrder(clientAtFirst, "zweite ");
        assertSenderOrder(clientAtSecond, "erste ");
        assertSenderOrder(clientAtSecond, "zweite ");
    }

    /**
     * Startet eine zweite Instanz derselben Anwendung, am selben RabbitMQ,
     * auf einem freien Port (server.port=0).
     */
    private ConfigurableApplicationContext startSecondInstance() {
        SpringApplication application = new SpringApplication(ChatServiceApplication.class);
        return application.run(
                "--server.port=0",
                "--spring.rabbitmq.host=" + rabbitMq.getHost(),
                "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbitMq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitMq.getAdminPassword());
    }

    /** Der Port, auf dem eine selbst gestartete Instanz lauscht. */
    private int portOf(ConfigurableApplicationContext instance) {
        WebServerApplicationContext webContext = (WebServerApplicationContext) instance;
        return webContext.getWebServer().getPort();
    }

    /**
     * Wie viele Queues "chat.delivery.…" gerade einen Abholer haben. Erst wenn
     * beide Instanzen an ihrer Queue hängen, darf gesendet werden.
     */
    private int countDeliveryConsumers() throws Exception {
        ExecResult result = rabbitMq.execInContainer("rabbitmqctl", "list_queues", "-q", "name", "consumers");
        String output = result.getStdout();
        String[] lines = output.split("\n");

        int count = 0;
        for (String line : lines) {
            if (line.startsWith("chat.delivery.") && line.endsWith("\t1")) {
                count = count + 1;
            }
        }
        return count;
    }

    /** Schickt eine Nachricht über POST /messages an eine bestimmte Instanz. */
    private void sendMessage(int port, UUID roomId, String content) throws Exception {
        String json = """
                {"roomId":"%s","senderId":"anna","senderName":"Anna","content":"%s"}
                """.formatted(roomId, content);
        URI uri = URI.create("http://localhost:" + port + "/messages");
        HttpRequest.BodyPublisher requestBody = HttpRequest.BodyPublishers.ofString(json);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(requestBody)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
    }

    /** Jede Nachrichten-ID kommt genau einmal vor, also keine doppelt. */
    private void assertEveryMessageOnce(SseTestClient client, int expectedCount) {
        List<String> eventIds = client.eventIds();
        Set<String> distinctIds = new HashSet<>(eventIds);
        assertEquals(expectedCount, eventIds.size());
        assertEquals(expectedCount, distinctIds.size());
    }

    /**
     * Die Nachrichten eines Absenders (hier: alle, die mit einem Präfix
     * beginnen) kommen in der Reihenfolge an, in der sie geschickt wurden.
     */
    private void assertSenderOrder(SseTestClient client, String prefix) throws Exception {
        List<String> contentsInArrivalOrder = new ArrayList<>();
        for (String data : client.dataLines()) {
            ChatMessage message = objectMapper.readValue(data, ChatMessage.class);
            String content = message.content();
            if (content.startsWith(prefix)) {
                contentsInArrivalOrder.add(content);
            }
        }

        List<String> expectedOrder = new ArrayList<>();
        for (int i = 0; i < MESSAGES_PER_INSTANCE; i++) {
            expectedOrder.add(prefix + i);
        }
        assertEquals(expectedOrder, contentsInArrivalOrder);
    }
}
