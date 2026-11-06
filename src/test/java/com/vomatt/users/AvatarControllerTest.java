package com.vomatt.users;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.storage.StorageService;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AvatarController")
class AvatarControllerTest {

    private static final String FILE = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47.jpg";

    @Mock StorageService storageService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks AvatarController avatarController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(avatarController)
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    @Test
    @DisplayName("should stream the JPEG with an immutable year-long cache when the object exists")
    void shouldStreamJpegWhenAvatarExists() throws Exception {
        byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
        when(storageService.get("avatars/" + FILE)).thenReturn(Optional.of(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) jpeg.length).build(), new ByteArrayInputStream(jpeg))));

        mockMvc.perform(get("/api/avatars/" + FILE))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(content().bytes(jpeg));
    }

    @Test
    @DisplayName("should answer 404 when no object has this name")
    void shouldReturnNotFoundWhenAvatarMissing() throws Exception {
        when(storageService.get("avatars/" + FILE)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/avatars/" + FILE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("common.not_found"));
    }
}
