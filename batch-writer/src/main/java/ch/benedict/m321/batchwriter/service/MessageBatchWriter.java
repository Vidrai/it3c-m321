package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.dto.ReceivedMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bringt einen Stapel gelesener Nachrichten in die Datenbank.
 *
 * Diese Klasse sitzt zwischen Listener und Repository, damit der Listener
 * nur RabbitMQ kennt und das Repository nur SQL.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchWriter {

    private final MessageRepository messageRepository;

    /**
     * Schreibt alle Nachrichten in einer Transaktion.
     *
     * Kommt die Methode ohne Exception zurück, ist der COMMIT passiert.
     */
    public void write(List<ReceivedMessage> receivedMessages) {
        if (receivedMessages.isEmpty()) {
            return;
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (ReceivedMessage receivedMessage : receivedMessages) {
            ChatMessage message = receivedMessage.message();
            messages.add(message);
        }

        int insertedCount = messageRepository.insertAll(messages);

        int batchSize = messages.size();
        int duplicateCount = batchSize - insertedCount;
        log.info("Stored batch of {} messages: {} new, {} duplicates skipped", batchSize, insertedCount, duplicateCount);
    }
}
