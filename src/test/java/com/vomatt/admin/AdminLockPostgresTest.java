package com.vomatt.admin;

import com.vomatt.entity.User;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two admins removing each other at once, with real commits: the last-admin guard's row lock makes the second wait
 * and then see the first one's change, so it cannot also pass.
 */
@Import(LockTimeout.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminLockPostgresTest extends PostgresRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate tx;
    @Autowired LockTimeout lockTimeout;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> {
            alice = admin("alice-");
            bob = admin("bob-");
        });
    }

    private User admin(String prefix) {
        User user = persistUser(prefix + UUID.randomUUID());
        user.setRoles(new String[] { "user", "admin" });
        return user;
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)")
                .setParameter("ids", List.of(alice.getId(), bob.getId())).executeUpdate());
    }

    @Test
    void shouldSeeFirstRemovalWhenSecondAdminRemovalWaitsOnLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Alice suspends Bob: lock, see Alice as another admin, suspend, hold the transaction open
        Future<List<UUID>> first = pool.submit(() -> tx.execute(s -> {
            List<UUID> admins = userRepository.lockActiveAdminIds();
            em.createNativeQuery("UPDATE vomatt.users SET active = false WHERE id = :id")
                    .setParameter("id", bob.getId()).executeUpdate();
            locked.countDown();
            await(release);
            return admins;
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // Bob suspends Alice meanwhile: blocked until Alice's transaction commits, then Bob is no longer an admin
        Future<List<UUID>> second = pool.submit(() -> tx.execute(s -> userRepository.lockActiveAdminIds()));
        assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).contains(alice.getId(), bob.getId());
        assertThat(second.get(5, TimeUnit.SECONDS)).contains(alice.getId()).doesNotContain(bob.getId());
    }

    @Test
    void shouldNotBlockAdminForeignKeyInsertWhenAdminRowsLocked() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<List<UUID>> guard = pool.submit(() -> tx.execute(s -> {
            List<UUID> admins = userRepository.lockActiveAdminIds();
            locked.countDown();
            await(release);
            return admins;
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // An admin creating a Poll takes KEY SHARE on their users row, which FOR NO KEY UPDATE does not block
        Future<?> insert = pool.submit(() -> tx.executeWithoutResult(s ->
                persistPoll(em.find(User.class, alice.getId()), OffsetDateTime.now(), OffsetDateTime.now().plusDays(1), "A", "B")));
        insert.get(2, TimeUnit.SECONDS);

        release.countDown();
        guard.get(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldRestoreDefaultLockTimeoutWhenReset() {
        String timeout = tx.execute(s -> {
            lockTimeout.apply();
            userRepository.lockActiveAdminIds();
            lockTimeout.reset();
            return (String) em.createNativeQuery("SHOW lock_timeout").getSingleResult();
        });

        assertThat(timeout).isEqualTo("0");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
