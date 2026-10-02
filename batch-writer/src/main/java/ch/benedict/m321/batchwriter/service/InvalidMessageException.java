package ch.benedict.m321.batchwriter.service;

/**
 * Eine Nachricht, die sich nie speichern lassen wird: kaputtes JSON,
 * fehlendes Feld, zu langer Name.
 *
 * Bewusst eine geprüfte Exception (extends Exception): Der Compiler
 * zwingt jeden Aufrufer, sich zu entscheiden, was mit so einer Nachricht
 * passiert. Bei uns: ab in die Dead-Letter-Queue (Spec 3.6).
 */
public class InvalidMessageException extends Exception {

    /** @param reason warum die Nachricht ungültig ist, landet so im Log */
    public InvalidMessageException(String reason) {
        super(reason);
    }
}
