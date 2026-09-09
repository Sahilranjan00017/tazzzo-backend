package com.tazzzo.catalog.schema;

/**
 * A release snapshot's recorded topology is not a tree — a cycle, or a child row without a
 * {@code node_id}. {@code taxonomy_snapshot_nodes} has no validator, so a directly-written or
 * corrupted row can produce this, and the reader FAILS CLOSED rather than silently skipping the
 * repeated node: skipping would present a corrupt snapshot as a smaller valid one.
 *
 * <p>Internal. ERR-1 normalises whatever consumer-facing 5xx this eventually becomes; this class
 * does not define a consumer error shape.
 */
public class SnapshotTopologyException extends IllegalStateException {

    public SnapshotTopologyException(String message) {
        super(message);
    }
}
