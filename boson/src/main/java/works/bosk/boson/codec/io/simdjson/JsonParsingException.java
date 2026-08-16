package works.bosk.boson.codec.io.simdjson;

public class JsonParsingException extends RuntimeException {

	JsonParsingException(String message) {
		super(message);
	}

	JsonParsingException(String message, Throwable throwable) {
		super(message, throwable);
	}
}
