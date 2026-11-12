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
 * Schreibt Nachrichten in die offenen Streams der Clients eines Raums,
 * live und beim Nachholen nach einem Abbruch.
 *
 * Jedes Ereignis hat zwei Zeilen: "id:" mit der Nachrichten-ID und
 * "data:" mit der Nachricht als JSON, genau so, wie sie in der Queue lag.
 * Die id braucht der Client, um nach einem Abbruch zu sagen, welche
 * Nachricht er zuletzt hatte (Last-Event-ID, S7).
 *
 * Warum deliver und connect synchronized sind: Verbindet sich ein Client
 * neu, während gerade eine Nachricht ankommt, könnte sie sonst doppelt
 * kommen (nachgeholt UND live) oder ganz fehlen (weder noch). Mit der
 * Sperre passiert immer eins nach dem anderen.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiveDelivery {

    private final ClientRegistry clientRegistry;

    private final MessageHistory messageHistory;

    /**
     * Merkt sich die Nachricht für spätere Neuverbindungen und schickt sie
     * an alle Clients ihres Raums, und nur an diese (S3).
     */
    public synchronized void deliver(UUID roomId, UUID messageId, String json) {
        messageHistory.remember(roomId, messageId, json);

        List<SseEmitter> clientsInRoom = clientRegistry.clientsInRoom(roomId);
        for (SseEmitter client : clientsInRoom) {
            sendTo(client, messageId, json);
        }
    }

    /**
     * Meldet einen Client im Raum an. Hat er eine Last-Event-ID
     * mitgeschickt, bekommt er zuerst alles, was er verpasst hat, in der
     * richtigen Reihenfolge, und erst dann geht es live weiter (S7).
     */
    public synchronized void connect(UUID roomId, SseEmitter client, String lastEventId) {
        boolean isReconnect = lastEventId != null && !lastEventId.isBlank();
        if (isReconnect) {
            List<StoredMessage> missedMessages = messageHistory.messagesAfter(roomId, lastEventId);
            for (StoredMessage missedMessage : missedMessages) {
                sendTo(client, missedMessage.messageId(), missedMessage.json());
            }
            int missedCount = missedMessages.size();
            log.info("Client reconnected to room {} after {}, sent {} missed messages", roomId, lastEventId, missedCount);
        }

        clientRegistry.register(roomId, client);
    }

    /**
     * Schickt eine Nachricht an genau einen Client.
     *
     * Ist seine Verbindung inzwischen weg, schlägt das Schreiben fehl. Dann
     * schliessen wir den Stream; die ClientRegistry streicht ihn daraufhin
     * von der Liste. Ein toter Client darf die anderen nicht aufhalten.
     */
    private void sendTo(SseEmitter client, UUID messageId, String json) {
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
