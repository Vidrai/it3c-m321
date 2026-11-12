#!/usr/bin/env bash
#
# Führt die Szenarien S2 bis S8 aus Bewertung 2 (Live-Zustellung) aus, in
# dieser Reihenfolge, auf demselben Stack, ohne Aufräumen dazwischen.
# Die Befehle entsprechen den Abnahmekriterien in docs/spec-live-zustellung.md.
#
# Gemessen wird von innen, im Netz chat-net: Jeder SSE-Client ist ein
# kleiner curl-Container (docker run -d … curl -N), und was er empfangen
# hat, liest das Skript mit "docker logs". Queues und Bindungen zeigt
# rabbitmqctl im RabbitMQ-Container.
#
# S1 (mvn clean test) läuft hier NICHT mit. Vorher selbst ausführen.
# Die Szenarien aus Bewertung 1 stehen in scripts/szenarien-bewertung-1.sh.
#
# Aufruf im Wurzelverzeichnis, in Git Bash oder einer Linux-Shell:
#   cp .env.example .env        # nur beim ersten Mal
#   bash scripts/szenarien.sh

set -u

# Git Bash unter Windows würde Argumente, die wie Pfade aussehen, sonst in
# Windows-Pfade umbauen.
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.." || exit 1

if [ ! -f .env ]; then
    echo ".env fehlt. Zuerst: cp .env.example .env"
    exit 1
fi

# Variablen aus .env laden, ohne Windows-Zeilenenden.
set -a
# shellcheck disable=SC1090
source <(tr -d '\r' < .env)
set +a

PASSED_COUNT=0
FAILED_COUNT=0
SUMMARY=""
WORK_DIR=$(mktemp -d)

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

# Vergleicht gemessen und erwartet und hält das Ergebnis fest.
# $1 Szenario, $2 Kriterium, $3 gemessen, $4 erwartet
check_equal() {
    if [ "$3" = "$4" ]; then
        record "$1" "$2" "$3" "$4" ok
    else
        record "$1" "$2" "$3" "$4" fail
    fi
}

# Eine SQL-Abfrage im Postgres-Container, nur das Ergebnis.
sql() {
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1" 2>/dev/null | tr -d '\r'
}

# Eine neue UUID für einen Raum, damit jedes Szenario nur seine Nachrichten zählt.
new_uuid() {
    sql "SELECT gen_random_uuid()"
}

# Der Containername der n-ten chat-service-Instanz (1 oder 2). Der Name hängt
# vom Ordnernamen des Projekts ab, darum wird er nachgeschlagen, nicht fest
# hingeschrieben.
instance() {
    docker compose ps --format '{{.Name}}' chat-service | tr -d '\r' | sort | sed -n "${1}p"
}

# Wie viele Queues "chat.delivery.…" am Fanout chat.delivery hängen.
delivery_bindings() {
    docker compose exec -T rabbitmq rabbitmqctl list_bindings -q source_name destination_name 2>/dev/null \
        | tr -d '\r' \
        | awk '$1 == "chat.delivery" && $2 ~ /^chat\.delivery\./' \
        | wc -l | tr -d ' '
}

# Wie viele Queues "chat.delivery.…" gerade einen Abholer haben.
delivery_consumers() {
    docker compose exec -T rabbitmq rabbitmqctl list_queues -q name consumers 2>/dev/null \
        | tr -d '\r' \
        | awk '$1 ~ /^chat\.delivery\./ && $2 == 1' \
        | wc -l | tr -d ' '
}

# Die meisten Nachrichten, die gerade in einer der Queues "chat.delivery.…" liegen.
largest_delivery_queue() {
    docker compose exec -T rabbitmq rabbitmqctl list_queues -q name messages 2>/dev/null \
        | tr -d '\r' \
        | awk '$1 ~ /^chat\.delivery\./ { print $2 }' \
        | sort -n | tail -1
}

# Wartet, bis eine Bedingung den erwarteten Wert liefert.
# $1 Befehl, $2 erwarteter Wert, $3 höchstens so viele Sekunden
wait_for() {
    local waited=0
    while [ "$waited" -le "$3" ]; do
        if [ "$(eval "$1")" = "$2" ]; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# Schickt Nachrichten über POST /messages an eine bestimmte Instanz.
# $1 Instanz (Containername), $2 Anzahl, $3 Raum-ID, $4 Textanfang.
# Die Texte heissen "<Textanfang> 0", "<Textanfang> 1" und so weiter.
send_messages() {
    docker run --rm --network chat-net \
        -e HOST="$1" -e COUNT="$2" -e ROOM="$3" -e PREFIX="$4" \
        --entrypoint sh curlimages/curl -c '
            i=0
            while [ "$i" -lt "$COUNT" ]; do
                curl -s -o /dev/null -X POST "http://$HOST:8080/messages" \
                    -H "Content-Type: application/json" \
                    -d "{\"roomId\":\"$ROOM\",\"senderId\":\"anna\",\"senderName\":\"Anna\",\"content\":\"$PREFIX $i\"}"
                i=$((i + 1))
            done'
}

# Startet einen SSE-Client als eigenen Container und wartet, bis er verbunden ist.
# $1 Name des Clients, $2 Instanz, $3 Raum-ID, $4 Last-Event-ID (leer = keine)
start_client() {
    docker rm -f "$1" > /dev/null 2>&1
    if [ -z "${4:-}" ]; then
        docker run -d --name "$1" --network chat-net curlimages/curl -sN \
            "http://$2:8080/stream?roomId=$3" > /dev/null
    else
        docker run -d --name "$1" --network chat-net curlimages/curl -sN \
            -H "Last-Event-ID: $4" "http://$2:8080/stream?roomId=$3" > /dev/null
    fi
    wait_for "client_log $1 | grep -c '^:connected'" 1 30
}

# Alles, was ein Client bisher empfangen hat.
client_log() {
    docker logs "$1" 2>/dev/null | tr -d '\r'
}

# Wie viele Nachrichten (data:-Zeilen) ein Client empfangen hat.
client_count() {
    client_log "$1" | grep -c '^data:'
}

# Wie viele verschiedene Nachrichten-IDs ein Client empfangen hat.
client_distinct_ids() {
    client_log "$1" | grep '^id:' | sort -u | wc -l | tr -d ' '
}

# Die Nummern der Nachrichten mit einem bestimmten Textanfang, in der
# Reihenfolge der Ankunft, mit Komma getrennt.
client_numbers() {
    client_log "$1" | grep '^data:' \
        | grep -o '"content":"[^"]*"' \
        | sed 's/^"content":"//; s/"$//' \
        | awk -v prefix="$2" 'index($0, prefix " ") == 1 { print substr($0, length(prefix) + 2) }' \
        | paste -sd, -
}

# Die erwartete Reihenfolge 0,1,2,…,n-1.
expected_numbers() {
    seq 0 $(($1 - 1)) | paste -sd, -
}

# ---------------------------------------------------------------------------
# S2: Stack starten, alle Dienste laufen, kein Port nach aussen
# ---------------------------------------------------------------------------
echo "S2  docker compose up -d --build"
docker compose up -d --build
wait_for "delivery_consumers" 1 120

running=$(docker compose ps --format '{{.Service}} {{.State}}' | tr -d '\r' | grep -c ' running$')
check_equal S2 "Dienste running" "$running" "4"
published=$(docker compose ps --format '{{.Ports}}' | grep -c -- '->')
check_equal S2 "veröffentlichte Ports" "$published" "0"

# ---------------------------------------------------------------------------
# S3: zwei Instanzen, je 200 Nachrichten für Raum A über jede Instanz
# ---------------------------------------------------------------------------
echo "S3  zwei Instanzen, zwei Clients in Raum A, einer in Raum B"
docker compose up -d --scale chat-service=2
wait_for "delivery_consumers" 2 120
first=$(instance 1)
second=$(instance 2)
room_a=$(new_uuid)
room_b=$(new_uuid)
start_client s3-a1 "$first" "$room_a"
start_client s3-a2 "$second" "$room_a"
start_client s3-b "$first" "$room_b"

send_messages "$first" 200 "$room_a" "S3-eins"
send_messages "$second" 200 "$room_a" "S3-zwei"
wait_for "client_count s3-a1" 400 60
wait_for "client_count s3-a2" 400 60

check_equal S3 "Client A an Instanz 1, Nachrichten" "$(client_count s3-a1)" "400"
check_equal S3 "Client A an Instanz 2, Nachrichten" "$(client_count s3-a2)" "400"
check_equal S3 "Client A an Instanz 1, verschiedene IDs" "$(client_distinct_ids s3-a1)" "400"
check_equal S3 "Client A an Instanz 2, verschiedene IDs" "$(client_distinct_ids s3-a2)" "400"
check_equal S3 "Client in Raum B, Nachrichten" "$(client_count s3-b)" "0"
expected_order=$(expected_numbers 200)
for client in s3-a1 s3-a2; do
    for sender in S3-eins S3-zwei; do
        if [ "$(client_numbers "$client" "$sender")" = "$expected_order" ]; then
            record S3 "$client, Reihenfolge von $sender" "in Reihenfolge" "in Reihenfolge" ok
        else
            record S3 "$client, Reihenfolge von $sender" "nicht in Reihenfolge" "in Reihenfolge" fail
        fi
    done
done
docker rm -f s3-a1 s3-a2 s3-b > /dev/null

# ---------------------------------------------------------------------------
# S4: zwei Instanzen, dann zurück auf eine
# ---------------------------------------------------------------------------
echo "S4  von zwei Instanzen zurück auf eine"
check_equal S4 "Queues am Fanout vorher" "$(delivery_bindings)" "2"
docker compose up -d --scale chat-service=1
start=$(date +%s)
if wait_for "delivery_bindings" 1 30; then
    seconds=$(( $(date +%s) - start ))
    record S4 "Queues am Fanout nachher" "1 nach $seconds s" "1 nach ≤ 30 s" ok
else
    record S4 "Queues am Fanout nachher" "$(delivery_bindings) nach 30 s" "1 nach ≤ 30 s" fail
fi
queues=$(docker compose exec -T rabbitmq rabbitmqctl list_queues -q name 2>/dev/null | tr -d '\r')
persist_and_dlq=$(echo "$queues" | grep -cxE 'chat\.persist|chat\.dlq')
check_equal S4 "chat.persist und chat.dlq noch da" "$persist_and_dlq" "2"

# ---------------------------------------------------------------------------
# S5: batch-writer und postgres gestoppt, 100 Nachrichten
# ---------------------------------------------------------------------------
echo "S5  batch-writer und postgres stoppen, 100 Nachrichten"
first=$(instance 1)
room=$(new_uuid)
start_client s5 "$first" "$room"
docker compose stop batch-writer postgres
send_messages "$first" 100 "$room" "S5"
start=$(date +%s)
if wait_for "client_count s5" 100 5; then
    seconds=$(( $(date +%s) - start ))
    record S5 "Client hat alle 100" "100 nach $seconds s" "100 nach ≤ 5 s" ok
else
    record S5 "Client hat alle 100" "$(client_count s5) nach 5 s" "100 nach ≤ 5 s" fail
fi
docker compose start postgres batch-writer
wait_for "sql \"SELECT count(*) FROM message WHERE room_id = '$room'\"" 100 90
check_equal S5 "in der Tabelle nach dem Neustart" "$(sql "SELECT count(*) FROM message WHERE room_id = '$room'")" "100"
docker rm -f s5 > /dev/null

# ---------------------------------------------------------------------------
# S6: eine von zwei Instanzen angehalten, 5000 Nachrichten
# ---------------------------------------------------------------------------
echo "S6  Instanz 2 anhalten, 5000 Nachrichten"
docker compose up -d --scale chat-service=2
wait_for "delivery_consumers" 2 120
first=$(instance 1)
second=$(instance 2)
room=$(new_uuid)
start_client s6-1 "$first" "$room"
start_client s6-2 "$second" "$room"
docker pause "$second" > /dev/null

# Im Hintergrund alle 2 s nachsehen, wie voll die grösste Zustell-Queue ist.
echo 0 > "$WORK_DIR/max"
touch "$WORK_DIR/sampling"
(
    largest=0
    while [ -f "$WORK_DIR/sampling" ]; do
        current=$(largest_delivery_queue)
        if [ -n "$current" ] && [ "$current" -gt "$largest" ]; then
            largest=$current
            echo "$largest" > "$WORK_DIR/max"
        fi
        sleep 2
    done
) &

send_messages "$first" 5000 "$room" "S6"
wait_for "client_count s6-1" 5000 120
rm -f "$WORK_DIR/sampling"
sleep 3
largest=$(cat "$WORK_DIR/max")
if [ "$largest" -le 1000 ]; then
    record S6 "grösste Zustell-Queue während der Pause" "$largest" "≤ 1000" ok
else
    record S6 "grösste Zustell-Queue während der Pause" "$largest" "≤ 1000" fail
fi
check_equal S6 "Client an Instanz 1, Nachrichten" "$(client_count s6-1)" "5000"

docker unpause "$second" > /dev/null
wait_for "delivery_consumers" 2 120
send_messages "$first" 1 "$room" "S6-danach"
if wait_for "client_log s6-2 | grep -c 'S6-danach 0'" 1 60; then
    record S6 "Instanz 2 stellt nach dem Fortsetzen zu" "ja" "ja" ok
else
    record S6 "Instanz 2 stellt nach dem Fortsetzen zu" "nein" "ja" fail
fi
docker rm -f s6-1 s6-2 > /dev/null

# ---------------------------------------------------------------------------
# S7: Client trennt nach einer Nachricht, 50 Nachrichten, Neuverbindung
# ---------------------------------------------------------------------------
echo "S7  trennen, 50 Nachrichten, mit Last-Event-ID neu verbinden"
first=$(instance 1)
room=$(new_uuid)
start_client s7 "$first" "$room"
send_messages "$first" 1 "$room" "S7-vorher"
wait_for "client_count s7" 1 30
last_event_id=$(client_log s7 | grep '^id:' | head -1 | sed 's/^id://')
docker rm -f s7 > /dev/null

send_messages "$first" 50 "$room" "S7"
sleep 10
start_client s7-neu "$first" "$room" "$last_event_id"
wait_for "client_count s7-neu" 50 30

check_equal S7 "nachgeholte Nachrichten" "$(client_count s7-neu)" "50"
check_equal S7 "verschiedene IDs" "$(client_distinct_ids s7-neu)" "50"
if [ "$(client_numbers s7-neu S7)" = "$(expected_numbers 50)" ]; then
    record S7 "Reihenfolge" "in Reihenfolge" "in Reihenfolge" ok
else
    record S7 "Reihenfolge" "nicht in Reihenfolge" "in Reihenfolge" fail
fi
send_messages "$first" 1 "$room" "S7-live"
if wait_for "client_count s7-neu" 51 30; then
    record S7 "danach wieder live" "ja" "ja" ok
else
    record S7 "danach wieder live" "nein" "ja" fail
fi
docker rm -f s7-neu > /dev/null

# ---------------------------------------------------------------------------
# S8: Quelltext nach CLAUDE.md
# ---------------------------------------------------------------------------
echo "S8  Quelltext von chat-service/"
streams=$(grep -rnE '\.stream\(|Stream\.of|Collectors\.' chat-service/src | wc -l | tr -d ' ')
check_equal S8 "Streams" "$streams" "0"

# Grobe Prüfung wie in Bewertung 1: Über jeder Klasse und Methode muss (über
# den Annotationen) ein Kommentar enden. Text-Blöcke (""") werden übersprungen.
missing=$(find chat-service/src -name '*.java' -print0 | xargs -0 awk '
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
check_equal S8 "Klassen/Methoden ohne Kommentar" "$missing_count" "0"
check_equal S8 ".env im Repository" "$(git ls-files .env | wc -l | tr -d ' ')" "0"

# ---------------------------------------------------------------------------
rm -rf "$WORK_DIR"
echo
echo "Zusammenfassung"
printf '%s' "$SUMMARY"
echo
echo "$PASSED_COUNT bestanden, $FAILED_COUNT nicht bestanden"
if [ "$FAILED_COUNT" -gt 0 ]; then
    exit 1
fi
