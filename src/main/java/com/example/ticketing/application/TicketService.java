package com.example.ticketing.application;

import com.example.ticketing.domain.Model.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class TicketService {
  private final TicketStore store;
  private final Clock clock;

  public TicketService(TicketStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public Mono<Event> create(String name, Instant date, String venue, int capacity) {
    return store.create(
        new Event(UUID.randomUUID().toString(), name, date, venue, capacity, capacity, 0, 0, 0, 0));
  }

  public Flux<Event> events() {
    return store.events();
  }

  public Mono<Event> event(String id) {
    return store.event(id);
  }

  public Mono<Order> order(String id) {
    return store.order(id);
  }

  public Flux<Audit> audit(String id) {
    return store.audit(id);
  }

  public Mono<Order> buy(
      String key, String eventId, String customerId, int quantity, boolean courtesy) {
    return retried(
        () ->
            store
                .event(eventId)
                .then(
                    store.submit(
                        new Order(
                            key,
                            eventId,
                            customerId,
                            quantity,
                            OrderStatus.QUEUED,
                            null,
                            clock.instant(),
                            courtesy))));
  }

  public Mono<Void> reserve(String id) {
    return retried(
            () ->
                store
                    .order(id)
                    .flatMap(
                        order ->
                            switch (order.status()) {
                              case QUEUED ->
                                  store
                                      .event(order.eventId())
                                      .flatMap(
                                          event -> {
                                            if (event.available() < order.quantity()) {
                                              return store.transition(
                                                  order,
                                                  OrderStatus.REJECTED,
                                                  null,
                                                  null,
                                                  null,
                                                  false);
                                            }
                                            return store.transition(
                                                order,
                                                order.courtesy()
                                                    ? OrderStatus.COMPLIMENTARY
                                                    : OrderStatus.RESERVED,
                                                order.courtesy()
                                                    ? null
                                                    : clock.instant().plusSeconds(600),
                                                TicketState.AVAILABLE,
                                                order.courtesy()
                                                    ? TicketState.COMPLIMENTARY
                                                    : TicketState.RESERVED,
                                                false);
                                          });
                              case PENDING_CONFIRMATION ->
                                  isExpired(order)
                                      ? release(order)
                                      : store.transition(
                                          order,
                                          OrderStatus.SOLD,
                                          null,
                                          TicketState.PENDING_CONFIRMATION,
                                          TicketState.SOLD,
                                          false);
                              default -> Mono.just(order);
                            }))
        .then();
  }

  public Mono<Order> confirm(String id) {
    return retried(
        () ->
            store
                .order(id)
                .flatMap(
                    order ->
                        switch (order.status()) {
                          case SOLD, PENDING_CONFIRMATION -> Mono.just(order);
                          case RESERVED ->
                              isExpired(order)
                                  ? release(order)
                                  : store.transition(
                                      order,
                                      OrderStatus.PENDING_CONFIRMATION,
                                      order.expiresAt(),
                                      TicketState.RESERVED,
                                      TicketState.PENDING_CONFIRMATION,
                                      true);
                          default ->
                              Mono.error(
                                  new Conflict("Only an active reservation can be confirmed"));
                        }));
  }

  public Mono<Void> expire() {
    return Mono.defer(
        () ->
            store
                .expired(clock.instant())
                .flatMap(
                    candidate ->
                        retried(
                            () ->
                                store
                                    .order(candidate.id())
                                    .flatMap(
                                        order ->
                                            (order.status() == OrderStatus.RESERVED
                                                        || order.status()
                                                            == OrderStatus.PENDING_CONFIRMATION)
                                                    && isExpired(order)
                                                ? release(order)
                                                : Mono.just(order))),
                    8)
                .then());
  }

  private boolean isExpired(Order order) {
    return !order.expiresAt().isAfter(clock.instant());
  }

  private Mono<Order> release(Order order) {
    return store.transition(
        order, OrderStatus.EXPIRED, null, order.ticketState(), TicketState.AVAILABLE, false);
  }

  private <T> Mono<T> retried(Supplier<Mono<T>> operation) {
    return Mono.defer(operation)
        .retryWhen(
            Retry.backoff(8, Duration.ofMillis(15))
                .maxBackoff(Duration.ofSeconds(1))
                .filter(
                    error ->
                        error instanceof ConcurrentChange || error instanceof TransientStoreFailure)
                .onRetryExhaustedThrow((specification, signal) -> signal.failure()));
  }
}
