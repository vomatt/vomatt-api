package com.vomatt.common.util;

import org.hibernate.generator.EventType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-#11: the generator is now a shared static final singleton. Verifies it still produces
 * non-null, version 7 (time-sortable), mutually distinct UUIDs.
 */
class UUIDv7GeneratorTest {

    private final UUIDv7Generator generator = new UUIDv7Generator();

    private UUID generate() {
        // session / owner / currentValue are unused by the BeforeExecutionGenerator implementation, so null is fine.
        return (UUID) generator.generate(null, null, null, EventType.INSERT);
    }

    @Test
    void generate_shouldReturnNonNullVersion7Uuid() {
        UUID id = generate();

        assertThat(id).isNotNull();
        // UUID v7: version nibble is 7
        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    void generate_twoCalls_shouldProduceDistinctTimeOrderedUuids() {
        UUID first = generate();
        UUID second = generate();

        assertThat(first).isNotEqualTo(second);
        // v7 puts the timestamp in the high bits, so later UUIDs sort no lower than earlier ones (within the same millisecond a monotonic counter guarantees increase).
        assertThat(second.compareTo(first)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void getEventTypes_shouldContainInsertOnly() {
        assertThat(generator.getEventTypes()).containsExactly(EventType.INSERT);
    }
}
