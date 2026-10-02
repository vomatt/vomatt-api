package com.vomatt.common.util;

import org.hibernate.generator.EventType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-#11：generator 改為 static final 共用單例。驗證仍能產生
 * 非 null、版本為 7（時序可排序）且彼此相異的 UUID。
 */
class UUIDv7GeneratorTest {

    private final UUIDv7Generator generator = new UUIDv7Generator();

    private UUID generate() {
        // session / owner / currentValue 在 BeforeExecutionGenerator 實作中未被使用，傳 null 即可。
        return (UUID) generator.generate(null, null, null, EventType.INSERT);
    }

    @Test
    void generate_shouldReturnNonNullVersion7Uuid() {
        UUID id = generate();

        assertThat(id).isNotNull();
        // UUID v7：version nibble 為 7
        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    void generate_twoCalls_shouldProduceDistinctTimeOrderedUuids() {
        UUID first = generate();
        UUID second = generate();

        assertThat(first).isNotEqualTo(second);
        // v7 以時間戳為高位元，後產生者排序不小於先產生者（同毫秒由單調計數器遞增保證遞增）。
        assertThat(second.compareTo(first)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void getEventTypes_shouldContainInsertOnly() {
        assertThat(generator.getEventTypes()).containsExactly(EventType.INSERT);
    }
}
