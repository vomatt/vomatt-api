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
    @DisplayName("should resolve the actor on the writer and anonymize entries of an actor that no longer exists")
    void shouldResolveActorWhenWritingEntry() {
        ReflectionTestUtils.setField(auditService, "auditEnabled", true);
        java.util.UUID id = java.util.UUID.randomUUID();
        java.util.UUID gone = java.util.UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(java.util.Optional.of(com.vomatt.entity.User.builder().id(id).username("alice").build()));
        when(userRepository.findById(gone)).thenReturn(java.util.Optional.empty());
        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.logAction(id.toString(), null, "VIEW", "USER", "r", "203.0.113.7", "ua", "/api/admin/users/r", "GET", null);
        // Queued before the account went away: identity dropped, the action and its resource kept
        auditService.logAction(gone.toString(), null, "BLOCK", "USER", "r", "203.0.113.7", "ua", "/api/users/me/blocks/r", "POST", null);
        // The deletion itself: resource id and request URI spell the deleted id, so they go too
        auditService.logAction(gone.toString(), null, "DELETE", "USER", gone.toString(), "203.0.113.7", "ua", "/api/users/" + gone, "DELETE", null);
        // Plain (5-arg) entries go through the same resolution
        auditService.logAction(gone.toString(), "stale", "SUSPEND", "USER", "r");

        org.mockito.ArgumentCaptor<AuditLog> saved = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(4)).save(saved.capture());
        java.util.List<AuditLog> rows = saved.getAllValues();
        assertThat(rows.get(0)).extracting(AuditLog::getUserId, AuditLog::getUsername, AuditLog::getIpAddress)
                .containsExactly(id.toString(), "alice", "203.0.113.7");
        assertThat(rows.get(1)).extracting(AuditLog::getUserId, AuditLog::getUsername, AuditLog::getIpAddress, AuditLog::getUserAgent,
                AuditLog::getResourceId, AuditLog::getRequestUri).containsExactly(null, null, null, null, "r", "/api/users/me/blocks/r");
        assertThat(rows.get(2)).extracting(AuditLog::getUserId, AuditLog::getUsername, AuditLog::getIpAddress, AuditLog::getUserAgent,
                AuditLog::getResourceId, AuditLog::getRequestUri, AuditLog::getAction).containsExactly(null, null, null, null, null, null, "DELETE");
        assertThat(rows.get(3)).extracting(AuditLog::getUserId, AuditLog::getUsername, AuditLog::getResourceId).containsExactly(null, null, "r");
    }

    @Test
    @DisplayName("should look the actor up only after a write permit is held")
    void shouldResolveActorUnderWritePermit() throws Exception {
        ReflectionTestUtils.setField(auditService, "auditEnabled", true);
        auditService.permitWaitMillis = 10;
        java.util.UUID id = java.util.UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(auditLogRepository.save(any())).thenAnswer(inv -> {
            started.countDown();
            release.await();
            return inv.getArgument(0);
        });
        Thread first = Thread.ofVirtual().start(() -> auditService.logAction(null, "u", "A", "R", "1"));
        Thread second = Thread.ofVirtual().start(() -> auditService.logAction(null, "u", "A", "R", "2"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        auditService.logAction(id.toString(), null, "VIEW", "USER", "r", null, null, "/x", "GET", null); // dropped: no permit

        release.countDown();
        first.join();
        second.join();
        verify(userRepository, org.mockito.Mockito.never()).findById(id);
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
        Thread first = Thread.ofVirtual().start(() -> auditService.logAction(null, "u", "A", "R", "1"));
        Thread second = Thread.ofVirtual().start(() -> auditService.logAction(null, "u", "A", "R", "2"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        auditService.logAction(null, "u", "A", "R", "3");  // waits out the permit timeout, then drops

        release.countDown();
        first.join();
        second.join();
        verify(auditLogRepository, times(2)).save(any());
    }
}
