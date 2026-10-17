package com.vomatt.admin;

import com.vomatt.admin.dto.AdminStatsResponse;
import com.vomatt.common.audit.AuditLog;
import com.vomatt.common.response.PageResponse;
import com.vomatt.entity.Audience;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.entity.VoteStatus;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin list filters and dashboard counts against real SQL; the container is shared, so tests scope by unique data. */
@Import(AdminQueryRepository.class)
class AdminQueryPostgresTest extends PostgresRepositoryTest {

    @Autowired AdminQueryRepository repository;
    @Autowired UserRepository userRepository;

    final OffsetDateTime now = OffsetDateTime.now();
    final Pageable firstPage = PageResponse.pageable(null, null);

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void shouldMatchAnyContactFieldWhenSearchingUsers() {
        String tag = unique("zq");
        User byName = persistUser(tag + "name");
        User byDisplay = persistUser(unique("other"));
        byDisplay.setDisplayName("Display " + tag.toUpperCase());
        User byPhone = persistUser(unique("phone"));
        String digits = String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
        byPhone.setPhoneNumber("+88691" + digits);
        persistUser(unique("unrelated"));
        em.flush();

        Page<User> page = repository.findUsers(tag, null, null, firstPage);

        assertThat(page.getContent()).extracting(User::getId).containsExactlyInAnyOrder(byName.getId(), byDisplay.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(repository.findUsers("91" + digits, null, null, firstPage).getContent()).extracting(User::getId)
                .containsExactly(byPhone.getId());
        assertThat(repository.findUsers(byPhone.getEmail().toUpperCase(), null, null, firstPage).getContent())
                .extracting(User::getId).containsExactly(byPhone.getId());
    }

    @Test
    void shouldFilterByStatusAndRoleWhenListingUsers() {
        String tag = unique("st");
        User suspended = persistUser(tag + "a");
        suspended.setActive(false);
        User moderator = persistUser(tag + "b");
        moderator.setRoles(new String[] { "user", "moderator" });
        em.flush();

        assertThat(repository.findUsers(tag, false, null, firstPage).getContent()).extracting(User::getId)
                .containsExactly(suspended.getId());
        assertThat(repository.findUsers(tag, true, "moderator", firstPage).getContent()).extracting(User::getId)
                .containsExactly(moderator.getId());
        assertThat(repository.findUsers(tag, null, null, firstPage).getContent()).extracting(User::getId)
                .containsExactly(moderator.getId(), suspended.getId()); // newest first
    }

    @Test
    void shouldPageWithTotalWhenMoreRowsThanLimit() {
        String tag = unique("pg");
        for (int i = 0; i < 3; i++) {
            persistUser(tag + i);
        }
        em.flush();

        Page<User> second = repository.findUsers(tag, null, null, PageResponse.pageable(2, 2));

        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(second.getContent()).hasSize(1);
    }

    @Test
    void shouldDeriveStatusFromTimesWhenFilteringPolls() {
        User owner = persistUser(unique("owner"));
        Vote scheduled = persistPoll(owner, now.plusDays(1), now.plusDays(2), "a", "b");
        Vote open = persistPoll(owner, now.minusDays(1), now.plusDays(1), "a", "b");
        Vote ended = persistPoll(owner, now.minusDays(2), now.minusDays(1), "a", "b");
        // Cancelled while Scheduled: end before start, so Ended wins
        Vote cancelled = persistPoll(owner, now.plusDays(1), now.minusHours(1), "a", "b");
        em.flush();

        assertThat(votes(VoteStatus.SCHEDULED, owner)).containsExactly(scheduled.getId());
        assertThat(votes(VoteStatus.OPEN, owner)).containsExactly(open.getId());
        assertThat(votes(VoteStatus.ENDED, owner)).containsExactlyInAnyOrder(ended.getId(), cancelled.getId());
        assertThat(votes(null, owner)).hasSize(4);
    }

    private List<UUID> votes(VoteStatus status, User owner) {
        return repository.findVotes(null, status, owner.getId(), null, null, now, firstPage).getContent()
                .stream().map(Vote::getId).toList();
    }

    @Test
    void shouldIncludeDeletedAndNonPublicWhenFilteringPolls() {
        User owner = persistUser(unique("owner"));
        Vote deleted = persistPoll(owner, now.minusDays(1), now.plusDays(1), "a", "b");
        deleted.setDeletedAt(now);
        Vote inviteOnly = persistPoll(owner, now.minusDays(1), now.plusDays(1), "a", "b");
        inviteOnly.setAudience(Audience.INVITE_ONLY);
        String word = unique("needle");
        inviteOnly.setTitle("Find the " + word + " here");
        em.flush();

        assertThat(repository.findVotes(null, null, owner.getId(), null, true, now, firstPage).getContent())
                .extracting(Vote::getId).containsExactly(deleted.getId());
        assertThat(repository.findVotes(null, null, owner.getId(), null, false, now, firstPage).getContent())
                .extracting(Vote::getId).containsExactly(inviteOnly.getId());
        assertThat(repository.findVotes(null, null, owner.getId(), Audience.INVITE_ONLY, null, now, firstPage).getContent())
                .extracting(Vote::getId).containsExactly(inviteOnly.getId());
        assertThat(repository.findVotes(word.toUpperCase(), null, null, null, null, now, firstPage).getContent())
                .extracting(Vote::getId).containsExactly(inviteOnly.getId());
    }

    @Test
    void shouldFilterByPollAuthorTextAndDeletedWhenListingComments() {
        User owner = persistUser(unique("owner"));
        User author = persistUser(unique("author"));
        Vote poll = persistPoll(owner, now.minusDays(1), now.plusDays(1), "a", "b");
        VoteComment live = new VoteComment(poll, author, "Totally fine comment");
        VoteComment spam = new VoteComment(poll, owner, "Buy CHEAP stuff");
        spam.softDelete();
        em.persist(live);
        em.persist(spam);
        em.flush();

        assertThat(repository.findComments(poll.getId(), null, null, null, firstPage).getTotalElements()).isEqualTo(2);
        assertThat(repository.findComments(poll.getId(), author.getId(), null, null, firstPage).getContent())
                .extracting(VoteComment::getId).containsExactly(live.getId());
        assertThat(repository.findComments(poll.getId(), null, "cheap", null, firstPage).getContent())
                .extracting(VoteComment::getId).containsExactly(spam.getId());
        assertThat(repository.findComments(poll.getId(), null, null, true, firstPage).getContent())
                .extracting(VoteComment::getId).containsExactly(spam.getId());
    }

    @Test
    void shouldFilterByActionResourceAndRangeWhenListingAuditLogs() {
        String actor = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString();
        AuditLog suspend = new AuditLog(actor, "admin@test.local", "SUSPEND", "USER", target);
        suspend.setDetails("SuspendUserRequest[reason=spam]");
        AuditLog other = new AuditLog(actor, "admin@test.local", "FORCE_DELETE", "VOTE", UUID.randomUUID().toString());
        em.persist(suspend);
        em.persist(other);
        em.flush();

        Page<AuditLog> byActor = repository.findAuditLogs(actor, null, null, null, null, null, firstPage);
        assertThat(byActor.getTotalElements()).isEqualTo(2);
        assertThat(repository.findAuditLogs(actor, "SUSPEND", "USER", target, now.minusMinutes(1), now.plusMinutes(1), firstPage)
                .getContent()).singleElement().satisfies(log -> assertThat(log.getDetails()).contains("spam"));
        assertThat(repository.findAuditLogs(actor, null, null, null, now.plusMinutes(1), null, firstPage).getContent()).isEmpty();
    }

    @Test
    void shouldCountByDerivedStatusWhenComputingStats() {
        AdminStatsResponse before = repository.stats(now);
        User owner = persistUser(unique("owner"));
        User voter = persistUser(unique("voter"));
        voter.setActive(false);
        persistPoll(owner, now.minusDays(1), now.plusDays(1), "a", "b");
        Vote ended = persistPoll(owner, now.minusDays(2), now.minusDays(1), "a", "b");
        Vote deleted = persistPoll(owner, now.minusDays(1), now.plusDays(1), "a", "b");
        deleted.setDeletedAt(now);
        persistPoll(owner, now.plusDays(1), now.plusDays(2), "a", "b"); // Scheduled: in no Poll count
        em.persist(new UserVote(voter, ended, ended.getOptions().iterator().next(), null));
        em.flush();

        AdminStatsResponse after = repository.stats(now);

        assertThat(after.usersTotal() - before.usersTotal()).isEqualTo(2);
        assertThat(after.usersNewLast7Days() - before.usersNewLast7Days()).isEqualTo(2);
        assertThat(after.usersSuspended() - before.usersSuspended()).isEqualTo(1);
        assertThat(after.pollsInProgress() - before.pollsInProgress()).isEqualTo(1);
        assertThat(after.pollsEnded() - before.pollsEnded()).isEqualTo(1);
        assertThat(after.pollsDeleted() - before.pollsDeleted()).isEqualTo(1);
        assertThat(after.ballotsLast24Hours() - before.ballotsLast24Hours()).isEqualTo(1);
    }

    @Test
    void shouldCountOnlyActiveAdminsWhenGuardingLastAdmin() {
        long before = userRepository.countActiveAdmins();
        User admin = persistUser(unique("adm"));
        admin.setRoles(new String[] { "user", "admin" });
        User suspendedAdmin = persistUser(unique("adm"));
        suspendedAdmin.setRoles(new String[] { "admin" });
        suspendedAdmin.setActive(false);
        em.flush();

        assertThat(userRepository.countActiveAdmins() - before).isEqualTo(1);
    }
}
