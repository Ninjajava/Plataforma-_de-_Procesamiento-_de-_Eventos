package com.example.ticketing.application;

import com.example.ticketing.domain.Model.*;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TicketStore {
  Mono<Event> create(Event event);

  Flux<Event> events();

  Mono<Event> event(String id);

  Mono<Order> order(String id);

  Mono<Order> submit(Order order);

  Mono<Order> transition(
      Order expected,
      OrderStatus status,
      Instant expiresAt,
      TicketState source,
      TicketState target,
      boolean enqueue);

  Flux<Order> expired(Instant cutoff);

  Flux<Outbox> outbox();

  Mono<Void> delivered(String id);

  Flux<Audit> audit(String id);
}
