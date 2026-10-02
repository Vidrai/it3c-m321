package ch.benedict.m321.batchwriter.config;

/**
 * Die Namen der Queues an genau EINER Stelle.
 *
 * Eigene Kopie, mit denselben Werten wie im chat-service. Ein gemeinsames
 * Modul würde die Dienste aneinanderbinden. Der Vertrag sind die Namen
 * und das JSON, nicht eine geteilte Klasse.
 */
public final class QueueNames {

    /** Schreibweg: hier holt der batch-writer die Nachrichten ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Dead Letter: was sich nie speichern lässt, landet hier. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
