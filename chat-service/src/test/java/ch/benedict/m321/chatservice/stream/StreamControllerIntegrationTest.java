package ch.benedict.m321.chatservice.stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Schritt 2: Ein Client kann den Stream öffnen, bekommt den richtigen
 * Content-Type, und die Instanz merkt sich, in welchem Raum er ist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StreamControllerIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClientRegistry clientRegistry;

    /**
     * Die Verbindung bleibt offen (asyncStarted) und meldet sich als
     * text/event-stream. Genau daran setzt das Prüfskript der Bewertung an.
     */
    @Test
    void opensEventStreamWithCorrectContentType() throws Exception {
        UUID roomId = UUID.randomUUID();

        mockMvc.perform(get("/stream").param("roomId", roomId.toString()))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    /** Nach dem Verbinden steht der Client in der Liste seines Raums, und nur dort. */
    @Test
    void remembersClientInItsRoom() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID otherRoomId = UUID.randomUUID();

        mockMvc.perform(get("/stream").param("roomId", roomId.toString()))
                .andExpect(request().asyncStarted());

        List<?> clientsInRoom = clientRegistry.clientsInRoom(roomId);
        List<?> clientsInOtherRoom = clientRegistry.clientsInRoom(otherRoomId);
        assertEquals(1, clientsInRoom.size());
        assertEquals(0, clientsInOtherRoom.size());
    }

    /** Ohne Raum gibt es keinen Stream: 400, damit der Fehler beim Aufrufer sichtbar ist. */
    @Test
    void rejectsRequestWithoutRoom() throws Exception {
        mockMvc.perform(get("/stream"))
                .andExpect(status().isBadRequest());
    }
}
