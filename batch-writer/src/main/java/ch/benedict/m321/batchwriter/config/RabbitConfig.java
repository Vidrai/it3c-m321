package ch.benedict.m321.batchwriter.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Alles, was der batch-writer bei RabbitMQ braucht: die beiden Queues und
 * die Einstellungen, wie Nachrichten zu Stapeln gesammelt werden.
 */
@Configuration
@RequiredArgsConstructor
public class RabbitConfig {

    private final BatchWriterProperties properties;

    /**
     * Der Schreibweg, mit GENAU denselben Argumenten wie im chat-service.
     *
     * Warum wir die Queue auch hier anlegen: Startet der batch-writer vor
     * dem chat-service, gäbe es sie sonst noch nicht. Weicht ein Argument
     * ab, lehnt RabbitMQ die Deklaration mit PRECONDITION_FAILED ab und der
     * Dienst kommt nicht hoch (Spec 2.1).
     *
     * Die beiden dead-letter-Argumente sorgen dafür, dass RabbitMQ eine mit
     * basicReject(requeue=false) abgelehnte Nachricht selbst nach chat.dlq
     * legt.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die sich nie speichern lassen. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Wie der Listener seine Nachrichten bekommt (Spec 3.1 und 5.2).
     *
     * Spring sammelt Nachrichten, bis batchSize erreicht ist oder die
     * Sammelzeit abläuft, und ruft den Listener dann EINMAL mit der ganzen
     * Liste auf. Aus 500 Nachrichten wird so ein Aufruf, ein INSERT, ein
     * COMMIT.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory batchListenerContainerFactory(ConnectionFactory connectionFactory) {
        int batchSize = properties.batchSize();
        long batchTimeoutMs = properties.batchTimeoutMs();

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        // Wir bestätigen selbst, und zwar erst nach dem COMMIT. Das ist
        // at-least-once: kein ACK, bevor die Zeilen wirklich gespeichert sind.
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // Der Listener bekommt eine Liste statt einzelner Nachrichten.
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);

        // Prefetch: so viele unbestätigte Nachrichten schickt RabbitMQ uns
        // höchstens. Wäre er kleiner als der Stapel, würde der Stapel nie voll.
        factory.setPrefetchCount(batchSize);

        // receiveTimeout: so lange warten wir höchstens auf die NÄCHSTE Nachricht.
        // batchReceiveTimeout: so lange darf das Sammeln INSGESAMT dauern.
        factory.setReceiveTimeout(batchTimeoutMs);
        factory.setBatchReceiveTimeout(batchTimeoutMs);

        // Ein Verbraucher pro Instanz. Mehr Durchsatz gibt es mit --scale,
        // nicht mit mehr Threads. So bleiben die Delivery-Tags eines Stapels
        // lückenlos, und basicAck(multiple=true) ist sicher.
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);

        return factory;
    }
}
