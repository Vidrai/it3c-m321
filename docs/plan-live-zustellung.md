# Umsetzungsplan Live-Zustellung

Grundlage: [`spec-live-zustellung.md`](spec-live-zustellung.md)

## Schritt 1: Eigene Queue pro Instanz

**Ziel:** Queue chat.delivery.<instanz-id> am Fanout, wird mit der Verbindung gelöscht, höchstens 1000, älteste fliegt raus

**Warum an dieser Stelle:** Ohne Queue kommt nichts an, alles andere baut darauf auf

**Auftrag an die KI:** Erstelle pro Instanz eine RabbitMQ-Queue chat.delivery.<instanz-id>. Binde sie an den Fanout chat.delivery. Setze auto-delete, maximal 1000 Nachrichten und drop-head.

**Test:** Integrationstest: Queue existiert, hängt an chat.delivery, hat die Grenze

**Geprüft und korrigiert:** Geprüft, dass jede Instanz beim Start ihre eigene Queue chat.delivery.<instanz-id> erstellt und an den Fanout chat.delivery bindet. Die Queue ist als auto-delete markiert, hat x-max-length: 1000 und x-overflow: drop-head. Damit wird sie geschlossen, wenn die Instanz weg ist, und bei Überlauf fliegt die älteste Nachricht heraus.

## Schritt 2: Stream-Endpunkt

**Ziel:** GET /stream?roomId=… mit text/event-stream, die Instanz merkt sich, wer in welchem Raum ist

**Warum an dieser Stelle:** Die Clients müssen sich verbinden können, bevor man ihnen etwas schickt

**Auftrag an die KI:** Baue GET /stream?roomId=… als SSE-Endpunkt. Sende Content-Type: text/event-stream. Speichere, welcher Client in welchem Raum ist.

**Test:** Verbindung wird geöffnet, Content-Type stimmt

**Geprüft und korrigiert:** Geprüft, dass GET /stream?roomId=… eine SSE-Verbindung öffnet und Content-Type: text/event-stream zurückgibt. Die Instanz speichert beim Verbinden, welcher Client zu welchem Raum gehört, und entfernt ihn wieder, wenn die Verbindung endet.

## Schritt 3: Zustellen nach Raum

**Ziel:** Nachricht aus der eigenen Queue an alle Clients des Raums, mit id: und data:

**Warum an dieser Stelle:** Verbindet Schritt 1 und 2

**Auftrag an die KI:** Lies Nachrichten aus der eigenen Queue. Sende sie nur an Clients im richtigen Raum als SSE-Event mit id: und data:.

**Test:** Zwei-Instanzen-Test (S3): beide Clients in Raum A bekommen alles, Raum B nichts

**Geprüft und korrigiert:** Geprüft, dass Nachrichten aus der eigenen Queue gelesen und nur an Clients im gleichen roomId geschickt werden. Jede Nachricht wird als SSE-Event mit id: und data: gesendet. RabbitMQ filtert nicht nach Raum; die Instanz entscheidet selbst, wer die Nachricht bekommt.

## Schritt 4: Nachholen mit Last-Event-ID

**Ziel:** Speicher der letzten 1000 pro Raum, beim Neuverbinden alles nach der ID schicken

**Warum an dieser Stelle:** Braucht den funktionierenden Live-Weg aus Schritt 3

**Auftrag an die KI:** Speichere pro Raum die letzten 1000 Nachrichten. Beim Neuverbinden lies Last-Event-ID und schicke alle neueren Nachrichten zuerst.

**Test:** Neuverbindungs-Test (S7)

**Geprüft und korrigiert:** (wird nach dem Schritt ausgefüllt)

## Schritt 5: Stack und Doku

**Ziel:** .env.example, README-Tabelle «Stand», docker-compose.yml prüfen

**Warum an dieser Stelle:** Erst wenn der Dienst im Test läuft

**Auftrag an die KI:** Prüfe .env.example, README-Tabelle «Stand» und docker-compose.yml. Sorge dafür, dass docker compose up -d --build funktioniert.

**Test:** docker compose up -d --build, S2

**Geprüft und korrigiert:** (wird nach dem Schritt ausgefüllt)

## Schritt 6: Prüfskript

**Ziel:** scripts/szenarien.sh für S2 bis S8 der Bewertung 2

**Warum an dieser Stelle:** Braucht den laufenden Stack

**Auftrag an die KI:** Erstelle scripts/szenarien.sh. Es soll S2 bis S8 automatisch prüfen und am Ende zeigen, welche Tests bestanden haben.

**Test:** Skript läuft durch

**Geprüft und korrigiert:** (wird nach dem Schritt ausgefüllt)
