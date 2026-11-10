package com.vomatt.users;

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.ActingMember;
import com.vomatt.entity.OfficialRole;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.OfficialMemberRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.users.dto.UserDto;
import com.vomatt.votes.VoteServiceSlice;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({ OfficialService.class, UserMapper.class, VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
class OfficialPostgresTest extends PostgresRepositoryTest {

    @Autowired OfficialService officialService;
    @Autowired OfficialMemberRepository memberRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.vomatt.votes.VoteService voteService;
    @Autowired VoteCommentService commentService;

    private User owner;
    private UUID officialId;

    @BeforeEach
    void setUp() {
        owner = persistUser("of-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        UserDto official = officialService.createOfficial(
                "official_" + UUID.randomUUID().toString().substring(0, 8), "Sunrise Tower", owner.getId());
        officialId = UUID.fromString(official.id());
        em.flush();
        em.clear();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(UUID memberId, OfficialRole role) {
        var auth = new UsernamePasswordAuthenticationToken(officialId.toString(), null, List.of());
        auth.setDetails(new ActingMember(memberId, officialId, role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void shouldCreateOfficialWithoutEmailOrPhoneWhenAdminCreates() {
        User official = userRepository.findById(officialId).orElseThrow();

        assertThat(official.isOfficial()).isTrue();
        assertThat(official.getEmail()).isNull();
        assertThat(official.getPhoneNumber()).isNull();
        assertThat(memberRepository.findActiveRole(officialId, owner.getId())).contains(OfficialRole.OWNER);
        assertThat(officialService.listMine(owner.getId().toString()))
                .singleElement().satisfies(m -> assertThat(m.role()).isEqualTo(OfficialRole.OWNER));
        // The public profile badge comes from the profile projection
        assertThat(userRepository.findProfileByUsername(official.getUsername()).orElseThrow().getRoles()).contains("official");
    }

    @Test
    void shouldRefuseUsernameTakenWhenAnotherAccountHasIt() {
        String taken = userRepository.findById(officialId).orElseThrow().getUsername();

        assertThatThrownBy(() -> officialService.createOfficial(taken, "Copycat", owner.getId()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_USERNAME_TAKEN));
    }

    @Test
    void shouldRefuseOwnerWhenOwnerIsOfficialAccount() {
        assertThatThrownBy(() -> officialService.createOfficial("official_child", "Child", officialId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.OFFICIAL_MEMBER_INVALID));
        assertThat(userRepository.existsByUsername("official_child")).isFalse();
    }

    @Test
    void shouldHideMembershipWhenOfficialIsSuspended() {
        em.createNativeQuery("UPDATE vomatt.users SET active = false WHERE id = :id").setParameter("id", officialId).executeUpdate();

        assertThat(memberRepository.findActiveRole(officialId, owner.getId())).isEmpty();
        assertThat(officialService.listMine(owner.getId().toString())).isEmpty();
    }

    @Test
    void shouldPersistActedByWhenMemberPublishesAsOfficial() {
        actAs(owner.getId(), OfficialRole.OWNER);
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("Elevator repair");
        request.setOptions(List.of(option("Yes"), option("No")));
        request.setEndTime(OffsetDateTime.now().plusDays(1));

        VoteResponse poll = voteService.createVote(request, officialId.toString());
        CreateCommentRequest comment = new CreateCommentRequest();
        comment.setText("Vote by Friday");
        String commentId = commentService.createComment(poll.getId(), officialId.toString(), comment).getId();
        em.flush();
        em.clear();

        assertThat(poll.isCreatorOfficial()).isTrue();
        assertThat(em.find(Vote.class, UUID.fromString(poll.getId())).getActedBy()).isEqualTo(owner.getId());
        assertThat(em.find(VoteComment.class, UUID.fromString(commentId)).getActedBy()).isEqualTo(owner.getId());
    }

    @Test
    void shouldLeaveActedByNullWhenUserPublishesAsThemself() {
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("Lunch");
        request.setOptions(List.of(option("Rice"), option("Noodles")));
        request.setEndTime(OffsetDateTime.now().plusDays(1));

        VoteResponse poll = voteService.createVote(request, owner.getId().toString());
        em.flush();
        em.clear();

        assertThat(poll.isCreatorOfficial()).isFalse();
        assertThat(em.find(Vote.class, UUID.fromString(poll.getId())).getActedBy()).isNull();
    }

    @Test
    void shouldRefuseRemovingLastOwnerWhenSoleOwner() {
        String ownerId = owner.getId().toString();

        assertThatThrownBy(() -> officialService.changeRole(officialId, owner.getId(), OfficialRole.ADMIN, ownerId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.OFFICIAL_LAST_OWNER));
        assertThatThrownBy(() -> officialService.removeMember(officialId, owner.getId(), ownerId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.OFFICIAL_LAST_OWNER));
        assertThat(memberRepository.isSoleOwnerOfAny(owner.getId())).isTrue();
    }

    @Test
    void shouldStepDownWhenAnotherOwnerRemains() {
        User second = persistUser("of2-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        String ownerId = owner.getId().toString();
        officialService.addMember(officialId, second.getId(), OfficialRole.OWNER, ownerId);
        em.flush();

        officialService.changeRole(officialId, owner.getId(), OfficialRole.ADMIN, ownerId);
        em.flush();
        // A new request: members are read fresh with their users
        em.clear();

        assertThat(memberRepository.findActiveRole(officialId, owner.getId())).contains(OfficialRole.ADMIN);
        assertThat(memberRepository.isSoleOwnerOfAny(second.getId())).isTrue();
        assertThat(officialService.listMembers(officialId, ownerId)).hasSize(2);
    }

    @Test
    void shouldTransferOwnershipWhenOwnerTransfers() {
        User editor = persistUser("of3-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        String ownerId = owner.getId().toString();
        officialService.addMember(officialId, editor.getId(), OfficialRole.EDITOR, ownerId);
        em.flush();

        officialService.transferOwner(officialId, editor.getId(), ownerId);
        em.flush();

        assertThat(memberRepository.findActiveRole(officialId, editor.getId())).contains(OfficialRole.OWNER);
        assertThat(memberRepository.findActiveRole(officialId, owner.getId())).contains(OfficialRole.ADMIN);
    }

    private static CreateVoteRequest.VoteOptionRequest option(String text) {
        CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
        option.setText(text);
        return option;
    }
}
