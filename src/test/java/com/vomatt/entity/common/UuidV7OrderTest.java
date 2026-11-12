package com.vomatt.entity.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Keyset pagination orders by id, so ids from Hibernate's UUID v7 strategy (used by BaseEntity and the native inserts)
 * must strictly increase in Postgres uuid order (unsigned bytes), also when many fall in the same millisecond.
 */
class UuidV7OrderTest {

    @Test
    void shouldGenerateStrictlyIncreasingVersion7IdsWhenCalledInATightLoop() {
        UUID previous = BaseEntity.newTimeOrderedId();
        for (int i = 0; i < 100_000; i++) {
            UUID next = BaseEntity.newTimeOrderedId();
            assertThat(next.version()).isEqualTo(7);
            assertThat(postgresOrder(previous, next)).as("%s then %s", previous, next).isNegative();
            previous = next;
        }
    }

    private static int postgresOrder(UUID a, UUID b) {
        int msb = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return msb != 0 ? msb : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
