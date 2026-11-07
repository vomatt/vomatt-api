package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.vomatt.entity.ContactMessage;

@DisplayName("ContactMessageRepository (Postgres)")
class ContactMessagePostgresTest extends PostgresRepositoryTest {

    @Autowired ContactMessageRepository contactMessageRepository;

    @Test
    void shouldPersistMessageWhenSaved() {
        ContactMessage saved = contactMessageRepository.saveAndFlush(
                new ContactMessage("Alice", "alice@example.com", "Hello", "zh"));
        em.clear();

        ContactMessage found = contactMessageRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getEmail()).isEqualTo("alice@example.com");
        assertThat(found.getLocale()).isEqualTo("zh");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldPageNewestFirstWhenListed() {
        contactMessageRepository.saveAndFlush(new ContactMessage("A", "a@example.com", "first-contact", "en"));
        contactMessageRepository.saveAndFlush(new ContactMessage("B", "b@example.com", "second-contact", "en"));
        em.clear();

        assertThat(contactMessageRepository.findPage(PageRequest.of(0, 20)).getContent())
                .extracting(ContactMessage::getMessage).containsSubsequence("second-contact", "first-contact");
    }
}
