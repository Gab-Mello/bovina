package com.bovina.platform.api;

import com.bovina.platform.application.ApplicationFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private final ApiProblems problems;

  public ApiExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(ApplicationFailure.class)
  ResponseEntity<Object> applicationFailure(
      ApplicationFailure failure, HttpServletRequest request) {
    var status =
        switch (failure.kind()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
          case REJECTED -> HttpStatus.UNPROCESSABLE_CONTENT;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
    return ResponseEntity.status(status)
        .body(problems.create(status, failure.code(), failure.getMessage(), request));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    var status = HttpStatus.valueOf(statusCode.value());
    var problem =
        problems.create(
            status,
            "HTTP_" + status.value(),
            status.getReasonPhrase(),
            ((ServletWebRequest) request).getRequest());
    if (exception instanceof MethodArgumentNotValidException invalid) {
      List<FieldViolation> violations =
          invalid.getBindingResult().getFieldErrors().stream()
              .map(error -> new FieldViolation(error.getField(), error.getCode(), "Invalid value"))
              .distinct()
              .toList();
      problem.setProperty("code", "VALIDATION_FAILED");
      problem.setProperty("errors", violations);
    }
    return new ResponseEntity<>(problem, headers, statusCode);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
    LOG.atError()
        .setCause(exception)
        .addKeyValue("exceptionType", exception.getClass().getName())
        .log("Unhandled request failure");
    return ResponseEntity.internalServerError()
        .body(
            problems.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal server error",
                request));
  }

  public record FieldViolation(String field, String code, String message) {}

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Object> constraintConflict(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            problems.create(
                HttpStatus.CONFLICT,
                "CONSTRAINT_CONFLICT",
                "The command conflicts with an existing record or reference",
                request));
  }

  @ExceptionHandler({
    OptimisticLockingFailureException.class,
    PessimisticLockingFailureException.class
  })
  ResponseEntity<Object> concurrentWrite(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            problems.create(
                HttpStatus.CONFLICT,
                "CONCURRENT_WRITE_CONFLICT",
                "Retry the command with the same idempotency key",
                request));
  }
}
