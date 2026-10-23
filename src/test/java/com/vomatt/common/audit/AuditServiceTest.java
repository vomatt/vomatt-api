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
    @Mock
    com.vomatt.repository.UserRepository userRepository;

    @InjectMocks
    AuditService auditService;

    @Test
    @DisplayName("should resolve the actor's username on the writer and leave a deleted account unnamed")
    void shouldResolveUsernameWhenWritingDetailedEntry() {
        ReflectionTestUtils.setField(auditService, "auditEnabled", true);
        java.util.UUID id = java.util.UUID.randomUUID();
        java.util.UUID gone = java.util.UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(java.util.Optional.of(com.vomatt.entity.User.builder().id(id).username("alice").build()));
        when(userRepository.findById(gone)).thenReturn(java.util.Optional.empty());
        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.logAction(id.toString(), null, "VIEW", "USER", "r", "203.0.113.7", "ua", "/x", "GET", null);
        auditService.logAction(gone.toString(), null, "VIEW", "USER", "r", "203.0.113.7", "ua", "/x", "GET", null);
        auditService.logAction(null, null, "DELETE", "USER", "r", null, null, "/x", "DELETE", null);

        org.mockito.ArgumentCaptor<AuditLog> saved = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AuditLog::getUsername).containsExactly("alice", null, null);
        verify(userRepository, times(2)).findById(any());
    }

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
