package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Startpunkt des batch-writer.
 *
 * Dieser Dienst ist der einzige Schreiber in die Tabelle message. Er holt
 * die Nachrichten aus der Queue chat.persist und schreibt sie stapelweise,
 * damit die Datenbank nicht 1'667 Einzel-Transaktionen pro Sekunde
 * verkraften muss.
 *
 * ConfigurationPropertiesScan sorgt dafür, dass Spring den record
 * BatchWriterProperties findet und aus application.yml füllt.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BatchWriterApplication {

    /** Startet Spring. Mehr passiert hier nicht. */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
