package com.vomatt.common.retention;

import com.vomatt.common.audit.AuditLogRepository;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.UserVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * The one place data is removed on a schedule (the retention policy in code):
 * <ul>
 *   <li>audit_logs: 180 days; entries whose actor's account no longer exists are anonymized (the rule of account
 *       deletion, applied again for entries that were written while that deletion was uncommitted)</li>
 *   <li>vote_invitation_emails and vote_invitees without an account: 30 days after their Poll ended</li>
 *   <li>Anonymous Ballots of Ended Polls: option_id set to NULL, so the link between an anonymous Participant and
 *       their choice stops existing once the Poll is over (counts live in vote_options.vote_count)</li>
 * </ul>
 * Runs daily on every instance; a transaction-scoped Postgres advisory lock lets exactly one of them do the work.
 * Every statement is idempotent, so a missed or repeated run changes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionJob {

    static final Duration AUDIT_LOG_RETENTION = Duration.ofDays(180);
    static final Duration UNREGISTERED_INVITEE_RETENTION = Duration.ofDays(30);
    // Arbitrary fixed key; only this job uses it
    static final long ADVISORY_LOCK_KEY = 0x766f6d617474L;

    private final TransactionTemplate transactionTemplate;
    private final AuditLogRepository auditLogRepository;
    private final InviteeRepository inviteeRepository;
    private final UserVoteRepository userVoteRepository;

    /** Rows touched by one run; all zero when another instance held the lock. */
    public record Result(int auditLogs, int orphanedAuditActors, int invitationEmails, int unregisteredInvitees, int sealedBallots) {
        static final Result SKIPPED = new Result(0, 0, 0, 0, 0);
    }

    @Scheduled(cron = "${app.retention.cron:0 30 4 * * *}", zone = "UTC")
    public void runDaily() {
        try {
            Result result = run(OffsetDateTime.now());
            log.info("retention.run audit_logs={} orphaned_audit_actors={} invitation_emails={} unregistered_invitees={} sealed_ballots={}",
                    result.auditLogs(), result.orphanedAuditActors(), result.invitationEmails(), result.unregisteredInvitees(),
                    result.sealedBallots());
        } catch (RuntimeException e) {
            log.error("retention.run failed", e);
        }
    }

    /** One transaction: lock, then the statements; returns {@link Result#SKIPPED} when the lock is held elsewhere. */
    public Result run(OffsetDateTime now) {
        return transactionTemplate.execute(status -> {
            if (!auditLogRepository.tryAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
                log.info("retention.run skipped: another instance holds the lock");
                return Result.SKIPPED;
            }
            return new Result(
                    auditLogRepository.deleteCreatedBefore(now.minus(AUDIT_LOG_RETENTION)),
                    auditLogRepository.anonymizeOrphanedActors(),
                    inviteeRepository.deleteInvitationEmailsOfPollsEndedBefore(now.minus(UNREGISTERED_INVITEE_RETENTION)),
                    inviteeRepository.deleteUnregisteredOfPollsEndedBefore(now.minus(UNREGISTERED_INVITEE_RETENTION)),
                    userVoteRepository.sealAnonymousBallotsOfPollsEndedBy(now));
        });
    }
}
