package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.common.audit.AuditService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Plan;
import com.vomatt.entity.User;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteServiceSlice;

/**
 * Plan changes racing on one user, with real commits: each waits on the user row and then sees what the other did, so
 * two creations make one and two selection-job runs lock (and log) each community account once.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ CommunityService.class, UserMapper.class, PlanService.class, AuditService.class, CommunitySelectionJob.class,
        VoteServiceSlice.class })
class CommunityLimitLockPostgresTest extends PostgresRepositoryTest {

    @Autowired TransactionTemplate tx;
    @Autowired CommunityService communityService;
    @Autowired CommunityMemberRepository memberRepository;
    @MockitoBean UserService userService;
    @Autowired PlanService planService;
    @Autowired CommunitySelectionJob job;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private User owner;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> owner = persistUser("race-" + UUID.randomUUID()));
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM vomatt.audit_logs WHERE resource_id IN (SELECT CAST(community_user_id AS text) "
                    + "FROM vomatt.community_members WHERE user_id = :id) OR resource_id = CAST(:id AS text)")
                    .setParameter("id", owner.getId()).executeUpdate();
            em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (SELECT community_user_id FROM vomatt.community_members WHERE user_id = :id)")
                    .setParameter("id", owner.getId()).executeUpdate();
            em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", owner.getId()).executeUpdate();
        });
    }

    @Test
    void shouldCreateOnlyOneWhenTwoCreationsRace() throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> first = pool.submit(() -> tx.executeWithoutResult(s -> {
            create();
            created.countDown();
            await(release);
        }));
        assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> second = pool.submit(() -> tx.executeWithoutResult(s -> create()));
        Thread.sleep(300);
        assertThat(second.isDone()).as("second creation waits on the user row").isFalse();
        release.countDown();
        first.get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_LIMIT_REACHED));
        assertThat(memberRepository.countOwned(owner.getId())).isEqualTo(1);
    }

    @Test
    void shouldLockEachCommunityOnceWhenSelectionJobRunsTwiceAtOnce() throws Exception {
        tx.executeWithoutResult(s -> planService.changePlan(owner.getId(), Plan.PRO, PlanService.Source.ADMIN, null));
        for (int i = 0; i < 3; i++) {
            tx.executeWithoutResult(s -> create());
        }
        OffsetDateTime now = OffsetDateTime.now();
        tx.executeWithoutResult(s -> {
            planService.changePlan(owner.getId(), Plan.FREE, PlanService.Source.ADMIN, null);
            em.createNativeQuery("UPDATE vomatt.users SET community_selection_deadline = :at WHERE id = :id")
                    .setParameter("at", now.minusMinutes(1)).setParameter("id", owner.getId()).executeUpdate();
        });
        CountDownLatch ran = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> first = pool.submit(() -> tx.execute(s -> {
            int locked = planService.expireSelection(owner.getId(), now);
            ran.countDown();
            await(release);
            return locked;
        }));
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Integer> second = pool.submit(() -> job.run(now));
        Thread.sleep(300);
        assertThat(second.isDone()).as("second run waits on the user row").isFalse();
        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(second.get(5, TimeUnit.SECONDS)).isZero();
        assertThat(memberRepository.countOwned(owner.getId())).isEqualTo(1);
        Object lockAudits = tx.execute(s -> em.createNativeQuery("SELECT count(*) FROM vomatt.audit_logs WHERE action = 'LOCK' AND resource_id IN "
                + "(SELECT CAST(community_user_id AS text) FROM vomatt.community_members WHERE user_id = :id)")
                .setParameter("id", owner.getId()).getSingleResult());
        assertThat(lockAudits).isEqualTo(2L);
    }

    private void create() {
        communityService.createCommunity("race_" + UUID.randomUUID().toString().substring(0, 8), "Race", owner.getId());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
