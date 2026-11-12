package com.flashsale.shared.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
     * Path / query parameter of the wrong type (not-a-uuid, bad date, bad enum) is a client error.
     */
    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(TypeMismatchException ex) {
        String name =
                ex instanceof MethodArgumentTypeMismatchException m
                        ? m.getName()
                        : ex.getPropertyName();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(name == null ? "request" : name, "invalid value");
        return respond(ProblemFactory.withFields(ErrorCode.VALIDATION_FAILED, fields));
    }

    /** Bean validation on method parameters ({@code @Valid} lists, {@code @Min} on a param...). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            String message =
                    result.getResolvableErrors().stream()
                            .map(MessageSourceResolvable::getDefaultMessage)
                            .findFirst()
                            .orElse("invalid value");
            fields.putIfAbsent(name == null ? "request" : name, message);
        }
        return respond(ProblemFactory.withFields(ErrorCode.VALIDATION_FAILED, fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), violation.getMessage());
        }
        return respond(ProblemFactory.withFields(ErrorCode.VALIDATION_FAILED, fields));
    }

    /**
     * Two writers raced: optimistic version check, deadlock victim, lock timeout. The request can
     * be retried, so it is a conflict, not a server error.
     *
     * <p>Ordering: {@link ConcurrencyFailureException} is itself a {@link
     * TransientDataAccessException}, and Spring picks the closest declared type, so this handler
     * wins over {@link #handleBusy} for locking failures (409) while query timeouts and other
     * transient resource failures still go to 503.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.warn("Concurrent update rejected ({})", ex.getClass().getSimpleName());
        return respond(ProblemFactory.of(ErrorCode.CONFLICT));
    }

    /**
     * A unique / check / foreign-key constraint caught something that slipped past an
     * application-level check. Retrying or changing the input resolves it: 409.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Constraint violated: {}", describeViolation(ex));
        return respond(ProblemFactory.of(ErrorCode.CONFLICT));
    }

    /**
     * The database (or Redis) is unavailable or too slow for this request: no free connection
     * within the pool timeout, connection lost, statement or transaction timeout. The client should
     * back off and retry, so this is 503 with Retry-After, not 500.
     */
    @ExceptionHandler({
        TransientDataAccessException.class,
        RecoverableDataAccessException.class,
        DataAccessResourceFailureException.class,
        RedisSystemException.class,
        CannotCreateTransactionException.class,
        TransactionTimedOutException.class
    })
    ResponseEntity<ProblemDetail> handleBusy(Exception ex) {
        log.warn("Backend unavailable or timed out: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemFactory.of(ErrorCode.SERVICE_BUSY));
    }

    /** Constraint name (Hibernate) or SQLState of the root cause; never sent to the client. */
    private static String describeViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException h
                    && h.getConstraintName() != null) {
                return "constraint=" + h.getConstraintName();
            }
        }
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        return root instanceof SQLException sql
                ? "sqlState=" + sql.getSQLState()
                : root.getClass().getSimpleName();
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
