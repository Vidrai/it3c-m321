package ch.benedict.m321.chatservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen einen ECHTEN RabbitMQ, dass unsere Queues beim Start
 * tatsächlich angelegt werden. Ein Mock würde hier nichts beweisen:
 * die Deklaration passiert im Broker, nicht in unserem Code.
 */
@SpringBootTest
@Testcontainers
class RabbitConfigIntegrationTest {

    /**
     * ServiceConnection setzt spring.rabbitmq.host und -port automatisch
     * auf den gestarteten Container. Wir müssen nichts konfigurieren.
     */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /** Die Queue dieser Instanz. Ihr Name ist bei jedem Start ein anderer. */
    @Autowired
    @Qualifier("instanceDeliveryQueue")
    private Queue instanceDeliveryQueue;

    /** Der Schreibweg chat.persist existiert im Broker. */
    @Test
    void declaresPersistQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);

        assertNotNull(properties);
    }

    /** Das Abstellgleis chat.dlq existiert im Broker. */
    @Test
    void declaresDeadLetterQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(properties);
    }

    /**
     * Die eigene Queue dieser Instanz existiert im Broker, mit genau den
     * Einstellungen aus der Spezifikation: exclusive, auto-delete, eine
     * Grenze (950 wartende, dazu höchstens 50 unterwegs, zusammen nie mehr
     * als 1000), älteste fliegt raus.
     *
     * Gefragt wird RabbitMQ selbst über rabbitmqctl. So prüfen wir, was im
     * Broker angekommen ist, nicht nur, was in unserer Java-Bean steht.
     */
    @Test
    void declaresOwnDeliveryQueueWithLimit() throws Exception {
        String queueName = instanceDeliveryQueue.getName();
        Properties properties = rabbitAdmin.getQueueProperties(queueName);
        assertNotNull(properties);

        ExecResult result = rabbitMq.execInContainer("rabbitmqctl", "list_queues", "-q",
                "name", "exclusive", "auto_delete", "arguments");
        String queues = result.getStdout();
        String queueLine = findLine(queues, queueName);

        assertTrue(queueName.startsWith(QueueNames.DELIVERY_QUEUE_PREFIX), queueName);
        assertTrue(queueLine.contains("true\ttrue"), "nicht exclusive und auto-delete: " + queueLine);
        String maxLength = String.valueOf(RabbitConfig.DELIVERY_QUEUE_MAX_LENGTH);
        assertTrue(queueLine.contains("x-max-length") && queueLine.contains(maxLength), queueLine);
        assertTrue(queueLine.contains("drop-head"), queueLine);
    }

    /**
     * Die Queue hängt am Fanout chat.delivery. Ohne diese Bindung würde der
     * Exchange jede Kopie verwerfen, und kein Client sähe etwas.
     */
    @Test
    void bindsOwnDeliveryQueueToFanout() throws Exception {
        String queueName = instanceDeliveryQueue.getName();
        rabbitAdmin.getQueueProperties(queueName);

        ExecResult result = rabbitMq.execInContainer("rabbitmqctl", "list_bindings", "-q",
                "source_name", "destination_name");
        String bindings = result.getStdout();

        // Jede Queue hängt automatisch auch am Standard-Exchange (Name leer).
        // Darum suchen wir genau die Zeile "chat.delivery <Tab> unsere Queue".
        String expectedBinding = QueueNames.DELIVERY_EXCHANGE + "\t" + queueName;
        assertTrue(bindings.contains(expectedBinding), "Bindung fehlt in:\n" + bindings);
    }

    /**
     * Sucht in der Ausgabe von rabbitmqctl die Zeile, in der die Queue
     * steht. Fehlt sie, schlägt der Test mit der ganzen Ausgabe fehl.
     */
    private String findLine(String output, String queueName) {
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.contains(queueName)) {
                return line;
            }
        }
        throw new AssertionError("Queue " + queueName + " nicht gefunden in:\n" + output);
    }
}
