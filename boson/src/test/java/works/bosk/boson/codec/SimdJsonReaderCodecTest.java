package works.bosk.boson.codec;

import java.io.IOException;
import java.io.StringWriter;
import works.bosk.boson.TestUtils.OneOfEach;
import works.bosk.boson.codec.io.SimdJsonReader;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.types.DataType;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.junit.InjectedTest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.boson.TestUtils.ONE_OF_EACH;
import static works.bosk.boson.TestUtils.expectedOneOfEach;
import static works.bosk.boson.codec.compiler.SpecCompilerTest.testTypeMap;

/**
 * Tests that the {@link Codec} works with the SIMD-backed {@link SimdJsonReader},
 * through both the interpreter and compiled codec paths.
 */
@InjectFields
@InjectFrom(SettingsInjector.class)
class SimdJsonReaderCodecTest {
	@Injected TypeMap.Settings settings;

	TypeMap typeMap;
	JsonValueSpec spec;

	@InjectedTest
	void testParser() throws IOException, NoSuchMethodException, IllegalAccessException {
		initialize();
		Codec codec = CodecBuilder.using(typeMap).build();
		assertEquals(expectedOneOfEach(),
			codec.parserFor(spec).parse(new SimdJsonReader(ONE_OF_EACH.getBytes(UTF_8))));
	}

	@InjectedTest
	void roundTrip() throws IOException, NoSuchMethodException, IllegalAccessException {
		initialize();
		Codec codec = CodecBuilder.using(typeMap).build();
		StringWriter sw = new StringWriter();
		codec.generatorFor(spec).generate(sw, expectedOneOfEach());
		assertEquals(expectedOneOfEach(),
			codec.parserFor(spec).parse(new SimdJsonReader(sw.toString().getBytes(UTF_8))));
	}

	private void initialize() throws NoSuchMethodException, IllegalAccessException {
		DataType type = DataType.of(OneOfEach.class);
		typeMap = testTypeMap(type, settings);
		spec = typeMap.get(type);
	}
}
