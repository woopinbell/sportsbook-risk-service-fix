package com.sportsbook.risk.api;

import com.sportsbook.protocol.error.ErrorCode;
import com.sportsbook.protocol.error.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Funnels every uncaught exception into an RFC 7807 ProblemDetail response so the wire shape
 * matches what every other sportsbook service emits (ADR-0004). Spring's default error handling
 * would return a Spring-flavoured ProblemDetail with different field names; we forward through
 * {@code shared-protocol}'s {@link ProblemDetail} record instead.
 */
@RestControllerAdvice
public class RestExceptionHandler {

  private static final MediaType PROBLEM_JSON =
      MediaType.parseMediaType("application/problem+json");
  private static final int HTTP_NOT_FOUND = 404;

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex) {
    String detail =
        ex.getBindingResult().getAllErrors().stream()
            .map(err -> err.getDefaultMessage() == null ? err.toString() : err.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");
    return problem(ErrorCode.VALIDATION_FAILED, detail);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex) {
    return problem(ErrorCode.VALIDATION_FAILED, ex.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
    return problem(ErrorCode.VALIDATION_FAILED, ex.getMessage());
  }

  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(NoHandlerFoundException ex) {
    ProblemDetail body =
        new ProblemDetail(
            java.net.URI.create("https://sportsbook/errors/not-found"),
            "Not found",
            HTTP_NOT_FOUND,
            "NOT_FOUND",
            ex.getMessage(),
            null,
            null);
    return ResponseEntity.status(HTTP_NOT_FOUND).contentType(PROBLEM_JSON).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
    return problem(ErrorCode.INTERNAL_ERROR, ex.getMessage());
  }

  private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail) {
    ProblemDetail body = code.toProblemDetail(detail);
    return ResponseEntity.status(code.httpStatus()).contentType(PROBLEM_JSON).body(body);
  }
}
