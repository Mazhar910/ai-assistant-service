package com.development.agent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Statuses set deliberately by application code and safe to surface as-is. */
    private static final Set<Integer> TRUSTED_STATUSES = Set.of(401, 403, 404, 409);

    @ExceptionHandler(AiAgentException.class)
    public ResponseEntity<ErrorResponse> handleAiAgentException(AiAgentException ex, WebRequest request) {
        HttpStatus status = resolveStatus(ex.getStatus());
        if (status.is4xxClientError()) {
            log.warn("AiAgentException [{}] while processing {}: {}",
                    ex.getCode(), request.getDescription(false), ex.getMessage());
        } else {
            log.error("AiAgentException [{}] while processing {}: {}",
                    ex.getCode(), request.getDescription(false), ex.getMessage());
        }
        if (log.isDebugEnabled()) {
            log.debug("AiAgentException stack trace:", ex);
        }
        ErrorResponse error = new ErrorResponse(ex.getCode(), ex.getMessage(), Instant.now().toEpochMilli());
        return ResponseEntity.status(status).body(error);
    }

    /**
     * Maps the upstream/domain status captured in AiAgentException to an HTTP
     * status: 429 stays 429, upstream 5xx stays 5xx (so a 502/503/504 is not
     * flattened to a misleading 500), and statuses this application sets on purpose
     * (401/403/404/409) are preserved. Anything else falls back to 500.
     */
    private HttpStatus resolveStatus(int status) {
        if (status == 429) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (status >= 500 && status <= 599) {
            return HttpStatus.resolve(status) != null ? HttpStatus.resolve(status) : HttpStatus.BAD_GATEWAY;
        }
        if (TRUSTED_STATUSES.contains(status)) {
            HttpStatus resolved = HttpStatus.resolve(status);
            return resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                      WebRequest request) {
        // Unique constraint races (e.g. concurrent duplicate registration) reach here.
        log.warn("Data integrity violation while processing {}: {}", request.getDescription(false), ex.getMessage());
        ErrorResponse error = new ErrorResponse("CONFLICT",
                "The operation conflicts with existing data (username or email may already be registered).",
                Instant.now().toEpochMilli());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.warn("Bad request while processing {}: {}", request.getDescription(false), ex.getMessage());
        ErrorResponse error = new ErrorResponse("BAD_REQUEST", ex.getMessage(), Instant.now().toEpochMilli());
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        log.warn("Validation failed while processing {}: {}", request.getDescription(false), ex.getMessage());
        Map<String, Object> error = new HashMap<>();
        error.put("code", "VALIDATION_ERROR");
        error.put("timestamp", Instant.now().toEpochMilli());
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        error.put("fields", fieldErrors);
        error.put("message", "Validation failed");
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, WebRequest request) {
        log.error("Unexpected error while processing {}: {}", request.getDescription(false), ex.getMessage(), ex);
        ErrorResponse error = new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred",
                Instant.now().toEpochMilli());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}