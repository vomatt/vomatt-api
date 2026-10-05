package com.vomatt.users;

import static org.mockito.Mockito.inOrder;
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

import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.entity.User;
import com.vomatt.repository.UserPreferenceRepository;
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
    @DisplayName("應該在刪除帳號前先從選項計數釋放該使用者的 Ballot")
    void shouldReleaseBallotCountsBeforeDeletingUser() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("u").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), "u@test.local", List.of("user")));

        InOrder order = inOrder(voteOptionRepository, userRepository);
        order.verify(voteOptionRepository).decrementForUserBallots(id);
        order.verify(userRepository).delete(user);
    }
}
