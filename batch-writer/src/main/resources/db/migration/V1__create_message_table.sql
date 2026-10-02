-- Die Tabelle für den Chat-Verlauf, Spalten wie in PLANUNG.md 3.7.
--
-- Flyway führt diese Datei genau einmal aus und merkt sich das in der
-- Tabelle flyway_schema_history. Änderungen kommen als neue Datei
-- V2__..., niemals als Änderung an dieser Datei.

CREATE TABLE message (
    -- Die ID vergibt der chat-service. Nur weil sie Primärschlüssel ist,
    -- erkennt ON CONFLICT (id) DO NOTHING ein Duplikat (Spec 3.4).
    id          UUID         PRIMARY KEY,

    -- Bewusst OHNE Fremdschlüssel auf room: Räume sind nicht Teil dieser
    -- Aufgabe und niemand legt sie an. Mit Fremdschlüssel würde jede
    -- Nachricht abgelehnt (Spec 4.2).
    room_id     UUID         NOT NULL,

    -- sub aus Keycloak, und der Anzeigename zum Zeitpunkt des Sendens.
    sender_id   VARCHAR(255) NOT NULL,
    sender_name VARCHAR(255) NOT NULL,

    content     TEXT         NOT NULL,

    -- Ein Zeitpunkt, kein Ortszeit-Wert. PostgreSQL speichert intern in UTC.
    sent_at     TIMESTAMPTZ  NOT NULL
);

-- Für die einzige Abfrage im Lesepfad: "die letzten 50 Nachrichten eines
-- Raums". Kostet beim Schreiben einen zweiten Index-Eintrag pro Zeile.
CREATE INDEX message_room_id_sent_at_idx ON message (room_id, sent_at DESC);
