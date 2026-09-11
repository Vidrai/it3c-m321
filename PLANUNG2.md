1. Projektübersicht & Zielsetzung
Eine selbst gehostete Echtzeit-Chat-App, aufgebaut aus kleinen, getrennten Diensten (Microservices) und gestartet mit docker-compose. Alles läuft lokal mit einem einzigen Befehl.

Kernfunktionen (MVP):

1:1-Chat — direkte Unterhaltung zwischen zwei Nutzern
Gruppen-Chat — Räume mit Mitgliedern und Rollen
Echtzeitnachrichten — Zustellung über WebSocket
Statusanzeigen — online/offline, „schreibt gerade", gelesen
Verlauf — Nachrichtenhistorie mit Nachladen (Pagination)
Login — zentral über Keycloak (OpenID Connect)
Nicht im MVP: Verschlüsselung, Datei-Uploads, Sprach-/Videoanrufe, Push aufs Handy.

Qualitätsziele:

Sicherheit: Nur ein einziger Port ist von außen erreichbar. Alle Dienste und Datenbanken liegen in einem internen Docker-Netz.
Zustandslose Dienste: Jeder Backend-Dienst kann mehrfach gestartet werden; alle Daten liegen in PostgreSQL oder Redis.
Reproduzierbarkeit: docker compose up baut aus einem leeren Zustand eine vollständig lauffähige Umgebung.
2. Technologie-Stack
2.1 Frontend
React 19 + Vite (TypeScript). Vite liefert eine reine Single-Page-App, die als statische Dateien gebaut und vom Gateway ausgeliefert wird. Kein Server-Rendering, keine BFF-Schicht — das hält den Aufbau schlanker als bei Next.js.
WebSocket-Client: native WebSocket-API in einem React-Hook mit automatischem Reconnect (Backoff mit Jitter) und Heartbeat.
State: TanStack Query für REST-Daten, Zustand für die Live-Session.
UI: Tailwind CSS + shadcn/ui.
2.2 Backend-Dienste
Dienst	Technologie	Aufgabe
gateway	nginx (alpine)	Einziger Eingang nach außen; Pfad-Routing, WebSocket-Weiterleitung, statische Frontend-Dateien
chat-service	Node.js 22 + Fastify + @fastify/websocket	WebSocket-Verbindungen, Presence, Typing, Nachrichten senden und verteilen
user-service	Node.js 22 + Fastify + Drizzle ORM	Profile, Channels, Mitgliedschaften, Historie (REST)
keycloak	Keycloak 26	Login, Token-Ausgabe, Rollen
migrate	Node.js (Einmal-Job)	Datenbank-Schema anlegen, läuft vor den Diensten
Warum Node.js für beide Dienste: Ein einheitliches Sprach-Ökosystem erlaubt geteilte TypeScript-Typen zwischen Frontend und Backend (gemeinsames packages/contracts-Paket). Das Fehlen des BFF-Servers bedeutet: der React-Client spricht direkt über das Gateway mit den APIs und nutzt den Authorization Code Flow + PKCE als öffentlicher Client — der Standardweg für reine SPA-Architekturen, ohne eigenes Client-Secret.

2.3 Daten & Messaging
Komponente	Zweck
postgres-app (PostgreSQL 17)	Nutzerprofile, Channels, Mitgliedschaften, Nachrichten
postgres-keycloak (PostgreSQL 17)	Nur Keycloak-Daten, getrennt vom App-Schema
redis (7.4)	Pub/Sub für Nachrichtenverteilung, Presence, Rate-Limits, Ticket-Speicher
Zwei getrennte Datenbanken, damit ein Keycloak-Upgrade niemals die Chat-Daten gefährdet.

Datenmodell (Kurzform):

Tabelle	Inhalt
users	id (= Keycloak sub), username, display_name
channels	id, type (direct/group), name
channel_members	channel_id, user_id, role, last_read_message_id
messages	id (UUIDv7), channel_id, sender_id, body, client_msg_id, created_at
messages.id als UUIDv7 ist zeitlich sortiert und erlaubt verteiltes Schreiben ohne zentrale Zählersequenz. client_msg_id verhindert Doppel-Nachrichten bei einem Retry nach Verbindungsabbruch. Presence liegt in Redis mit kurzer Lebensdauer, nicht in der Datenbank.

2.4 Betrieb
Docker mit Multi-Stage-Builds, Docker Compose v2, Healthchecks an jedem Dienst, .env-Datei für Passwörter (.env.example ohne echte Werte).

3. Architektur & Systementwurf
3.1 Übersicht
mermaid






chat_network (internal)

chat_edge

HTTPS / WSS

/ - statische React-App

/api/

/ws

/auth/

Mitgliedsprüfung

Browser
127.0.0.1:8080

gateway (nginx)
:8080

chat-service
:8081

user-service
:8082

keycloak :8080

redis :6379

postgres-app :5432

postgres-keycloak :5432

3.2 Aufgaben der Dienste
gateway — Terminiert alle eingehenden Verbindungen, liefert die statische React-App aus, leitet API- und WebSocket-Aufrufe weiter und blockiert nicht benötigte Keycloak-Pfade (/auth/admin/). Es ist der einzige Container mit einer Portveröffentlichung, und diese ist an 127.0.0.1 gebunden.

chat-service — Hält die offenen WebSocket-Verbindungen und speichert Nachrichten. Für jeden Channel, in dem ein verbundener Nutzer Mitglied ist, abonniert die Instanz den Redis-Kanal channel:{id}. Eingehende Nachrichten werden erst in Postgres gespeichert, dann über Redis an alle Instanzen verteilt.

user-service — Verantwortet Profile, Channels, Mitgliedschaften und Historie über eine REST-API. Der chat-service fragt hier Mitgliedschaften ab und cached die Antwort kurz in Redis.

keycloak — Realm chat mit dem öffentlichen Client chat-web (PKCE). Rollen: user, moderator, admin.

3.3 Netzwerk- und Port-Konzept
Zwei Docker-Netzwerke:

chat_edge — normales Brücken-Netz, enthält nur das Gateway. Existiert nur, damit ein Port nach außen veröffentlicht werden kann.
chat_network — mit internal: true. Container hier haben keinen Internetzugang und Docker kann keine Ports auf den Host mappen. Die Isolation ist damit technisch erzwungen, nicht bloß eine Konvention.
Dienst	Interner Port	Host-Port	Netzwerk
gateway	8080	127.0.0.1:8080	chat_edge, chat_network
chat-service	8081	–	chat_network
user-service	8082	–	chat_network
keycloak	8080	–	chat_network
postgres-app	5432	–	chat_network
postgres-keycloak	5432	–	chat_network
redis	6379	–	chat_network
migrate (Einmal-Job)	–	–	chat_network
Damit ist belegt: docker compose ps zeigt genau eine Zeile mit Host-Port. Ein Port-Scan von außerhalb der Maschine findet nichts, weil die Bindung auf Loopback beschränkt ist.

Routing im Gateway:

Pfad	Ziel
/	statische React-Dateien
/api/	user-service:8082
/ws	chat-service:8081 (WebSocket-Upgrade)
/auth/	keycloak:8080
Keycloak-Kapselung: Der Login-Redirect zwingt zu einer Besonderheit — der Browser muss Keycloak erreichen können. Vollständige Unerreichbarkeit ist mit Standard-OIDC also nicht möglich. Die Lösung ist Kapselung unter demselben Origin: Keycloak läuft unter [localhost](http://localhost:8080/auth), das Gateway leitet weiter. Der Issuer in den Tokens lautet damit für Browser und Backend identisch, während die Backends das JWKS über die interne Adresse [keycloak](http://keycloak:8080/) beziehen. Ein Issuer-Mismatch (der klassische Fehler) wird so vermieden.

3.4 Ablauf: Login bis Nachricht
mermaid






postgres-app
redis
chat-service
keycloak
gateway
Browser (React)
postgres-app
redis
chat-service
keycloak
gateway
Browser (React)
Login-Klick
1
/auth/.../authorize (PKCE)
2
Login-Seite
3
Zugangsdaten
4
302 mit code
5
code + verifier gegen Token tauschen
6
access_token (im Speicher, nicht localStorage)
7
GET /ws?ticket=... (Upgrade)
8
weiterleiten
9
JWKS laden (intern, gecacht)
10
101 Switching Protocols
11
presence setzen + channel abonnieren
12
message.send (channelId, clientMsgId, body)
13
Nachricht speichern (UUIDv7)
14
message.ack
15
PUBLISH channel:{id}
16
Verteilung an alle Instanzen
17
message.new an Empfänger
18
WebSocket-Login: Die Browser-API erlaubt keine eigenen Header, und ein Token im Query-String landet in Logs. Der Client holt deshalb ein kurzlebiges Einmal-Ticket vom user-service, das beim Verbindungsaufbau serverseitig atomar verbraucht wird. Läuft das Token während einer offenen Verbindung ab, sendet der Server vorher ein Auffrisch-Signal.

3.5 docker-compose.yml (Entwurf)
yaml


name: chat-app

x-restart: &restart
  restart: unless-stopped

networks:
  chat_edge:
    driver: bridge
  chat_network:
    driver: bridge
    internal: true          # kein Internet, keine Host-Ports

volumes:
  pg_app_data:
  pg_kc_data:
  redis_data:

services:
  gateway:
    image: nginx:1.27-alpine
    <<: *restart
    ports:
      - "127.0.0.1:8080:8080"     # EINZIGER veröffentlichter Port
    volumes:
      - ./infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./apps/web/dist:/usr/share/nginx/html:ro   # gebaute React-App
    networks: [chat_edge, chat_network]
    depends_on:
      keycloak:     { condition: service_healthy }
      chat-service: { condition: service_healthy }
      user-service: { condition: service_healthy }

  chat-service:
    build: ./services/chat
    <<: *restart
    environment:
      PORT: 8081
      OIDC_ISSUER: [localhost](http://localhost:8080/auth/realms/chat)
      OIDC_JWKS_URI: [keycloak](http://keycloak:8080/auth/realms/chat/protocol/openid-connect/certs)
      DATABASE_URL: postgres://chat:${APP_DB_PASSWORD}@postgres-app:5432/chatdb
      REDIS_URL: redis://redis:6379/0
      USER_SERVICE_URL: [user-service](http://user-service:8082)
    networks: [chat_network]
    depends_on:
      migrate:  { condition: service_completed_successfully }
      redis:    { condition: service_healthy }
    deploy:
      replicas: 2               # belegt die Verteilung über mehrere Instanzen

  user-service:
    build: ./services/user
    <<: *restart
    environment:
      PORT: 8082
      OIDC_ISSUER: [localhost](http://localhost:8080/auth/realms/chat)
      OIDC_JWKS_URI: [keycloak](http://keycloak:8080/auth/realms/chat/protocol/openid-connect/certs)
      DATABASE_URL: postgres://chat:${APP_DB_PASSWORD}@postgres-app:5432/chatdb
      REDIS_URL: redis://redis:6379/0
    networks: [chat_network]
    depends_on:
      migrate: { condition: service_completed_successfully }

  migrate:
    build: ./services/migrate
    command: ["npm", "run", "migrate:deploy"]
    environment:
      DATABASE_URL: postgres://chat:${APP_DB_PASSWORD}@postgres-app:5432/chatdb
    networks: [chat_network]
    depends_on:
      postgres-app: { condition: service_healthy }
    restart: "no"

  keycloak:
    image: quay.io/keycloak/keycloak:26.0
    <<: *restart
    command: ["start", "--optimized", "--import-realm"]
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres-keycloak:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: ${KC_DB_PASSWORD}
      KC_HTTP_ENABLED: "true"
      KC_HTTP_RELATIVE_PATH: /auth
      KC_HOSTNAME: [localhost](http://localhost:8080/auth)
      KC_PROXY_HEADERS: xforwarded
      KC_BOOTSTRAP_ADMIN_USERNAME: ${KC_ADMIN_USER}
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KC_ADMIN_PASSWORD}
    volumes:
      - ./infra/keycloak/realm-chat.json:/opt/keycloak/data/import/realm-chat.json:ro
    networks: [chat_network]
    depends_on:
      postgres-keycloak: { condition: service_healthy }

  postgres-app:
    image: postgres:17-alpine
    <<: *restart
    environment:
      POSTGRES_DB: chatdb
      POSTGRES_USER: chat
      POSTGRES_PASSWORD: ${APP_DB_PASSWORD}
    volumes: [pg_app_data:/var/lib/postgresql/data]
    networks: [chat_network]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U chat -d chatdb"]
      interval: 5s
      retries: 20

  postgres-keycloak:
    image: postgres:17-alpine
    <<: *restart
    environment:
      POSTGRES_DB: keycloak
      POSTGRES_USER: keycloak
      POSTGRES_PASSWORD: ${KC_DB_PASSWORD}
    volumes: [pg_kc_data:/var/lib/postgresql/data]
    networks: [chat_network]

  redis:
    image: redis:7.4-alpine
    <<: *restart
    command: ["redis-server", "--appendonly", "yes"]
    volumes: [redis_data:/data]
    networks: [chat_network]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
4. Offene Punkte & Risiken
WebSocket über mehrere Instanzen: Redis Pub/Sub ist „fire and forget" — geht während der Verteilung etwas verloren, sieht der Client die Nachricht erst nach einem Reload. Gegenmaßnahme: Nach jedem Reconnect lädt der Client verpasste Nachrichten per REST nach. Damit ist der Live-Kanal „best effort", die Wahrheit liegt in der Datenbank. Offen: Ab welcher Last Redis Streams nötig werden.

Keycloak-Ersteinrichtung: --import-realm importiert nur, wenn der Realm noch nicht existiert — spätere Änderungen an der JSON-Datei greifen bei bestehendem Volume stillschweigend nicht. Gegenmaßnahme: Ein make realm-sync-Target und ein dokumentiertes make reset-idp. Offen: Automatischer Abgleich der Nutzer zwischen Keycloak und users-Tabelle.

Migrationen: Der Job läuft bei jedem up und muss wiederholbar sein. Bei mehreren gleichzeitig startenden Diensten verhindert ein Datenbank-Lock Kollisionen. Schemaänderungen immer erst additiv (neue Spalte), dann in einem zweiten Schritt aufräumen.

Rate Limiting: Drei Ebenen — Gateway (pro IP), chat-service (pro Nutzer, z. B. 60 Nachrichten/Minute), user-service (strenger bei Schreibzugriffen). Offen: Ob Typing-Signale ein eigenes, lockereres Budget bekommen.

Aufbewahrung von Nachrichten: Zu klären sind Aufbewahrungsdauer, Löschart (Soft-Delete mit späterem Aufräumjob) und Umgang mit gelöschten Accounts. Gelöschte Nutzer sollten anonymisiert statt kaskadierend gelöscht werden, um Gesprächskontext zu erhalten. Diese Punkte berühren Datenschutzrecht und brauchen fachliche Prüfung.

Weitere Risiken:

Risiko	Maßnahme
Kein TLS lokal	Ab der ersten nicht-lokalen Umgebung wss:// und HSTS
Secrets in .env	Lokal ok; produktiv Docker Secrets oder Vault
Fehlende Observability	OpenTelemetry von Beginn an einbauen
Nachrichtenreihenfolge	Serverseitig nach (created_at, id) sortieren
5. Verlauf (Vom System / der KI verfasst)
Dieser Abschnitt hält aus meiner Perspektive fest, wie diese Planung entstanden ist — welche Fragen ich geklärt habe, wo ich umentschieden habe und welche Varianten verworfen wurden, damit später nachvollziehbar bleibt, warum die Architektur so aussieht.

5.1 Geklärte Fragen
Datenbankwahl: Brauchen Keycloak und die App eine gemeinsame PostgreSQL-Instanz? Ich habe mich dagegen entschieden — Keycloak fährt beim Upgrade eigene Migrationen, die die Chat-Historie nicht gefährden sollen. Zweite Frage: relationale oder dokumentenorientierte Speicherung? Zugriffe erfolgen fast immer als „letzte n Nachrichten eines Channels", und Mitgliedschaften brauchen Integrität — ein zweiter Datenbanktyp lohnt nicht.

Echtzeitprotokoll: Polling, Server-Sent Events und WebSocket standen zur Wahl. Da Typing- und Presence-Signale bidirektional und häufig sind, fiel SSE heraus (bräuchte einen zweiten Rückkanal). WebSocket deckt alles mit einem Protokoll ab.

Token-Validierung: Lokale Prüfung per JWKS oder Nachfrage per Introspection bei jeder Nachricht? Lokale Prüfung mit kurzer Token-Lebensdauer (5 Minuten) gewann — Introspection hätte einen Roundtrip pro Nachricht bedeutet und Keycloak in den kritischen Pfad gestellt.

Keycloak-Erreichbarkeit: Hier musste ich die Anforderung präzisieren. „Sauber gekapselt" ist mit Standard-OIDC nur als Proxy-Kapselung umsetzbar — der Browser muss beim Login zwingend zur Login-Seite umgeleitet werden. Ich habe das als „gleicher Origin, kein eigener Port, gefilterte Pfade" interpretiert. Der alternative Passwort-Flow, bei dem ein Backend die Zugangsdaten entgegennimmt, ist in OAuth 2.1 abgeschafft und war keine Option.

WebSocket-Authentifizierung: Wie kommt das Token an einen Socket, wenn die Browser-API keine Header erlaubt? Query-Parameter (landet in Logs), Cookie (CSRF-anfällig) und das Subprotocol-Feld waren die Kandidaten. Gewonnen hat das Einmal-Ticket.

5.2 Änderungen der Richtung
Von einem Monolithen zu Redis-Pub/Sub. Erster Entwurf: alle Verbindungen in einem Prozess, Channel-Zuordnung im Speicher. Einfacher, aber nur bis zur zweiten Instanz tragfähig. Da Microservices und mehrere Repliken ausdrücklich gefordert waren, habe ich umgestellt. Redis war für Presence ohnehin gesetzt, der Pub/Sub-Anteil kostete keine zusätzliche Komponente.

Von Auto-Increment-IDs zu UUIDv7. Ursprünglich BIGSERIAL — kompakt und sortiert. Mit mehreren schreibenden Instanzen wurde die zentrale Sequenz zum Problem. UUIDv7 behält die Zeitsortierung.

Von drei externen Ports auf einen. Der erste Entwurf veröffentlichte pragmatisch Frontend, Keycloak und Datenbank für bequemes Debugging. Das widerspricht der Vorgabe und erzeugt zusätzlich das Issuer-Mismatch-Problem. Beides verschwindet hinter einem einzigen Gateway.

Von expose: zu internal: true. Isolation war zunächst nur über weggelassene Ports modelliert — eine Konvention, die jeder mit einer Zeile brechen kann. Mit internal: true verweigert Docker die Portveröffentlichung strukturell.

Von Next.js zu React + Vite. Ich hatte Next.js inklusive einer BFF-Schicht vorgesehen, die den OIDC-Code-Austausch serverseitig übernimmt und Tokens in httpOnly-Cookies hält — das ist sicherer, weil kein Token im Browser-JavaScript liegt, kostet aber einen zusätzlichen Server-Anteil, serverseitiges Routing und mehr Konfiguration. Auf Wunsch auf eine schlankere Architektur reduziert: reine SPA mit PKCE. Der Preis ist eine kleine Sicherheitseinbuße (das Access Token liegt im Browser-Speicher), der Gewinn deutlich weniger bewegliche Teile. Der Server-Teil fällt komplett weg.

Von Socket.IO zu rohem WebSocket. Socket.IO bot fertigen Redis-Adapter und Reconnect. Sein Long-Polling-Fallback erzwingt aber Sticky Sessions am Gateway — genau das wollte ich vermeiden. Der Reconnect-Handler ist überschaubarer eigener Code.

5.3 Verworfene Varianten
Variante	Verworfen, weil
Next.js als Frontend mit BFF	Zu viele bewegliche Teile für ein MVP; eine reine SPA erfüllt den Zweck
HTTP-Polling	Latenz und Last steigen mit der Nutzerzahl; Typing-Signale kaum darstellbar
SSE + REST-POST	Zwei Protokolle für einen Anwendungsfall
Keycloak auf eigenem Host-Port	Verstößt gegen die Vorgabe; erzeugt Issuer-Mismatch
Introspection pro Request	Roundtrip pro Nachricht; Keycloak wird Single Point of Failure
Gemeinsame Datenbank	Koppelt Upgrade-Zyklen; Keycloak-Fehler gefährdet Chat-Daten
Kafka statt Redis	Betriebsaufwand steht in keinem Verhältnis zum MVP
Go für den chat-service	Technisch besser bei sehr vielen Verbindungen, aber geteilte TypeScript-Typen wiegen im MVP schwerer
Socket.IO	Erzwingt Sticky Sessions durch den Polling-Fallback
Traefik statt nginx	Gleichwertig; nginx hat die explizitere, leichter prüfbare Konfiguration
5.4 Nächste Klärungen
Drei Punkte vor der Umsetzung: die Aufbewahrungsregeln für Nachrichten (mit rechtlicher Prüfung), die Zielgröße für Lasttests (entscheidet die Redis-Pub/Sub-Frage) und ob die Nutzerverwaltung vollständig in Keycloak liegt oder Profile in der App editierbar sein sollen.

Zur Datei: In dieser Umgebung kann ich keine Download-Datei erzeugen, aber der Text oben ist vollständig — kopiere den gesamten Block von # PLANUNG.md bis zum Ende in einen Editor und speichere ihn als PLANUNG.md. Alle Code- und Diagramm-Blöcke sind bereits korrekt eingezäunt und bleiben beim Kopieren erhalten.




Unterteile diese Aufgabe in Logische steps für meinen KI agenten das heisst auch das du den ersten schritt für ihn prompten solltest. Übrigens Schreibe bitte im prompt das er mir den CODE erklären Sollte was dieser Macht


Projektplan: Chat-App in logischen Schritten für einen KI-Agenten
Der Plan zerlegt die Umsetzung in 9 aufeinander aufbauende Schritte. Jeder Schritt hat ein klares Ziel, ein sichtbares Ergebnis und eine Definition of Done. So kann ein Agent sequenziell arbeiten und du kannst nach jedem Schritt prüfen, ob es läuft.

Wichtig für alle Schritte: Jeder Schritt endet lauffähig. Kein Schritt lässt das Projekt in einem kaputten Zustand zurück.

Übersicht der Schritte
#	Schritt	Ergebnis am Ende
1	Projektgerüst & Compose-Skelett	docker compose up startet leere Dienste, nur Gateway exponiert
2	Netzwerk-Isolation verifizieren	Nachweis, dass nur Port 8080 erreichbar ist
3	Datenbanken + Migrationen	migrate legt Schema an, Postgres läuft
4	Keycloak + Realm	Login über /auth/ funktioniert
5	user-service REST-API	CRUD für Profile, Channels, Historie
6	Frontend React + Vite Grundgerüst	App lädt, Login-Klick funktioniert
7	chat-service WebSocket	Nachrichten fließen in Echtzeit
8	Redis Pub/Sub + Replicas	Verteilung über mehrere Instanzen
9	Presence, Typing, Rate-Limits, Härtung	Statusanzeigen, Schutzmechanismen
Prompt für Schritt 1 (zum Kopieren für deinen Agenten)
text


ROLLE
Du bist ein erfahrener Software-Architekt und Full-Stack-Entwickler. Du baust
das Fundament für eine selbst gehostete Echtzeit-Chat-Anwendung, die als
Microservice-Verbund über docker-compose orchestriert wird.

AUFGABE (Schritt 1 von 9)
Erstelle das Projektgerüst und ein lauffähiges docker-compose-Skelett. Es
sollen noch KEINE fachlichen Funktionen (kein Login, keine Nachrichten,
keine Datenbanktabellen) implementiert werden. Ziel ist ausschließlich ein
startbares, sauber getrenntes Grundgerüst.

TECHNISCHE VORGABEN
- Architekturstil: Microservices, orchestriert via docker-compose.
- Sämtliche Backend-Dienste und Datenbanken leben in einem internen,
  isolierten Docker-Netz namens `chat_network` (deklariert mit
  internal: true, also ohne Internetzugang und ohne Host-Port-Mapping).
- Ein zweites Netz `chat_edge` enthält nur das Gateway.
- NUR das Gateway ist nach außen über `127.0.0.1:8080` exponiert. Kein
  anderer Dienst darf einen Host-Port mappen.
- Authentifizierung läuft später über Keycloak (OpenID Connect). In diesem
  Schritt nur als leerer Dienst vorbereiten.

UMSETZUNGSSCHRITTE
1. Lege eine Ordnerstruktur an:
   - apps/web/            (React + Vite Frontend, noch Minimalgerüst)
   - services/chat/       (Node.js 22 + Fastify)
   - services/user/       (Node.js 22 + Fastify)
   - services/migrate/    (Node.js Einmal-Job)
   - infra/nginx/         (Konfiguration des Gateways)
   - infra/keycloak/      (Realm-Export, noch leer)
   - packages/contracts/  (gemeinsame TypeScript-Typen)
2. Erstelle für jeden Backend-Dienst ein minimales Node.js-Projekt mit
   einem /healthz-Endpunkt, der HTTP 200 zurückgibt.
3. Erstelle die docker-compose.yml mit allen Diensten:
   gateway (nginx), chat-service, user-service, migrate, keycloak,
   postgres-app, postgres-keycloak, redis.
4. Konfiguriere nginx im Gateway:
   - `/`            -> statische Dateien (React-Build-Ordner, noch leer)
   - `/api/`        -> user-service:8082
   - `/ws`          -> chat-service:8081 mit WebSocket-Upgrade-Headern
   - `/auth/`       -> keycloak:8080
   - `/healthz`     -> liefert 200 für den Gateway-Healthcheck
5. Richte an jedem Dienst einen Docker-Healthcheck ein und nutze
   depends_on mit condition: service_healthy.
6. Lege eine .env.example mit allen benötigten Variablen an (ohne echte
   Werte) und eine kurze README mit dem Startbefehl.

WICHTIG — ERKLÄRUNG
Erkläre mir nach jeder erstellten Datei und jedem Konfigurationsblock in
klarer, verständlicher Sprache, WAS der Code macht und WARUM er so
geschrieben ist. Gehe insbesondere auf das nginx-Routing, die
Netzwerk-Separierung (internal: true) und die Healthcheck-Logik ein. Ich
möchte den Code verstehen, nicht nur erhalten. Kommentiere den Code
zusätzlich an den wichtigen Stellen direkt im Quelltext.

DEFINITION OF DONE
- `docker compose up` startet alle Container ohne Fehler.
- `docker compose ps` zeigt GENAU EINEN Dienst mit Host-Port-Mapping
  (das Gateway auf 127.0.0.1:8080).
- `curl [localhost](http://localhost:8080/healthz)` liefert HTTP 200.
- Ein Zugriff auf die Backend-Ports (z. B. 8081, 8082, 5432) vom Host
  aus schlägt fehl.
- Die README beschreibt den Startvorgang.

Gib mir am Ende eine kurze Zusammenfassung, welche Dateien du erstellt
hast und was als Nächstes ansteht (Schritt 2: Netzwerk-Isolation
verifizieren).
Die weiteren Schritte sind bewusst so geschnitten, dass Schritt 1 nur das Skelett liefert. Wenn du möchtest, formuliere ich dir auf dieselbe Weise den Prompt für Schritt 2 (Netzwerk-Isolation verifizieren) als nächstes — er baut direkt auf dem Ergebnis von Schritt 1 auf.