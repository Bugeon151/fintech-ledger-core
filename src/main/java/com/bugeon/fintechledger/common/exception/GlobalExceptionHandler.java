package com.bugeon.fintechledger.common.exception;

import com.bugeon.fintechledger.common.persistence.Constraints;
import com.bugeon.fintechledger.common.web.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates every exception type into a uniform {@link ApiResponse} JSON error.
 *
 * <pre>
 * BusinessException           → structured error code + message
 * MethodArgumentNotValid      → 400 with per-field validation details
 * HttpMessageNotReadable      → 400 malformed JSON body
 * MissingRequestParameter     → 400 missing query param
 * MethodArgumentTypeMismatch  → 400 wrong param type
 * AccessDeniedException       → 403 (Spring Security @PreAuthorize)
 * Exception (catch-all)       → 500, internal message suppressed
 * </pre>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── BusinessException ─────────────────────────────────────────────────────

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        log.debug("BusinessException [{}]: {}", code.getCode(), ex.getMessage());

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        ex.getMessage(),
                        null
                )));
    }

    // ── Validation: @Valid on @RequestBody ────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null
                                ? fe.getDefaultMessage()
                                : "Invalid value",
                        (first, second) -> first      // keep first error per field
                ));

        log.debug("Validation failed: {}", fieldErrors);

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        code.getDefaultMessage(),
                        fieldErrors
                )));
    }

    // ── Malformed JSON body ───────────────────────────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException ex) {

        log.debug("Unreadable request body: {}", ex.getMessage());

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        "Request body is missing or malformed",
                        null
                )));
    }

    // ── Missing query / path parameters ──────────────────────────────────────

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {

        log.debug("Missing request parameter: {}", ex.getParameterName());

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        "Required parameter '" + ex.getParameterName() + "' is missing",
                        null
                )));
    }

    // ── Missing Request Header (e.g. Idempotency-Key) ───────────────────────

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(
            MissingRequestHeaderException ex) {

        log.debug("Missing request header: {}", ex.getHeaderName());

        // Idempotency-Key는 모든 쓰기 API의 필수 헤더이므로 전용 에러 코드 반환
        boolean isIdempotencyKey = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName());
        ErrorCode code = isIdempotencyKey
                ? ErrorCode.IDEMPOTENCY_KEY_MISSING
                : ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        isIdempotencyKey
                                ? code.getDefaultMessage()
                                : "Required header '" + ex.getHeaderName() + "' is missing",
                        null
                )));
    }

    // ── Wrong parameter type ──────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        log.debug("Type mismatch for parameter [{}]: {}", ex.getName(), ex.getMessage());

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        "Parameter '" + ex.getName() + "' has an invalid value",
                        null
                )));
    }

    // ── Spring Security: 403 Forbidden ────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.debug("Access denied: {}", ex.getMessage());

        return ResponseEntity
                .status(403)
                .body(ApiResponse.fail(new ApiResponse.Error(
                        "GEN_403",
                        "You do not have permission to access this resource",
                        null
                )));
    }

    // ── Optimistic Lock Failure ────────────────────────────────────────────────
    //
    // Occurs when @Version field mismatch is detected on flush.
    // This is a secondary guard — the primary concurrency protection is
    // PESSIMISTIC_WRITE (SELECT FOR UPDATE). If this exception fires, it means
    // a concurrent modification happened on an unguarded path.
    // Return 409 Conflict — client should retry the operation.

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict on {}: {}",
                ex.getPersistentClass() != null ? ex.getPersistentClass().getSimpleName() : "unknown",
                ex.getMessage());
        ErrorCode code = ErrorCode.CONCURRENT_MODIFICATION;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        "Concurrent modification detected. Please retry the request.",
                        null
                )));
    }

    // ── Invalid sort property (e.g. ?sort=password) ──────────────────────────

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadSort(PropertyReferenceException ex) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(), "Unknown sort property '" + ex.getPropertyName() + "'", null)));
    }

    // ── DB constraint violations ─────────────────────────────────────────────
    // Duplicate idempotency keys are resolved (replayed) inside TransactionService,
    // so anything reaching this handler is a genuine conflict with stored state.

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        ErrorCode code = Constraints.isViolation(ex, Constraints.TRANSACTION_IDEMPOTENCY_KEY)
                ? ErrorCode.IDEMPOTENCY_KEY_CONFLICT
                : ErrorCode.DATA_CONFLICT;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(code.getCode(), code.getDefaultMessage(), null)));
    }

    // ── Lock timeout / deadlock victim — safe to retry ───────────────────────

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockFailure(PessimisticLockingFailureException ex) {
        log.warn("Pessimistic locking failure: {}", ex.getMostSpecificCause().getMessage());
        ErrorCode code = ErrorCode.CONCURRENT_MODIFICATION;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(code.getCode(), code.getDefaultMessage(), null)));
    }

    // ── Framework errors that carry their own status (404, 405, 415, ...) ────

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
                       HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ApiResponse<Void>> handleFrameworkError(Exception ex) {
        HttpStatusCode status = ((ErrorResponse) ex).getStatusCode();
        return ResponseEntity
                .status(status)
                .body(ApiResponse.fail(new ApiResponse.Error("GEN_" + status.value(),
                        ((ErrorResponse) ex).getBody().getTitle(), null)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);

        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        code.getDefaultMessage(),
                        null       // never leak internal details
                )));
    }
}
