package com.flashsale.shared.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts every exception leaving a controller into the same problem body. Unknown exceptions are
 * logged with their stack trace but never echoed to the client, so internal details (SQL, class
 * names, stack frames) cannot leak.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        return respond(ProblemFactory.of(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return respond(ProblemFactory.withFields(ErrorCode.VALIDATION_FAILED, fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        return respond(ProblemFactory.of(ErrorCode.MALFORMED_REQUEST));
    }

    /**
     * Security exceptions thrown from inside a controller (e.g. {@code @PreAuthorize}) are rethrown
     * so Spring Security's filter decides between 401 (anonymous) and 403 (authenticated).
     */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    void rethrowSecurity(RuntimeException ex) {
        throw ex;
    }

    /**
     * Two writers raced on the same row: an optimistic version check failed, or a unique / check
     * constraint caught a duplicate that slipped past an application-level check. The request can
     * be retried, so it is a conflict, not a server error.
     */
    @ExceptionHandler({
        ObjectOptimisticLockingFailureException.class,
        DataIntegrityViolationException.class
    })
    ResponseEntity<ProblemDetail> handleConcurrentWrite(Exception ex) {
        log.warn("Concurrent write rejected: {}", ex.getClass().getSimpleName());
        return respond(ProblemFactory.of(ErrorCode.CONFLICT));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleOther(Exception ex) {
        if (ex instanceof ErrorResponse springError) {
            int status = springError.getStatusCode().value();
            return respond(ProblemFactory.of(ErrorCode.fromStatus(status)));
        }
        log.error("Unhandled exception", ex);
        return respond(ProblemFactory.of(ErrorCode.INTERNAL_ERROR));
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
