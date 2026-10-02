package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Der einzige Ort im ganzen System, der in die Tabelle message schreibt.
 *
 * Bewusst JdbcTemplate statt JPA (PLANUNG.md 2.1): Wir wollen sehen, dass
 * aus 500 Nachrichten EIN Datenbank-Aufruf in EINER Transaktion wird.
 * Ein ORM würde genau das verstecken.
 */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * ON CONFLICT (id) DO NOTHING macht Duplikate harmlos: Kommt eine
     * Nachricht ein zweites Mal, lässt PostgreSQL die Zeile einfach weg,
     * statt mit einem Fehler den ganzen Stapel abzubrechen (Spec 3.4).
     */
    private static final String INSERT_SQL = """
            INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt einen ganzen Stapel in EINER Transaktion.
     *
     * Entweder stehen danach alle Zeilen in der Tabelle oder keine. Erst
     * wenn diese Methode ohne Exception zurückkommt, ist der COMMIT
     * passiert, und erst dann darf der Listener bei RabbitMQ bestätigen.
     *
     * @return wie viele Zeilen wirklich neu sind. Der Rest waren Duplikate.
     */
    @Transactional
    public int insertAll(List<ChatMessage> messages) {
        List<Object[]> rows = new ArrayList<>();
        for (ChatMessage message : messages) {
            Object[] row = toRow(message);
            rows.add(row);
        }

        // Ein batchUpdate schickt alle Zeilen in einem Paket an die Datenbank.
        // Zurück kommt pro Zeile 1 (eingefügt) oder 0 (Duplikat übersprungen).
        int[] rowCounts = jdbcTemplate.batchUpdate(INSERT_SQL, rows);

        int insertedCount = 0;
        for (int rowCount : rowCounts) {
            if (rowCount > 0) {
                insertedCount = insertedCount + rowCount;
            }
        }
        return insertedCount;
    }

    /**
     * Schreibt eine einzelne Nachricht, in ihrer eigenen kleinen Transaktion.
     *
     * Nur für den Rückfall, wenn die Datenbank einen Stapel wegen einer
     * einzelnen kaputten Nachricht ablehnt (Spec 3.6). Im Normalfall wird
     * diese Methode nie aufgerufen.
     *
     * @return 1, wenn die Zeile neu ist, 0 bei einem Duplikat
     */
    public int insertOne(ChatMessage message) {
        Object[] row = toRow(message);
        return jdbcTemplate.update(INSERT_SQL, row);
    }

    /**
     * Die Werte einer Zeile, in der Reihenfolge der Fragezeichen in INSERT_SQL.
     *
     * Der Zeitpunkt geht als OffsetDateTime in UTC an den Treiber. Ein
     * Instant nimmt der PostgreSQL-Treiber nicht direkt an.
     */
    private Object[] toRow(ChatMessage message) {
        OffsetDateTime sentAt = message.sentAt().atOffset(ZoneOffset.UTC);
        return new Object[] {
                message.id(),
                message.roomId(),
                message.senderId(),
                message.senderName(),
                message.content(),
                sentAt
        };
    }
}
