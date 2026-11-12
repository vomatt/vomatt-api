package com.vomatt.users;

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.common.audit.AuditAspect;
import com.vomatt.common.audit.AuditLog;
import com.vomatt.common.audit.AuditLogRepository;
import com.vomatt.common.audit.AuditService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.common.storage.StorageService;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.User;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteServiceSlice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** DELETE /api/communities/{id} with real commits: its audit entry and a member added while it runs. */
@Import({ CommunityService.class, UserService.class, UserMapper.class, VoteCommentService.class, CommentMapper.class,
        VoteServiceSlice.class, StorageService.class, PlanService.class, AuditService.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommunityDeletePostgresTest extends PostgresRepositoryTest {

    @Autowired CommunityService communityService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired TransactionTemplate tx;
    @MockitoBean RefreshTokenService refreshTokenService;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private UUID communityId;
    private String communityUsername;
    private User owner;
    private User admin;
    private User newcomer;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> {
            owner = persistUser("del-own-" + UUID.randomUUID().toString().substring(0, 8));
            admin = persistUser("del-adm-" + UUID.randomUUID().toString().substring(0, 8));
            newcomer = persistUser("del-new-" + UUID.randomUUID().toString().substring(0, 8));
            em.flush();
            communityUsername = "community_" + UUID.randomUUID().toString().substring(0, 8);
            communityId = UUID.fromString(communityService.createCommunity(communityUsername, "Sunrise Tower", owner.getId()).id());
        });
        // Joins later than the owner, as a real invitation does, so the members are listed in that order
        tx.executeWithoutResult(s -> join(communityId, admin, CommunityRole.ADMIN, owner.getId().toString()));
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)")
                    .setParameter("ids", List.of(communityId, owner.getId(), admin.getId(), newcomer.getId())).executeUpdate();
            em.createNativeQuery("DELETE FROM vomatt.audit_logs WHERE resource_id = :id")
                    .setParameter("id", communityId.toString()).executeUpdate();
        });
    }

    @Test
    void shouldKeepDeleteEntryAndEarlierEntriesWhenAfterReturnWriterDropsIt() {
        AuditLog rename = new AuditLog(admin.getId().toString(), admin.getUsername(), "UPDATE", "COMMUNITY", communityId.toString());
        auditLogRepository.save(rename);
        AuditService droppingWriter = mock(AuditService.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(owner.getId().toString(), List.of("user"), null), null, List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("DELETE", "/api/communities/" + communityId)));
        AspectJProxyFactory factory = new AspectJProxyFactory(new CommunityController(communityService));
        factory.addAspect(new AuditAspect(droppingWriter));
        CommunityController controller = factory.getProxy();

        controller.deleteCommunity(communityId, new UserPrincipal(owner.getId().toString(), List.of("user"), null));

        List<AuditLog> entries = auditLogRepository.findAll().stream()
                .filter(e -> communityId.toString().equals(e.getResourceId())).toList();
        assertThat(entries).extracting(AuditLog::getAction).containsExactlyInAnyOrder("UPDATE", "DELETE");
        assertThat(entries).filteredOn(e -> e.getAction().equals("UPDATE")).singleElement()
                .satisfies(e -> assertThat(e.getUserId()).isEqualTo(admin.getId().toString()));
        assertThat(entries).filteredOn(e -> e.getAction().equals("DELETE")).singleElement().satisfies(e -> {
            assertThat(e.getUserId()).isEqualTo(owner.getId().toString());
            assertThat(e.getUsername()).isEqualTo(owner.getUsername());
            assertThat(e.getResourceType()).isEqualTo("COMMUNITY");
            assertThat(e.getHttpMethod()).isEqualTo("DELETE");
            // Members are listed by join time; the two here can join in the same instant, so either order is right
            assertThat(e.getDetails()).startsWith("username=" + communityUsername + "; displayName=\"Sunrise Tower\"; members=")
                    .contains(owner.getUsername() + ":OWNER", admin.getUsername() + ":ADMIN");
        });
        verify(droppingWriter, never()).logAction(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldRefuseAcceptingWhenCommunityIsDeletedMeanwhile() throws Exception {
        UUID invitationId = tx.execute(s -> UUID.fromString(communityService.invite(communityId, newcomer.getId(), null,
                CommunityRole.EDITOR, admin.getId().toString()).id()));
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> delete = pool.submit(() -> tx.executeWithoutResult(s -> {
            communityService.deleteCommunity(communityId, owner.getId().toString());
            deleted.countDown();
            await(release);
        }));
        assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> add = pool.submit(() -> tx.executeWithoutResult(s ->
                communityService.acceptInvitation(invitationId, newcomer.getId().toString())));
        assertThatThrownBy(() -> add.get(500, TimeUnit.MILLISECONDS)).as("waits for the delete").isInstanceOf(TimeoutException.class);
        release.countDown();
        delete.get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> add.get(5, TimeUnit.SECONDS)).cause()
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_INVITATION_NOT_FOUND));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Invites the user by id and has them accept: the one way to add a member. */
    private void join(UUID community, User user, CommunityRole role, String inviterId) {
        String invitationId = communityService.invite(community, user.getId(), null, role, inviterId).id();
        communityService.acceptInvitation(UUID.fromString(invitationId), user.getId().toString());
    }
}
