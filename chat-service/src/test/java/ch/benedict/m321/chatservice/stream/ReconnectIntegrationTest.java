package ch.benedict.m321.chatservice.stream;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * S7 im Kleinen: Ein Client trennt nach einer Nachricht, 50 Nachrichten
 * kommen, dann verbindet er sich mit Last-Event-ID neu.
 *
 * Erwartet: genau die 50 fehlenden, in ihrer Reihenfolge, keine doppelt,
 * und danach geht es wieder live weiter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconnectIntegrationTest {

    private static final int MISSED_COUNT = 50;

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageHistory messageHistory;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Der eigentliche S7-Test. */
    @Test
    void reconnectGetsExactlyTheMissedMessagesThenLiveAgain() throws Exception {
        UUID roomId = UUID.randomUUID();

        // 1. Verbinden und genau eine Nachricht bekommen
        SseTestClient firstConnection = SseTestClient.connect(port, roomId, null);
        sendMessage(roomId, "vorher");
        await().atMost(Duration.ofSeconds(30)).until(() -> firstConnection.eventIds().size() == 1);
        String lastEventId = firstConnection.eventIds().get(0);

        // 2. Trennen, dann kommen 50 Nachrichten, während der Client weg ist
        firstConnection.close();
        for (int i = 0; i < MISSED_COUNT; i++) {
            sendMessage(roomId, "verpasst " + i);
        }
        await().atMost(Duration.ofSeconds(30))
                .until(() -> messageHistory.messagesAfter(roomId, lastEventId).size() == MISSED_COUNT);

        // 3. Mit Last-Event-ID neu verbinden: genau die 50, in Reihenfolge, keine doppelt
        SseTestClient secondConnection = SseTestClient.connect(port, roomId, lastEventId);
        await().atMost(Duration.ofSeconds(30)).until(() -> secondConnection.dataLines().size() == MISSED_COUNT);

        List<String> expectedContents = new ArrayList<>();
        for (int i = 0; i < MISSED_COUNT; i++) {
            expectedContents.add("verpasst " + i);
        }
        assertEquals(expectedContents, contentsOf(secondConnection));
        List<String> eventIds = secondConnection.eventIds();
        Set<String> distinctIds = new HashSet<>(eventIds);
        assertEquals(MISSED_COUNT, distinctIds.size());

        // 4. Danach wieder live
        sendMessage(roomId, "danach live");
        await().atMost(Duration.ofSeconds(30)).until(() -> secondConnection.dataLines().size() == MISSED_COUNT + 1);
        List<String> contents = contentsOf(secondConnection);
        String lastContent = contents.get(MISSED_COUNT);
        assertEquals("danach live", lastContent);
    }

    /** Die Texte aller Nachrichten, die ein Client bekommen hat, in Ankunftsreihenfolge. */
    private List<String> contentsOf(SseTestClient client) throws Exception {
        List<String> contents = new ArrayList<>();
        for (String data : client.dataLines()) {
            ChatMessage message = objectMapper.readValue(data, ChatMessage.class);
            contents.add(message.content());
        }
        return contents;
    }

    /** Schickt eine Nachricht über POST /messages. */
    private void sendMessage(UUID roomId, String content) throws Exception {
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
}
