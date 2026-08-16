package works.bosk.boson.codec.io;

import java.util.Arrays;
import works.bosk.boson.codec.JsonReader;
import works.bosk.boson.codec.Token;
import works.bosk.boson.codec.io.simdjson.BitIndexes;
import works.bosk.boson.codec.io.simdjson.JsonParsingException;
import works.bosk.boson.codec.io.simdjson.StructuralIndexer;
import works.bosk.boson.codec.io.simdjson.Utf8Validator;
import works.bosk.boson.exceptions.JsonSyntaxException;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static works.bosk.boson.codec.Token.END_TEXT;
import static works.bosk.boson.codec.Token.NUMBER;
import static works.bosk.boson.codec.Token.STRING;

/**
 * {@link JsonReader} backed by the simdjson structural indexer.
 * <p>
 * The input must be a complete JSON document in a {@code byte[]}.
 * At construction, the input is scanned once, using SIMD instructions,
 * to produce a structural index: one entry for every {@code { } [ ] , : "}
 * character and for the first character of every number, literal, or string.
 * The reader then walks that index to describe tokens, which is far less work
 * than scanning the raw bytes as the other readers do.
 * <p>
 * This reader is deliberately lenient about input that simdjson would reject
 * outright, like unclosed strings and unescaped control characters in strings:
 * the structural index is still complete enough to read such input,
 * and errors surface when the offending characters are actually consumed,
 * just as with the other readers. Invalid UTF-8 is rejected at construction.
 * <p>
 * Only the {@code byte[]} input form is supported: the SIMD scan needs the
 * whole document in memory, padded with slack bytes.
 */
public final class SimdJsonReader implements JsonReader {

	private static final int PADDING = 64;
	private static final byte SPACE = 0x20;

	private final byte[] paddedBuffer;
	private final int length;
	private final BitIndexes bitIndexes;
	private final int count;
	private final StructuralIndexer indexer;

	/**
	 * Position in {@link #bitIndexes}, i.e. which structural entry is current.
	 */
	private int cursor;

	/**
	 * Current position in {@link #paddedBuffer}, used for the byte-oriented
	 * methods and for {@link #peekRawToken}.
	 */
	private int bytePos;

	/**
	 * The value token most recently returned by {@link #peekValueToken}, if any.
	 */
	private Token peekedValueToken;

	/**
	 * The byte position of {@link #peekedValueToken}.
	 */
	private int peekedBytePos;

	/**
	 * @param utf8Bytes a complete JSON document in UTF-8
	 */
	public SimdJsonReader(byte[] utf8Bytes) {
		this.length = utf8Bytes.length;
		this.paddedBuffer = new byte[length + PADDING];
		System.arraycopy(utf8Bytes, 0, paddedBuffer, 0, length);
		Arrays.fill(paddedBuffer, length, paddedBuffer.length, SPACE);
		this.bitIndexes = new BitIndexes(length + 1);
		this.indexer = new StructuralIndexer(bitIndexes);
		try {
			Utf8Validator.validate(paddedBuffer, length);
		} catch (JsonParsingException e) {
			throw new JsonSyntaxException(e.getMessage(), e);
		}
		try {
			indexer.index(paddedBuffer, length);
		} catch (JsonParsingException e) {
			// Lenient: simdjson rejects unclosed strings and unescaped control
			// characters in strings, but the structural index is complete by the
			// time it throws, and the other readers tolerate such input until the
			// offending characters are actually consumed. So we ignore the error.
		}
		this.count = bitIndexes.size();
	}

	@Override
	public Token peekValueToken() {
		if (peekedValueToken != null) {
			return peekedValueToken;
		}
		int i = cursor;
		while (i < count && isCommaOrColonEntry(i)) {
			i++;
		}
		Token token;
		int tokenBytePos;
		if (i < count) {
			tokenBytePos = bitIndexes.get(i);
			token = Token.startingWith(paddedBuffer[tokenBytePos] & 0xFF);
		} else {
			// The structural index is exhausted; there may still be bytes to read.
			tokenBytePos = skipWhitespace(bytePos);
			if (tokenBytePos >= length) {
				token = END_TEXT;
			} else {
				token = Token.startingWith(paddedBuffer[tokenBytePos] & 0xFF);
			}
		}
		cursor = i;
		bytePos = tokenBytePos;
		peekedBytePos = tokenBytePos;
		peekedValueToken = token;
		return token;
	}

	@Override
	public Token peekNonWhitespaceToken() {
		bytePos = skipWhitespace(bytePos);
		if (bytePos >= length) {
			return END_TEXT;
		}
		return Token.startingWith(paddedBuffer[bytePos] & 0xFF);
	}

	@Override
	public Token peekRawToken() {
		if (bytePos >= length) {
			return END_TEXT;
		}
		return Token.startingWith(paddedBuffer[bytePos] & 0xFF);
	}

	@Override
	public void consumeSyntax(Token token) {
		assert token.hasFixedRepresentation();
		assert peekRawToken() == token: "Expected token " + token + ", not " + peekRawToken();
		int startBytePos;
		if (token == peekedValueToken) {
			startBytePos = peekedBytePos;
			peekedValueToken = null;
		} else {
			startBytePos = bytePos;
		}
		bytePos = startBytePos + token.fixedRepresentation().length();
		advanceCursorPast(bytePos);
	}

	@Override
	public CharSequence consumeNumber() {
		assert peekRawToken() == NUMBER;
		int startBytePos;
		if (peekedValueToken == NUMBER) {
			startBytePos = peekedBytePos;
			peekedValueToken = null;
		} else {
			startBytePos = bytePos;
		}
		int end = startBytePos;
		while (Util.isNumberChar(paddedBuffer[end])) {
			end++;
		}
		bytePos = end;
		advanceCursorPast(bytePos);
		return new AsciiChunkCharSequence(paddedBuffer, startBytePos, end - startBytePos);
	}

	@Override
	public void startConsumingString() {
		assert peekRawToken() == STRING;
		int quotePos;
		if (peekedValueToken == STRING) {
			quotePos = peekedBytePos;
			peekedValueToken = null;
		} else {
			quotePos = bytePos;
		}
		assert paddedBuffer[quotePos] == '"';
		bytePos = quotePos + 1;
		advanceCursorPast(bytePos);
	}

	@Override
	public int nextStringChar() {
		if (bytePos >= length) {
			throw new JsonSyntaxException("Unexpected end of text in the middle of a string");
		}
		int b = paddedBuffer[bytePos] & 0xFF;
		switch (b) {
			case '"' -> {
				bytePos++;
				return END_OF_STRING;
			}
			case '\\' -> {
				bytePos++;
				return decodeEscape();
			}
			default -> {
				if (b < 0x20) {
					throw new JsonSyntaxException("Invalid character in string: 0x" + Integer.toHexString(b));
				}
				if (b < 0x80) {
					bytePos++;
					return b;
				}
				bytePos++;
				return decodeUtf8Char(b);
			}
		}
	}

	@Override
	public void skipToEndOfString() {
		while (nextStringChar() >= 0) {}
	}

	@Override
	public String consumeString() {
		int quotePos;
		if (peekedValueToken == STRING) {
			quotePos = peekedBytePos;
			peekedValueToken = null;
		} else {
			quotePos = bytePos;
		}
		assert paddedBuffer[quotePos] == '"';
		bytePos = quotePos + 1;
		advanceCursorPast(bytePos);

		// Fast path: the string is plain ASCII with no escape sequences.
		int start = bytePos;
		int p = start;
		while (p < length) {
			byte b = paddedBuffer[p];
			if (b == '"') {
				bytePos = p + 1;
				advanceCursorPast(bytePos);
				return new String(paddedBuffer, start, p - start, US_ASCII);
			} else if (b == '\\' || b < 0x20) {
				break;
			}
			p++;
		}

		// Fall back to the character-by-character path.
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = nextStringChar()) >= 0) {
			sb.appendCodePoint(c);
		}
		return sb.toString();
	}

	@Override
	public void validateSyntax(CharSequence expectedCharacters) {
		for (int i = 0; i < expectedCharacters.length(); i++) {
			if (bytePos + i >= length) {
				throw new JsonSyntaxException("Unexpected end of input; expected \"" + expectedCharacters + "\"");
			}
			char expectedChar = expectedCharacters.charAt(i);
			if ((paddedBuffer[bytePos + i] & 0xFF) != expectedChar) {
				throw new JsonSyntaxException("Unexpected character '" + (char) paddedBuffer[bytePos + i] +
					"'; expected '" + expectedChar + "'");
			}
		}
		bytePos += expectedCharacters.length();
		advanceCursorPast(bytePos);
	}

	@Override
	public String previewString(int requestedLength) {
		int actualLength = Math.min(requestedLength, length - bytePos);
		char[] result = new char[actualLength];
		for (int i = 0; i < actualLength; i++) {
			result[i] = (char) (paddedBuffer[bytePos + i] & 0xFF);
		}
		return new String(result);
	}

	@Override
	public long currentOffset() {
		return bytePos;
	}

	@Override
	public void close() {
		// Nothing to close: the input is fully in memory.
	}

	private int decodeEscape() {
		if (bytePos >= length) {
			throw new JsonSyntaxException("Unexpected end of text in the middle of a string");
		}
		int esc = paddedBuffer[bytePos++] & 0xFF;
		if (esc == 'u') {
			if (bytePos + 4 > length) {
				throw new JsonSyntaxException("Incomplete Unicode escape sequence");
			}
			int value = 0;
			for (int i = 0; i < 4; i++) {
				int digit = Character.digit(paddedBuffer[bytePos] & 0xFF, 16);
				if (digit == -1) {
					throw new JsonSyntaxException("Invalid hex digit in Unicode escape: " + (char) paddedBuffer[bytePos]);
				}
				bytePos++;
				value = value * 16 + digit;
			}
			return value;
		}
		return switch (esc) {
			case '"' -> '"';
			case '\\' -> '\\';
			case '/' -> '/';
			case 'b' -> '\b';
			case 'f' -> '\f';
			case 'n' -> '\n';
			case 'r' -> '\r';
			case 't' -> '\t';
			default -> throw new JsonSyntaxException("Invalid escape: \\" + (char) esc);
		};
	}

	private int decodeUtf8Char(int firstChar) {
		int codePoint;
		int sequenceLength;
		if ((firstChar & 0xE0) == 0xC0) {
			sequenceLength = 2;
			codePoint = firstChar & 0x1F;
		} else if ((firstChar & 0xF0) == 0xE0) {
			sequenceLength = 3;
			codePoint = firstChar & 0x0F;
		} else if ((firstChar & 0xF8) == 0xF0) {
			sequenceLength = 4;
			codePoint = firstChar & 0x07;
		} else {
			throw new JsonSyntaxException("Invalid UTF-8 start byte: " + Integer.toHexString(firstChar));
		}
		if (bytePos + sequenceLength - 1 > length) {
			throw new JsonSyntaxException("Unexpected end of text in the middle of a string character");
		}
		for (int i = 1; i < sequenceLength; i++) {
			int bx = paddedBuffer[bytePos++] & 0xFF;
			if ((bx & 0xC0) != 0x80) {
				throw new JsonSyntaxException("Invalid UTF-8 continuation byte: " + Integer.toHexString(bx));
			}
			codePoint = (codePoint << 6) | (bx & 0x3F);
		}
		return codePoint;
	}

	private boolean isCommaOrColonEntry(int i) {
		byte b = paddedBuffer[bitIndexes.get(i)];
		return b == ',' || b == ':';
	}

	private void advanceCursorPast(int newBytePos) {
		while (cursor < count && bitIndexes.get(cursor) < newBytePos) {
			cursor++;
		}
	}

	private int skipWhitespace(int start) {
		int i = start;
		while (i < length && isWhitespace(paddedBuffer[i])) {
			i++;
		}
		return i;
	}

	private static boolean isWhitespace(byte b) {
		return b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D;
	}
}
