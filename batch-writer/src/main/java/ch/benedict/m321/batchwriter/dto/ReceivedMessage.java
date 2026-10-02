package ch.benedict.m321.batchwriter.dto;

/**
 * Eine gelesene Nachricht zusammen mit ihrem Delivery-Tag.
 *
 * Der Delivery-Tag ist die Nummer, unter der RabbitMQ uns die Nachricht
 * zugestellt hat. Nur mit dieser Nummer können wir sie später bestätigen
 * (basicAck) oder ablehnen (basicReject). Darum bleiben beide beisammen.
 *
 * @param deliveryTag die Zustellnummer von RabbitMQ auf diesem Kanal
 * @param message     die gelesene und geprüfte Nachricht
 */
public record ReceivedMessage(long deliveryTag, ChatMessage message) {
}
