package com.vomatt.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.contact.dto.CreateContactMessageRequest;
import com.vomatt.entity.ContactMessage;
import com.vomatt.lookups.LookupService;
import com.vomatt.repository.ContactMessageRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContactService")
class ContactServiceTest {

    private static final String IP = "203.0.113.7";

    @Mock ContactMessageRepository contactMessageRepository;
    @Mock RedisService redisService;
    @Mock LookupService lookupService;
    @InjectMocks ContactService contactService;

    private static CreateContactMessageRequest request() {
        CreateContactMessageRequest request = new CreateContactMessageRequest();
        request.setName("  Alice ");
        request.setEmail(" Alice@Example.com ");
        request.setMessage("\n Hello \n");
        request.setLocale("en");
        return request;
    }

    @Test
    void shouldRejectWhenDailyLimitReachedForIp() {
        when(redisService.get(eq("contact:daily"), startsWith(IP + ":"), eq(Long.class))).thenReturn(5L);
        when(lookupService.configInt("contact_daily_limit", ContactService.DEFAULT_DAILY_LIMIT)).thenReturn(5);

        assertThatThrownBy(() -> contactService.createMessage(IP, request()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(contactMessageRepository);
    }

    @Test
    void shouldStoreTrimmedAndCountWhenUnderLimit() {
        when(redisService.get(eq("contact:daily"), anyString(), eq(Long.class))).thenReturn(4L);
        when(lookupService.configInt("contact_daily_limit", ContactService.DEFAULT_DAILY_LIMIT)).thenReturn(5);
        when(contactMessageRepository.saveAndFlush(any(ContactMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        contactService.createMessage(IP, request());

        ArgumentCaptor<ContactMessage> saved = ArgumentCaptor.forClass(ContactMessage.class);
        verify(contactMessageRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Alice");
        assertThat(saved.getValue().getEmail()).isEqualTo("Alice@Example.com");
        assertThat(saved.getValue().getMessage()).isEqualTo("Hello");
        verify(redisService).incrementAndExpire(eq("contact:daily"), startsWith(IP + ":"), any());
    }

    @Test
    void shouldNotCountWhenSaveFails() {
        when(contactMessageRepository.saveAndFlush(any(ContactMessage.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> contactService.createMessage(IP, request())).hasMessage("db down");
        verify(redisService, never()).incrementAndExpire(anyString(), anyString(), any());
    }

    @Test
    void shouldStoreMessageWhenRedisIsDown() {
        when(redisService.incrementAndExpire(eq("contact:daily"), anyString(), any())).thenThrow(new RuntimeException("down"));
        when(contactMessageRepository.saveAndFlush(any(ContactMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        contactService.createMessage(IP, request());

        verify(contactMessageRepository).saveAndFlush(any(ContactMessage.class));
    }
}
