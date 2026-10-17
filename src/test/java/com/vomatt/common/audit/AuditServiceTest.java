package com.vomatt.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    AuditLogRepository auditLogRepository;

    @InjectMocks
    AuditService auditService;

    @Test
    @DisplayName("should drop the audit write when both writers stay busy")
    void shouldDropAuditWriteWhenWritersBusy() throws Exception {
        ReflectionTestUtils.setField(auditService, "auditEnabled", true);
        auditService.permitWaitMillis = 10;
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(auditLogRepository.save(any())).thenAnswer(inv -> {
            started.countDown();
            release.await();
            return inv.getArgument(0);
        });
        Thread first = Thread.ofVirtual().start(() -> auditService.logAction("u", "u", "A", "R", "1"));
        Thread second = Thread.ofVirtual().start(() -> auditService.logAction("u", "u", "A", "R", "2"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        auditService.logAction("u", "u", "A", "R", "3");  // waits out the permit timeout, then drops

        release.countDown();
        first.join();
        second.join();
        verify(auditLogRepository, times(2)).save(any());
    }
}
