package com.vomatt.admin;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.VoteCommentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCommentService")
class AdminCommentServiceTest {

    @Mock AdminQueryRepository adminQueryRepository;
    @Mock VoteCommentRepository commentRepository;
    @Mock AdminMapper adminMapper;
    @InjectMocks AdminCommentService service;

    @Test
    void shouldSoftDeleteWhenCallerIsNotAuthor() {
        UUID commentId = UUID.randomUUID();
        when(commentRepository.softDelete(commentId)).thenReturn(1);

        service.deleteComment(commentId, UUID.randomUUID().toString());

        verify(commentRepository).softDelete(commentId);
    }

    @Test
    void shouldReturn404WhenCommentMissing() {
        UUID commentId = UUID.randomUUID();
        when(commentRepository.softDelete(commentId)).thenReturn(0);
        when(commentRepository.existsById(commentId)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteComment(commentId, "admin"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMENT_NOT_FOUND));
    }
}
