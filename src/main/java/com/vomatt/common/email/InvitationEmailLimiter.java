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
    private int takeQuota(UUID senderId, int n) {
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
     * Of the sign-up invitation addresses, those to mail now: not mailed by this sender about this scope (the Invite
     * List or community account) within {@link #RESEND_AFTER}, within the daily quota.
     */
    public List<String> claim(UUID senderId, UUID scopeId, Collection<String> emails) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID sender = realSender(senderId);
        return claim(sender, emails,
                unsent -> invitationEmailRepository.findSentSince(sender, scopeId, unsent, now.minus(RESEND_AFTER)),
                recipients -> invitationEmailRepository.recordSent(sender, scopeId, recipients, now));
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

    /** The member acting as the community account {@code senderId}, or the sender itself. */
    private static UUID realSender(UUID senderId) {
        return ActingMember.current().map(ActingMember::memberId).orElse(senderId);
    }
}
