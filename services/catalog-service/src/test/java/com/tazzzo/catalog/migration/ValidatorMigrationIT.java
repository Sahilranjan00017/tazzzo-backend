package com.tazzzo.catalog.migration;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The validator MECHANISM (DB-1 proposed validators; none is enabled by DB-3): conformance scan, controlled
 * collMod, post-validation, captured rollback info and a safe failure path. A scratch collection only.
 */
class ValidatorMigrationIT extends AbstractMigrationIT {

    private static final Document SCHEMA = Document.parse(
            "{ bsonType: 'object', required: ['a'], properties: { a: { bsonType: 'string' } } }");

    private ValidatorMigration migration(ValidationLevel level, ValidationAction action) {
        return new ValidatorMigration("T-VAL", "validator on vcoll", "vcoll", SCHEMA, level, action, true, 1000);
    }

    private MigrationRunner.RunReport run(MongoDatabase d, ValidatorMigration m) {
        return runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());
    }

    private static Document validatorOf(MongoDatabase d) {
        Document info = d.listCollections().filter(new Document("name", "vcoll")).first();
        return info.get("options", Document.class);
    }

    @Test
    void clean_data_strict_error_applies_and_post_validates() {
        MongoDatabase d = scratch();
        d.getCollection("vcoll").insertOne(new Document("a", "ok"));
        ValidatorMigration m = migration(ValidationLevel.STRICT, ValidationAction.ERROR);

        MigrationRunner.RunReport r = run(d, m);

        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(m.validate(d)).isEmpty();
        assertThat(validatorOf(d).getString("validationLevel")).isEqualTo("strict");
        assertThatThrownBy(() -> d.getCollection("vcoll").insertOne(new Document("a", 5))).isInstanceOf(MongoWriteException.class);
        assertThat(history(d, "T-VAL").get("rollbackInfo", Document.class).get("previous", Document.class)).isEmpty();
    }

    @Test
    void non_conforming_documents_block_strict_error_and_nothing_is_changed() {
        MongoDatabase d = scratch();
        d.getCollection("vcoll").insertMany(List.of(new Document("_id", "good").append("a", "ok"),
                new Document("_id", "bad-1").append("a", 1), new Document("_id", "bad-2").append("b", true)));

        MigrationRunner.RunReport r = run(d, migration(ValidationLevel.STRICT, ValidationAction.ERROR));

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        String blocker = r.steps().get(0).blockers().get(0);
        assertThat(blocker).contains("2 existing document(s) do not conform").contains("bad-1").contains("never rewritten");
        assertThat(validatorOf(d).get("validator")).as("no validator was applied").isNull();
        assertThat(d.getCollection("vcoll").countDocuments()).as("no document touched").isEqualTo(3);
    }

    @Test
    void moderate_tolerates_existing_non_conforming_documents_but_rejects_new_ones() {
        MongoDatabase d = scratch();
        d.getCollection("vcoll").insertOne(new Document("_id", "bad").append("a", 1));
        ValidatorMigration m = migration(ValidationLevel.MODERATE, ValidationAction.ERROR);

        MigrationRunner.RunReport dry = runner(d, List.of(m)).dryRun(target(d), MigrationRunner.Selection.all());
        assertThat(dry.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.WOULD_APPLY);
        assertThat(dry.steps().get(0).notes()).anyMatch(n -> n.contains("do not conform") && n.contains("tolerated"));

        assertThat(run(d, m).ok()).isTrue();

        assertThatThrownBy(() -> d.getCollection("vcoll").insertOne(new Document("a", 7))).isInstanceOf(MongoWriteException.class);
        d.getCollection("vcoll").updateOne(new Document("_id", "bad"), new Document("$set", new Document("note", "still updatable")));
        d.getCollection("vcoll").insertOne(new Document("a", "fine"));
    }

    @Test
    void warn_action_never_blocks() {
        MongoDatabase d = scratch();
        d.getCollection("vcoll").insertOne(new Document("a", 1));
        assertThat(run(d, migration(ValidationLevel.STRICT, ValidationAction.WARN)).ok()).isTrue();
        d.getCollection("vcoll").insertOne(new Document("a", 2)); // warn logs only
    }

    @Test
    void the_previous_validator_is_captured_and_can_be_restored() {
        MongoDatabase d = scratch();
        d.createCollection("vcoll");
        Document previous = Document.parse("{ bsonType: 'object', required: ['zzz'] }");
        d.runCommand(new Document("collMod", "vcoll").append("validator", new Document("$jsonSchema", previous))
                .append("validationLevel", "moderate").append("validationAction", "warn"));
        ValidatorMigration m = migration(ValidationLevel.STRICT, ValidationAction.ERROR);

        MigrationRunner.RunReport r = run(d, m);
        assertThat(r.ok()).as(r.render()).isTrue();
        Document rollback = history(d, "T-VAL").get("rollbackInfo", Document.class);
        assertThat(rollback.getString("collection")).isEqualTo("vcoll");
        assertThat(rollback.get("previous", Document.class).getString("validationLevel")).isEqualTo("moderate");

        ValidatorMigration.restore(d, rollback);

        Document options = validatorOf(d);
        assertThat(options.getString("validationLevel")).isEqualTo("moderate");
        assertThat(options.getString("validationAction")).isEqualTo("warn");
        assertThat(options.get("validator", Document.class).toJson()).isEqualTo(new Document("$jsonSchema", previous).toJson());
    }

    @Test
    void restoring_when_there_was_no_validator_turns_validation_off() {
        MongoDatabase d = scratch();
        d.createCollection("vcoll");
        run(d, migration(ValidationLevel.STRICT, ValidationAction.ERROR));
        assertThatThrownBy(() -> d.getCollection("vcoll").insertOne(new Document("a", 123)))
                .as("the validator is ACTIVE before the restore").isInstanceOf(MongoWriteException.class);
        ValidatorMigration.restore(d, history(d, "T-VAL").get("rollbackInfo", Document.class));
        d.getCollection("vcoll").insertOne(new Document("a", 123)); // accepted again
        assertThat(d.getCollection("vcoll").countDocuments()).isEqualTo(1);
    }

    @Test
    void a_validator_that_is_already_in_place_is_adopted() {
        MongoDatabase d = scratch();
        d.createCollection("vcoll");
        ValidatorMigration m = migration(ValidationLevel.STRICT, ValidationAction.ERROR);
        assertThat(run(d, m).ok()).isTrue();
        assertThat(m.preflight(d).status()).isEqualTo(Preflight.Status.ALREADY_SATISFIED);
        MongoDatabase other = scratch();
        other.createCollection("vcoll");
        other.runCommand(new Document("collMod", "vcoll").append("validator", new Document("$jsonSchema", SCHEMA))
                .append("validationLevel", "strict").append("validationAction", "error"));
        assertThat(run(other, m).steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.ADOPTED);
    }

    @Test
    void a_missing_collection_blocks() {
        MongoDatabase d = scratch();
        MigrationRunner.RunReport r = run(d, migration(ValidationLevel.STRICT, ValidationAction.ERROR));
        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).blockers().get(0)).contains("does not exist");
    }

    @Test
    void a_preflight_says_when_an_existing_different_validator_would_be_replaced() {
        MongoDatabase d = scratch();
        d.createCollection("vcoll");
        d.runCommand(new Document("collMod", "vcoll").append("validator",
                new Document("$jsonSchema", Document.parse("{ bsonType: 'object', required: ['zzz'] }")))
                .append("validationLevel", "moderate").append("validationAction", "warn"));
        Preflight pf = migration(ValidationLevel.STRICT, ValidationAction.ERROR).preflight(d);
        assertThat(pf.status()).isEqualTo(Preflight.Status.READY);
        assertThat(pf.notes()).anyMatch(n -> n.contains("EXISTING, different validator") && n.contains("will be replaced"));
    }
}
