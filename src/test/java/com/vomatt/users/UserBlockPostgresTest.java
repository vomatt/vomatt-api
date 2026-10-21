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

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserBlockRepository;
import com.vomatt.repository.VoteListRepository;
import com.vomatt.votes.VoteListOrder;
import com.vomatt.votes.VoteServiceSlice;

@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
@DisplayName("User blocks (Postgres)")
class UserBlockPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;
    @Autowired VoteListRepository voteListRepository;
    @Autowired UserBlockRepository blockRepository;

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
        // One-way: the blocked user still sees the blocker's Polls
        Vote viewerPoll = persistPoll(viewer, now.minusMinutes(5), now.plusDays(1), "A", "B");
        em.flush();
        assertThat(feed(blocked)).contains(viewerPoll.getId());
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
    void shouldPageBlocksNewestFirst() {
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
}
