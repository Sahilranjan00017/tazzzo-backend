package com.tazzzo.catalog.tx;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Explicit ClientSession transactions. All T1-T7 services run through this. */
@Component
public class Tx {

    private final MongoClient client;

    public Tx(MongoClient client) {
        this.client = client;
    }

    public void run(Consumer<ClientSession> body) {
        try (ClientSession session = client.startSession()) {
            session.withTransaction(() -> {
                body.accept(session);
                return null;
            });
        }
    }
}
