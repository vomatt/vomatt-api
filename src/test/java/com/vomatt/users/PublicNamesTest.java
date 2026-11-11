package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.vomatt.entity.User;
import com.vomatt.entity.UserPreference;
import com.vomatt.repository.UserPreferenceRepository;

@DisplayName("PublicNames")
class PublicNamesTest {

    private final UserPreferenceRepository preferences = mock(UserPreferenceRepository.class);

    private User user(String username) {
        return User.builder().id(UUID.randomUUID()).username(username)
                .displayName("Display " + username).firstName("First").lastName("Last").build();
    }

    private UserPreference pref(User user, String field, boolean shown) {
        UserPreference pref = new UserPreference();
        pref.setUser(user);
        pref.setKey("visibility." + field);
        pref.setValue(Boolean.toString(shown));
        return pref;
    }

    @Test
    @DisplayName("should show only the display name when the owner never set visibility")
    void shouldShowDisplayNameOnlyWhenSettingsUnset() {
        User alice = user("alice");

        assertThat(PublicNames.load(alice, preferences)).isEqualTo(new PublicName("Display alice", null, null));
    }

    @Test
    @DisplayName("should follow each owner's own settings when loading several users")
    void shouldApplyEachOwnersSettingsWhenLoadingMany() {
        User shown = user("shown");
        User hidden = user("hidden");
        when(preferences.findByUserIdInAndKeyIn(any(), any())).thenReturn(List.of(
                pref(shown, "firstName", true), pref(shown, "lastName", true),
                pref(hidden, "displayName", false), pref(hidden, "lastName", true)));

        Map<UUID, PublicName> names = PublicNames.load(List.of(shown, hidden), preferences);

        assertThat(names).containsEntry(shown.getId(), new PublicName("Display shown", "First", "Last"))
                .containsEntry(hidden.getId(), new PublicName(null, null, "Last"));
    }

    @Test
    @DisplayName("should not query when there are no users")
    void shouldSkipQueryWhenNoUsers() {
        assertThat(PublicNames.load(List.of(), preferences)).isEmpty();
        verifyNoInteractions(preferences);
    }
}
