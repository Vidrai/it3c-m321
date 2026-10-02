#!/usr/bin/env bash
#
# Führt die Szenarien S2 bis S8 aus der Bewertung aus, in dieser
# Reihenfolge, auf demselben Stack, ohne Aufräumen dazwischen.
# Gemessen wird wie in der Bewertung von innen: psql im Postgres-Container,
# rabbitmqctl im RabbitMQ-Container. Die Befehle stehen auch in
# docs/spec-batch-writer.md, Abschnitt 6.
#
# S1 (mvn clean test) läuft hier NICHT mit, weil es nichts mit dem Stack
# zu tun hat. Vorher selbst ausführen.
#
# Aufruf im Wurzelverzeichnis, in Git Bash oder einer Linux-Shell:
#   cp .env.example .env        # nur beim ersten Mal
#   bash scripts/szenarien.sh

set -u

# Git Bash unter Windows würde Argumente, die wie Pfade aussehen
# (zum Beispiel /dev/null im curl-Befehl), sonst in Windows-Pfade umbauen.
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.." || exit 1

if [ ! -f .env ]; then
    echo ".env fehlt. Zuerst: cp .env.example .env"
    exit 1
fi

# Variablen aus .env laden. tr entfernt Windows-Zeilenenden, sonst hinge
# an jedem Wert ein unsichtbares \r und psql fände den Benutzer nicht.
set -a
# shellcheck disable=SC1090
source <(tr -d '\r' < .env)
set +a

PASSED_COUNT=0
FAILED_COUNT=0
SUMMARY=""

# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------

# Hält ein Ergebnis fest und gibt es sofort aus.
# $1 Szenario, $2 Kriterium, $3 gemessen, $4 erwartet, $5 "ok" oder "fail"
record() {
    local line
    if [ "$5" = "ok" ]; then
        PASSED_COUNT=$((PASSED_COUNT + 1))
        line="BESTANDEN        $1  $2: gemessen $3, erwartet $4"
    else
        FAILED_COUNT=$((FAILED_COUNT + 1))
        line="NICHT BESTANDEN  $1  $2: gemessen $3, erwartet $4"
    fi
    echo "  $line"
    SUMMARY="${SUMMARY}${line}"$'\n'
}

# Eine SQL-Abfrage im Postgres-Container, nur das Ergebnis, ohne Rahmen.
sql() {
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1" 2>/dev/null | tr -d '\r'
}

# Eine Spalte aus rabbitmqctl list_queues für eine Queue.
# $1 Queue-Name, $2 Spalte: 2 = messages (bereit + unbestätigt), 3 = consumers
queue_value() {
    docker compose exec -T rabbitmq rabbitmqctl list_queues -q name messages consumers 2>/dev/null \
        | tr -d '\r' \
        | awk -v queue="$1" -v column="$2" '$1 == queue { print $column }'
}

# Eine neue UUID. PostgreSQL kann das selbst, so braucht es kein uuidgen.
new_uuid() {
    sql "SELECT gen_random_uuid()"
}

# Zählt die Zeilen eines Raums. Ist Postgres gerade weg, kommt 0 zurück.
count_room() {
    local count
    count=$(sql "SELECT count(*) FROM message WHERE room_id = '$1'")
    if [ -z "$count" ]; then
        count=0
    fi
    echo "$count"
}

# Alle Transaktionen der Datenbank bisher, abgeschlossen oder zurückgerollt.
transaction_total() {
    sql "SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()"
}

# Schickt Nachrichten über POST /messages, von INNEN aus dem Netz chat-net.
# $1 Anzahl, $2 Raum-ID, $3 Textanfang. Gibt aus, wie oft welcher
# HTTP-Status kam (erwartet: nur 202).
send_messages() {
    docker run --rm --network chat-net \
        -e COUNT="$1" -e ROOM="$2" -e PREFIX="$3" \
        --entrypoint sh curlimages/curl -c '
            for i in $(seq 1 "$COUNT"); do
                curl -s -o /dev/null -w "%{http_code}\n" -X POST http://chat-service:8080/messages \
                    -H "Content-Type: application/json" \
                    -d "{\"roomId\":\"$ROOM\",\"senderId\":\"anna\",\"senderName\":\"Anna\",\"content\":\"$PREFIX $i\"}"
            done' \
        | sort | uniq -c | tr -d '\r'
}

# Wartet, bis ein Raum die erwartete Anzahl Zeilen hat.
# $1 Raum-ID, $2 erwartete Anzahl, $3 höchstens so viele Sekunden.
# Gibt die gebrauchten Sekunden aus, oder "zu lange".
wait_for_room() {
    local start now elapsed count
    start=$(date +%s)
    while true; do
        count=$(count_room "$1")
        now=$(date +%s)
        elapsed=$((now - start))
        if [ "$count" = "$2" ]; then
            echo "$elapsed"
            return 0
        fi
        if [ "$elapsed" -gt "$3" ]; then
            echo "zu lange ($count von $2 nach $elapsed s)"
            return 1
        fi
        sleep 2
    done
}

# Wartet, bis an chat.persist genau so viele Verbraucher hängen.
# $1 Anzahl, $2 höchstens so viele Sekunden.
wait_for_consumers() {
    local waited=0 consumers
    while [ "$waited" -le "$2" ]; do
        consumers=$(queue_value chat.persist 3)
        if [ "$consumers" = "$1" ]; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

# ---------------------------------------------------------------------------
# S2: Stack starten, alle Dienste laufen, kein Port nach aussen
# ---------------------------------------------------------------------------
echo "S2  docker compose up -d --build"
docker compose up -d --build

echo "    warte, bis Flyway die Tabelle angelegt hat und der batch-writer an der Queue hängt ..."
waited=0
while [ "$(sql "SELECT to_regclass('public.message') IS NOT NULL")" != "t" ] && [ "$waited" -lt 180 ]; do
    sleep 3
    waited=$((waited + 3))
done
wait_for_consumers 1 120

running=$(docker compose ps --format '{{.Service}} {{.State}}' | tr -d '\r' | grep -c ' running$')
if [ "$running" = "4" ]; then
    record S2 "Dienste running" "$running" "4" ok
else
    record S2 "Dienste running" "$running" "4" fail
fi

published=$(docker compose ps --format '{{.Ports}}' | grep -c -- '->')
if [ "$published" = "0" ]; then
    record S2 "veröffentlichte Ports" "$published" "0" ok
else
    record S2 "veröffentlichte Ports" "$published" "0" fail
fi

columns=$(sql "SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name = 'message'")
expected_columns="id,room_id,sender_id,sender_name,content,sent_at"
if [ "$columns" = "$expected_columns" ]; then
    record S2 "Spalten der Tabelle message" "$columns" "$expected_columns" ok
else
    record S2 "Spalten der Tabelle message" "$columns" "$expected_columns" fail
fi

# ---------------------------------------------------------------------------
# S3: 1000 Nachrichten, nach höchstens 60 s alle in der Tabelle, Queue leer
# ---------------------------------------------------------------------------
echo "S3  1000 Nachrichten über POST /messages"
room=$(new_uuid)
send_messages 1000 "$room" "S3"
seconds=$(wait_for_room "$room" 1000 60)
if [ $? -eq 0 ]; then
    record S3 "1000 in der Tabelle" "nach $seconds s" "≤ 60 s" ok
else
    record S3 "1000 in der Tabelle" "$seconds" "≤ 60 s" fail
fi

sleep 2
left=$(queue_value chat.persist 2)
if [ "$left" = "0" ]; then
    record S3 "chat.persist leer" "$left" "0" ok
else
    record S3 "chat.persist leer" "$left" "0" fail
fi

# ---------------------------------------------------------------------------
# S4: batch-writer gestoppt, 1000 senden, starten: nichts verloren,
#     höchstens 100 Transaktionen
# ---------------------------------------------------------------------------
echo "S4  batch-writer stoppen, 1000 Nachrichten, batch-writer starten"
docker compose stop batch-writer
room=$(new_uuid)
send_messages 1000 "$room" "S4"
echo "    chat.persist enthält jetzt $(queue_value chat.persist 2) Nachrichten"

# PostgreSQL führt seine Statistik mit bis zu 10 s Verzögerung nach.
# Darum vor dem Messen kurz warten, sonst zählen alte Transaktionen mit.
sleep 12
transactions_before=$(transaction_total)
docker compose start batch-writer
seconds=$(wait_for_room "$room" 1000 120)
if [ $? -eq 0 ]; then
    record S4 "nichts verloren" "1000 nach $seconds s" "1000" ok
else
    record S4 "nichts verloren" "$seconds" "1000" fail
fi
sleep 12
transactions_after=$(transaction_total)
transactions=$((transactions_after - transactions_before))
if [ "$transactions" -le 100 ]; then
    record S4 "Transaktionen (inkl. Start mit Flyway und eigener Abfragen)" "$transactions" "≤ 100" ok
else
    record S4 "Transaktionen (inkl. Start mit Flyway und eigener Abfragen)" "$transactions" "≤ 100" fail
fi
writing=$(sql "SELECT count(DISTINCT xmin::text) FROM message WHERE room_id = '$room'")
echo "    davon haben $writing Transaktionen die 1000 Zeilen geschrieben (xmin)"

# ---------------------------------------------------------------------------
# S5: dieselbe Nachricht zweimal direkt in chat.persist, nur mit content_type
# ---------------------------------------------------------------------------
echo "S5  dieselbe Nachricht zweimal direkt in chat.persist"
id=$(new_uuid)
room=$(new_uuid)
payload="{\"id\":\"$id\",\"roomId\":\"$room\",\"senderId\":\"anna\",\"senderName\":\"Anna\",\"content\":\"S5 Duplikat\",\"sentAt\":\"2026-10-02T12:00:00.123456Z\"}"
dead_letters_before=$(queue_value chat.dlq 2)
for copy in 1 2; do
    docker compose exec -T rabbitmq rabbitmqadmin -u "$RABBITMQ_USER" -p "$RABBITMQ_PASSWORD" \
        publish exchange=amq.default routing_key=chat.persist \
        properties='{"content_type":"application/json"}' payload="$payload"
done
wait_for_room "$room" 1 30 > /dev/null
sleep 3
rows=$(sql "SELECT count(*) FROM message WHERE id = '$id'")
if [ "$rows" = "1" ]; then
    record S5 "Zeilen mit dieser id" "$rows" "1" ok
else
    record S5 "Zeilen mit dieser id" "$rows" "1" fail
fi
dead_letters=$(queue_value chat.dlq 2)
if [ "$dead_letters" = "0" ]; then
    record S5 "chat.dlq" "$dead_letters (vorher $dead_letters_before)" "0" ok
else
    record S5 "chat.dlq" "$dead_letters (vorher $dead_letters_before)" "0" fail
fi

# ---------------------------------------------------------------------------
# S6: zwei Instanzen, 1000 Nachrichten, alle da, keine doppelt
# ---------------------------------------------------------------------------
echo "S6  docker compose up -d --scale batch-writer=2"
docker compose up -d --scale batch-writer=2
if wait_for_consumers 2 120; then
    record S6 "Verbraucher an chat.persist" "2" "2" ok
else
    record S6 "Verbraucher an chat.persist" "$(queue_value chat.persist 3)" "2" fail
fi

scale_start=$(date -u +%Y-%m-%dT%H:%M:%SZ)
room=$(new_uuid)
send_messages 1000 "$room" "S6"
seconds=$(wait_for_room "$room" 1000 60)
counts=$(sql "SELECT count(*) || '/' || count(DISTINCT content) FROM message WHERE room_id = '$room'")
if [ "$counts" = "1000/1000" ]; then
    record S6 "Zeilen/verschiedene Inhalte" "$counts" "1000/1000" ok
else
    record S6 "Zeilen/verschiedene Inhalte" "$counts" "1000/1000" fail
fi
working=$(docker compose logs --since "$scale_start" batch-writer 2>/dev/null \
    | grep 'Stored batch' | awk '{ print $1 }' | sort -u | wc -l | tr -d ' ')
if [ "$working" -ge 2 ]; then
    record S6 "Instanzen, die geschrieben haben" "$working" "2" ok
else
    record S6 "Instanzen, die geschrieben haben" "$working" "2" fail
fi

# ---------------------------------------------------------------------------
# S7: Postgres gestoppt, 300 senden, nach 15 s wieder starten:
#     nach höchstens 90 s alle 300 da, batch-writer ohne Neustart von Hand
# ---------------------------------------------------------------------------
echo "S7  Postgres stoppen, 300 Nachrichten, Postgres nach 15 s wieder starten"
restarts_before=""
for container in $(docker compose ps -q batch-writer); do
    restarts_before="$restarts_before $(docker inspect -f '{{.RestartCount}}/{{.State.StartedAt}}' "$container")"
done

room=$(new_uuid)
docker compose stop postgres
stop_time=$(date +%s)
send_messages 300 "$room" "S7"
now=$(date +%s)
remaining=$((15 - (now - stop_time)))
if [ "$remaining" -gt 0 ]; then
    sleep "$remaining"
fi
docker compose start postgres
seconds=$(wait_for_room "$room" 300 90)
if [ $? -eq 0 ]; then
    record S7 "300 in der Tabelle" "nach $seconds s" "≤ 90 s" ok
else
    record S7 "300 in der Tabelle" "$seconds" "≤ 90 s" fail
fi

dead_letters=$(queue_value chat.dlq 2)
if [ "$dead_letters" = "0" ]; then
    record S7 "chat.dlq" "$dead_letters" "0" ok
else
    record S7 "chat.dlq" "$dead_letters" "0" fail
fi

restarts_after=""
for container in $(docker compose ps -q batch-writer); do
    restarts_after="$restarts_after $(docker inspect -f '{{.RestartCount}}/{{.State.StartedAt}}' "$container")"
done
if [ "$restarts_before" = "$restarts_after" ]; then
    record S7 "batch-writer ohne Neustart" "gleich" "gleich" ok
else
    record S7 "batch-writer ohne Neustart" "vorher$restarts_before, nachher$restarts_after" "gleich" fail
fi

# ---------------------------------------------------------------------------
# S8: Quelltext nach CLAUDE.md
# ---------------------------------------------------------------------------
echo "S8  Quelltext von batch-writer/"
streams=$(grep -rnE '\.stream\(|Stream\.of|Collectors\.' batch-writer/src | wc -l | tr -d ' ')
if [ "$streams" = "0" ]; then
    record S8 "Streams" "$streams" "0" ok
else
    record S8 "Streams" "$streams" "0" fail
fi

# Grobe Prüfung: Über jeder Klasse und jeder Methode muss (über den
# Annotationen) ein Kommentar enden. Inhalt von Text-Blöcken (""") wird
# übersprungen, weil SQL darin wie eine Methode aussehen kann.
missing=$(find batch-writer/src -name '*.java' -print0 | xargs -0 awk '
    FNR == 1 { in_text_block = 0; previous = "" }
    {
        line = $0
        sub(/^[ \t]+/, "", line)
        quotes = gsub(/"""/, "&", line)
        if (in_text_block) {
            if (quotes % 2 == 1) { in_text_block = 0 }
            next
        }
        if (quotes % 2 == 1) { in_text_block = 1 }
        if (line ~ /^@/) { next }
        is_type = line ~ /^((public|protected|private|static|final|abstract)[ ]+)*(class|record|interface|enum)[ ]/
        is_method = line ~ /^((public|protected|private|static|final|synchronized)[ ]+)*[A-Za-z][A-Za-z0-9_<>,. ?\[\]]*[ ]+[a-zA-Z_][A-Za-z0-9_]*[ ]*\(/ \
                    && line !~ /=/ && line !~ /^(return|new|throw|else|if|for|while|catch|switch|try)[ (]/
        is_constructor = line ~ /^(public|protected|private)[ ]+[A-Z][A-Za-z0-9_]*[ ]*\(/
        if ((is_type || is_method || is_constructor) && previous !~ /(\*\/|^\/\/)/) {
            print FILENAME ":" FNR ": " line
        }
        if (line != "") { previous = line }
    }')
missing_count=0
if [ -n "$missing" ]; then
    missing_count=$(echo "$missing" | wc -l | tr -d ' ')
    echo "$missing"
fi
if [ "$missing_count" = "0" ]; then
    record S8 "Klassen/Methoden ohne Kommentar" "$missing_count" "0" ok
else
    record S8 "Klassen/Methoden ohne Kommentar" "$missing_count" "0" fail
fi

tracked_env=$(git ls-files .env | wc -l | tr -d ' ')
if [ "$tracked_env" = "0" ]; then
    record S8 ".env im Repository" "$tracked_env" "0" ok
else
    record S8 ".env im Repository" "$tracked_env" "0" fail
fi

# ---------------------------------------------------------------------------
echo
echo "Zusammenfassung"
printf '%s' "$SUMMARY"
echo
echo "$PASSED_COUNT bestanden, $FAILED_COUNT nicht bestanden"
if [ "$FAILED_COUNT" -gt 0 ]; then
    exit 1
fi
