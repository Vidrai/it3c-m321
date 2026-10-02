package ch.benedict.m321.batchwriter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft, dass Flyway beim Start genau die Tabelle aus Spec 4.1 anlegt.
 *
 * Das Prüfskript der Bewertung verlässt sich auf die Spalten aus
 * PLANUNG.md 3.7. Ändert jemand die Migration, soll dieser Test rot
 * werden, nicht erst die Bewertung.
 */
@SpringBootTest
@Testcontainers
class MessageSchemaIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Sechs Spalten in dieser Reihenfolge, mit Typ, alle NOT NULL. */
    @Test
    void createsMessageTableWithColumnsFromPlanning() {
        String sql = """
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'message'
                ORDER BY ordinal_position
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String column = row.get("column_name") + " " + row.get("data_type") + " " + row.get("is_nullable");
            columns.add(column);
        }

        List<String> expected = List.of(
                "id uuid NO",
                "room_id uuid NO",
                "sender_id character varying NO",
                "sender_name character varying NO",
                "content text NO",
                "sent_at timestamp with time zone NO");
        assertEquals(expected, columns);
    }

    /** Der Primärschlüssel liegt auf id. Ohne ihn gäbe es keinen Schutz vor Duplikaten (S5). */
    @Test
    void usesIdAsPrimaryKey() {
        String sql = """
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'message'
                  AND tc.constraint_type = 'PRIMARY KEY'
                """;
        List<String> primaryKeyColumns = jdbcTemplate.queryForList(sql, String.class);

        assertEquals(List.of("id"), primaryKeyColumns);
    }

    /** Kein Fremdschlüssel auf room, sonst würde jede Nachricht abgelehnt (Spec 4.2). */
    @Test
    void hasNoForeignKey() {
        String sql = """
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE table_name = 'message'
                  AND constraint_type = 'FOREIGN KEY'
                """;
        Integer foreignKeyCount = jdbcTemplate.queryForObject(sql, Integer.class);

        assertEquals(0, foreignKeyCount);
    }

    /** Der Index für den Lesepfad aus PLANUNG.md 3.7 ist da. */
    @Test
    void createsIndexForReadingHistoryOfRoom() {
        String sql = """
                SELECT indexdef
                FROM pg_indexes
                WHERE tablename = 'message'
                  AND indexname = 'message_room_id_sent_at_idx'
                """;
        String indexDefinition = jdbcTemplate.queryForObject(sql, String.class);

        assertTrue(indexDefinition.contains("(room_id, sent_at DESC)"),
                "Unerwartete Index-Definition: " + indexDefinition);
    }
}
