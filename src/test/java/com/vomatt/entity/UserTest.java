package com.vomatt.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

@DisplayName("User")
class UserTest {

    @Test
    @DisplayName("should trim and lower-case when the address is not Gmail")
    void shouldLowerCaseOnlyWhenDomainIsNotGmail() {
        assertThat(User.normalizeEmail("  Carol@Example.COM ")).isEqualTo("carol@example.com");
        assertThat(User.normalizeEmail("a.b+x@example.com")).isEqualTo("a.b+x@example.com");
        assertThat(User.normalizeEmail("a.b+x@mail.gmail.com")).isEqualTo("a.b+x@mail.gmail.com");
    }

    @Test
    @DisplayName("should lower-case a Gmail address")
    void shouldLowerCaseWhenGmail() {
        assertThat(User.normalizeEmail(" AB@Gmail.com ")).isEqualTo("ab@gmail.com");
    }

    @Test
    @DisplayName("should drop the +tag of a Gmail address from the first +")
    void shouldDropTagWhenGmail() {
        assertThat(User.normalizeEmail("ab+news@gmail.com")).isEqualTo("ab@gmail.com");
        assertThat(User.normalizeEmail("ab+news+more@gmail.com")).isEqualTo("ab@gmail.com");
    }

    @Test
    @DisplayName("should remove every dot of a Gmail local part")
    void shouldRemoveDotsWhenGmail() {
        assertThat(User.normalizeEmail("a.b.c@gmail.com")).isEqualTo("abc@gmail.com");
        assertThat(User.normalizeEmail("A.B+x.y@Gmail.com")).isEqualTo("ab@gmail.com");
    }

    @Test
    @DisplayName("should map googlemail.com to gmail.com")
    void shouldMapToGmailWhenGooglemail() {
        assertThat(User.normalizeEmail("A.b+x@GoogleMail.com")).isEqualTo("ab@gmail.com");
    }

    @Test
    @DisplayName("should convert a Taiwan mobile number to E.164 when it starts with 09")
    void shouldConvertToE164WhenTaiwanMobile() {
        assertThat(User.normalizePhone("0912345678")).isEqualTo("+886912345678");
    }

    @Test
    @DisplayName("should strip spaces, dashes and parentheses when normalizing")
    void shouldStripSeparatorsWhenNormalizing() {
        assertThat(User.normalizePhone(" 0912-345 678 ")).isEqualTo("+886912345678");
        assertThat(User.normalizePhone("(0912) 345-678")).isEqualTo("+886912345678");
        assertThat(User.normalizePhone("+886 912-345-678")).isEqualTo("+886912345678");
    }

    @Test
    @DisplayName("should keep a + number as is when it is already E.164")
    void shouldKeepNumberWhenAlreadyE164() {
        assertThat(User.normalizePhone("+886912345678")).isEqualTo("+886912345678");
        assertThat(User.normalizePhone("+14155552671")).isEqualTo("+14155552671");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "912345678", "091234567", "09123456789", "0212345678", "+0912345678", "+1234567",
            "+1234567890123456", "+886-abc-1234", "0912345678x", "886912345678" })
    @DisplayName("should reject with auth.phone.invalid when the number is in no supported format")
    void shouldThrowWhenPhoneInvalid(String phone) {
        assertThatThrownBy(() -> User.normalizePhone(phone))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.AUTH_PHONE_INVALID));
    }

    @Test
    void shouldBeAdminOnlyWhenRolesHoldAdmin() {
        assertThat(User.builder().roles(new String[] { "user", "admin" }).build().isAdmin()).isTrue();
        assertThat(User.builder().roles(new String[] { "user", "moderator" }).build().isAdmin()).isFalse();
    }
}
