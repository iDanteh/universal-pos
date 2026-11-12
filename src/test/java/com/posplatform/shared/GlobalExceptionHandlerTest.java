package com.posplatform.shared;

import com.posplatform.shared.api.ConflictException;
import com.posplatform.shared.api.GlobalExceptionHandler;
import com.posplatform.shared.api.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit: centralized error mapping contract (RFC 9457). */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundMapsTo404ProblemDetail() {
        ProblemDetail problem = handler.handleNotFound(
                new NotFoundException("organization", "abc"));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Resource not found");
        assertThat(problem.getProperties()).containsEntry("resource", "organization");
    }

    @Test
    void conflictMapsTo409ProblemDetail() {
        ProblemDetail problem = handler.handleConflict(
                new ConflictException("membership.duplicate", "Ya existe membresía"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "membership.duplicate");
    }

    @Test
    void unexpectedMapsTo500WithoutLeakingMessage() {
        ProblemDetail problem = handler.handleUnexpected(new RuntimeException("secret internals"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).doesNotContain("secret internals");
    }
}
