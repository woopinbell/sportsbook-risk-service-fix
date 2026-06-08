package com.sportsbook.risk.reservation;

import com.sportsbook.risk.service.RiskCheckCommand;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates a deterministic, order-independent selection fingerprint for idempotency checks. */
final class ReservationFingerprint {

  private ReservationFingerprint() {}

  static String of(RiskCheckCommand command) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      add(digest, command.userId());
      add(digest, command.betId());
      add(digest, Long.toString(command.stake().amount()));
      add(digest, command.stake().currency().name());
      command.selectionIds().stream().sorted().forEach(value -> add(digest, value));
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void add(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
