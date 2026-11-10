package com.vomatt.users;

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.ActingMember;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.CommunityMemberRepository;
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

@Import({ CommunityService.class, UserMapper.class, VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
class CommunityPostgresTest extends PostgresRepositoryTest {

    @Autowired CommunityService communityService;
    @Autowired CommunityMemberRepository memberRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.vomatt.votes.VoteService voteService;
    @Autowired VoteCommentService commentService;

    private User owner;
    private UUID communityId;

    @BeforeEach
    void setUp() {
        owner = persistUser("of-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        UserDto community = communityService.createCommunity(
                "community_" + UUID.randomUUID().toString().substring(0, 8), "Sunrise Tower", owner.getId());
        communityId = UUID.fromString(community.id());
        em.flush();
        em.clear();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(UUID memberId, CommunityRole role) {
        var auth = new UsernamePasswordAuthenticationToken(communityId.toString(), null, List.of());
        auth.setDetails(new ActingMember(memberId, communityId, role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void shouldCreateCommunityWithoutEmailOrPhoneWhenAdminCreates() {
        User community = userRepository.findById(communityId).orElseThrow();

        assertThat(community.isCommunity()).isTrue();
        assertThat(community.getEmail()).isNull();
        assertThat(community.getPhoneNumber()).isNull();
        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).contains(CommunityRole.OWNER);
        assertThat(communityService.listMine(owner.getId().toString()))
                .singleElement().satisfies(m -> assertThat(m.role()).isEqualTo(CommunityRole.OWNER));
        // The public profile badge comes from the profile projection
        assertThat(userRepository.findProfileByUsername(community.getUsername()).orElseThrow().getRoles()).contains("community");
    }

    @Test
    void shouldRefuseUsernameTakenWhenAnotherAccountHasIt() {
        String taken = userRepository.findById(communityId).orElseThrow().getUsername();

        assertThatThrownBy(() -> communityService.createCommunity(taken, "Copycat", owner.getId()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_USERNAME_TAKEN));
    }

    @Test
    void shouldRefuseOwnerWhenOwnerIsCommunityAccount() {
        assertThatThrownBy(() -> communityService.createCommunity("community_child", "Child", communityId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_MEMBER_INVALID));
        assertThat(userRepository.existsByUsername("community_child")).isFalse();
    }

    @Test
    void shouldHideMembershipWhenCommunityIsSuspended() {
        em.createNativeQuery("UPDATE vomatt.users SET active = false WHERE id = :id").setParameter("id", communityId).executeUpdate();

        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).isEmpty();
        assertThat(communityService.listMine(owner.getId().toString())).isEmpty();
    }

    @Test
    void shouldPersistActedByWhenMemberPublishesAsCommunity() {
        actAs(owner.getId(), CommunityRole.OWNER);
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("Elevator repair");
        request.setOptions(List.of(option("Yes"), option("No")));
        request.setEndTime(OffsetDateTime.now().plusDays(1));

        VoteResponse poll = voteService.createVote(request, communityId.toString());
        CreateCommentRequest comment = new CreateCommentRequest();
        comment.setText("Vote by Friday");
        String commentId = commentService.createComment(poll.getId(), communityId.toString(), comment).getId();
        em.flush();
        em.clear();

        assertThat(poll.isCreatorCommunity()).isTrue();
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

        assertThat(poll.isCreatorCommunity()).isFalse();
        assertThat(em.find(Vote.class, UUID.fromString(poll.getId())).getActedBy()).isNull();
    }

    @Test
    void shouldRefuseRemovingLastOwnerWhenSoleOwner() {
        String ownerId = owner.getId().toString();

        assertThatThrownBy(() -> communityService.changeRole(communityId, owner.getId(), CommunityRole.ADMIN, ownerId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_LAST_OWNER));
        assertThatThrownBy(() -> communityService.removeMember(communityId, owner.getId(), ownerId))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_LAST_OWNER));
        assertThat(memberRepository.isSoleOwnerOfAny(owner.getId())).isTrue();
    }

    @Test
    void shouldStepDownWhenAnotherOwnerRemains() {
        User second = persistUser("of2-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        String ownerId = owner.getId().toString();
        communityService.addMember(communityId, second.getId(), CommunityRole.OWNER, ownerId);
        em.flush();

        communityService.changeRole(communityId, owner.getId(), CommunityRole.ADMIN, ownerId);
        em.flush();
        // A new request: members are read fresh with their users
        em.clear();

        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).contains(CommunityRole.ADMIN);
        assertThat(memberRepository.isSoleOwnerOfAny(second.getId())).isTrue();
        assertThat(communityService.listMembers(communityId, ownerId)).hasSize(2);
    }

    @Test
    void shouldTransferOwnershipWhenOwnerTransfers() {
        User editor = persistUser("of3-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        String ownerId = owner.getId().toString();
        communityService.addMember(communityId, editor.getId(), CommunityRole.EDITOR, ownerId);
        em.flush();

        communityService.transferOwner(communityId, editor.getId(), ownerId);
        em.flush();

        assertThat(memberRepository.findActiveRole(communityId, editor.getId())).contains(CommunityRole.OWNER);
        assertThat(memberRepository.findActiveRole(communityId, owner.getId())).contains(CommunityRole.ADMIN);
    }

    private static CreateVoteRequest.VoteOptionRequest option(String text) {
        CreateVoteRequest.VoteOptionRequest option = new CreateVoteRequest.VoteOptionRequest();
        option.setText(text);
        return option;
    }
}
