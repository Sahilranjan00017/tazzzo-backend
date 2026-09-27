package com.tazzzo.auth.session;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.SessionAuthority;
import com.tazzzo.auth.SessionId;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * PR-11C — implements the {@code com.tazzzo.auth} foundation package's {@link SessionAuthority}
 * seam against real session persistence. {@code CustomerAuthFilter} depends only on the interface;
 * this is the ONE place that turns "is this session still active" into an actual database check —
 * correctness first (no caching layer in this PR; see the class-level rationale in
 * {@code CustomerAuthFilter}).
 */
@Component
public class SessionAuthorityImpl implements SessionAuthority {

    private final CustomerSessionRepository sessions;
    private final Clock clock;

    public SessionAuthorityImpl(CustomerSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public boolean isSessionActive(CustomerId customerId, SessionId sessionId) {
        return sessions.isActive(customerId, sessionId, clock.instant());
    }
}
