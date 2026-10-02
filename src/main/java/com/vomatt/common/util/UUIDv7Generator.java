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

    // 共用單一 generator：thread-safe，且保留毫秒內的單調遞增狀態，
    // 避免每次呼叫都重建（既浪費也喪失同毫秒排序保證）。
    private static final NoArgGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    /**
     * 供非 entity-id 情境（例：E2 POS {@code checkoutId}，僅記憶體產生、不落地）直接產生 UUID v7，
     * 複用同一個共用 generator instance，不額外重建。
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
