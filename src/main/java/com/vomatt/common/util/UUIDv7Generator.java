package com.vomatt.common.util;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import org.hibernate.annotations.IdGeneratorType;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.EnumSet;
import java.util.UUID;

public class UUIDv7Generator implements BeforeExecutionGenerator {

    // Share a single generator: it is thread-safe and keeps the monotonic state within a millisecond,
    // avoiding a rebuild on every call (which is wasteful and loses the same-millisecond ordering guarantee).
    private static final NoArgGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    /**
     * Generates a UUID v7 directly for non-entity-id cases (e.g. E2 POS {@code checkoutId}, generated in memory only and never persisted),
     * reusing the same shared generator instance instead of building a new one.
     */
    public static UUID generateId() {
        return GENERATOR.generate();
    }

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
            EventType eventType) {
        return GENERATOR.generate();
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EnumSet.of(EventType.INSERT);
    }

    @IdGeneratorType(UUIDv7Generator.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface UUIDv7 {
    }
}
