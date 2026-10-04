package com.tazzzo.catalog.datastore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.bson.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * A REAL MongoDB 7 replica set with authentication enabled (keyfile + root user), provisioned with the three roles and
 * users generated from {@link PrivilegeModel}: exactly the definitions shipped under docs/database/roles. Test
 * fixtures only: the passwords here protect a throw-away container and are used nowhere else.
 *
 * <p>The single member is reached with {@code directConnection=true} (its advertised host is the container's own
 * localhost, which the test JVM cannot reach, so discovery would fail). The contract that forbids
 * {@code directConnection} is exercised by pure tests; here the point is the server-side authorization.
 */
final class AuthenticatedReplicaSet {

    static final String DB = "tazzzo_staging";
    static final String ROOT_PASSWORD = "RootFixturePw0Aa1Bb2Cc3";
    static final String USER_PASSWORD = "UserFixturePw0Aa1Bb2Cc3";
    static final String RUNTIME_USER = "tazzzo_app_runtime";
    static final String MIGRATOR_USER = "tazzzo_app_migrator";
    static final String READER_USER = "tazzzo_app_migration_reader";
    static final String RUNTIME_ROLE = "tazzzo_runtime";
    static final String MIGRATOR_ROLE = "tazzzo_migrator";
    static final String READER_ROLE = "tazzzo_migration_reader";

    private static GenericContainer<?> container;
    private static MongoClient root;

    private AuthenticatedReplicaSet() {
    }

    static synchronized void start() {
        if (container != null) {
            return;
        }
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("mongo:7"))
                .withExposedPorts(27017)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "-c").withCmd(
                        "head -c 600 /dev/urandom | base64 | tr -d '\\n' > /tmp/keyfile && chmod 400 /tmp/keyfile "
                                + "&& chown mongodb:mongodb /tmp/keyfile "
                                + "&& exec gosu mongodb mongod --replSet rs0 --keyFile /tmp/keyfile --bind_ip_all"))
                .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));
        c.start();
        try {
            exec(c, "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})");
            exec(c, "while(!db.hello().isWritablePrimary){sleep(200)}");
            exec(c, "db.getSiblingDB('admin').createUser({user:'root',pwd:'" + ROOT_PASSWORD + "',roles:['root']})");
        } catch (Exception e) {
            c.stop();
            throw new IllegalStateException("could not provision the authenticated replica set", e);
        }
        container = c;
        root = MongoClients.create(uri("root", ROOT_PASSWORD, null));
        provision();
    }

    private static void exec(GenericContainer<?> c, String js) throws Exception {
        var r = c.execInContainer("mongosh", "--quiet", "--eval", js);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("mongosh failed (" + r.getExitCode() + "): " + r.getStdout() + r.getStderr());
        }
    }

    static String uri(String user, String password, String database) {
        return "mongodb://" + user + ":" + password + "@" + container.getHost() + ":" + container.getMappedPort(27017) + "/"
                + (database == null ? "" : database) + "?authSource=admin&directConnection=true&serverSelectionTimeoutMS=5000";
    }

    static String uriWithoutCredentials() {
        return "mongodb://" + container.getHost() + ":" + container.getMappedPort(27017) + "/" + DB
                + "?directConnection=true&serverSelectionTimeoutMS=5000";
    }

    static String unreachableUri() {
        return "mongodb://u:p@127.0.0.1:1/" + DB + "?authSource=admin&directConnection=true&serverSelectionTimeoutMS=1200";
    }

    static MongoClient root() {
        return root;
    }

    static MongoClient client(String user) {
        return MongoClients.create(uri(user, USER_PASSWORD, DB));
    }

    static void resetDatabase() {
        root.getDatabase(DB).drop();
    }

    private static void provision() {
        var admin = root.getDatabase("admin");
        List<String> collections = SchemaBootstrap.COLLECTIONS;
        admin.runCommand(PrivilegeModel.createRole(RUNTIME_ROLE, PrivilegeModel.Spec.RUNTIME, DB, collections));
        admin.runCommand(PrivilegeModel.createRole(MIGRATOR_ROLE, PrivilegeModel.Spec.MIGRATOR, DB, collections));
        admin.runCommand(PrivilegeModel.createRole(READER_ROLE, PrivilegeModel.Spec.READER, DB, collections));
        user(RUNTIME_USER, RUNTIME_ROLE);
        user(MIGRATOR_USER, MIGRATOR_ROLE);
        user(READER_USER, READER_ROLE);
    }

    private static void user(String name, String role) {
        root.getDatabase("admin").runCommand(new Document("createUser", name).append("pwd", USER_PASSWORD)
                .append("roles", List.of(new Document("role", role).append("db", "admin"))));
    }
}
