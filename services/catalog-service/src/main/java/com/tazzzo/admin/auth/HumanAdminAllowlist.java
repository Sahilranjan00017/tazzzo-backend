package com.tazzzo.admin.auth;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stage B of human admin access: the Tazzzo backend decides whether a VERIFIED provider identity is an admin, and with
 * which roles. Keyed by provider + subject only. Roles come from here, never from token claims; the email label is
 * metadata (kept only for display via {@link #emailLabel}) and plays no part in the decision. Built only by {@link HumanAdminSettings#from}, which rejects duplicates.
 */
public final class HumanAdminAllowlist {

    record Key(String provider, String subject) {
    }

    /** {@code emailLabel}: validated display/reference metadata only; never part of identity or authorization. */
    record Entry(Set<String> roles, boolean enabled, String emailLabel) {
        Entry {
            roles = Set.copyOf(roles);
        }
    }

    /** Granted: the allowlisted roles. Refused: {@link AdminAuthRejection#NOT_ALLOWLISTED} or {@link AdminAuthRejection#DISABLED}. */
    public sealed interface Resolution {
        record Granted(Set<String> roles) implements Resolution {
        }

        record Refused(AdminAuthRejection reason) implements Resolution {
        }
    }

    private final Map<Key, Entry> entries;

    HumanAdminAllowlist(Map<Key, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    /**
     * The configured email LABEL of an allowlisted identity, for display only (e.g. the CMS bootstrap). It is looked up by
     * provider + subject like every other decision here, and it is never compared with a token's email.
     */
    public Optional<String> emailLabel(String provider, String subject) {
        Entry entry = entries.get(new Key(provider, subject));
        return entry == null ? Optional.empty() : Optional.of(entry.emailLabel());
    }

    public Resolution resolve(String provider, String subject) {
        Entry entry = entries.get(new Key(provider, subject));
        if (entry == null) {
            return new Resolution.Refused(AdminAuthRejection.NOT_ALLOWLISTED);
        }
        if (!entry.enabled()) {
            return new Resolution.Refused(AdminAuthRejection.DISABLED);
        }
        return new Resolution.Granted(entry.roles());
    }
}
