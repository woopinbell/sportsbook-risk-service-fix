package com.sportsbook.risk.event;

import java.io.ByteArrayOutputStream;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;

/**
 * Minimal Avro binary codec for the SpecificRecord types generated out of {@code shared-protocol}.
 *
 * <p>V1 stays single-schema-per-topic: there is no Schema Registry (ADR-0014), every producer and
 * consumer in the monorepo-like deployment links the same {@code shared-protocol} version, so we
 * can ship the bytes plain rather than prefixing a schema id. V2 swaps this for the Apicurio
 * serializer.
 */
final class AvroCodec {

  private AvroCodec() {}

  static <T extends SpecificRecordBase> byte[] encode(T record) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
      SpecificDatumWriter<T> writer = new SpecificDatumWriter<>(record.getSchema());
      writer.write(record, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to encode " + record.getClass().getSimpleName() + " to Avro", e);
    }
  }

  static <T extends SpecificRecordBase> T decode(byte[] payload, Class<T> type) {
    try {
      T template = type.getDeclaredConstructor().newInstance();
      SpecificDatumReader<T> reader = new SpecificDatumReader<>(type);
      reader.setSchema(template.getSchema());
      BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(payload, null);
      return reader.read(null, decoder);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to decode " + type.getSimpleName() + " from Avro", e);
    }
  }
}
