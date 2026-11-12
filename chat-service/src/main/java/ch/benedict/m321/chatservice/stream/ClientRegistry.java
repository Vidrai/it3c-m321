package ch.benedict.m321.chatservice.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Die Liste dieser Instanz: welcher Client hängt an welchem Raum.
 *
 * Jede Instanz hat ihre eigene Liste, denn eine SSE-Verbindung hängt
 * immer an genau einer Instanz. RabbitMQ filtert nicht nach Raum. Kommt
 * eine Nachricht, schaut die Instanz hier nach, wer sie bekommt (S3).
 *
 * Warum ConcurrentHashMap und CopyOnWriteArrayList statt HashMap und
 * ArrayList: Clients verbinden sich auf den Threads des Webservers,
 * Nachrichten kommen auf dem Thread von RabbitMQ an. Beide greifen
 * gleichzeitig auf die Liste zu, und diese beiden Klassen halten das aus.
 */
@Component
@Slf4j
public class ClientRegistry {

    private final Map<UUID, List<SseEmitter>> clientsByRoom = new ConcurrentHashMap<>();

    /**
     * Merkt sich einen neuen Client im Raum.
     *
     * Zugleich wird festgelegt, dass er wieder von der Liste verschwindet,
     * sobald seine Verbindung endet, auf welchem Weg auch immer. Sonst
     * würde die Instanz weiter an tote Verbindungen schicken.
     */
    public void register(UUID roomId, SseEmitter client) {
        List<SseEmitter> clientsInRoom = clientsByRoom.computeIfAbsent(roomId, key -> new CopyOnWriteArrayList<>());
        clientsInRoom.add(client);

        client.onCompletion(() -> remove(roomId, client));
        client.onTimeout(() -> remove(roomId, client));
        client.onError(error -> remove(roomId, client));

        int clientCount = clientsInRoom.size();
        log.info("Client connected to room {}, now {} clients in this room", roomId, clientCount);
    }

    /**
     * Alle Clients, die gerade an diesem Raum hängen. Ist niemand da,
     * kommt eine leere Liste zurück, nie null.
     */
    public List<SseEmitter> clientsInRoom(UUID roomId) {
        List<SseEmitter> noClients = List.of();
        return clientsByRoom.getOrDefault(roomId, noClients);
    }

    /** Streicht einen Client von der Liste, wenn seine Verbindung endet. */
    private void remove(UUID roomId, SseEmitter client) {
        List<SseEmitter> clientsInRoom = clientsInRoom(roomId);
        clientsInRoom.remove(client);
        log.info("Client left room {}", roomId);
    }
}
