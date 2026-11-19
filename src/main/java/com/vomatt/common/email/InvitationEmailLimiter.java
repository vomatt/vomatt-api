package com.vomatt.common.email;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.util.TextUtil;
import com.vomatt.entity.User;
import com.vomatt.lookups.LookupService;
import com.vomatt.repository.InvitationEmailRepository;

import com.vomatt.common.security.ActingMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The one daily budget of invitation emails per person, shared by Poll invitations, Invite List members and community
 * account invitations; admins tune it with the {@code config} lookup {@value #LIMIT_KEY}. While a member acts as a
 * community account the budget is the member's own, so more community accounts never mean more emails.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvitationEmailLimiter {

    static final String COUNTER_NS = "email:invitation";
    public static final String LIMIT_KEY = "invitation_email_daily_limit";
    public static final String BURST_LIMIT_KEY = "invitation_email_burst_limit";
    public static final String BURST_WINDOW_HOURS_KEY = "invitation_email_burst_window_hours";
    public static final String LOCK_DAYS_KEY = "invitation_email_lock_days";
    static final int DEFAULT_BURST_LIMIT = 3;
    static final Duration DEFAULT_BURST_WINDOW = Duration.ofHours(12);
    static final Duration DEFAULT_LOCK = Duration.ofDays(7);

    private final RedisService redisService;
    private final LookupService lookupService;
    private final InvitationEmailRepository invitationEmailRepository;

    /** Fallback when the {@value #LIMIT_KEY} config lookup is unset or invalid. */
    static final int DEFAULT_DAILY_LIMIT = 200;

    /**
     * Takes up to {@code n} emails from the sender's daily quota with one INCRBY; the counter moves by {@code n} even
     * over the cap, as {@code n} single increments would. Fails closed: when Redis is down nothing is sent.
     */
    private int takeQuota(UUID senderId, int n) {
        // Read before counting and outside the catch: a failing lookup query must not be swallowed inside the caller's transaction
        int limit = lookupService.configInt(LIMIT_KEY, DEFAULT_DAILY_LIMIT);
        try {
            long count = redisService.incrementAndExpire(COUNTER_NS, senderId + ":" + TextUtil.utcDay(), n, Duration.ofDays(1));
            return Math.clamp(limit - (count - n), 0, n);
        } catch (RuntimeException e) {
            log.warn("Invitation email limit check failed for sender {}: {}", senderId, e.getMessage());
            return 0;
        }
    }

    /**
     * Of the sign-up invitation addresses (Invite List members, community account invitations), those to mail now: the
     * ones the sender is not locked out of ({@link #lockedUntil}), within the daily quota. Each one mailed counts toward
     * the sender's burst for that address, whatever the Invite List or community account.
     */
    public List<String> claim(UUID senderId, Collection<String> emails) {
        OffsetDateTime now = sentNow();
        UUID sender = realSender(senderId);
        return claim(sender, emails, unsent -> invitationEmailRepository.findLocked(sender, unsent, now).keySet(),
                recipients -> record(sender, recipients, now));
    }

    /**
     * Claims one more sign-up invitation email to the address, the way {@link #claim(UUID, Collection)} would, but says
     * why not: 429 {@code invitation.email.too_soon} while the sender is locked out of the address (checked first, so it
     * uses no quota), 429 {@code invitation.email.daily_limit} over the sender's daily quota. Returns when the sender may
     * next mail the address: {@code null} at once, or when the lock this email started ends.
     */
    public OffsetDateTime claimOrThrow(UUID senderId, String email) {
        OffsetDateTime now = sentNow();
        UUID sender = realSender(senderId);
        if (!invitationEmailRepository.findLocked(sender, List.of(email), now).isEmpty()) {
            throw ApiException.tooManyRequests(MessageKey.INVITATION_EMAIL_TOO_SOON);
        }
        if (claim(sender, List.of(email), unsent -> List.of(), recipients -> record(sender, recipients, now)).isEmpty()) {
            throw ApiException.tooManyRequests(MessageKey.INVITATION_EMAIL_DAILY_LIMIT);
        }
        return invitationEmailRepository.findLocked(sender, List.of(email), now).get(email);
    }

    /**
     * The addresses the sender (or the member acting as it) is locked out of, with when each lock ends; the others may be
     * mailed now. A sender is locked out of an address for {@value #LOCK_DAYS_KEY} days ({@link #DEFAULT_LOCK}) once they
     * mail it {@value #BURST_LIMIT_KEY} times ({@link #DEFAULT_BURST_LIMIT}) within one window of
     * {@value #BURST_WINDOW_HOURS_KEY} hours ({@link #DEFAULT_BURST_WINDOW}), until an admin unlocks the pair.
     */
    public Map<String, OffsetDateTime> lockedUntil(UUID senderId, Collection<String> emails) {
        return invitationEmailRepository.findLocked(realSender(senderId), emails, OffsetDateTime.now());
    }

    /** Admin: every sender locked out of an address now, the latest lock to end first. */
    @Transactional(readOnly = true)
    public List<InvitationEmailRepository.Lock> locks() {
        return invitationEmailRepository.findLocks(OffsetDateTime.now());
    }

    /** Admin: lifts the sender's lock on the address and resets its count; 404 when there is no lock in force. */
    @Transactional
    public void unlock(UUID senderId, String email) {
        if (!invitationEmailRepository.unlock(senderId, User.normalizeEmail(email), OffsetDateTime.now())) {
            throw ApiException.notFound(MessageKey.INVITATION_EMAIL_LOCK_NOT_FOUND);
        }
        log.info("Invitation email lock of sender {} on {} lifted", senderId, TextUtil.maskEmail(email));
    }

    // ponytail: the lock check and this record are separate statements, so two invitations to one address at the same
    // moment can both go out past the burst limit; lock the pair first if that matters
    private void record(UUID sender, List<String> recipients, OffsetDateTime now) {
        int burstLimit = lookupService.configInt(BURST_LIMIT_KEY, DEFAULT_BURST_LIMIT);
        Duration window = lookupService.configDuration(BURST_WINDOW_HOURS_KEY, ChronoUnit.HOURS, DEFAULT_BURST_WINDOW);
        Duration lock = lookupService.configDuration(LOCK_DAYS_KEY, ChronoUnit.DAYS, DEFAULT_LOCK);
        invitationEmailRepository.recordSent(sender, recipients, now, now.minus(window), burstLimit, now.plus(lock));
    }

    /**
     * Of {@code emails}, those to mail now: the ones {@code alreadySent} leaves out, up to the sender's daily quota.
     * Records them with {@code record} before returning (at most once: a failed send is not retried); the caller mails
     * them after commit. Over the quota the rest are skipped, never queued.
     */
    public List<String> claim(UUID senderId, Collection<String> emails, Function<Collection<String>, Collection<String>> alreadySent,
                              Consumer<List<String>> record) {
        if (emails.isEmpty()) {
            return List.of();
        }
        UUID sender = realSender(senderId);
        List<String> unsent = new ArrayList<>(emails);
        unsent.removeAll(alreadySent.apply(emails));
        if (unsent.isEmpty()) {
            return List.of();
        }
        int allowed = takeQuota(sender, unsent.size());
        if (allowed < unsent.size()) {
            log.warn("Invitation email daily limit reached: {} invitation(s) by sender {} not sent", unsent.size() - allowed, sender);
        }
        List<String> recipients = List.copyOf(unsent.subList(0, allowed));
        if (!recipients.isEmpty()) {
            record.accept(recipients);
        }
        return recipients;
    }

    // At the precision sent_at keeps (Postgres stores microseconds), so a returned time equals the one read back later
    private static OffsetDateTime sentNow() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** The member acting as the community account {@code senderId}, or the sender itself. */
    private static UUID realSender(UUID senderId) {
        return ActingMember.current().map(ActingMember::memberId).orElse(senderId);
    }
}
