package com.flashsale.shared.error;

import com.flashsale.shared.web.RequestIdFilter;
import java.net.URI;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/**
 * Builds the single error body shape used across the API: {@code {type, title, status, detail,
 * code, requestId, fields?}}.
 */
public final class ProblemFactory {

    private ProblemFactory() {}

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create("urn:flashsale:error:" + code.name().toLowerCase()));
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }

    public static ProblemDetail of(ErrorCode code) {
        return of(code, code.defaultMessage());
    }

    public static ProblemDetail withFields(ErrorCode code, Map<String, String> fields) {
        ProblemDetail problem = of(code);
        problem.setProperty("fields", fields);
        return problem;
    }
}
