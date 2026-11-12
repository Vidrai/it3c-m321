package ch.benedict.m321.chatservice.stream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein einfacher SSE-Client für die Tests: verbindet sich mit
 * GET /stream?roomId=… und sammelt alles, was ankommt.
 *
 * Er macht genau das, was später ein Browser oder das Prüfskript tut: die
 * Verbindung offen halten und jede Zeile "id:" und "data:" mitschreiben.
 * Gelesen wird auf einem eigenen Thread, damit der Test weiterlaufen kann.
 */
class SseTestClient {

    private final List<String> eventIds = new CopyOnWriteArrayList<>();

    private final List<String> dataLines = new CopyOnWriteArrayList<>();

    private InputStream body;

    /**
     * Öffnet den Stream an einer Instanz. Ist lastEventId nicht null, wird
     * sie als Header Last-Event-ID mitgeschickt, wie bei einer
     * Neuverbindung (S7).
     */
    static SseTestClient connect(int port, UUID roomId, String lastEventId) throws Exception {
        SseTestClient client = new SseTestClient();
        client.open(port, roomId, lastEventId);
        return client;
    }

    /** Baut die Verbindung auf und startet den Lese-Thread. */
    private void open(int port, UUID roomId, String lastEventId) throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri);
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId);
        }
        HttpRequest request = requestBuilder.build();

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        body = response.body();

        Thread reader = new Thread(this::readLines);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Liest Zeile für Zeile, bis die Verbindung zugeht. Ein SSE-Ereignis
     * von Spring sieht so aus: "id:…", dann "data:…", dann eine Leerzeile.
     */
    private void readLines() {
        InputStreamReader streamReader = new InputStreamReader(body, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(streamReader)) {
            String line = reader.readLine();
            while (line != null) {
                remember(line);
                line = reader.readLine();
            }
        } catch (IOException exception) {
            // Verbindung geschlossen, zum Beispiel durch close(). Für den Test ist das das Ende.
        }
    }

    /** Merkt sich die id- und data-Zeilen, alles andere (Kommentare, Leerzeilen) nicht. */
    private void remember(String line) {
        if (line.startsWith("id:")) {
            String eventId = line.substring(3);
            eventIds.add(eventId);
        }
        if (line.startsWith("data:")) {
            String data = line.substring(5);
            dataLines.add(data);
        }
    }

    /** Alle bisher empfangenen Nachrichten-IDs, in der Reihenfolge der Ankunft. */
    List<String> eventIds() {
        return eventIds;
    }

    /** Alle bisher empfangenen Nachrichten als JSON, in der Reihenfolge der Ankunft. */
    List<String> dataLines() {
        return dataLines;
    }

    /** Trennt die Verbindung, wie ein Client, der den Browser-Tab schliesst. */
    void close() throws IOException {
        body.close();
    }
}
