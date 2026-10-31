package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.MentionDto;
import com.vomatt.comments.dto.UpdateCommentRequest;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteServiceSlice;

@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
@DisplayName("Comment mentions (Postgres)")
class CommentMentionsPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;

    private User author;
    private User alice;
    private Vote poll;

    @BeforeEach
    void setUp() {
        author = persistUser(handle("au"));
        alice = persistUser(handle("al"));
        OffsetDateTime now = OffsetDateTime.now();
        poll = persistPoll(author, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
    }

    private static String handle(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private CommentDto post(String text, String parentId) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText(text);
        request.setParentId(parentId == null ? null : UUID.fromString(parentId));
        CommentDto dto = commentService.createComment(poll.getId().toString(), author.getId().toString(), request);
        em.flush();
        return dto;
    }

    private List<CommentDto> list() {
        em.flush();
        em.clear();
        return commentService.getCommentsByVote(poll.getId().toString(), null, null, author.getId().toString()).items();
    }

    private List<CommentDto> replies(String rootId) {
        em.flush();
        em.clear();
        return commentService.getReplies(poll.getId().toString(), UUID.fromString(rootId), null, 50,
                author.getId().toString()).items();
    }

    private static MentionDto mention(User user) {
        return new MentionDto(user.getId().toString(), user.getUsername());
    }

    @Test
    @DisplayName("should list only resolved handles, in order of first appearance, through create, list, replies and edit")
    void shouldReturnResolvedMentionsWhenTextHasRealUnknownAndEmailHandles() {
        User bob = persistUser(handle("bo"));
        em.flush();
        String text = "@" + alice.getUsername().toUpperCase() + " vs @ghost_nobody, mail a@b.com or "
                + bob.getUsername() + "@b.com, then @" + bob.getUsername() + " and @" + alice.getUsername();

        CommentDto created = post(text, null);
        CommentDto reply = post(text, created.getId());

        assertThat(created.getMentions()).containsExactly(mention(alice), mention(bob));
        assertThat(reply.getMentions()).containsExactly(mention(alice), mention(bob));
        CommentDto listed = list().getFirst();
        assertThat(listed.getMentions()).containsExactly(mention(alice), mention(bob));
        assertThat(listed.getReplies()).singleElement()
                .satisfies(r -> assertThat(r.getMentions()).containsExactly(mention(alice), mention(bob)));
        assertThat(replies(created.getId())).singleElement()
                .satisfies(r -> assertThat(r.getMentions()).containsExactly(mention(alice), mention(bob)));

        UpdateCommentRequest edit = new UpdateCommentRequest();
        edit.setText("only @" + bob.getUsername() + " now, and @" + author.getUsername() + " added late");
        CommentDto edited = commentService.updateComment(UUID.fromString(created.getId()), author.getId().toString(), edit);
        assertThat(edited.getMentions()).containsExactly(mention(bob));
        assertThat(list().getFirst().getMentions()).containsExactly(mention(bob));
    }

    @Test
    @DisplayName("should resolve only the first MAX_MENTIONS distinct handles")
    void shouldCapMentionsWhenTextHasMoreThanMaxHandles() {
        List<User> users = IntStream.rangeClosed(0, VoteCommentService.MAX_MENTIONS)
                .mapToObj(i -> persistUser(handle("c" + i))).toList();
        em.flush();
        String text = String.join(" ", users.stream().map(u -> "@" + u.getUsername()).toList());
        List<MentionDto> firstTen = users.subList(0, VoteCommentService.MAX_MENTIONS).stream()
                .map(CommentMentionsPostgresTest::mention).toList();

        CommentDto created = post(text, null);

        assertThat(created.getMentions()).containsExactlyElementsOf(firstTen);
        assertThat(list().getFirst().getMentions()).containsExactlyElementsOf(firstTen);
    }

    @Test
    @DisplayName("should keep a suspended user's mention and drop a renamed user's, as their old handle no longer names them")
    void shouldDropMentionWhenUserRenamedAndKeepItWhenSuspended() {
        User bob = persistUser(handle("bo"));
        em.flush();
        post("@" + alice.getUsername() + " @" + bob.getUsername(), null);

        em.find(User.class, alice.getId()).setUsername(handle("re"));
        em.find(User.class, bob.getId()).setActive(false);

        assertThat(list().getFirst().getMentions()).containsExactly(mention(bob));
    }

    @Test
    @DisplayName("should resolve the mentions of a whole page in one query")
    void shouldRunSameQueriesWhenPageWithMentionsGrows() {
        Vote one = poll;
        post("@" + alice.getUsername(), null);
        poll = persistPoll(author, OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1), "X", "Y");
        for (int i = 0; i < 5; i++) {
            User mentioned = persistUser(handle("p" + i));
            em.flush();
            post("@" + mentioned.getUsername() + " @" + alice.getUsername(), null);
        }
        em.flush();
        em.clear();
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        commentService.getCommentsByVote(one.getId().toString(), null, null, author.getId().toString());
        long onePage = stats.getPrepareStatementCount();
        em.clear();
        stats.clear();
        List<CommentDto> five = commentService.getCommentsByVote(poll.getId().toString(), null, null,
                author.getId().toString()).items();
        long fivePage = stats.getPrepareStatementCount();

        assertThat(five).allSatisfy(c -> assertThat(c.getMentions()).hasSize(2));
        assertThat(fivePage).isEqualTo(onePage);
    }
}
