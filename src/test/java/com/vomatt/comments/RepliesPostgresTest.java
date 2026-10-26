package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import com.vomatt.votes.VoteServiceSlice;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.VoteCommentRepository;

@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
@DisplayName("Replies (Postgres)")
class RepliesPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;
    @Autowired VoteCommentRepository commentRepository;

    private User user;
    private Vote poll;

    @BeforeEach
    void setUp() {
        user = persistUser("rp-" + UUID.randomUUID());
        user.setAvatarUrl("bot:boba");
        OffsetDateTime now = OffsetDateTime.now();
        poll = persistPoll(user, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
    }

    private CommentDto post(String text, String parentId) {
        return postAs(user, text, parentId);
    }

    private CommentDto postAs(User author, String text, String parentId) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText(text);
        request.setParentId(parentId == null ? null : UUID.fromString(parentId));
        CommentDto dto = commentService.createComment(poll.getId().toString(), author.getId().toString(), request);
        em.flush();
        return dto;
    }

    private List<CommentDto> topLevelFor(User viewer) {
        em.flush();
        em.clear();
        return commentService.getCommentsByVote(poll.getId().toString(), null, null,
                viewer == null ? null : viewer.getId().toString()).items();
    }

    private List<String> texts(List<CommentDto> comments) {
        return comments.stream().map(CommentDto::getText).toList();
    }

    private List<String> restOfReplies(CommentDto root, User viewer) {
        em.clear();
        return texts(commentService.getReplies(poll.getId().toString(), UUID.fromString(root.getId()),
                root.getRepliesNextCursor(), 50, viewer == null ? null : viewer.getId().toString()).items());
    }

    private List<CommentDto> topLevel() {
        em.clear();
        return commentService.getCommentsByVote(poll.getId().toString(), null, null, user.getId().toString()).items();
    }

    private List<String> replies(String rootId, int limit) {
        em.clear();
        List<String> texts = new ArrayList<>();
        String cursor = null;
        do {
            CursorResponse<CommentDto> page = commentService.getReplies(poll.getId().toString(),
                    UUID.fromString(rootId), cursor, limit, null);
            page.items().forEach(c -> texts.add(c.getText()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return texts;
    }

    @Test
    @DisplayName("should attach to the same top-level comment when replying to a reply")
    void shouldAttachReplyToRootWhenReplyingToReply() {
        CommentDto root = post("root", null);
        CommentDto reply = post("reply", root.getId());

        CommentDto nested = post("reply to reply", reply.getId());

        assertThat(nested.getParentId()).isEqualTo(root.getId());
        assertThat(topLevel()).extracting(CommentDto::getText).containsExactly("root");
        assertThat(topLevel().getFirst().getReplyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should paginate replies from oldest to newest")
    void shouldListRepliesOldestFirst() {
        CommentDto root = post("root", null);
        for (int i = 0; i < 5; i++) {
            post("r" + i, root.getId());
        }

        assertThat(replies(root.getId(), 2)).containsExactly("r0", "r1", "r2", "r3", "r4");
    }

    @Test
    @DisplayName("should keep a placeholder when a comment with replies is deleted, and drop it once all replies are deleted")
    void shouldKeepPlaceholderWhenDeletedCommentHasReplies() {
        CommentDto root = post("root", null);
        CommentDto reply = post("reply", root.getId());
        assertThat(reply.getAuthorAvatarUrl()).isEqualTo("bot:boba");
        assertThat(topLevel().getFirst().getAuthorAvatarUrl()).isEqualTo("bot:boba");

        commentService.deleteComment(UUID.fromString(root.getId()), user.getId().toString());
        em.flush();
        CommentDto placeholder = topLevel().getFirst();
        assertThat(placeholder.isDeleted()).isTrue();
        assertThat(placeholder.getText()).isNull();
        assertThat(placeholder.getAuthor()).isNull();
        assertThat(placeholder.getAuthorAvatarUrl()).isNull();
        assertThat(placeholder.getReplyCount()).isEqualTo(1);

        commentService.deleteComment(UUID.fromString(reply.getId()), user.getId().toString());
        em.flush();
        assertThat(topLevel()).isEmpty();
    }

    @Test
    @DisplayName("should count replies but not deleted comments in commentCount")
    void shouldCountRepliesInCommentCount() {
        CommentDto root = post("root", null);
        post("reply", root.getId());
        CommentDto gone = post("gone", root.getId());
        commentService.deleteComment(UUID.fromString(gone.getId()), user.getId().toString());
        em.flush();

        assertThat(commentRepository.countVisibleByVoteIds(List.of(poll.getId())).getFirst().getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should reject replying to a comment from another Poll")
    void shouldRejectReplyToCommentOfAnotherPoll() {
        Vote other = persistPoll(user, OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1), "X", "Y");
        em.flush();
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText("elsewhere");
        CommentDto foreign = commentService.createComment(other.getId().toString(), user.getId().toString(), request);

        assertThatThrownBy(() -> post("bad", foreign.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_PARENT_INVALID));
    }

    @Test
    @DisplayName("should preview the first two Replies of each Comment and continue after them with repliesNextCursor")
    void shouldPreviewFirstRepliesWhenListingComments() {
        CommentDto busy = post("busy", null);
        for (int i = 0; i < 4; i++) {
            post("b" + i, busy.getId());
        }
        CommentDto single = post("single", null);
        post("s0", single.getId());
        post("quiet", null);

        List<CommentDto> roots = topLevelFor(user);

        assertThat(roots).extracting(CommentDto::getText).containsExactly("quiet", "single", "busy");
        CommentDto quiet = roots.get(0);
        assertThat(quiet.getReplies()).isEmpty();
        assertThat(quiet.getRepliesNextCursor()).isNull();
        CommentDto one = roots.get(1);
        assertThat(texts(one.getReplies())).containsExactly("s0");
        assertThat(one.getRepliesNextCursor()).isNull();
        CommentDto many = roots.get(2);
        assertThat(many.getReplyCount()).isEqualTo(4);
        assertThat(texts(many.getReplies())).containsExactly("b0", "b1");
        assertThat(many.getReplies()).allSatisfy(reply -> {
            assertThat(reply.getParentId()).isEqualTo(busy.getId());
            assertThat(reply.getReplies()).isEmpty();
            assertThat(reply.getRepliesNextCursor()).isNull();
        });
        assertThat(restOfReplies(many, user)).containsExactly("b2", "b3");
    }

    @Test
    @DisplayName("should leave deleted Replies and Replies by users the viewer blocked out of the preview and its cursor")
    void shouldSkipDeletedAndBlockedRepliesWhenPreviewing() {
        User blocked = persistUser("rp-blocked-" + UUID.randomUUID());
        CommentDto root = post("root", null);
        postAs(blocked, "r0 blocked", root.getId());
        CommentDto deleted = post("r1 deleted", root.getId());
        commentService.deleteComment(UUID.fromString(deleted.getId()), user.getId().toString());
        post("r2", root.getId());
        post("r3", root.getId());
        post("r4", root.getId());
        em.persist(new UserBlock(user, blocked));

        CommentDto forViewer = topLevelFor(user).getFirst();
        assertThat(forViewer.getReplyCount()).isEqualTo(3);
        assertThat(texts(forViewer.getReplies())).containsExactly("r2", "r3");
        assertThat(restOfReplies(forViewer, user)).containsExactly("r4");

        CommentDto signedOut = topLevelFor(null).getFirst();
        assertThat(signedOut.getReplyCount()).isEqualTo(4);
        assertThat(texts(signedOut.getReplies())).containsExactly("r0 blocked", "r2");
        assertThat(restOfReplies(signedOut, null)).containsExactly("r3", "r4");
    }

    @Test
    @DisplayName("should preview the Replies of a deleted placeholder, and none on create responses")
    void shouldPreviewRepliesOfPlaceholderWhenRootDeleted() {
        CommentDto root = post("root", null);
        CommentDto reply = post("reply", root.getId());
        commentService.deleteComment(UUID.fromString(root.getId()), user.getId().toString());

        CommentDto placeholder = topLevelFor(user).getFirst();

        assertThat(placeholder.isDeleted()).isTrue();
        assertThat(texts(placeholder.getReplies())).containsExactly("reply");
        assertThat(root.getReplies()).isEmpty();
        assertThat(reply.getReplies()).isEmpty();
        assertThat(reply.getRepliesNextCursor()).isNull();
    }

    @Test
    @DisplayName("should give previewed Replies their like count and the viewer's like")
    void shouldFillLikesWhenReplyPreviewed() {
        User fan = persistUser("rp-fan-" + UUID.randomUUID());
        CommentDto root = post("root", null);
        CommentDto reply = post("reply", root.getId());
        commentService.likeComment(UUID.fromString(reply.getId()), fan.getId().toString());

        CommentDto preview = topLevelFor(fan).getFirst().getReplies().getFirst();

        assertThat(preview.getLikeCount()).isEqualTo(1);
        assertThat(preview.isLikedByCurrentUser()).isTrue();
    }

    @Test
    @DisplayName("should run as many queries for a page of five threads as for a page of one")
    void shouldRunSameQueriesWhenCommentPageGrows() {
        Vote other = persistPoll(user, OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1), "X", "Y");
        CommentDto lone = post("lone", null);
        post("lone reply", lone.getId());
        Vote busy = poll;
        poll = other;
        for (int i = 0; i < 5; i++) {
            CommentDto root = postAs(persistUser("rp-a-" + i + "-" + UUID.randomUUID()), "root " + i, null);
            for (int j = 0; j < 3; j++) {
                postAs(persistUser("rp-r-" + i + j + "-" + UUID.randomUUID()), "reply " + j, root.getId());
            }
        }
        em.flush();
        em.clear();
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        commentService.getCommentsByVote(busy.getId().toString(), null, null, user.getId().toString());
        long onePage = stats.getPrepareStatementCount();
        em.clear();
        stats.clear();
        List<CommentDto> five = commentService.getCommentsByVote(other.getId().toString(), null, null,
                user.getId().toString()).items();
        long fivePage = stats.getPrepareStatementCount();

        assertThat(five).allSatisfy(root -> assertThat(root.getReplies()).hasSize(2));
        assertThat(fivePage).isEqualTo(onePage);
    }
}
