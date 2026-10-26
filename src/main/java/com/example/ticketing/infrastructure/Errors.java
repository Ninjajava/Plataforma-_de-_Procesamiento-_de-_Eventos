package com.example.ticketing.infrastructure;

import com.example.ticketing.domain.Model.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Missing.class)
  ResponseEntity<ProblemDetail> missing(Missing e) {
    return problem(404, e.getMessage());
  }

  @ExceptionHandler(Conflict.class)
  ResponseEntity<ProblemDetail> conflict(Conflict e) {
    return problem(409, e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e) {
    return problem(400, e.getMessage());
  }

  private ResponseEntity<ProblemDetail> problem(int code, String detail) {
    return ResponseEntity.status(code)
        .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code), detail));
  }
}
