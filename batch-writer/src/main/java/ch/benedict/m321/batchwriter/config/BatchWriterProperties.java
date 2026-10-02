package ch.benedict.m321.batchwriter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Die Stellschrauben des batch-writer an genau einer Stelle.
 *
 * Spring füllt diesen record aus den Einträgen unter "batch-writer:" in
 * application.yml. Dort stehen sie als ${BATCH_SIZE:500} und so weiter,
 * also aus der Umgebung, sonst mit Vorgabewert.
 *
 * Ein record statt drei @Value-Feldern, weil @RequiredArgsConstructor die
 * @Value-Annotation nicht in den Konstruktor übernehmen würde.
 *
 * @param batchSize      höchstens so viele Nachrichten pro Stapel, zugleich der Prefetch
 * @param batchTimeoutMs so lange wird höchstens gesammelt, bevor geschrieben wird
 * @param dbRetryDelayMs Pause zwischen zwei Schreibversuchen, wenn die Datenbank weg ist
 */
@ConfigurationProperties("batch-writer")
public record BatchWriterProperties(
        int batchSize,
        long batchTimeoutMs,
        long dbRetryDelayMs) {
}
