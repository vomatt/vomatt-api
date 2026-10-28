package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.data.domain.PageRequest;

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.util.UUIDv7Generator;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserFollow;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserBlockRepository;
import com.vomatt.repository.VoteListRepository;
import com.vomatt.votes.VoteListOrder;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.VoteServiceSlice;
import com.vomatt.votes.dto.VoteResponse;

@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
@DisplayName("User blocks (Postgres)")
class UserBlockPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;
    @Autowired VoteListRepository voteListRepository;
    @Autowired UserBlockRepository blockRepository;
    @Autowired VoteService voteService;

    private User viewer;
    private User blocked;
    private User other;
    private Vote poll;

    @BeforeEach
    void setUp() {
        viewer = persistUser("ub-viewer-" + UUID.randomUUID());
        blocked = persistUser("ub-blocked-" + UUID.randomUUID());
        other = persistUser("ub-other-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        poll = persistPoll(other, now.minusHours(1), now.plusDays(1), "A", "B");
        em.persist(new UserBlock(viewer, blocked));
        em.flush();
    }

    private CommentDto post(User author, String text, CommentDto parent) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText(text);
        request.setParentId(parent == null ? null : UUID.fromString(parent.getId()));
        CommentDto dto = commentService.createComment(poll.getId().toString(), author.getId().toString(), request);
        em.flush();
        return dto;
    }

    private List<CommentDto> topLevel(User as) {
        em.clear();
        return commentService.getCommentsByVote(poll.getId().toString(), null, 50,
                as == null ? null : as.getId().toString()).items();
    }

    private List<String> replies(CommentDto root, User as) {
        em.clear();
        return commentService.getReplies(poll.getId().toString(), UUID.fromString(root.getId()), null, 50,
                as == null ? null : as.getId().toString()).items().stream().map(CommentDto::getText).toList();
    }

    private List<UUID> feed(User as) {
        return voteListRepository.findPage(VoteListOrder.NEWEST, null, null, null, OffsetDateTime.now(), 1000,
                as == null ? null : as.getId()).stream().map(Vote::getId).toList();
    }

    @Test
    @DisplayName("should hide Polls of blocked users from the blocker's feed only")
    void shouldHideBlockedCreatorsPollsWhenListingFeed() {
        OffsetDateTime now = OffsetDateTime.now();
        Vote blockedPoll = persistPoll(blocked, now.minusMinutes(5), now.plusDays(1), "A", "B");
        em.flush();

        assertThat(feed(viewer)).contains(poll.getId()).doesNotContain(blockedPoll.getId());
        assertThat(feed(other)).contains(poll.getId(), blockedPoll.getId());
        assertThat(feed(null)).contains(poll.getId(), blockedPoll.getId());
        // The other way too: the blocked user no longer sees the blocker's Polls
        Vote viewerPoll = persistPoll(viewer, now.minusMinutes(5), now.plusDays(1), "A", "B");
        em.flush();
        assertThat(feed(blocked)).contains(poll.getId()).doesNotContain(viewerPoll.getId());
        assertThat(feed(other)).contains(viewerPoll.getId());
    }

    private static void assertPollNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    @Test
    @DisplayName("should 404 every Poll of the blocker for the blocked user, Invite-only ones they are invited to included, until unblocked")
    void shouldReturnNotFoundWhenOwnerBlockedViewer() {
        OffsetDateTime now = OffsetDateTime.now();
        Vote viewerPoll = persistPoll(viewer, now.minusMinutes(5), now.plusDays(1), "A", "B");
        Vote invitePoll = persistPoll(viewer, now.minusMinutes(5), now.plusDays(1), "A", "B");
        invitePoll.setAudience(Audience.INVITE_ONLY);
        em.persist(new Invitee(invitePoll, blocked));
        em.flush();
        String as = blocked.getId().toString();

        for (Vote v : new Vote[] { viewerPoll, invitePoll }) {
            String id = v.getId().toString();
            assertPollNotFound(() -> voteService.getVote(id, as));
            assertPollNotFound(() -> voteService.hasUserVoted(id, as));
            assertPollNotFound(() -> commentService.getCommentsByVote(id, null, 20, as));
        }
        // The owner and everyone else are unaffected
        assertThat(voteService.getVote(viewerPoll.getId().toString(), viewer.getId().toString()).getId())
                .isEqualTo(viewerPoll.getId().toString());
        assertThat(voteService.getVote(viewerPoll.getId().toString(), other.getId().toString()).getId())
                .isEqualTo(viewerPoll.getId().toString());
        assertThat(voteService.getVote(viewerPoll.getId().toString(), null).getId()).isEqualTo(viewerPoll.getId().toString());

        blockRepository.deleteByBlockerIdAndBlockedId(viewer.getId(), blocked.getId());
        em.clear();
        assertThat(voteService.getVote(invitePoll.getId().toString(), as).getId()).isEqualTo(invitePoll.getId().toString());
    }

    @Test
    @DisplayName("should leave the blocker's Polls out of the blocked user's Following, My Polls and the blocker's profile list")
    void shouldHideBlockersPollsWhenBlockedUserListsThem() {
        OffsetDateTime now = OffsetDateTime.now();
        Vote viewerPoll = persistPoll(viewer, now.minusMinutes(5), now.plusDays(1), "A", "B");
        Vote otherPoll = persistPoll(other, now.minusMinutes(5), now.plusDays(1), "A", "B");
        em.persist(new UserFollow(blocked, viewer));
        em.persist(new UserFollow(blocked, other));
        em.persist(new UserVote(blocked, viewerPoll, viewerPoll.getOptions().iterator().next()));
        em.persist(new UserVote(blocked, otherPoll, otherPoll.getOptions().iterator().next()));
        em.flush();
        String as = blocked.getId().toString();

        assertThat(ids(voteService.listFollowingVotes(as, null, 50).items()))
                .contains(otherPoll.getId().toString()).doesNotContain(viewerPoll.getId().toString());
        assertThat(ids(voteService.getMyPolls(as, null, null, 50).items()))
                .contains(otherPoll.getId().toString()).doesNotContain(viewerPoll.getId().toString());
        assertThat(voteService.getUserPolls(viewer.getUsername(), null, 50, as).items()).isEmpty();
        assertThat(ids(voteService.getUserPolls(viewer.getUsername(), null, 50, other.getId().toString()).items()))
                .contains(viewerPoll.getId().toString());

        blockRepository.deleteByBlockerIdAndBlockedId(viewer.getId(), blocked.getId());
        em.clear();
        assertThat(ids(voteService.listFollowingVotes(as, null, 50).items())).contains(viewerPoll.getId().toString());
        assertThat(ids(voteService.getMyPolls(as, null, null, 50).items())).contains(viewerPoll.getId().toString());
    }

    private static List<String> ids(List<VoteResponse> polls) {
        return polls.stream().map(VoteResponse::getId).toList();
    }

    @Test
    @DisplayName("should hide threads and replies of blocked users from the blocker, not from others")
    void shouldHideBlockedUsersCommentsWhenBlockerReads() {
        CommentDto blockedRoot = post(blocked, "blocked root", null);
        post(other, "reply under blocked root", blockedRoot);
        CommentDto root = post(other, "root", null);
        post(other, "visible reply", root);
        post(blocked, "blocked reply", root);

        assertThat(topLevel(viewer)).extracting(CommentDto::getText).containsExactly("root");
        assertThat(topLevel(viewer).getFirst().getReplyCount()).isEqualTo(1);
        assertThat(replies(root, viewer)).containsExactly("visible reply");

        for (User as : new User[] { other, null }) {
            assertThat(topLevel(as)).extracting(CommentDto::getText).containsExactly("root", "blocked root");
            assertThat(topLevel(as).getFirst().getReplyCount()).isEqualTo(2);
            assertThat(replies(root, as)).containsExactly("visible reply", "blocked reply");
        }
    }

    @Test
    @DisplayName("should drop a deleted placeholder whose only replies are by blocked users")
    void shouldDropPlaceholderWhenOnlyBlockedRepliesRemain() {
        CommentDto root = post(other, "root", null);
        post(blocked, "blocked reply", root);
        commentService.deleteComment(UUID.fromString(root.getId()), other.getId().toString());
        em.flush();

        assertThat(topLevel(viewer)).isEmpty();
        assertThat(topLevel(other)).singleElement().satisfies(c -> assertThat(c.isDeleted()).isTrue());
    }

    @Test
    @DisplayName("should page blocked users most recently blocked first")
    void shouldListNewestFirstWhenPagingBlocks() {
        List<UUID> expected = new ArrayList<>(List.of(blocked.getId()));
        for (int i = 0; i < 3; i++) {
            User u = persistUser("ub-more-" + UUID.randomUUID());
            em.persist(new UserBlock(viewer, u));
            em.flush();
            expected.addFirst(u.getId());
        }

        List<UserBlock> first = blockRepository.findPage(viewer.getId(), null, null, Limit.of(2));
        UserBlock last = first.getLast();
        List<UserBlock> rest = blockRepository.findPage(viewer.getId(), last.getCreatedAt(), last.getId(), Limit.of(10));

        List<UUID> all = new ArrayList<>();
        first.forEach(b -> all.add(b.getBlocked().getId()));
        rest.forEach(b -> all.add(b.getBlocked().getId()));
        assertThat(all).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("should count and list blocks in both directions for the back office")
    void shouldListBothDirectionsWhenAdminReadsBlocks() {
        em.persist(new UserBlock(other, blocked));
        em.flush();

        assertThat(blockRepository.countByBlockerId(viewer.getId())).isEqualTo(1);
        assertThat(blockRepository.countByBlockedId(blocked.getId())).isEqualTo(2);
        assertThat(blockRepository.findBlocking(viewer.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(b -> b.getBlocked().getId()).containsExactly(blocked.getId());
        var blockedBy = blockRepository.findBlockedBy(blocked.getId(), PageRequest.of(0, 1));
        assertThat(blockedBy.getTotalElements()).isEqualTo(2);
        assertThat(blockedBy.getContent()).extracting(b -> b.getBlocker().getId()).containsExactly(other.getId());
    }

    @Test
    @DisplayName("should 404 the replies of a thread started by a blocked user, for the blocker only")
    void shouldRejectRepliesWhenRootAuthorIsBlocked() {
        CommentDto blockedRoot = post(blocked, "blocked root", null);
        post(other, "reply", blockedRoot);

        assertThatThrownBy(() -> replies(blockedRoot, viewer))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMENT_NOT_FOUND));
        assertThat(replies(blockedRoot, other)).containsExactly("reply");
    }

    @Test
    @DisplayName("should insert a block once and ignore the duplicate")
    void shouldIgnoreDuplicateWhenInsertingExistingBlock() {
        assertThat(blockRepository.insertIfAbsent(UUIDv7Generator.generateId(), viewer.getId(), other.getId())).isEqualTo(1);
        assertThat(blockRepository.insertIfAbsent(UUIDv7Generator.generateId(), viewer.getId(), other.getId())).isEqualTo(0);
        assertThat(blockRepository.deleteByBlockerIdAndBlockedId(viewer.getId(), other.getId())).isEqualTo(1);
        assertThat(blockRepository.deleteByBlockerIdAndBlockedId(viewer.getId(), other.getId())).isEqualTo(0);
    }
}
