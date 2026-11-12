package com.flashsale.shared;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/** Garbage in path / query parameters is a 400 problem body, never a 500. */
@IntegrationTest
class ErrorMappingIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void notAUuidPathVariable_is400WithFieldName() throws Exception {
        mvc.perform(
                        get("/api/v1/admin/products/not-a-uuid")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.id").value("invalid value"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void badDateQueryParam_is400() throws Exception {
        mvc.perform(
                        get("/api/v1/flash-sale/slots")
                                .param("date", "bad")
                                .with(
                                        jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.date").value("invalid value"));
    }
}
