package com.posplatform.shared.domain;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7 generator (time ordered, 48-bit Unix ms prefix).
 *
 * Chosen as primary key generator over UUIDv4 because:
 *  - correlation to physical time improves index locality and replication;
 *  - globally unique: safe for offline-first devices (each POS generates ids
 *    without coordination with the server; see docs/domain/sync-strategy.md).
 *
 * PostgreSQL 17 has no native uuidv7(); when the platform's minimum supported
 * PostgreSQL offers stable generation (PG18 uuidv7()), DB-side generation may
 * replace this helper (ADR-002 notes).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        long unixMs = System.currentTimeMillis();

        long msb = (unixMs << 16)                       // 48 bits timestamp
                | 0x7000L                               // version 7
                | (RANDOM.nextLong() & 0x0FFFL);        // rand_a (12 bits)

        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)   // rand_b 62 bits
                | 0x8000_0000_0000_0000L;               // variant 10xx

        return new UUID(msb, lsb);
    }
}
