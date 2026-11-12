package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
@Slf4j
public class RabbitConfig {

    /**
     * So viele Nachrichten hält die Queue einer Instanz höchstens. Hängt die
     * Instanz (S6), läuft ihre Queue nicht ins Unendliche voll.
     */
    public static final int DELIVERY_QUEUE_MAX_LENGTH = 1000;

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede chat-service-Instanz jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer SSE-Clients im
     * betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Die eigene Queue dieser Instanz am Zustellweg.
     *
     * Warum jede Instanz ihre eigene braucht: Teilten sich zwei Instanzen
     * eine Queue, bekäme jede Nachricht nur eine von beiden, und der Client
     * an der anderen Instanz verpasste sie (S3).
     *
     * - Der Name endet auf eine zufällige ID, die bei jedem Start neu
     *   gewürfelt wird. So hat garantiert jede Instanz einen eigenen Namen.
     * - exclusive: Die Queue gehört nur der Verbindung dieser Instanz.
     *   Geht die Verbindung zu, löscht RabbitMQ die Queue sofort (S4).
     * - autoDelete: Meldet sich der letzte Abholer ab, wird sie ebenfalls
     *   gelöscht.
     * - nonDurable: Ohne die Instanz hat die Queue keinen Sinn, also muss
     *   sie auch einen Neustart des Brokers nicht überleben.
     * - maxLength 1000 mit dropHead: Hängt die Instanz, fliegt bei voller
     *   Queue die älteste Nachricht raus (S6). Gespeichert ist sie trotzdem,
     *   über chat.persist.
     */
    @Bean
    public Queue instanceDeliveryQueue() {
        UUID instanceId = UUID.randomUUID();
        String queueName = QueueNames.DELIVERY_QUEUE_PREFIX + instanceId;
        log.info("Delivery queue of this instance: {}", queueName);

        return QueueBuilder.nonDurable(queueName)
                .exclusive()
                .autoDelete()
                .maxLength(DELIVERY_QUEUE_MAX_LENGTH)
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    /**
     * Hängt die eigene Queue an den Fanout-Exchange. Ohne diese Bindung
     * würde der Exchange die Kopie verwerfen, weil keine Queue dran hängt.
     */
    @Bean
    public Binding instanceDeliveryBinding(Queue instanceDeliveryQueue, FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(instanceDeliveryQueue).to(deliveryExchange);
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
