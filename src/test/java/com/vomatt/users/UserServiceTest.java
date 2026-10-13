package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.entity.User;
import com.vomatt.repository.UserPreferenceRepository;
import com.vomatt.users.dto.UpdateProfileRequest;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteOptionRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserPreferenceRepository preferenceRepository;
    @Mock UserMapper userMapper;
    @Mock RefreshTokenService refreshTokenService;
    @Mock VoteOptionRepository voteOptionRepository;
    @InjectMocks UserService userService;

    @Test
    @DisplayName("should release user's Ballot from option counts before deleting account")
    void shouldReleaseBallotCountsBeforeDeletingUser() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("u").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), "u@test.local", List.of("user")));

        InOrder order = inOrder(voteOptionRepository, userRepository);
        order.verify(voteOptionRepository).decrementForUserBallots(id);
        order.verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("should reject username already used by another account")
    void shouldThrowConflictWhenUsernameTaken() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("old_name").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("taken")).thenReturn(true);
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername("taken");

        assertThatThrownBy(() -> userService.updateMyProfile(id.toString(), request))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(user.getUsername()).isEqualTo("old_name");
        verify(userRepository, never()).save(user);
    }
}
