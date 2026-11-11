package com.vomatt.users;

import com.vomatt.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Gives everyone over their limit a selection deadline ({@link PlanService#reconcileAll}), then locks the community
 * accounts of those who let it pass ({@link PlanService#expireSelection}).
 * Runs on every instance; each user is handled in their own transaction under their row lock, so concurrent or repeated
 * runs lock each account once and log it once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommunitySelectionJob {

    private final UserRepository userRepository;
    private final PlanService planService;

    @Scheduled(cron = "${app.community.selection-cron:0 */10 * * * *}", zone = "UTC")
    public void runScheduled() {
        int locked = run(OffsetDateTime.now());
        if (locked > 0) {
            log.info("community.selection locked={}", locked);
        }
    }

    /** Returns how many community accounts it locked. */
    public int run(OffsetDateTime now) {
        try {
            int reconciled = planService.reconcileAll(now);
            if (reconciled > 0) {
                log.info("community.selection reconciled={}", reconciled);
            }
        } catch (RuntimeException e) {
            log.error("community.selection reconcile failed", e);
        }
        int locked = 0;
        for (UUID userId : userRepository.findSelectionDueBy(now)) {
            try {
                locked += planService.expireSelection(userId, now);
            } catch (RuntimeException e) {
                log.error("community.selection failed for user {}", userId, e);
            }
        }
        return locked;
    }
}
