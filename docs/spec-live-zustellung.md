Vertrag
Die Nachricht kommt genau so am Exchange an, wie es in docs/spec-batch-writer.md, Abschnitt 2.2 steht. Am Stream geht sie als data: heraus. Die Nachrichten-ID ist dabei id:.

Das weiss ich, weil in chat-service, MessagePublisher.java, die Nachricht an chat.delivery geschickt wird. Der Endpunkt ist GET /stream?roomId=…. Der Content-Type ist text/event-stream.

Verhalten (je Szenario)
S3 — Raum-Filter
Beim Verbinden merkt sich die Instanz, in welchem Raum der Client ist. Kommt eine Nachricht, schaut die Instanz auf roomId. Sie schickt die Nachricht nur an die Clients im gleichen Raum. RabbitMQ filtert nichts. Die Instanz macht das.

Jede Nachricht kommt nur einmal an. Die Nachrichten eines Absenders kommen in der richtigen Reihenfolge an. Das funktioniert, weil eine Queue die Nachrichten in der gleichen Reihenfolge wieder ausgibt, wie sie reinkommen. Pro Instanz holt nur ein Abholer die Nachrichten ab. Deshalb kommt nichts doppelt und die Reihenfolge bleibt gleich.

S4 — Zurück auf eine Instanz
Wenn man von zwei Instanzen auf eine Instanz zurückgeht, wird eine Instanz beendet. Die Verbindung zur Queue wird geschlossen. RabbitMQ löscht die Queue. Nach spätestens 30 Sekunden hängt nur noch eine Queue an chat.delivery.

S5 — Zustellung ohne Datenbank
Die Nachricht geht auf zwei Wegen. Ein Weg geht zur Datenbank über chat.persist. Der andere Weg geht über den Fanout zu den Clients. Der Weg zu den Clients braucht die Datenbank nicht. Deshalb kommt die Nachricht beim Client an, auch wenn die Datenbank und der Batch-Writer gestoppt sind. Nach dem Neustart holt der Batch-Writer die Nachrichten aus chat.persist und schreibt sie in die Tabelle.

S6 — Instanz eingefroren
Die Instanz wird eingefroren. Deshalb kann RabbitMQ die Queue nicht löschen. Die Nachrichten bleiben in der Queue. Die Queue wird immer voller.

Wenn die Queue voll ist, zum Beispiel bei 1001 Nachrichten, wird die älteste Nachricht rausgeworfen. Die neue Nachricht kommt hinein. Für den Benutzer sind die neuesten Nachrichten wichtiger. Der Rest der Anlage merkt davon nichts.

Die rausgeworfene Nachricht geht nicht verloren. Sie ist noch in der Datenbank. Sie wurde über chat.persist gespeichert.

S7 — Neuverbindung
Jede Nachricht im Stream hat eine Nummer. Die Nummer heisst id. Wenn ein Client neu verbindet, schickt er seine letzte Nummer mit. Das heisst Last-Event-ID. Die Instanz schickt ihm dann alle Nachrichten nach dieser Nummer. Danach geht es live weiter.

Die Instanz merkt sich zum Beispiel die letzten 1000 Nachrichten pro Raum im Speicher. Wenn die Last-Event-ID nicht mehr im Speicher ist, zum Beispiel weil sie zu alt ist oder unbekannt ist, schickt die Instanz die letzten 1000 Nachrichten noch einmal. Danach geht es live weiter.

Die Datenbank hilft nicht. Sie kann in S5 gestoppt sein. Ausserdem sind die IDs UUIDs. UUIDs haben keine Reihenfolge. Deshalb kann man daraus nicht ablesen, welche Nachricht zuerst kam.

Queues
Der Client ist mit SSE immer nur mit einer Instanz verbunden. Wenn zwei Instanzen in der gleichen Queue sind, bekommt nur eine Instanz die Nachricht. Die andere bekommt nichts. Deshalb macht der Fanout Exchange für jede Queue eine Kopie der Nachricht.

Die Queue hat genau eine Verbindung. Wenn die Verbindung geschlossen wird, löscht RabbitMQ die Queue sofort. Das passiert auch, wenn der letzte Abholer sich abmeldet. Das braucht man, damit der «Briefkasten» nicht überläuft. Sonst würden immer wieder Kopien für Instanzen entstehen, die nicht mehr gebraucht werden.

Name	Lebensdauer	Grenze	Was beim Erreichen der Grenze passiert
chat.persist	Bleibt bestehen	Keine	Es wird nichts gelöscht. Die Nachrichten warten, bis der Batch-Writer sie abholt.
chat.dlq	Bleibt bestehen	Keine	Es wird nichts gelöscht.
chat.delivery.<instanz-id>	Wird gelöscht, wenn die Verbindung geschlossen wird oder der letzte Abholer weg ist	1000 Nachrichten	Die älteste Nachricht wird gelöscht. Die neue Nachricht kommt hinein.
Abnahmekriterien
Raum A = 3f2b1c4e-0000-0000-0000-00000000000a
Raum B = 3f2b1c4e-0000-0000-0000-00000000000b

send <instanz> <raum> <anzahl> schickt Nachrichten über POST /messages an die genannte Instanz.

Szenario	Befehl	Erwartung
Szenario	Befehl	Erwartung
S1	mvn clean test	Alle Tests laufen durch und sind grün.
S2	cp .env.example .env
docker compose up -d --build
docker compose ps	Alle Dienste sind running. In der Spalte Ports steht kein ->. Es wird kein Port veröffentlicht.
S3	docker compose up -d --scale chat-service=2
docker run --rm --network chat-net curlimages/curl -N "http://it3c-m321-chat-service-1:8080/stream?roomId=3f2b1c4e-0000-0000-0000-00000000000a"
docker run --rm --network chat-net curlimages/curl -N "http://it3c-m321-chat-service-2:8080/stream?roomId=3f2b1c4e-0000-0000-0000-00000000000a"
docker run --rm --network chat-net curlimages/curl -N "http://it3c-m321-chat-service-1:8080/stream?roomId=3f2b1c4e-0000-0000-0000-00000000000b"
send it3c-m321-chat-service-1 3f2b1c4e-0000-0000-0000-00000000000a 200
send it3c-m321-chat-service-2 3f2b1c4e-0000-0000-0000-00000000000a 200	Die Clients in Raum A bekommen alle 400 Nachrichten. Der Client in Raum B bekommt nichts. Jede Nachricht kommt nur einmal an. Die Nachrichten eines Absenders kommen in der richtigen Reihenfolge an.
S4	docker compose up -d --scale chat-service=2
docker compose exec rabbitmq rabbitmqctl list_bindings source_name destination_name
docker compose up -d --scale chat-service=1
30 Sekunden warten
docker compose exec rabbitmq rabbitmqctl list_bindings source_name destination_name
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers	Zuerst hängen zwei Queues an chat.delivery. Nach dem Zurückgehen auf eine Instanz und 30 Sekunden Warten hängt nur noch eine Queue an chat.delivery. Die Queue der Instanz, die weg ist, wurde gelöscht. chat.persist und chat.dlq sind noch da.
S5	docker run --rm --network chat-net curlimages/curl -N "http://it3c-m321-chat-service-1:8080/stream?roomId=3f2b1c4e-0000-0000-0000-00000000000a"
docker compose stop postgres batch-writer
send it3c-m321-chat-service-1 3f2b1c4e-0000-0000-0000-00000000000a 100
docker compose start postgres batch-writer
docker compose exec postgres psql -U chat -d chat -c "SELECT COUNT(*) FROM message WHERE room_id = '3f2b1c4e-0000-0000-0000-00000000000a';"	Der Client bekommt alle 100 Nachrichten nach spätestens 5 Sekunden. Das geht, obwohl die Datenbank und der Batch-Writer gestoppt sind. Nach dem Neustart schreibt der Batch-Writer die 100 Nachrichten in die Tabelle. In der Tabelle sind dann 100 Nachrichten mehr.
S6	docker run --rm --network chat-net curlimages/curl -N "http://it3c-m321-chat-service-1:8080/stream?roomId=3f2b1c4e-0000-0000-0000-00000000000a"
docker run --rm --network chat-net curlimages/curl -N "http://it3c-m321-chat-service-2:8080/stream?roomId=3f2b1c4e-0000-0000-0000-00000000000a"
docker pause it3c-m321-chat-service-2
send it3c-m321-chat-service-1 3f2b1c4e-0000-0000-0000-00000000000a 5000
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
docker unpause it3c-m321-chat-service-2
send it3c-m321-chat-service-1 3f2b1c4e-0000-0000-0000-00000000000a 1	Die Queue von Instanz 2 hat höchstens 1000 Nachrichten. Der Client an Instanz 1 bekommt alle 5000 Nachrichten. Nach unpause stellt Instanz 2 die neue Nachricht wieder zu.
S7	docker run --rm --network chat-net curlimages/curl -N "http://it3c-m321-chat-service-1:8080/stream?roomId=3f2b1c4e-0000-0000-0000-00000000000a"
Strg+C nach der ersten Nachricht
send it3c-m321-chat-service-1 3f2b1c4e-0000-0000-0000-00000000000a 50
10 Sekunden warten
docker run --rm --network chat-net curlimages/curl -N -H "Last-Event-ID: <id>" "http://it3c-m321-chat-service-1:8080/stream?roomId=3f2b1c4e-0000-0000-0000-00000000000a"	Der Client bekommt genau die 50 fehlenden Nachrichten. Sie kommen in der richtigen Reihenfolge. Keine Nachricht kommt doppelt. Danach kommen neue Nachrichten live. <id> ist die id:-Zeile der letzten Nachricht, die der Client vor Strg+C gesehen hat.
S8	grep -R "Stream" chat-service/src
grep -R "//" chat-service/src
git ls-files .env	Es gibt keine Streams im Code. Über jeder Klasse und Methode ist ein Kommentar. .env ist nicht im Repository.