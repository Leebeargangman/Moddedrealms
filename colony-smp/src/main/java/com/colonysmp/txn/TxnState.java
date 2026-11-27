package com.colonysmp.txn;

/**
 * Where a transaction is in its life.
 *
 * <pre>
 *   PREPARED ──▶ COMMITTED      the items changed hands and the other side's books were updated
 *      │
 *      └──────▶ ROLLED_BACK    nothing changed hands (refused at the last step, or undone after a crash)
 * </pre>
 *
 * A row that is still PREPARED when the server starts is "in doubt": the server stopped between writing it and
 * finishing it. It is settled when its player next joins, by looking at the marker saved in the same file as
 * their inventory (see {@link Journal}).
 */
public enum TxnState {
    PREPARED, COMMITTED, ROLLED_BACK;

    public static TxnState parse(String s) {
        try {
            return valueOf(s);
        } catch (RuntimeException e) {
            return ROLLED_BACK;
        }
    }
}
