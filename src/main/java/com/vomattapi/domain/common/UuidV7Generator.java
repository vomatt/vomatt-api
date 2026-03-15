package com.vomattapi.domain.common;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UUID v7 generator for Hibernate 6.x.
 * UUID v7 is time-ordered, combining a millisecond-precision timestamp
 * in the most significant bits with random bits in the least significant bits.
 * This provides sequential ordering (better B-tree index performance) while
 * remaining random and non-guessable.
 */
public class UuidV7Generator implements IdentifierGenerator {

    @Override
    public Object generate(SharedSessionContractImplementor session, Object object) {
        return generateUuidV7();
    }

    public static UUID generateUuidV7() {
        long timestamp = System.currentTimeMillis();
        long random = ThreadLocalRandom.current().nextLong();

        // MSB: 48-bit timestamp | 4-bit version (0x7) | 12-bit random
        long msb = (timestamp << 16) | 0x7000L | ((random >>> 52) & 0x0FFFL);
        // LSB: 2-bit variant (0b10) | 62-bit random
        long lsb = (random & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(msb, lsb);
    }
}
