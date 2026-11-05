package works.bosk.load;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Sorts;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A disposable MongoDB replica set for a load run.
 *
 * <p>The driver needs change streams, so the container is started as a single-node replica
 * set. Each run gets its own container, which keeps runs independent and makes teardown
 * reliable.
 */
final class MongoEnvironment implements AutoCloseable {
	private final MongoDBContainer container;
	private final MongoClientSettings clientSettings;

	static MongoEnvironment start() {
		MongoDBContainer container = new MongoDBContainer(DockerImageName.parse(MONGO_IMAGE))
			.withTmpFs(Map.of("/data/db", "rw"))
			.withReplicaSet();
		container.start();
		MongoClientSettings clientSettings = MongoClientSettings.builder()
			.applyConnectionString(new ConnectionString(container.getConnectionString()))
			.addCommandListener(CommandTimer.INSTANCE)
			.build();
		return new MongoEnvironment(container, clientSettings);
	}

	private MongoEnvironment(MongoDBContainer container, MongoClientSettings clientSettings) {
		this.container = container;
		this.clientSettings = clientSettings;
	}

	MongoClientSettings clientSettings() {
		return clientSettings;
	}

	void dropCollection(String database, String collection) {
		try (MongoClient client = MongoClients.create(clientSettings)) {
			client.getDatabase(database).getCollection(collection).drop();
		}
	}

	/**
	 * Turns on level-2 profiling for one database and gives {@code system.profile}
	 * enough space to hold the whole run.
	 */
	void startProfiling(String database) {
		try (MongoClient client = MongoClients.create(clientSettings)) {
			MongoDatabase db = client.getDatabase(database);
			// system.profile is capped at 1 MB by default, which is too small for
			// a few thousand update/commit entries. Recreate it larger while
			// profiling is still off.
			db.getCollection("system.profile").drop();
			db.createCollection("system.profile", new CreateCollectionOptions()
				.capped(true)
				.sizeInBytes(64L * 1024 * 1024));
			db.runCommand(new BsonDocument("profile", new BsonInt32(2))
				.append("slowms", new BsonInt32(0)));
		}
	}

	/**
	 * Dumps the profiler entries and collection stats to build/load-results.
	 */
	void dumpProfile(String database, String collection, String runId) {
		Path output = Path.of("build", "load-results", "profile-" + runId + ".jsonl");
		try {
			Files.createDirectories(output.getParent());
			try (MongoClient client = MongoClients.create(clientSettings)) {
				MongoDatabase db = client.getDatabase(database);
				BsonDocument stats = db.runCommand(new BsonDocument("collStats", new BsonString(collection)), BsonDocument.class);
				System.out.println("\n=== Collection stats (" + database + "." + collection + ") ===");
				System.out.println("count=" + stats.get("count")
					+ " size=" + stats.get("size")
					+ " avgObjSize=" + stats.get("avgObjSize")
					+ " storageSize=" + stats.get("storageSize")
					+ " totalIndexSize=" + stats.get("totalIndexSize"));

				try (BufferedWriter writer = Files.newBufferedWriter(output)) {
					db.getCollection("system.profile")
						.find()
						.sort(Sorts.ascending("ts"))
						.forEach(doc -> {
							try {
								writer.write(doc.toJson());
								writer.newLine();
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
				}
				System.out.println("Profile written to " + output.toAbsolutePath());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void close() {
		container.stop();
	}

	private static final String MONGO_IMAGE = "mongo:9.0.4";
}
