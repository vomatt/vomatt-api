package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.vomatt.entity.User;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteServiceSlice;

/** Two creations by one FREE user racing, with real commits: the second waits on the user row and then counts the first. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ CommunityService.class, UserMapper.class, PlanService.class, AuditService.class, VoteServiceSlice.class })
class CommunityLimitLockPostgresTest extends PostgresRepositoryTest {

    @Autowired TransactionTemplate tx;
    @Autowired CommunityService communityService;
    @Autowired CommunityMemberRepository memberRepository;
    @MockitoBean UserService userService;

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
