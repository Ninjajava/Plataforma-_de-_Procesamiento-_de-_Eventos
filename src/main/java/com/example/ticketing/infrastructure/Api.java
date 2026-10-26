package com.example.ticketing.infrastructure;

import com.example.ticketing.application.TicketService;
import com.example.ticketing.domain.Model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;

@RestController
@RequestMapping("/api")
public class Api {
  private final TicketService service;
  private final String adminKey;

  public Api(TicketService service, @Value("${ticketing.admin-key:}") String adminKey) {
    this.service = service;
    this.adminKey = adminKey;
  }

  public record CreateEvent(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Future Instant date,
      @NotBlank @Size(max = 300) String venue,
      @Min(1) int capacity) {}

  public record Buy(
      @NotBlank String eventId,
      @NotBlank @Size(max = 200) String customerId,
      @Min(1) @Max(20) int quantity) {}

  public record OrderView(Order order, TicketState ticketState) {}

  @PostMapping("/events")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<Event> create(@Valid @RequestBody CreateEvent request) {
    return service.create(request.name(), request.date(), request.venue(), request.capacity());
  }

  @GetMapping("/events")
  public Flux<Event> events() {
    return service.events();
  }

  @GetMapping("/events/{id}/availability")
  public Mono<Event> event(@PathVariable String id) {
    return service.event(id);
  }

  @PostMapping("/orders")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Mono<OrderView> buy(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Buy request) {
    if (!key.matches("[a-zA-Z0-9_-]{8,100}"))
      return Mono.error(
          new IllegalArgumentException(
              "Idempotency-Key must contain 8 to 100 letters, digits, hyphens or underscores"));
    return service
        .buy(key, request.eventId(), request.customerId(), request.quantity(), false)
        .map(order -> new OrderView(order, order.ticketState()));
  }

  @GetMapping("/orders/{id}")
  public Mono<OrderView> order(@PathVariable String id) {
    return service.order(id).map(order -> new OrderView(order, order.ticketState()));
  }

  @PostMapping("/complimentary-orders")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Mono<OrderView> complimentary(
      @RequestHeader("X-Admin-Key") String supplied,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Buy request) {
    if (adminKey.isBlank()
        || !java.security.MessageDigest.isEqual(
            adminKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      return Mono.error(
          new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN));
    if (!key.matches("[a-zA-Z0-9_-]{8,100}"))
      return Mono.error(new IllegalArgumentException("Invalid Idempotency-Key"));
    return service
        .buy(key, request.eventId(), request.customerId(), request.quantity(), true)
        .map(order -> new OrderView(order, order.ticketState()));
  }

  @PostMapping("/orders/{id}/confirm")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Mono<OrderView> confirm(@PathVariable String id) {
    return service.confirm(id).map(order -> new OrderView(order, order.ticketState()));
  }

  @GetMapping("/orders/{id}/audit")
  public Flux<Audit> audit(@PathVariable String id) {
    return service.audit(id);
  }
}
