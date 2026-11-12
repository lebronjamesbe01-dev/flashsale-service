package com.flashsale.shared.error;

import com.flashsale.shared.web.RequestIdFilter;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

/**
 * Errors raised outside Spring MVC (servlet container 400s, firewall-rejected URLs) are rendered by
 * Spring Boot's {@code /error} endpoint, not by {@link GlobalExceptionHandler}. This adds the same
 * {@code code} and {@code requestId} fields to that body so clients see one error shape.
 */
@Component
public class ProblemErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(
            WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        if (attributes.get("status") instanceof Integer status) {
            attributes.put("code", ErrorCode.fromStatus(status).name());
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            attributes.put("requestId", requestId);
        }
        return attributes;
    }
}
