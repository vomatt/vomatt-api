package com.vomatt.users;

import com.vomatt.comments.CommentMapper;
import com.vomatt.comments.VoteCommentService;
import com.vomatt.common.audit.AuditAspect;
import com.vomatt.common.audit.AuditService;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.ActingMember;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.common.storage.StorageService;
import com.vomatt.entity.CommunityRole;
import com.vomatt.entity.User;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;
import com.vomatt.votes.VoteServiceSlice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.AopTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** PUT /api/communities/{id}/avatar with real commits: the upload runs outside the caller's transaction. */
@Import({ CommunityService.class, UserService.class, UserMapper.class, VoteCommentService.class, CommentMapper.class,
        VoteServiceSlice.class, StorageService.class, PlanService.class, AuditService.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommunityAvatarPostgresTest extends PostgresRepositoryTest {

    @Autowired CommunityService communityService;
    @Autowired PlanService planService;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired TransactionTemplate tx;
    @MockitoBean StorageService storageService;
    @MockitoBean RefreshTokenService refreshTokenService;

    private final AuditService auditService = mock(AuditService.class);
    private UUID communityId;
    private User owner;
    private User admin;
    private User editor;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(s -> {
            owner = persistUser("av-own-" + UUID.randomUUID().toString().substring(0, 8));
            admin = persistUser("av-adm-" + UUID.randomUUID().toString().substring(0, 8));
            editor = persistUser("av-ed-" + UUID.randomUUID().toString().substring(0, 8));
            em.flush();
            communityId = UUID.fromString(communityService.createCommunity(
                    "community_" + UUID.randomUUID().toString().substring(0, 8), "Sunrise Tower", owner.getId()).id());
            em.persist(new com.vomatt.entity.CommunityMember(communityId, admin.getId(), CommunityRole.ADMIN));
            em.persist(new com.vomatt.entity.CommunityMember(communityId, editor.getId(), CommunityRole.EDITOR));
        });
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)")
                .setParameter("ids", List.of(communityId, owner.getId(), admin.getId(), editor.getId())).executeUpdate());
    }

    private CommunityController auditedController(UUID memberId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal(memberId), null, List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        return new CommunityController(auditedService());
    }

    private CommunityService auditedService() {
        AspectJProxyFactory service = new AspectJProxyFactory((Object) AopTestUtils.getUltimateTargetObject(communityService));
        service.addAspect(new AuditAspect(auditService));
        return service.getProxy();
    }

    private static UserPrincipal principal(UUID id) {
        return new UserPrincipal(id.toString(), List.of("user"), null);
    }

    private static MockMultipartFile image() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "logo.png", "image/png", out.toByteArray());
    }

    private String storedAvatar() {
        return userRepository.findById(communityId).orElseThrow().getAvatarUrl();
    }

    @Test
    void shouldStoreAvatarAndAuditBeforeAndAfterWhenAdminUploads() throws IOException {
        var response = auditedController(admin.getId()).uploadAvatar(communityId, image(), principal(admin.getId()));

        String avatarUrl = response.getBody().data().avatarUrl();
        assertThat(avatarUrl).startsWith(UserService.AVATAR_URL_PREFIX);
        assertThat(storedAvatar()).isEqualTo(avatarUrl);
        verify(storageService).put(any(), any(), eq("image/jpeg"));
        verify(auditService).logAction(eq(admin.getId().toString()), eq("UPDATE"), eq("COMMUNITY"), eq(communityId.toString()),
                any(), any(), any(), any(), eq("avatarUrl: null -> \"" + avatarUrl + "\""));
    }

    @Test
    void shouldAuditAsCommunityUpdateWhenAdminUploadsThroughUsersMeWhileActing() throws IOException {
        var acting = new UsernamePasswordAuthenticationToken(principal(communityId), null, List.of());
        acting.setDetails(new ActingMember(admin.getId(), communityId, CommunityRole.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(acting);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        UserController controller = new UserController(userService, null, null, null, null, planService, auditedService());

        String avatarUrl = controller.uploadAvatar(image(), principal(communityId)).getBody().data().avatarUrl();

        assertThat(storedAvatar()).isEqualTo(avatarUrl);
        verify(auditService).logAction(eq(admin.getId().toString()), eq("UPDATE"), eq("COMMUNITY"), eq(communityId.toString()),
                any(), any(), any(), any(), eq("actingAs=" + communityId + "; avatarUrl: null -> \"" + avatarUrl + "\""));
    }

    @Test
    void shouldKeepAvatarAndAuditRefusalWhenEditorUploads() throws IOException {
        CommunityController controller = auditedController(editor.getId());
        MockMultipartFile image = image();

        assertThatThrownBy(() -> controller.uploadAvatar(communityId, image, principal(editor.getId())))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMUNITY_ROLE_FORBIDDEN));

        assertThat(storedAvatar()).isNull();
        verifyNoInteractions(storageService);
        verify(auditService).logFailedAction(eq(editor.getId().toString()), eq("UPDATE"), eq("COMMUNITY"),
                eq(communityId.toString()), any(), any(), any());
        verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
