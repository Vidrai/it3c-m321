-- Die Namenskonvention V1__init_schema.sql wird von Flyway strikt gefordert.
-- Das doppelte Unterstrich-Zeichen (__) trennt die Versionsnummer vom Beschreibungsnamen.
-- IF NOT EXISTS ist essenziell für Idempotenz: Ein zweiter Start des Scripts wirft keinen Fehler.

-- 1. Raum-Tabelle
CREATE TABLE IF NOT EXISTS room (
    id UUID PRIMARY KEY, -- Die UUID wird vom chat-service generiert (UUIDv7)
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Zuordnungstabelle: Wer ist in welchem Raum
CREATE TABLE IF NOT EXISTS room_member (
    room_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL, -- Subject-ID aus Keycloak
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_room FOREIGN KEY (room_id) REFERENCES room (id)
);

-- 3. Nachrichtentabelle
CREATE TABLE IF NOT EXISTS message (
    id UUID PRIMARY KEY, -- Auch hier: UUIDv7 vom Backend für zeitsortierte Indexierung
    room_id UUID NOT NULL,
    sender_id VARCHAR(255) NOT NULL, -- Subject-ID aus Keycloak
    sender_name VARCHAR(255) NOT NULL, -- Denormalisiert für direkte Anzeige
    content TEXT NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_message_room FOREIGN KEY (room_id) REFERENCES room (id)
);

-- 4. Index für die Paginierung der Historie (nach Raum und absteigender Zeit)
CREATE INDEX IF NOT EXISTS idx_message_room_sent ON message(room_id, sent_at DESC);

