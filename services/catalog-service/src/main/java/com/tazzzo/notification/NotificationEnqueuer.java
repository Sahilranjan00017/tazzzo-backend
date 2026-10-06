package com.tazzzo.notification;

import com.mongodb.client.ClientSession;

/**
 * Enqueue a notification INSIDE the caller's transaction: it becomes visible to the dispatcher only if the business write
 * commits, and a rolled-back attempt leaves nothing behind (the transactional-outbox pattern).
 */
public interface NotificationEnqueuer {

    void enqueue(ClientSession session, NotificationRequest request);

    /** For narrow unit fixtures that construct a service by hand; production wiring always injects the outbox. */
    NotificationEnqueuer NONE = (session, request) -> { };
}
