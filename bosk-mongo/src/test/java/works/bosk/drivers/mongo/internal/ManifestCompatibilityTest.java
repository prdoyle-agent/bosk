package works.bosk.drivers.mongo.internal;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * The manifest carries a {@code version} so that a newer Bosk can recognize an
 * older collection. Adding {@code formatId} to the manifest changed its shape,
 * so a collection written by an older version has a manifest that lacks it.
 * A newer version must still be able to read that manifest, so that it can
 * refurbish the collection into the new shape; otherwise upgrading a deployment
 * bricks the database.
 */
class ManifestCompatibilityTest {
	@Test
	void manifestWithoutFormatId_isRecognizedAsALegacyManifest() throws Exception {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("legacyManifest"),
			TestEntity.class,
			AbstractMongoDriverTest::initialRoot,
			BoskConfig.simple());
		Formatter formatter = new Formatter(bosk, new BsonSerializer());

		// The shape a previous version wrote: no formatId.
		BsonDocument legacyManifest = new BsonDocument("_id", MANIFEST_ID)
			.append("version", new BsonInt32(1))
			.append("sequoia", new BsonDocument());

		Manifest manifest = formatter.decodeManifest(legacyManifest);
		assertEquals(DatabaseFormat.SEQUOIA, manifest.format(),
			"A manifest from a previous version must still be recognized");
	}
}
