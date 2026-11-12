package ch.benedict.m321.chatservice.stream;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.UUID;

/**
 * Der Stream, an dem die Clients hängen: GET /stream?roomId=…
 *
 * Server-Sent Events heisst: Der Client öffnet eine HTTP-Verbindung, und
 * der Server lässt sie offen. Jede neue Nachricht schreibt er als Ereignis
 * hinein (Content-Type text/event-stream). Der Client fragt also nicht
 * ständig nach, der Server schiebt.
 */
@RestController
@RequiredArgsConstructor
public class StreamController {

    /** 0 heisst: Die Verbindung läuft nie von selbst ab. Ein Chat bleibt offen. */
    private static final long NO_TIMEOUT = 0L;

    private final ClientRegistry clientRegistry;

    /**
     * Öffnet den Stream für einen Raum und merkt sich den Client.
     *
     * Der Kommentar "connected" ist für SSE-Clients unsichtbar. Er sorgt
     * nur dafür, dass sofort etwas über die Leitung geht und der Client
     * sieht, dass die Verbindung steht, auch wenn noch niemand schreibt.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam UUID roomId) throws IOException {
        SseEmitter client = new SseEmitter(NO_TIMEOUT);
        clientRegistry.register(roomId, client);

        SseEventBuilder connected = SseEmitter.event().comment("connected");
        client.send(connected);
        return client;
    }
}
