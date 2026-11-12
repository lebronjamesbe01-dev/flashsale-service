package com.flashsale.shared.security;

import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.error.ProblemFactory;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security failures happen in the filter chain, before any controller runs, so the
 * {@code @RestControllerAdvice} never sees them. These handlers write the same problem body by
 * hand.
 */
@Component
public class ProblemAuthenticationHandlers {

    private final JsonMapper jsonMapper;

    public ProblemAuthenticationHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, ErrorCode.UNAUTHORIZED);
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        ProblemDetail problem = ProblemFactory.of(code);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
