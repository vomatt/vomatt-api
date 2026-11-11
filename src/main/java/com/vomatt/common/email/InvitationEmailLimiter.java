package com.vomatt.common.email;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.vomatt.common.redis.RedisService;
import com.vomatt.common.util.TextUtil;
import com.vomatt.lookups.LookupService;
import com.vomatt.repository.InvitationEmailRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The one daily budget of invitation emails per sender (a user, or a community account), shared by Poll invitations,
 * Invite List members and community account invitations; admins tune it with the {@code config} lookup
 * {@value #LIMIT_KEY}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvitationEmailLimiter {

    static final String COUNTER_NS = "email:invitation";
    public static final String LIMIT_KEY = "invitation_email_daily_limit";
    /** A sign-up invitation reaches an address from the same sender at most once in this window. */
    static final Duration RESEND_AFTER = Duration.ofDays(7);

    private final RedisService redisService;
    private final LookupService lookupService;
    private final InvitationEmailRepository invitationEmailRepository;

    @Value("${app.email.invitation-daily-limit:200}")
    private int dailyLimit;

    /**
     * Takes up to {@code n} emails from the sender's daily quota with one INCRBY; the counter moves by {@code n} even
     * over the cap, as {@code n} single increments would. Fails closed: when Redis is down nothing is sent.
     */
    public int takeQuota(UUID senderId, int n) {
        // Read before counting and outside the catch: a failing lookup query must not be swallowed inside the caller's transaction
        int limit = lookupService.configInt(LIMIT_KEY, dailyLimit);
        try {
            long count = redisService.incrementAndExpire(COUNTER_NS, senderId + ":" + TextUtil.utcDay(), n, Duration.ofDays(1));
            return Math.clamp(limit - (count - n), 0, n);
        } catch (RuntimeException e) {
            log.warn("Invitation email limit check failed for sender {}: {}", senderId, e.getMessage());
            return 0;
        }
    }

    /**
     * Of the sign-up invitation addresses, those to mail now: not mailed by this sender within {@link #RESEND_AFTER},
     * within the daily quota.
     */
    public List<String> claim(UUID senderId, Collection<String> emails) {
        OffsetDateTime now = OffsetDateTime.now();
        return claim(senderId, emails,
                unsent -> invitationEmailRepository.findSentSince(senderId, unsent, now.minus(RESEND_AFTER)),
                recipients -> invitationEmailRepository.recordSent(senderId, recipients, now));
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
        List<String> unsent = new ArrayList<>(emails);
        unsent.removeAll(alreadySent.apply(emails));
        if (unsent.isEmpty()) {
            return List.of();
        }
        int allowed = takeQuota(senderId, unsent.size());
        if (allowed < unsent.size()) {
            log.warn("Invitation email daily limit reached: {} invitation(s) by sender {} not sent", unsent.size() - allowed, senderId);
        }
        List<String> recipients = List.copyOf(unsent.subList(0, allowed));
        if (!recipients.isEmpty()) {
            record.accept(recipients);
        }
        return recipients;
    }
}
