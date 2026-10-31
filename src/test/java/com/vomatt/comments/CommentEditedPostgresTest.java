package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import com.vomatt.votes.VoteServiceSlice;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.UpdateCommentRequest;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.VoteCommentRepository;

@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
@DisplayName("Comment edited flag (Postgres)")
class CommentEditedPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;
    @Autowired VoteCommentRepository commentRepository;
    @Autowired CommentMapper commentMapper;

    private User user;
    private Vote poll;

    @BeforeEach
    void setUp() {
        user = persistUser("ce-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        poll = persistPoll(user, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
    }

    private UUID post(String text) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText(text);
        CommentDto dto = commentService.createComment(poll.getId().toString(), user.getId().toString(), request);
        em.flush();
        em.clear();
        return UUID.fromString(dto.getId());
    }

    // Timestamps and the edited flag only: likes are passed as none, not read
    private CommentDto reloadIgnoringLikes(UUID id) {
        em.flush();
        em.clear();
        return commentMapper.toDto(commentRepository.findById(id).orElseThrow(), 0, false, java.util.Map.of());
    }

    @Test
    @DisplayName("should have identical created and updated times when a new comment is written")
    void shouldStoreEqualTimestampsOnInsert() {
        CommentDto dto = reloadIgnoringLikes(post("hi"));

        assertThat(dto.getUpdatedAt()).isEqualTo(dto.getCreatedAt());
        assertThat(dto.isEdited()).isFalse();
    }

    @Test
    @DisplayName("should mark as edited when edited immediately after creation")
    void shouldMarkEditedWhenUpdatedImmediately() {
        UUID id = post("hi");
        UpdateCommentRequest request = new UpdateCommentRequest();
        request.setText("hi, fixed");
        commentService.updateComment(id, user.getId().toString(), request);

        assertThat(reloadIgnoringLikes(id).isEdited()).isTrue();
    }

    @Test
    @DisplayName("should keep likedByCurrentUser true in the edit response when the author liked their own comment")
    void shouldKeepLikedStateWhenAuthorEditsLikedComment() {
        UUID id = post("hi");
        commentService.likeComment(id, user.getId().toString());
        UpdateCommentRequest request = new UpdateCommentRequest();
        request.setText("hi, fixed");

        CommentDto edited = commentService.updateComment(id, user.getId().toString(), request);

        assertThat(edited.isLikedByCurrentUser()).isTrue();
        assertThat(edited.getLikeCount()).isEqualTo(1);
    }

    private UUID[] storedMentions(UUID id) {
        em.flush();
        em.clear();
        return commentRepository.findById(id).orElseThrow().getMentionedUserIds();
    }

    @Test
    @DisplayName("should only drop mentions when editing: no new handle, nor a renamed user's old handle, is mentioned")
    void shouldOnlyRemoveMentionsWhenEditing() {
        String suffix = Long.toString(System.nanoTime());
        User alice = persistUser("alice_" + suffix);
        User bob = persistUser("bob_" + suffix);
        User carol = persistUser("carol_" + suffix);
        UUID id = post("@" + alice.getUsername() + " @" + bob.getUsername());
        User renamed = em.find(User.class, bob.getId());
        renamed.setUsername("bob2_" + suffix);
        em.flush();
        User taker = User.builder().username("bob_" + suffix).email("taker_" + suffix + "@test.local").build();
        em.persist(taker);

        UpdateCommentRequest request = new UpdateCommentRequest();
        request.setText("@" + alice.getUsername() + " @" + taker.getUsername() + " @" + carol.getUsername());
        commentService.updateComment(id, user.getId().toString(), request);

        assertThat(storedMentions(id)).containsExactly(alice.getId());
    }

    @Test
    @DisplayName("should store at most comment_max_mentions (default 10) users, the first handles in the text")
    void shouldCapMentionsWhenTextMentionsTooMany() {
        String suffix = Long.toString(System.nanoTime());
        List<User> users = IntStream.range(0, VoteCommentService.MAX_MENTIONS + 1)
            .mapToObj(i -> persistUser("m" + i + "_" + suffix)).toList();

        UUID id = post(users.stream().map(u -> "@" + u.getUsername()).collect(Collectors.joining(" ")));

        assertThat(storedMentions(id)).containsExactlyInAnyOrderElementsOf(
            users.subList(0, VoteCommentService.MAX_MENTIONS).stream().map(User::getId).toList());
    }
}
