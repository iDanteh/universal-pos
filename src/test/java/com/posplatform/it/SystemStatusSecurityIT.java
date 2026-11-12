package com.posplatform.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Boot + configuration + security posture:
 *  - Spring context boots with Flyway migrations and Hibernate validation.
 *  - Public surface: health, OpenAPI, system status, login.
 *  - Access denies CLOSED (401) for anything else — real session auth via
 *    HttpOnly cookie since v0.2 (ADR-011); business endpoints additionally
 *    fail closed when no server-verified tenant is bound (404).
 */
@AutoConfigureMockMvc
class SystemStatusSecurityIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void systemStatusEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("up"));
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocsArePublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void nonPublicBusinessEndpointsFailClosed() throws Exception {
        mockMvc.perform(get("/api/v1/organizations"))
                .andExpect(status().isUnauthorized());
    }
}
