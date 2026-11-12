package ch.benedict.m321.chatservice.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Schreibt eine Nachricht in die offenen Streams der Clients eines Raums.
 *
 * Jedes Ereignis hat zwei Zeilen: "id:" mit der Nachrichten-ID und
 * "data:" mit der Nachricht als JSON, genau so, wie sie in der Queue lag.
 * Die id braucht der Client, um nach einem Abbruch zu sagen, welche
 * Nachricht er zuletzt hatte (Last-Event-ID, S7).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiveDelivery {

    private final ClientRegistry clientRegistry;

    /**
     * Schickt die Nachricht an alle Clients ihres Raums, und nur an diese.
     * Clients in anderen Räumen bekommen nichts (S3).
     */
    public void deliver(UUID roomId, UUID messageId, String json) {
        List<SseEmitter> clientsInRoom = clientRegistry.clientsInRoom(roomId);
        for (SseEmitter client : clientsInRoom) {
            sendTo(client, messageId, json);
        }
    }

    /**
     * Schickt eine Nachricht an genau einen Client.
     *
     * Ist seine Verbindung inzwischen weg, schlägt das Schreiben fehl. Dann
     * schliessen wir den Stream; die ClientRegistry streicht ihn daraufhin
     * von der Liste. Ein toter Client darf die anderen nicht aufhalten.
     */
    public void sendTo(SseEmitter client, UUID messageId, String json) {
        String eventId = messageId.toString();
        SseEventBuilder event = SseEmitter.event().id(eventId).data(json);
        try {
            client.send(event);
        } catch (IOException | IllegalStateException exception) {
            log.debug("Client connection lost, closing stream: {}", exception.getMessage());
            client.completeWithError(exception);
        }
    }
}
