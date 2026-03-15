package com.vomattapi.infrastructure.generator;

import com.fasterxml.uuid.Generators;
import org.hibernate.annotations.IdGeneratorType;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.EnumSet;

public class UUIDv7Generator implements BeforeExecutionGenerator {

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
            EventType eventType) {
        return Generators.timeBasedEpochGenerator().generate();
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
