package ch.benedict.m321.chatservice.stream;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Holt die Nachrichten aus der EIGENEN Queue dieser Instanz ab und gibt
 * sie an LiveDelivery weiter.
 *
 * Bewusst ein einfacher MessageListener statt @RabbitListener: Bei
 * @RabbitListener wandelt Spring den Body VOR unserem Code automatisch in
 * eine Java-Klasse um, anhand des Headers __TypeId__. Das scheiterte im
 * Test, Spring stellte die Nachricht endlos neu zu, und bei den Clients
 * kam nie etwas an. Hier bekommen wir die rohe Nachricht und lesen das
 * JSON selbst. Welcher Queue der Listener zuhört, steht in RabbitConfig
 * (deliveryListenerContainer).
 *
 * Pro Instanz holt nur ein einziger Abholer ab. Eine Queue gibt die
 * Nachrichten in der Reihenfolge heraus, in der sie hineinkamen, also
 * bleibt die Reihenfolge eines Absenders erhalten (S3).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeliveryListener implements MessageListener {

    private final ObjectMapper objectMapper;

    private final LiveDelivery liveDelivery;

    /**
     * Wird für jede Nachricht in der eigenen Queue aufgerufen.
     *
     * Der Body wird zweimal gebraucht: als Text, der unverändert als
     * "data:" an die Clients geht (gleiches Format wie in chat.persist),
     * und gelesen als ChatMessage, um Raum und ID zu kennen.
     */
    @Override
    public void onMessage(Message delivery) {
        byte[] body = delivery.getBody();
        String json = new String(body, StandardCharsets.UTF_8);

        ChatMessage message = readMessage(body);
        if (message == null) {
            return;
        }
        liveDelivery.deliver(message.roomId(), message.id(), json);
    }

    /**
     * Liest Raum und ID aus dem JSON. Ist die Nachricht kaputt, wird sie
     * nur im Log gemeldet und verworfen.
     *
     * Warum verwerfen und keine Exception werfen: Eine Exception würde
     * RabbitMQ die Nachricht immer wieder zustellen lassen. Für die Anzeige
     * ist eine kaputte Nachricht ohnehin wertlos, und gespeichert wird sie
     * über chat.persist trotzdem.
     */
    private ChatMessage readMessage(byte[] body) {
        ChatMessage message;
        try {
            message = objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            log.warn("Unreadable message in delivery queue dropped: {}", exception.getMessage());
            return null;
        }

        if (message == null || message.id() == null || message.roomId() == null) {
            log.warn("Message without id or roomId in delivery queue dropped");
            return null;
        }
        return message;
    }
}
