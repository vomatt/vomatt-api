package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;

/** Own-profile createdPollCount / votedPollCount must match what My Polls lists. */
@Import(VoteListRepository.class)
@DisplayName("Own-profile Poll counts (Postgres)")
class MyProfileCountsPostgresTest extends PostgresRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired VoteListRepository voteListRepository;

    @Test
    @DisplayName("should match My Polls lists across every Audience and status")
    void shouldMatchMyPollsListsWhenCountingCreatedAndVotedPolls() {
        OffsetDateTime now = OffsetDateTime.now();
        User me = persistUser("mc-" + System.nanoTime());
        User other = persistUser("mo-" + System.nanoTime());
        User blocker = persistUser("mb-" + System.nanoTime());
        em.persist(new UserBlock(blocker, me));

        // Created by me: Open Public (I also vote), Open Unlisted, Ended Invite-only, Scheduled, Cancelled; Deleted left out
        Vote ownPublic = persistPoll(me, now.minusHours(1), now.plusDays(1), "A", "B");
        persistPoll(me, now.minusHours(1), now.plusDays(1), Audience.UNLISTED, "A", "B");
        persistPoll(me, now.minusDays(3), now.minusDays(1), Audience.INVITE_ONLY, "A", "B");
        persistPoll(me, now.plusHours(1), now.plusDays(1), "A", "B");
        persistPoll(me, now.minusHours(2), now.minusHours(3), "A", "B");
        persistPoll(me, now.minusHours(1), now.plusDays(1), "A", "B").setDeletedAt(now);

        // Others' Polls I voted in: Public, Ended Unlisted, Invite-only with an Anonymous Ballot; Deleted and blocker's left out
        Vote otherPublic = persistPoll(other, now.minusHours(1), now.plusDays(1), "A", "B");
        Vote otherUnlisted = persistPoll(other, now.minusDays(3), now.minusDays(1), Audience.UNLISTED, "A", "B");
        Vote otherInviteOnly = persistPoll(other, now.minusHours(1), now.plusDays(1), Audience.INVITE_ONLY, "A", "B");
        em.persist(new Invitee(otherInviteOnly, me));
        Vote otherDeleted = persistPoll(other, now.minusHours(1), now.plusDays(1), "A", "B");
        otherDeleted.setDeletedAt(now);
        Vote blockerPoll = persistPoll(blocker, now.minusHours(1), now.plusDays(1), "A", "B");
        // Invited only, no Ballot: listed in My Polls but counted in neither
        em.persist(new Invitee(persistPoll(other, now.minusHours(1), now.plusDays(1), Audience.INVITE_ONLY, "A", "B"), me));

        List<Vote> voted = List.of(ownPublic, otherPublic, otherUnlisted, otherInviteOnly, otherDeleted, blockerPoll);
        for (Vote poll : voted) {
            UserVote ballot = new UserVote(me, poll, poll.getOptions().iterator().next());
            ballot.setAnonymous(poll == otherInviteOnly);
            em.persist(ballot);
        }
        em.flush();
        em.clear();

        List<Vote> myPolls = new ArrayList<>(voteListRepository.findMine(me.getId(), false, null, now, 100));
        myPolls.addAll(voteListRepository.findMine(me.getId(), true, null, now, 100));
        Set<UUID> votedIds = voted.stream().map(Vote::getId).collect(Collectors.toSet());
        long createdInList = myPolls.stream().filter(v -> v.getCreator().getId().equals(me.getId())).count();
        long votedInList = myPolls.stream().filter(v -> votedIds.contains(v.getId())).count();

        assertThat(userRepository.countCreatedPolls(me.getId())).isEqualTo(createdInList).isEqualTo(5L);
        assertThat(userRepository.countVotedPolls(me.getId())).isEqualTo(votedInList).isEqualTo(4L);
        // Public profile counts stay as they were: opened Public Polls and Ballots on Public Polls only
        UserProfileProjection profile = userRepository.findProfileByUsername(me.getUsername()).orElseThrow();
        assertThat(Stream.of(profile.getTotalPolls(), profile.getTotalVotes())).containsExactly(1L, 3L);
    }
}
