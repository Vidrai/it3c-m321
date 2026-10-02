# batch-writer — Umsetzungsplan

**Ziel:** Der `batch-writer` holt Nachrichten aus `chat.persist`, schreibt sie stapelweise in
PostgreSQL und bestätigt sie erst nach dem COMMIT. Er besteht die Szenarien S1 bis S8 der
Bewertung.

**Spec:** [`spec-batch-writer.md`](spec-batch-writer.md). Jede Aufgabe unten nennt den Abschnitt,
den sie umsetzt.

**Architektur:** gleiche Schichtung wie im chat-service, `listener → service → repository`, dazu
`config` und `dto`. Der Listener kennt kein SQL, das Repository kein RabbitMQ.

```
PersistQueueListener ──► MessageParser            (JSON → ChatMessage, Prüfregeln)
        │
        └──────────────► MessageBatchWriter ──► MessageRepository ──► PostgreSQL
                         (Wiederholen, Einzel-      (ein Bulk-INSERT,
                          Rückfall)                  ON CONFLICT)
```

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP, Spring JDBC (`JdbcTemplate`), Flyway,
PostgreSQL 16, RabbitMQ 3.13, JUnit 5, Testcontainers, Awaitility.

## Globale Vorgaben

Gelten für **jede** Aufgabe, wie in [`plan-chat-service.md`](plan-chat-service.md):

- **Code auf Englisch**, also Klassen, Methoden, Variablen und Log-Meldungen. **Alles andere auf
  Deutsch**: Kommentare, Javadoc, Commit-Messages.
- **Keine verschachtelten Aufrufe, keine Streams.** `for`-Schleifen, ein Ergebnis pro Zeile.
- **Kommentar über jeder Klasse und jeder Methode**, auch in Tests.
- **Lombok** für `@Slf4j` und `@RequiredArgsConstructor`, Java-`record` für Datenklassen.
- **Kein `ports:`-Eintrag**, keine Geheimnisse im Repository.
- **Ein Thema pro Commit.** Jede Aufgabe endet mit genau einem Commit. Die Message steht in der
  Aufgabe und so im `git log`.
- Integrationstests heissen `...IntegrationTest` und starten echte Container mit Testcontainers,
  so wie im chat-service.
- **Voraussetzung:** JDK 21, Maven, Docker läuft.

## Bau-Reihenfolge auf einen Blick

| # | Aufgabe | Warum genau an dieser Stelle |
|---|---|---|
| 0 | Vorgaben wiederherstellen | Ohne die Namen aus der Bewertung (`chat-net`, `postgres`, `POSTGRES_*`) findet das Prüfskript nichts. Alles Weitere baut darauf auf |
| 1 | Maven-Modul und Startpunkt | Ohne Modul kann nichts übersetzt und nichts getestet werden |
| 2 | Vertrag: Nachricht lesen und prüfen | Der Vertrag ist die Grundlage für alles. Er lässt sich ganz ohne Infrastruktur testen, also zuerst |
| 3 | Schema mit Flyway | Bevor jemand schreibt, muss die Tabelle da sein |
| 4 | Repository: Bulk-INSERT mit `ON CONFLICT` | Der Kern von S4 und S5, testbar ohne RabbitMQ-Verkehr |
| 5 | Listener: abholen, schreiben, bestätigen | Erst jetzt gibt es alle Teile, die der Listener verbindet |
| 6 | Wiederholen, wenn die Datenbank weg ist | Baut auf einem funktionierenden Normalfall auf. Vorher könnte man den Fehlerfall nicht vom Normalfall unterscheiden |
| 7 | Einzel-Rückfall bei abgelehnten Inhalten | Verfeinert den Fehlerpfad aus 6, darum danach |
| 8 | Nachweis: wenige Transaktionen für 1000 Nachrichten | Misst, was 4 und 5 versprechen. Ergibt erst Sinn, wenn beides steht |
| 9 | Docker und docker-compose | Erst wenn der Dienst im Test läuft, lohnt sich der Container |
| 10 | Abnahme-Skript für S2 bis S8 | Braucht den laufenden Stack aus 9 |
| 11 | README nachführen | Zum Schluss, damit dort steht, was wirklich da ist |
| 12 | Messwerte nachtragen | Erst nach dem Bauen gibt es echte Zahlen statt Schätzungen |

---

## Aufgabe 0: Vorgaben wiederherstellen

**Spec:** Abschnitt 5.3, «Feste Punkte» der Bewertung.

Der letzte Commit vor dieser Arbeit (`3f04a27`) hat das Netz in `chat_network` umbenannt, den
Dienst `postgres-app` genannt, eigene Variablennamen eingeführt und `PLANUNG.md` ersetzt. Er
wird mit `git revert` vollständig zurückgenommen, weil er ausschliesslich Doku und
Infrastruktur betraf.

- **Test:** `git show HEAD:docker-compose.yml | grep -n "chat-net"` findet das Netz,
  `git show HEAD:PLANUNG.md | grep -n "### 3.7 Datenmodell"` findet den Abschnitt.
- **Commit:** `revert: Grundgerüst-Commit zurücknehmen, Vorgaben wiederherstellen`

## Aufgabe 0b: Spezifikation und dieser Plan

- **Commits:** `docs: Spezifikation für den batch-writer`, danach
  `docs: Umsetzungsplan für den batch-writer`

---

## Aufgabe 1: Maven-Modul und Startpunkt

**Spec:** Abschnitt 5.

**Dateien:**
- Ändern: `pom.xml` (Modul `batch-writer` dazu)
- Ändern: `chat-service/Dockerfile` (baut sein Modul mit `-f chat-service/pom.xml`, siehe unten)
- Anlegen: `batch-writer/pom.xml`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/BatchWriterApplication.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/BatchWriterProperties.java`
- Anlegen: `batch-writer/src/main/resources/application.yml`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/BatchWriterApplicationTest.java`

**Abhängigkeiten im Modul-POM:** `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`,
`org.postgresql:postgresql` (runtime), `lombok` (optional). Für Tests:
`spring-boot-starter-test`, `spring-boot-testcontainers`, Testcontainers `junit-jupiter`,
`postgresql`, `rabbitmq`, dazu `org.awaitility:awaitility`.

**`BatchWriterProperties`** ist ein `record` mit `@ConfigurationProperties("batch-writer")`:
`batchSize`, `batchTimeoutMs`, `dbRetryDelayMs`. In `application.yml` stehen sie mit
`${BATCH_SIZE:500}` und so weiter. Ein record statt drei `@Value`-Feldern, weil
`@RequiredArgsConstructor` die `@Value`-Annotation nicht in den Konstruktor übernimmt.

**Warum der chat-service-Dockerfile angepasst werden muss:** Er baut mit
`mvn -pl chat-service -am`. Dabei liest Maven das Eltern-POM *mit allen Modulen*. Kennt das
Eltern-POM das Modul `batch-writer`, dessen Ordner im Build-Container fehlt, bricht der Build mit
«Child module ... does not exist» ab. Mit `mvn -f chat-service/pom.xml` baut Maven nur dieses
Modul und findet das Eltern-POM über `../pom.xml`, ohne die Modulliste zu prüfen.

- [ ] Test schreiben: `contextLoads()` mit `@SpringBootTest`, `@Testcontainers`, einem
      `PostgreSQLContainer` und einem `RabbitMQContainer`, beide mit `@ServiceConnection`.
- [ ] Ausführen: `mvn -q -pl batch-writer test`. Erwartet zuerst: Fehlschlag (kein Modul).
- [ ] POMs, Startklasse, Properties und `application.yml` anlegen.
- [ ] Ausführen: `mvn -q -pl batch-writer test`. Erwartet: grün.
- [ ] Ausführen: `mvn -q clean test` im Wurzelverzeichnis. Erwartet: beide Module grün.
- [ ] **Commit:** `chore: Maven-Modul batch-writer anlegen`

---

## Aufgabe 2: Vertrag, also Nachricht lesen und prüfen

**Spec:** Abschnitt 2.2 bis 2.4.

**Dateien:**
- Anlegen: `.../batchwriter/dto/ChatMessage.java`, eigene Kopie des records, gleiche Feldnamen wie im chat-service
- Anlegen: `.../batchwriter/service/InvalidMessageException.java`
- Anlegen: `.../batchwriter/service/MessageParser.java`
- Test: `.../batchwriter/service/MessageParserTest.java` (reiner Unit-Test, kein Docker)

**`MessageParser.parse(Message)`** liest den Body mit dem ObjectMapper von Spring Boot in
`ChatMessage`, prüft die Regeln aus 2.4 und wirft sonst `InvalidMessageException` mit dem Grund.
Header werden nicht angeschaut.

**Tests**, je ein Satz, warum es sie gibt:
- echtes Beispiel aus Spec 2.2 wird gelesen, inklusive Nanosekunden in `sentAt`
- `sentAt` als Zahl wird gelesen, denn ein nackter ObjectMapper schreibt so (Spec 2.2, Punkt 2)
- Nachricht **ohne** `__TypeId__`, nur mit `content_type`, wird gelesen (S5)
- unbekanntes Zusatzfeld wird ignoriert (toleranter Leser)
- kein JSON → Exception
- fehlendes Feld → Exception, und der Grund nennt das Feld
- `id` ist keine UUID → Exception
- `senderName` mit 256 Zeichen → Exception

Der ObjectMapper im Test kommt aus `Jackson2ObjectMapperBuilder` mit
`failOnUnknownProperties(false)` und ausgeschaltetem `WRITE_DATES_AS_TIMESTAMPS`, also mit
denselben Voreinstellungen wie in Spring Boot.

- [ ] Tests schreiben, `mvn -q -pl batch-writer test -Dtest=MessageParserTest`, Fehlschlag.
- [ ] `ChatMessage`, `InvalidMessageException`, `MessageParser` schreiben, Test grün.
- [ ] **Commit:** `feat: Nachricht aus chat.persist lesen und prüfen`

---

## Aufgabe 3: Schema mit Flyway

**Spec:** Abschnitt 4.

**Dateien:**
- Ändern: `batch-writer/pom.xml` (`flyway-core`, `flyway-database-postgresql`)
- Anlegen: `batch-writer/src/main/resources/db/migration/V1__create_message_table.sql`
- Test: `.../batchwriter/repository/MessageSchemaIntegrationTest.java`

**Test:** fragt `information_schema.columns` ab und erwartet genau die sechs Spalten mit Typ und
`NOT NULL`, dazu den Primärschlüssel auf `id` und den Index `message_room_id_sent_at_idx` aus
`pg_indexes`.

- [ ] Test schreiben, Fehlschlag (Tabelle fehlt).
- [ ] Flyway-Abhängigkeit und Migration anlegen, Test grün.
- [ ] **Commit:** `feat: Tabelle message mit Flyway anlegen`

---

## Aufgabe 4: Repository mit Bulk-INSERT und ON CONFLICT

**Spec:** Abschnitt 3.1 (Punkt 4) und 3.4.

**Dateien:**
- Anlegen: `.../batchwriter/repository/MessageRepository.java`
- Test: `.../batchwriter/repository/MessageRepositoryIntegrationTest.java`

**`MessageRepository`:**
- `insertAll(List<ChatMessage>)`, mit `@Transactional`: ein `jdbcTemplate.batchUpdate` mit dem
  SQL aus Spec 3.1, gibt die Anzahl *neuer* Zeilen zurück. `sentAt` geht als `OffsetDateTime` in
  UTC an den Treiber, weil der PostgreSQL-Treiber `Instant` nicht direkt annimmt.
- `insertOne(ChatMessage)`, ohne eigene Transaktion: ein einzelnes `update` (für Aufgabe 7).

**Tests:**
- drei Nachrichten → drei Zeilen, Rückgabe `3`, Felder kommen unverändert zurück
- dieselbe Nachricht in zwei Aufrufen → eine Zeile, zweite Rückgabe `0` (S5, Datenbankseite)
- dieselbe Nachricht zweimal im **selben** Stapel → eine Zeile, Rückgabe `1`

- [ ] Tests schreiben, Fehlschlag, Repository schreiben, grün.
- [ ] **Commit:** `feat: Nachrichten stapelweise speichern, Duplikate überspringen`

---

## Aufgabe 5: Listener, also abholen, schreiben, bestätigen

**Spec:** Abschnitt 2.1, 3.1, 3.6 (erste Zeile), 3.8 (Programmfehler).

**Dateien:**
- Anlegen: `.../batchwriter/config/QueueNames.java`, eigene Kopie der Namen
- Anlegen: `.../batchwriter/config/RabbitConfig.java`, beide Queues mit **denselben** Argumenten
  wie im chat-service, und die Bean `batchListenerContainerFactory`
- Anlegen: `.../batchwriter/dto/ReceivedMessage.java`, record aus Delivery-Tag und `ChatMessage`
- Anlegen: `.../batchwriter/service/MessageBatchWriter.java`, in dieser Aufgabe nur
  weiterreichen an `insertAll`
- Anlegen: `.../batchwriter/listener/PersistQueueListener.java`
- Test: `.../batchwriter/listener/PersistQueueListenerIntegrationTest.java`

**Container-Factory:** `AcknowledgeMode.MANUAL`, `prefetchCount = batchSize`,
`setBatchListener(true)`, `setConsumerBatchEnabled(true)`, `batchSize`, `receiveTimeout` und
`batchReceiveTimeout` = `batchTimeoutMs`, `concurrentConsumers = maxConcurrentConsumers = 1`.

**Listener `onBatch(List<Message>, Channel)`:** lesen, gültige schreiben, dann zuerst
ungültige mit `basicReject(tag, false)` ablehnen, danach `basicAck(höchster Tag, true)`. Ein
unerwarteter Fehler führt zu `basicNack(höchster Tag, true, true)`.

**Tests**, jeweils mit eigener Raum-ID:
- 50 Nachrichten über den `RabbitTemplate` → 50 Zeilen, `chat.persist` leer (S3 im Kleinen)
- dieselbe Nachricht zweimal **roh**, nur mit `content_type: application/json` → eine Zeile,
  `chat.dlq` leer (S5, genau wie im Prüfskript)
- eine kaputte Nachricht zwischen zwei gültigen → beide gültigen in der Tabelle, die kaputte in
  `chat.dlq`

- [ ] Tests schreiben, Fehlschlag, Klassen schreiben, grün.
- [ ] **Commit:** `feat: Nachrichten aus chat.persist abholen und nach dem Commit bestätigen`

---

## Aufgabe 6: Wiederholen, wenn die Datenbank weg ist

**Spec:** Abschnitt 3.7.

**Dateien:**
- Ändern: `.../service/MessageBatchWriter.java`, Schleife: schreiben; bei `DataAccessException`
  oder `TransactionException` loggen, `dbRetryDelayMs` warten, nochmals. Ein
  `InterruptedException` (Herunterfahren) geht an den Listener, der mit Requeue ablehnt.
- Ändern: `.../listener/PersistQueueListener.java`, `InterruptedException` behandeln
- Test: `.../batchwriter/service/DatabaseOutageIntegrationTest.java`

**Test (S7):** Der Postgres-Testcontainer bekommt einen **festen** Host-Port, damit er nach einem
Neustart unter derselben Adresse erreichbar ist. Testcontainers vergibt sonst bei jedem Start
einen neuen. Der Test stoppt den Container über den Docker-Client (`stopContainerCmd`), legt
30 Nachrichten in die Queue, wartet 5 s und startet ihn wieder (`startContainerCmd`). Erwartet:
innerhalb von 90 s alle 30 in der Tabelle, `chat.dlq` leer. Mit `DB_RETRY_DELAY_MS = 500` im Test,
damit er nicht unnötig lange dauert.

- [ ] Test schreiben. Fehlschlag: Ohne Wiederholung fängt der Listener die Exception als
      «Unexpected error» und schickt den Stapel per `basicNack` sofort zurück, als heisse
      Schleife. Am Ende landen die Nachrichten zwar trotzdem in der Tabelle, darum prüft der Test
      zusätzlich im Log (`OutputCaptureExtension`): «Database not available» muss vorkommen,
      «Unexpected error» darf nicht vorkommen.
- [ ] Schleife einbauen, grün.
- [ ] **Commit:** `feat: bei Datenbankausfall warten und denselben Stapel wiederholen`

---

## Aufgabe 7: Einzel-Rückfall bei abgelehnten Inhalten

**Spec:** Abschnitt 3.6 (zweite Zeile).

**Dateien:**
- Ändern: `.../service/MessageBatchWriter.java`: `DataIntegrityViolationException` beim Stapel →
  jede Nachricht einzeln mit `insertOne`. Abgelehnte kommen als Liste zurück.
- Ändern: `.../listener/PersistQueueListener.java`: abgelehnte Nachrichten gehen mit
  `basicReject(tag, false)` in die DLQ
- Test: Fall in `PersistQueueListenerIntegrationTest` ergänzen

**Test:** Stapel mit einer Nachricht, deren `content` ein Nullzeichen `\u0000` enthält, zwischen
zwei gültigen. Erwartet: zwei Zeilen, eine Nachricht in `chat.dlq`.

- [ ] Test schreiben, Fehlschlag: heute wird der ganze Stapel endlos wiederholt.
- [ ] Rückfall einbauen, grün.
- [ ] **Commit:** `feat: von der Datenbank abgelehnte Nachrichten einzeln in die DLQ legen`

---

## Aufgabe 8: Nachweis, wenige Transaktionen für 1000 Nachrichten

**Spec:** Abschnitt 3.3 (S4).

**Dateien:**
- Test: `.../batchwriter/listener/BatchSizeIntegrationTest.java`

**Test:** Listener über `RabbitListenerEndpointRegistry` anhalten, 1000 Nachrichten einreihen,
Listener starten, warten bis 1000 Zeilen da sind. Dann
`SELECT count(DISTINCT xmin::text) FROM message WHERE room_id = ?`. `xmin` ist die Transaktion,
die die Zeile geschrieben hat. Erwartet: höchstens 10 (rechnerisch 2).

- [ ] Test schreiben und laufen lassen. Ist er sofort grün, ist das hier richtig: der Test
      *belegt* eine Eigenschaft aus Aufgabe 4 und 5, er treibt keinen neuen Code.
- [ ] **Commit:** `test: 1000 Nachrichten in wenigen Transaktionen nachweisen`

---

## Aufgabe 9: Docker und docker-compose

**Spec:** Abschnitt 5.1 und 5.3.

**Dateien:**
- Anlegen: `batch-writer/Dockerfile`, zweistufig wie beim chat-service
- Ändern: `docker-compose.yml`, Dienste `postgres` und `batch-writer` im Netz `chat-net`, ohne
  `ports:`, Volume `postgres-data`
- Ändern: `.env.example`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `BATCH_SIZE`,
  `BATCH_TIMEOUT_MS`, `DB_RETRY_DELAY_MS`
- Anlegen: `.gitattributes`, damit Shell-Skripte und Dockerfiles auch unter Windows mit `LF`
  ausgecheckt werden

**Test (S2):**
```bash
cp .env.example .env
docker compose up -d --build
docker compose ps --format '{{.Service}} {{.State}} {{.Ports}}'
docker compose exec postgres psql -U chat -d chat -c '\d message'
docker compose logs batch-writer | grep -i flyway
```
Erwartet: vier Dienste `running`, keine Spalte mit `->`, die Tabelle mit sechs Spalten.

- [ ] **Commit:** `chore: batch-writer und Postgres in docker-compose abbilden`

---

## Aufgabe 10: Abnahme-Skript

**Spec:** Abschnitt 6.

**Dateien:**
- Anlegen: `scripts/szenarien.sh`

Führt S2 bis S8 in der Reihenfolge der Bewertung auf demselben Stack aus, mit den Befehlen aus
Spec 6, und schreibt pro Szenario den gemessenen Wert neben den erwarteten.

- **Test:** `bash scripts/szenarien.sh` auf einem frisch gestarteten Stack. Erwartet: S2 bis S8
  `BESTANDEN`.
- [ ] **Commit:** `test: Skript für die Szenarien S2 bis S8`

---

## Aufgabe 11: README nachführen

**Dateien:** `README.md`, Tabelle «Stand», Abschnitt «Bauen, testen, starten», Verweise auf Spec
und Plan.

- **Test:** Jeder Befehl im README läuft so, wie er dort steht.
- [ ] **Commit:** `docs: README auf den Stand mit batch-writer bringen`

---

## Aufgabe 12: Messwerte nachtragen

**Dateien:** `docs/spec-batch-writer.md` (3.3 und Abnahmekriterium S4), dieser Plan (Aufgabe 6).

Die Spec hatte für S4 «unter 30 Transaktionen» geschätzt. Gemessen sind 34 (Start mit Flyway
plus 2 Stapel). Beim Test von Aufgabe 6 war der erwartete Fehlschlag falsch beschrieben. Beides
wird korrigiert, statt es stehen zu lassen.

- **Test:** Die Zahlen in der Spec stimmen mit der Messung überein.
- [ ] **Commit:** `docs: Spezifikation und Plan mit Messwerten nachführen`

---

## Abschluss-Prüfung

- [ ] `mvn clean test`: alles grün, in einem Lauf (S1)
- [ ] `bash scripts/szenarien.sh`: S2 bis S8 bestanden
- [ ] `git status --short`: sauber, `.env` taucht nicht auf
- [ ] `git log --oneline`: jede Aufgabe oben hat genau einen Commit mit der genannten Message
- [ ] Tag setzen und pushen: `git tag bewertung-1 && git push origin main bewertung-1`
