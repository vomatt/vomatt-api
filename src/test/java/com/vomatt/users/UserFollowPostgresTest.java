package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;

import com.vomatt.common.util.UUIDv7Generator;
import com.vomatt.entity.Audience;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserFollow;
import com.vomatt.entity.Vote;
import com.vomatt.repository.FollowEdge;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserFollowRepository;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.VoteServiceSlice;
import com.vomatt.votes.dto.VoteResponse;

@Import(VoteServiceSlice.class)
@DisplayName("User follows (Postgres)")
class UserFollowPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired UserFollowRepository followRepository;
    @Autowired com.vomatt.repository.UserRepository userRepository;

    private User viewer;
    private User followed;
    private User other;

    @BeforeEach
    void setUp() {
        viewer = persistUser("uf-viewer-" + UUID.randomUUID());
        followed = persistUser("uf-followed-" + UUID.randomUUID());
        other = persistUser("uf-other-" + UUID.randomUUID());
        em.persist(new UserFollow(viewer, followed));
        em.flush();
    }

    private List<UUID> following(User as) {
        em.clear();
        return voteService.listFollowingVotes(as.getId().toString(), null, 50).items().stream()
                .map(v -> UUID.fromString(v.getId())).toList();
    }

    @Test
    @DisplayName("should list only Open PUBLIC Polls of followed users, newest first")
    void shouldListFollowedCreatorsOpenPublicPollsWhenReadingFollowing() {
        OffsetDateTime now = OffsetDateTime.now();
        Vote older = persistPoll(followed, now.minusHours(2), now.plusDays(1), "A", "B");
        Vote newer = persistPoll(followed, now.minusHours(1), now.plusDays(1), "A", "B");
        persistPoll(followed, now.minusDays(2), now.minusDays(1), "A", "B");   // Ended
        persistPoll(followed, now.plusHours(1), now.plusDays(1), "A", "B");    // Scheduled
        Vote unlisted = persistPoll(followed, now.minusHours(1), now.plusDays(1), "A", "B");
        unlisted.setAudience(Audience.UNLISTED);
        persistPoll(other, now.minusMinutes(5), now.plusDays(1), "A", "B");
        em.flush();

        assertThat(following(viewer)).containsExactly(newer.getId(), older.getId());
        // One-way: the followed user's Following list does not show the follower's Polls
        persistPoll(viewer, now.minusMinutes(5), now.plusDays(1), "A", "B");
        em.flush();
        assertThat(following(followed)).isEmpty();
    }

    @Test
    @DisplayName("should leave out a followed user's Polls once the follower blocks them")
    void shouldHideFollowedCreatorWhenAlsoBlocked() {
        OffsetDateTime now = OffsetDateTime.now();
        persistPoll(followed, now.minusHours(1), now.plusDays(1), "A", "B");
        em.persist(new UserBlock(viewer, followed));
        em.flush();

        assertThat(following(viewer)).isEmpty();
    }

    @Test
    @DisplayName("should page the Following list with its own cursor")
    void shouldPageWhenFollowingListSpansPages() {
        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expected.addFirst(persistPoll(followed, now.minusHours(3 - i), now.plusDays(1), "A", "B").getId());
        }
        em.flush();
        em.clear();

        var first = voteService.listFollowingVotes(viewer.getId().toString(), null, 2);
        var rest = voteService.listFollowingVotes(viewer.getId().toString(), first.nextCursor(), 2);

        List<UUID> all = new ArrayList<>();
        first.items().forEach(v -> all.add(UUID.fromString(v.getId())));
        rest.items().forEach(v -> all.add(UUID.fromString(v.getId())));
        assertThat(all).containsExactlyElementsOf(expected);
        assertThat(rest.nextCursor()).isNull();
    }

    @Test
    @DisplayName("should page followed users most recently followed first")
    void shouldListNewestFirstWhenPagingFollows() {
        List<UUID> expected = new ArrayList<>(List.of(followed.getId()));
        for (int i = 0; i < 3; i++) {
            User u = persistUser("uf-more-" + UUID.randomUUID());
            em.persist(new UserFollow(viewer, u));
            em.flush();
            expected.addFirst(u.getId());
        }

        List<UserFollow> first = followRepository.findPage(viewer.getId(), null, null, Limit.of(2));
        UserFollow last = first.getLast();
        List<UserFollow> rest = followRepository.findPage(viewer.getId(), last.getCreatedAt(), last.getId(), Limit.of(10));

        List<UUID> all = new ArrayList<>();
        first.forEach(f -> all.add(f.getFollowed().getId()));
        rest.forEach(f -> all.add(f.getFollowed().getId()));
        assertThat(all).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("should leave suspended users out of the follow list but keep them in the export")
    void shouldHideSuspendedUserWhenPagingFollows() {
        followed.setActive(false);
        em.flush();

        assertThat(followRepository.findPage(viewer.getId(), null, null, Limit.of(10))).isEmpty();
        assertThat(followRepository.findAllByFollower(viewer.getId()))
                .extracting(f -> f.getFollowed().getId()).containsExactly(followed.getId());
    }

    @Test
    @DisplayName("should page a user's followers most recently followed first, leaving out suspended followers and other users' follows")
    void shouldListNewestFirstWhenPagingFollowers() {
        List<UUID> expected = new ArrayList<>(List.of(viewer.getId()));
        for (int i = 0; i < 3; i++) {
            User u = persistUser("uf-follower-" + UUID.randomUUID());
            em.persist(new UserFollow(u, followed));
            em.flush();
            expected.addFirst(u.getId());
        }
        User suspended = persistUser("uf-suspended-" + UUID.randomUUID());
        suspended.setActive(false);
        em.persist(new UserFollow(suspended, followed));
        em.persist(new UserFollow(viewer, other));
        em.flush();

        List<UserFollow> first = followRepository.findFollowersPage(followed.getId(), null, null, Limit.of(2));
        UserFollow last = first.getLast();
        List<UserFollow> rest = followRepository.findFollowersPage(followed.getId(), last.getCreatedAt(), last.getId(), Limit.of(10));

        List<UUID> all = new ArrayList<>();
        first.forEach(f -> all.add(f.getFollower().getId()));
        rest.forEach(f -> all.add(f.getFollower().getId()));
        assertThat(all).containsExactlyElementsOf(expected);
        assertThat(followRepository.findFollowersPage(other.getId(), null, null, Limit.of(10)))
                .extracting(f -> f.getFollower().getId()).containsExactly(viewer.getId());
    }

    @Test
    @DisplayName("should read the viewer's follow and block of the user in the profile query")
    void shouldReturnRelationFlagsWhenReadingProfileForViewer() {
        em.persist(new UserBlock(viewer, other));
        em.flush();

        var profile = userRepository.findProfileForViewer(followed.getUsername(), viewer.getId()).orElseThrow();
        assertThat(profile.getFollowing()).isTrue();
        assertThat(profile.getBlocked()).isFalse();
        var blocked = userRepository.findProfileForViewer(other.getUsername(), viewer.getId()).orElseThrow();
        assertThat(blocked.getFollowing()).isFalse();
        assertThat(blocked.getBlocked()).isTrue();
        // One-way, own profile and no viewer: all false
        var reverse = userRepository.findProfileForViewer(viewer.getUsername(), followed.getId()).orElseThrow();
        assertThat(reverse.getFollowing()).isFalse();
        var own = userRepository.findProfileForViewer(viewer.getUsername(), viewer.getId()).orElseThrow();
        assertThat(own.getFollowing()).isFalse();
        assertThat(own.getBlocked()).isFalse();
        var signedOut = userRepository.findProfileByUsername(followed.getUsername()).orElseThrow();
        assertThat(signedOut.getFollowing()).isFalse();
        assertThat(signedOut.getBlocked()).isFalse();
    }

    @Test
    @DisplayName("should count followers and following like the lists: suspended accounts left out, blocks not filtered")
    void shouldMatchListsWhenCountingFollows() {
        User fan = persistUser("uf-fan-" + UUID.randomUUID());
        User suspended = persistUser("uf-suspended-" + UUID.randomUUID());
        suspended.setActive(false);
        em.persist(new UserFollow(fan, followed));
        em.persist(new UserFollow(suspended, followed));
        em.persist(new UserFollow(followed, other));
        em.persist(new UserFollow(followed, suspended));
        // other blocked followed; followed still follows other (a block is one-way)
        em.persist(new UserBlock(other, followed));
        em.flush();

        var profile = userRepository.findProfileByUsername(followed.getUsername()).orElseThrow();
        assertThat(profile.getFollowersCount()).isEqualTo(2L)
                .isEqualTo(followRepository.findFollowersPage(followed.getId(), null, null, Limit.of(50)).size());
        assertThat(profile.getFollowingCount()).isEqualTo(1L)
                .isEqualTo(followRepository.findPage(followed.getId(), null, null, Limit.of(50)).size());
        assertThat(userRepository.findProfileByUsername(other.getUsername()).orElseThrow().getFollowersCount()).isEqualTo(1L);
        var blockedByViewer = userRepository.findProfileForViewer(followed.getUsername(), other.getId()).orElseThrow();
        assertThat(blockedByViewer.getBlocked()).isTrue();
        assertThat(blockedByViewer.getFollowsViewer()).isTrue();
    }

    @Test
    @DisplayName("should read whether the user follows the viewer in the profile query")
    void shouldReturnFollowsViewerWhenReadingProfileForViewer() {
        assertThat(userRepository.findProfileForViewer(viewer.getUsername(), followed.getId()).orElseThrow().getFollowsViewer()).isTrue();
        assertThat(userRepository.findProfileForViewer(followed.getUsername(), viewer.getId()).orElseThrow().getFollowsViewer()).isFalse();
        assertThat(userRepository.findProfileForViewer(viewer.getUsername(), viewer.getId()).orElseThrow().getFollowsViewer()).isFalse();
        assertThat(userRepository.findProfileByUsername(viewer.getUsername()).orElseThrow().getFollowsViewer()).isFalse();
    }

    @Test
    @DisplayName("should page the user's followers whom the viewer follows in follower list order, leaving out suspended and blocked users")
    void shouldListMutualFollowersWhenViewerFollowsSomeFollowers() {
        User target = persistUser("uf-target-" + UUID.randomUUID());
        List<User> mutuals = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mutuals.add(persistUser("uf-mutual-" + UUID.randomUUID()));
        }
        // The viewer followed them newest first; the list follows when they followed the target
        for (User m : mutuals.reversed()) {
            em.persist(new UserFollow(viewer, m));
            em.flush();
        }
        for (User m : mutuals) {
            em.persist(new UserFollow(m, target));
            em.flush();
        }
        User stranger = persistUser("uf-stranger-" + UUID.randomUUID());
        User suspended = persistUser("uf-suspended-" + UUID.randomUUID());
        suspended.setActive(false);
        User blockedByViewer = persistUser("uf-blocked-" + UUID.randomUUID());
        User blocksViewer = persistUser("uf-blocker-" + UUID.randomUUID());
        for (User u : List.of(stranger, suspended, blockedByViewer, blocksViewer)) {
            em.persist(new UserFollow(u, target));
        }
        for (User u : List.of(suspended, blockedByViewer, blocksViewer)) {
            em.persist(new UserFollow(viewer, u));
        }
        em.persist(new UserBlock(viewer, blockedByViewer));
        em.persist(new UserBlock(blocksViewer, viewer));
        // The viewer also follows `followed` (setUp), who does not follow the target
        em.flush();
        em.clear();

        List<UserFollow> first = followRepository.findMutualFollowersPage(target.getId(), viewer.getId(), null, null, Limit.of(2));
        UserFollow last = first.getLast();
        List<UserFollow> rest = followRepository.findMutualFollowersPage(target.getId(), viewer.getId(),
                last.getCreatedAt(), last.getId(), Limit.of(10));

        List<UUID> all = new ArrayList<>();
        first.forEach(f -> all.add(f.getFollower().getId()));
        rest.forEach(f -> all.add(f.getFollower().getId()));
        assertThat(all).containsExactlyElementsOf(mutuals.reversed().stream().map(User::getId).toList());
        assertThat(followRepository.countMutualFollowers(target.getId(), viewer.getId())).isEqualTo(3L);
        // The stranger follows none of the target's followers; swapping the roles finds nobody
        assertThat(followRepository.countMutualFollowers(target.getId(), stranger.getId())).isZero();
        assertThat(followRepository.findMutualFollowersPage(viewer.getId(), target.getId(), null, null, Limit.of(10))).isEmpty();
    }

    @Test
    @DisplayName("should return only the follows between the viewer and the given users, in both directions")
    void shouldReturnBothDirectionsWhenFindingEdgesWithViewer() {
        User stranger = persistUser("uf-stranger-" + UUID.randomUUID());
        em.persist(new UserFollow(other, viewer));
        em.persist(new UserFollow(followed, other));
        em.persist(new UserFollow(viewer, stranger));
        em.flush();

        assertThat(followRepository.findEdgesWithViewer(viewer.getId(), List.of(followed.getId(), other.getId())))
                .containsExactlyInAnyOrder(new FollowEdge(viewer.getId(), followed.getId()), new FollowEdge(other.getId(), viewer.getId()));
    }

    @Test
    @DisplayName("should take the pair advisory lock inside the transaction, re-entrantly")
    void shouldLockPairWhenFollowOrBlockRuns() {
        assertThat(followRepository.lockPair(viewer.getId(), other.getId())).isEqualTo(1);
        assertThat(followRepository.lockPair(viewer.getId(), other.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("should insert a follow once and ignore the duplicate")
    void shouldIgnoreDuplicateWhenInsertingExistingFollow() {
        assertThat(followRepository.insertIfAbsent(UUIDv7Generator.generateId(), viewer.getId(), other.getId())).isEqualTo(1);
        assertThat(followRepository.insertIfAbsent(UUIDv7Generator.generateId(), viewer.getId(), other.getId())).isEqualTo(0);
        assertThat(followRepository.deleteByFollowerIdAndFollowedId(viewer.getId(), other.getId())).isEqualTo(1);
        assertThat(followRepository.deleteByFollowerIdAndFollowedId(viewer.getId(), other.getId())).isEqualTo(0);
    }
}
