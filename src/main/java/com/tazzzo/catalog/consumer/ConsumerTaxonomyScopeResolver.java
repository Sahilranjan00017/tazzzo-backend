package com.tazzzo.catalog.consumer;

import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import com.tazzzo.catalog.schema.SnapshotTopologyException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * THE consumer scope-resolution seam (CONSUMER-ERR-2): the release-bound, consumer-valid vertical
 * set under a node, for ROOT, CHILDREN and LIST alike.
 *
 * <p>A release snapshot whose recorded topology is not a tree — a cycle, or a child row without a
 * {@code node_id} — is server-side corruption, not a bad shopper request. The reader fails closed
 * with {@link SnapshotTopologyException}, which extends {@code IllegalStateException}; left alone
 * that reaches the CMS advice's conflict mapping and the public envelope turns it into
 * {@code 400 INVALID_REQUEST} while the measured boundary records {@code unavailable}. Translating
 * it HERE, once, keeps schema corruption out of the transport layer for all three routes:
 * {@code 503 SERVICE_UNAVAILABLE}, flat ERR-1, outcome {@code unavailable}. The reader's own
 * semantics are untouched, and so is the CMS mapping of {@code IllegalStateException}.
 *
 * <p>Nothing is repaired or skipped: a corrupt snapshot is never presented as a smaller valid one.
 */
@Component
public class ConsumerTaxonomyScopeResolver {

    private final SnapshotTaxonomyReader snapshots;

    public ConsumerTaxonomyScopeResolver(SnapshotTaxonomyReader snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * @return the consumer-valid vertical ids under {@code nodeId} in {@code release}, pruning
     *         non-active branches (Phase 5A hardening); empty when the node is absent or pruned
     * @throws ConsumerFailures.Unavailable the snapshot topology under the node is corrupt
     */
    public List<String> scope(String release, String nodeId) {
        try {
            return snapshots.consumerVerticalIdsInSubtree(release, nodeId);
        } catch (SnapshotTopologyException e) {
            throw new ConsumerFailures.Unavailable("snapshot topology corrupt in release " + release);
        }
    }
}
