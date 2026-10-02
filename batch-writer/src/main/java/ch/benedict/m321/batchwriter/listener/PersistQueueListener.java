package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.dto.ReceivedMessage;
import ch.benedict.m321.batchwriter.service.InvalidMessageException;
import ch.benedict.m321.batchwriter.service.MessageBatchWriter;
import ch.benedict.m321.batchwriter.service.MessageParser;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holt Stapel aus chat.persist ab und entscheidet für jede Nachricht:
 * bestätigen oder in die Dead-Letter-Queue.
 *
 * Diese Klasse ist die einzige, die mit RabbitMQ spricht. Sie weiss
 * nicht, wie geschrieben wird, nur WANN bestätigt werden darf: erst
 * nachdem MessageBatchWriter ohne Exception zurückgekommen ist.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PersistQueueListener {

    private final MessageParser messageParser;

    private final MessageBatchWriter messageBatchWriter;

    /**
     * Wird von Spring mit einem ganzen Stapel aufgerufen (Spec 3.1).
     *
     * Bei AcknowledgeMode.MANUAL bestätigt Spring nie von selbst, auch
     * nicht nach einer Exception. Ein unerwarteter Fehler würde den Stapel
     * also für immer unbestätigt liegen lassen, und dieser Verbraucher
     * bekäme nichts Neues mehr. Darum geben wir den Stapel in diesem Fall
     * ausdrücklich mit basicNack an die Queue zurück.
     *
     * Dasselbe beim Herunterfahren, während auf die Datenbank gewartet wird:
     * Der Stapel ist nicht gespeichert, also zurück in die Queue.
     */
    @RabbitListener(queues = QueueNames.PERSIST_QUEUE, containerFactory = "batchListenerContainerFactory")
    public void onBatch(List<Message> deliveries, Channel channel) throws IOException {
        long highestDeliveryTag = findHighestDeliveryTag(deliveries);
        try {
            processBatch(deliveries, channel);
        } catch (InterruptedException exception) {
            // Das Unterbrechungs-Signal wieder setzen, damit Spring sieht,
            // dass dieser Thread aufhören soll.
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for the database, returning batch of {} messages to the queue",
                    deliveries.size());
            channel.basicNack(highestDeliveryTag, true, true);
        } catch (RuntimeException exception) {
            log.error("Unexpected error, returning batch of {} messages to the queue", deliveries.size(), exception);
            channel.basicNack(highestDeliveryTag, true, true);
        }
    }

    /**
     * Lesen, schreiben, bestätigen. In genau dieser Reihenfolge.
     */
    private void processBatch(List<Message> deliveries, Channel channel) throws IOException, InterruptedException {
        List<ReceivedMessage> validMessages = new ArrayList<>();
        List<Long> invalidDeliveryTags = new ArrayList<>();

        for (Message delivery : deliveries) {
            long deliveryTag = deliveryTagOf(delivery);
            try {
                ChatMessage message = messageParser.parse(delivery);
                ReceivedMessage receivedMessage = new ReceivedMessage(deliveryTag, message);
                validMessages.add(receivedMessage);
            } catch (InvalidMessageException exception) {
                log.warn("Invalid message with delivery tag {} goes to {}: {}",
                        deliveryTag, QueueNames.DEAD_LETTER_QUEUE, exception.getMessage());
                invalidDeliveryTags.add(deliveryTag);
            }
        }

        // Kommt dieser Aufruf zurück, ist der COMMIT passiert. Erst danach
        // wird bei RabbitMQ irgendetwas bestätigt. Ist die Datenbank weg,
        // wartet der Aufruf hier, bis sie wieder da ist (Spec 3.7).
        messageBatchWriter.write(validMessages);

        rejectToDeadLetterQueue(channel, invalidDeliveryTags);
        acknowledgeStored(channel, validMessages);
    }

    /**
     * Lehnt Nachrichten endgültig ab. requeue=false heisst: nicht zurück in
     * chat.persist, sondern über die dead-letter-Argumente nach chat.dlq.
     *
     * Muss VOR acknowledgeStored laufen, siehe dort.
     */
    private void rejectToDeadLetterQueue(Channel channel, List<Long> deliveryTags) throws IOException {
        for (Long deliveryTag : deliveryTags) {
            channel.basicReject(deliveryTag, false);
        }
    }

    /**
     * Bestätigt alle gespeicherten Nachrichten mit EINEM basicAck.
     *
     * multiple=true bestätigt jede noch offene Nachricht bis einschliesslich
     * dieses Tags. Darum müssen die ungültigen Nachrichten vorher abgelehnt
     * sein: Sonst würden sie hier mitbestätigt und wären verloren, statt in
     * der Dead-Letter-Queue zu landen.
     */
    private void acknowledgeStored(Channel channel, List<ReceivedMessage> storedMessages) throws IOException {
        if (storedMessages.isEmpty()) {
            return;
        }

        long highestDeliveryTag = 0;
        for (ReceivedMessage storedMessage : storedMessages) {
            long deliveryTag = storedMessage.deliveryTag();
            if (deliveryTag > highestDeliveryTag) {
                highestDeliveryTag = deliveryTag;
            }
        }
        channel.basicAck(highestDeliveryTag, true);
    }

    /** Die höchste Zustellnummer im Stapel. Damit lässt sich der ganze Stapel auf einmal zurückgeben. */
    private long findHighestDeliveryTag(List<Message> deliveries) {
        long highestDeliveryTag = 0;
        for (Message delivery : deliveries) {
            long deliveryTag = deliveryTagOf(delivery);
            if (deliveryTag > highestDeliveryTag) {
                highestDeliveryTag = deliveryTag;
            }
        }
        return highestDeliveryTag;
    }

    /** Die Zustellnummer steht in den Eigenschaften der Nachricht, nicht im Body. */
    private long deliveryTagOf(Message delivery) {
        MessageProperties properties = delivery.getMessageProperties();
        return properties.getDeliveryTag();
    }
}
