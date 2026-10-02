package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.BatchWriterProperties;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.dto.ReceivedMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.ArrayList;
import java.util.List;

/**
 * Bringt einen Stapel gelesener Nachrichten in die Datenbank, auch wenn
 * die Datenbank gerade weg ist.
 *
 * Diese Klasse sitzt zwischen Listener und Repository, damit der Listener
 * nur RabbitMQ kennt und das Repository nur SQL. Hier steckt die Regel
 * aus Spec 3.7: Datenbank weg heisst warten und denselben Stapel noch
 * einmal schreiben, nicht aufgeben.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchWriter {

    private final MessageRepository messageRepository;

    private final BatchWriterProperties properties;

    /**
     * Schreibt alle Nachrichten in einer Transaktion und versucht es so
     * lange erneut, bis die Datenbank sie angenommen hat.
     *
     * Kommt die Methode zurück, ist der COMMIT passiert. Während sie
     * wartet, bleiben die Nachrichten bei RabbitMQ unbestätigt. Nichts
     * geht verloren, auch wenn der Prozess in dieser Zeit stirbt.
     *
     * @throws InterruptedException wenn der Dienst beim Warten heruntergefahren wird
     */
    public void write(List<ReceivedMessage> receivedMessages) throws InterruptedException {
        if (receivedMessages.isEmpty()) {
            return;
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (ReceivedMessage receivedMessage : receivedMessages) {
            ChatMessage message = receivedMessage.message();
            messages.add(message);
        }

        int batchSize = messages.size();
        int attempt = 1;
        boolean stored = false;
        while (!stored) {
            try {
                insertAndLog(messages);
                stored = true;
            } catch (DataAccessException | TransactionException exception) {
                // TransactionException braucht es zusätzlich: Ist die Datenbank
                // weg, scheitert schon das Öffnen der Transaktion, und diese
                // Exception ist keine DataAccessException (Spec 3.7).
                waitBeforeNextAttempt(attempt, batchSize, exception);
                attempt = attempt + 1;
            }
        }
    }

    /** Ein Schreibversuch für den ganzen Stapel, mit einer Zeile im Log. */
    private void insertAndLog(List<ChatMessage> messages) {
        int insertedCount = messageRepository.insertAll(messages);

        int batchSize = messages.size();
        int duplicateCount = batchSize - insertedCount;
        log.info("Stored batch of {} messages: {} new, {} duplicates skipped", batchSize, insertedCount, duplicateCount);
    }

    /**
     * Wartet eine feste Zeit, bevor es weitergeht.
     *
     * Bewusst eine feste Pause statt sofort erneut: Eine Datenbank, die
     * gerade neu startet, braucht ein paar Sekunden. Tausend Versuche pro
     * Sekunde würden nur das Log fluten.
     */
    private void waitBeforeNextAttempt(int attempt, int batchSize, RuntimeException exception)
            throws InterruptedException {
        long retryDelayMs = properties.dbRetryDelayMs();
        log.warn("Database not available (attempt {}), retrying batch of {} messages in {} ms: {}",
                attempt, batchSize, retryDelayMs, exception.getMessage());
        Thread.sleep(retryDelayMs);
    }
}
