package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.BatchWriterProperties;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.dto.ReceivedMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.ArrayList;
import java.util.List;

/**
 * Bringt einen Stapel gelesener Nachrichten in die Datenbank, auch wenn
 * die Datenbank gerade weg ist oder eine einzelne Nachricht ablehnt.
 *
 * Diese Klasse sitzt zwischen Listener und Repository, damit der Listener
 * nur RabbitMQ kennt und das Repository nur SQL. Hier stecken die beiden
 * Regeln für Fehler beim Schreiben:
 *
 * - Datenbank weg (Spec 3.7): warten und denselben Stapel noch einmal
 *   schreiben, nicht aufgeben.
 * - Datenbank lehnt den Inhalt ab (Spec 3.6): den Stapel einzeln
 *   schreiben, damit nur die eine kaputte Nachricht in die DLQ geht.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchWriter {

    private final MessageRepository messageRepository;

    private final BatchWriterProperties properties;

    /**
     * Schreibt alle Nachrichten und versucht es so lange erneut, bis die
     * Datenbank erreichbar war.
     *
     * Kommt die Methode zurück, ist jede Nachricht entweder gespeichert
     * oder in der zurückgegebenen Liste. Während sie wartet, bleiben die
     * Nachrichten bei RabbitMQ unbestätigt. Nichts geht verloren, auch
     * wenn der Prozess in dieser Zeit stirbt.
     *
     * @return die Nachrichten, die die Datenbank abgelehnt hat. Im Normalfall leer.
     * @throws InterruptedException wenn der Dienst beim Warten heruntergefahren wird
     */
    public List<ReceivedMessage> write(List<ReceivedMessage> receivedMessages) throws InterruptedException {
        List<ReceivedMessage> refusedMessages = new ArrayList<>();
        if (receivedMessages.isEmpty()) {
            return refusedMessages;
        }

        int batchSize = receivedMessages.size();
        int attempt = 1;
        boolean written = false;
        while (!written) {
            try {
                refusedMessages = insertBatchOrOneByOne(receivedMessages);
                written = true;
            } catch (DataAccessException | TransactionException exception) {
                // TransactionException braucht es zusätzlich: Ist die Datenbank
                // weg, scheitert schon das Öffnen der Transaktion, und diese
                // Exception ist keine DataAccessException (Spec 3.7).
                waitBeforeNextAttempt(attempt, batchSize, exception);
                attempt = attempt + 1;
            }
        }
        return refusedMessages;
    }

    /**
     * Der Normalfall ist ein einziger Bulk-INSERT. Nur wenn die Datenbank
     * den Inhalt ablehnt, geht es einzeln weiter.
     *
     * Wichtig: DataIntegrityViolationException ist auch eine
     * DataAccessException. Sie muss HIER gefangen werden, bevor sie in
     * write() als "Datenbank weg" gilt. Sonst würde ein kaputter Stapel
     * endlos wiederholt.
     */
    private List<ReceivedMessage> insertBatchOrOneByOne(List<ReceivedMessage> receivedMessages) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ReceivedMessage receivedMessage : receivedMessages) {
            ChatMessage message = receivedMessage.message();
            messages.add(message);
        }

        try {
            insertAndLog(messages);
            return new ArrayList<>();
        } catch (DataIntegrityViolationException exception) {
            int batchSize = messages.size();
            log.warn("Database refused batch of {} messages, writing them one by one: {}",
                    batchSize, exception.getMessage());
            return insertOneByOne(receivedMessages);
        }
    }

    /** Ein Schreibversuch für den ganzen Stapel, in einer Transaktion, mit einer Zeile im Log. */
    private void insertAndLog(List<ChatMessage> messages) {
        int insertedCount = messageRepository.insertAll(messages);

        int batchSize = messages.size();
        int duplicateCount = batchSize - insertedCount;
        log.info("Stored batch of {} messages: {} new, {} duplicates skipped", batchSize, insertedCount, duplicateCount);
    }

    /**
     * Der Rückfall: jede Nachricht in ihrer eigenen kleinen Transaktion.
     *
     * Kostet bis zu 500 Transaktionen statt einer, aber nur in diesem
     * seltenen Fehlerfall. Was schon gespeichert ist, überspringt
     * ON CONFLICT, falls der Rückfall nach einem Ausfall wiederholt wird.
     */
    private List<ReceivedMessage> insertOneByOne(List<ReceivedMessage> receivedMessages) {
        List<ReceivedMessage> refusedMessages = new ArrayList<>();
        int insertedCount = 0;

        for (ReceivedMessage receivedMessage : receivedMessages) {
            ChatMessage message = receivedMessage.message();
            try {
                int rowCount = messageRepository.insertOne(message);
                insertedCount = insertedCount + rowCount;
            } catch (DataIntegrityViolationException exception) {
                log.warn("Database refused message {}, it goes to the dead letter queue: {}",
                        message.id(), exception.getMessage());
                refusedMessages.add(receivedMessage);
            }
        }

        int refusedCount = refusedMessages.size();
        log.info("Stored {} messages one by one, {} refused", insertedCount, refusedCount);
        return refusedMessages;
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
