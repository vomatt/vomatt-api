package com.vomatt.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("User.normalizeEmail")
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
}
