package com.vomatt.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.vomatt.admin.dto.AdminUserResponse;
import com.vomatt.entity.User;

class AdminMapperTest {

    private final AdminMapper mapper = new AdminMapper();

    @Test
    void shouldMaskContactDetailsWhenListingUsers() {
        User user = User.builder().id(UUID.randomUUID()).username("alice").email("alice@example.com")
                .phoneNumber("+886912345678").firstName("Alice").roles(new String[] { "user" }).build();

        AdminUserResponse listed = mapper.toUserResponse(user);

        assertThat(listed.email()).isEqualTo("a***@example.com");
        assertThat(listed.phoneNumber()).isEqualTo("+88****78");
        assertThat(AdminUserResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("firstName", "lastName", "birthday");
        // The detail view keeps the full values
        assertThat(mapper.toUserDetail(user, 0, 0, 0, 0, 0).email()).isEqualTo("alice@example.com");
    }

    @Test
    void shouldKeepNullContactWhenAccountHasNone() {
        User phoneOnly = User.builder().id(UUID.randomUUID()).username("p").phoneNumber("+886912345678").roles(new String[] { "user" }).build();

        AdminUserResponse listed = mapper.toUserResponse(phoneOnly);

        assertThat(listed.email()).isNull();
        assertThat(listed.phoneNumber()).isEqualTo("+88****78");
    }
}
