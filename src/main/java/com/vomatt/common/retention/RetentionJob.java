package com.vomatt.common.retention;

import com.vomatt.common.audit.AuditLogRepository;
import com.vomatt.reports.PollReportService;
import com.vomatt.repository.PollReportRepository;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.UserVoteRepository;
import com.vomatt.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The one place data is removed on a schedule (the retention policy in code):
 * <ul>
 *   <li>first, Ended Pro polls without a result report get their snapshot (PollReportService), while their Anonymous
 *       Ballots still hold an option and their unregistered Invitees still count; each in its own transaction, so one
 *       failing report neither stops the run nor gets its Anonymous Ballots sealed (they wait for the report)</li>
 *   <li>audit_logs: 180 days; entries whose actor's account no longer exists are anonymized (the rule of account
 *       deletion, applied again for entries that were written while that deletion was uncommitted)</li>
 *   <li>vote_invitation_emails and vote_invitees without an account: 30 days after their Poll ended</li>
 *   <li>Anonymous Ballots of Ended Polls: option_id set to NULL, so the link between an anonymous Participant and
 *       their choice stops existing once the Poll is over (counts live in vote_options.vote_count)</li>
 *   <li>vote_view_hours: 30 days (Trending reads only recent hours)</li>
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
    static final Duration VIEW_HOUR_RETENTION = Duration.ofDays(30);
    // Arbitrary fixed key; only this job uses it
    static final long ADVISORY_LOCK_KEY = 0x766f6d617474L;

    private final TransactionTemplate transactionTemplate;
    private final AuditLogRepository auditLogRepository;
    private final InviteeRepository inviteeRepository;
    private final UserVoteRepository userVoteRepository;
    private final VoteRepository voteRepository;
    private final PollReportService pollReportService;
    private final PollReportRepository pollReportRepository;

    /** Rows touched by one run; all zero when another instance held the lock. */
    public record Result(int auditLogs, int orphanedAuditActors, int invitationEmails, int unregisteredInvitees, int pollReports,
            int sealedBallots, int viewHours) {
        static final Result SKIPPED = new Result(0, 0, 0, 0, 0, 0, 0);
    }

    @Scheduled(cron = "${app.retention.cron:0 30 4 * * *}", zone = "UTC")
    public void runDaily() {
        try {
            Result result = run(OffsetDateTime.now());
            log.info("retention.run audit_logs={} orphaned_audit_actors={} invitation_emails={} unregistered_invitees={} poll_reports={} sealed_ballots={} view_hours={}",
                    result.auditLogs(), result.orphanedAuditActors(), result.invitationEmails(), result.unregisteredInvitees(), result.pollReports(),
                    result.sealedBallots(), result.viewHours());
        } catch (RuntimeException e) {
            log.error("retention.run failed", e);
        }
    }

    /** One transaction (each report in its own, nested inside): lock, then the statements; returns {@link Result#SKIPPED} when the lock is held elsewhere. */
    public Result run(OffsetDateTime now) {
        return transactionTemplate.execute(status -> {
            if (!auditLogRepository.tryAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
                log.info("retention.run skipped: another instance holds the lock");
                return Result.SKIPPED;
            }
            // First: a report counts every Ballot by its option and every Invitee, before sealing and invitee cleanup.
            // A failed one is retried by the next run or the first read; sealing skips its Poll until then.
            int pollReports = 0;
            for (UUID voteId : pollReportRepository.findEndedProPollsWithoutReport(now)) {
                try {
                    pollReports += pollReportService.storeMissing(voteId, now) ? 1 : 0;
                } catch (RuntimeException e) {
                    log.error("Poll report generation failed for {}", voteId, e);
                }
            }
            return new Result(
                    auditLogRepository.deleteCreatedBefore(now.minus(AUDIT_LOG_RETENTION)),
                    auditLogRepository.anonymizeOrphanedActors(),
                    inviteeRepository.deleteInvitationEmailsOfPollsEndedBefore(now.minus(UNREGISTERED_INVITEE_RETENTION)),
                    inviteeRepository.deleteUnregisteredOfPollsEndedBefore(now.minus(UNREGISTERED_INVITEE_RETENTION)),
                    pollReports,
                    userVoteRepository.sealAnonymousBallotsOfPollsEndedBy(now),
                    voteRepository.deleteViewHoursBefore(now.minus(VIEW_HOUR_RETENTION)));
        });
    }
}
