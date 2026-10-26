package com.example.ticketing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ticketing.application.*;
import com.example.ticketing.domain.Model.*;
import com.example.ticketing.domain.Model.Order;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

class TicketServiceTest {
  private final Instant now = Instant.parse("2026-10-23T12:00:00Z");
  private TicketStore store;
  private TicketService service;
  private Event event;

  @BeforeEach
  void setup() {
    store = mock(TicketStore.class);
    service = new TicketService(store, Clock.fixed(now, ZoneOffset.UTC));
    event = new Event("event", "Concert", now.plusSeconds(86400), "Venue", 10, 10, 0, 0, 0, 0);
    when(store.event("event")).thenReturn(Mono.just(event));
    when(store.transition(
            any(),
            any(),
            nullable(Instant.class),
            nullable(TicketState.class),
            nullable(TicketState.class),
            anyBoolean()))
        .thenAnswer(
            invocation -> {
              Order original = invocation.getArgument(0);
              return Mono.just(
                  new Order(
                      original.id(),
                      original.eventId(),
                      original.customerId(),
                      original.quantity(),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      original.createdAt(),
                      original.courtesy()));
            });
  }

  private Order order(OrderStatus status, boolean courtesy, Instant deadline) {
    return new Order("order-001", "event", "customer", 2, status, deadline, now, courtesy);
  }

  private void current(Order order) {
    when(store.order(order.id())).thenReturn(Mono.just(order));
  }

  @Test
  void createInitializesAllInventoryBuckets() {
    when(store.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    StepVerifier.create(service.create("Concert", event.date(), "Venue", 10))
        .assertNext(
            created -> {
              assertNotNull(created.id());
              assertEquals(10, created.available());
              assertEquals(
                  0,
                  created.sold()
                      + created.reserved()
                      + created.pending()
                      + created.complimentary());
            })
        .verifyComplete();
  }

  @Test
  void purchaseCreatesQueuedOrderWithInjectedClockAndOriginalIdempotencyKey() {
    when(store.submit(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    StepVerifier.create(service.buy("order-001", "event", "customer", 2, false))
        .assertNext(
            created -> {
              assertEquals("order-001", created.id());
              assertEquals(OrderStatus.QUEUED, created.status());
              assertEquals(now, created.createdAt());
              assertNull(created.expiresAt());
            })
        .verifyComplete();
  }

  @Test
  void missingEventDoesNotPersistOrder() {
    when(store.event("missing")).thenReturn(Mono.error(new Missing("Event does not exist")));
    when(store.submit(any())).thenReturn(Mono.empty());
    StepVerifier.create(service.buy("order-001", "missing", "customer", 2, false))
        .expectError(Missing.class)
        .verify();
  }

  @Test
  void reactiveQueriesReturnRepositoryValuesAndPropagateErrors() {
    Order current = order(OrderStatus.QUEUED, false, null);
    current(current);
    Audit audit = new Audit("audit", current.id(), "NONE", "QUEUED", now);
    when(store.events()).thenReturn(Flux.just(event));
    when(store.audit(current.id())).thenReturn(Flux.just(audit));
    StepVerifier.create(service.events()).expectNext(event).verifyComplete();
    StepVerifier.create(service.event("event")).expectNext(event).verifyComplete();
    StepVerifier.create(service.order(current.id())).expectNext(current).verifyComplete();
    StepVerifier.create(service.audit(current.id())).expectNext(audit).verifyComplete();
    when(store.order(current.id())).thenReturn(Mono.error(new Missing("Order does not exist")));
    StepVerifier.create(service.order(current.id())).expectError(Missing.class).verify();
  }

  @Test
  void reservationHasExactlyTenMinutesAndDoesNotSellTickets() {
    Order current = order(OrderStatus.QUEUED, false, null);
    current(current);
    StepVerifier.create(service.reserve(current.id())).verifyComplete();
    verify(store)
        .transition(
            current,
            OrderStatus.RESERVED,
            now.plusSeconds(600),
            TicketState.AVAILABLE,
            TicketState.RESERVED,
            false);
  }

  @Test
  void courtesyConsumesInventoryWithoutSaleOrDeadline() {
    Order current = order(OrderStatus.QUEUED, true, null);
    current(current);
    StepVerifier.create(service.reserve(current.id())).verifyComplete();
    verify(store)
        .transition(
            current,
            OrderStatus.COMPLIMENTARY,
            null,
            TicketState.AVAILABLE,
            TicketState.COMPLIMENTARY,
            false);
  }

  @Test
  void insufficientInventoryRejectsWithoutChangingCounters() {
    Order current = order(OrderStatus.QUEUED, false, null);
    current(current);
    when(store.event("event"))
        .thenReturn(
            Mono.just(new Event("event", "Concert", event.date(), "Venue", 10, 1, 9, 0, 0, 0)));
    StepVerifier.create(service.reserve(current.id())).verifyComplete();
    verify(store).transition(current, OrderStatus.REJECTED, null, null, null, false);
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"RESERVED", "SOLD", "COMPLIMENTARY", "EXPIRED", "REJECTED"})
  void duplicateDeliveryDoesNotChangeProcessedOrder(OrderStatus status) {
    Order current = order(status, false, null);
    current(current);
    StepVerifier.create(service.reserve(current.id())).verifyComplete();
    verify(store, never()).transition(any(), any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void confirmationPreservesDeadlineAndQueuesFinalization() {
    Order current = order(OrderStatus.RESERVED, false, now.plusSeconds(60));
    current(current);
    StepVerifier.create(service.confirm(current.id()))
        .assertNext(updated -> assertEquals(OrderStatus.PENDING_CONFIRMATION, updated.status()))
        .verifyComplete();
    verify(store)
        .transition(
            current,
            OrderStatus.PENDING_CONFIRMATION,
            current.expiresAt(),
            TicketState.RESERVED,
            TicketState.PENDING_CONFIRMATION,
            true);
  }

  @Test
  void pendingConsumerFinalizesOnlyBeforeDeadline() {
    Order current = order(OrderStatus.PENDING_CONFIRMATION, false, now.plusSeconds(1));
    current(current);
    StepVerifier.create(service.reserve(current.id())).verifyComplete();
    verify(store)
        .transition(
            current,
            OrderStatus.SOLD,
            null,
            TicketState.PENDING_CONFIRMATION,
            TicketState.SOLD,
            false);
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"SOLD", "PENDING_CONFIRMATION"})
  void repeatedConfirmationIsIdempotent(OrderStatus status) {
    Order current = order(status, false, now.plusSeconds(30));
    current(current);
    StepVerifier.create(service.confirm(current.id())).expectNext(current).verifyComplete();
    verify(store, never()).transition(any(), any(), any(), any(), any(), anyBoolean());
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"QUEUED", "EXPIRED", "REJECTED", "COMPLIMENTARY"})
  void invalidConfirmationFailsWithoutMutation(OrderStatus status) {
    Order current = order(status, false, null);
    current(current);
    StepVerifier.create(service.confirm(current.id())).expectError(Conflict.class).verify();
  }

  @Test
  void deadlineEqualityExpiresReservedAndPendingOrders() {
    Order reserved = order(OrderStatus.RESERVED, false, now);
    current(reserved);
    StepVerifier.create(service.confirm(reserved.id()))
        .assertNext(updated -> assertEquals(OrderStatus.EXPIRED, updated.status()))
        .verifyComplete();
    Order pending = order(OrderStatus.PENDING_CONFIRMATION, false, now);
    current(pending);
    StepVerifier.create(service.reserve(pending.id())).verifyComplete();
    verify(store)
        .transition(
            pending,
            OrderStatus.EXPIRED,
            null,
            TicketState.PENDING_CONFIRMATION,
            TicketState.AVAILABLE,
            false);
  }

  @Test
  void expirationRereadsCandidatesAndSkipsAlreadySoldAndUnexpiredOrders() {
    Order expired = order(OrderStatus.RESERVED, false, now.minusSeconds(1));
    Order pending =
        new Order(
            "pending", "event", "customer", 1, OrderStatus.PENDING_CONFIRMATION, now, now, false);
    Order sold = new Order("sold", "event", "customer", 1, OrderStatus.SOLD, null, now, false);
    Order renewed =
        new Order(
            "future", "event", "customer", 1, OrderStatus.RESERVED, now.plusSeconds(1), now, false);
    Order queued =
        new Order("queued", "event", "customer", 1, OrderStatus.QUEUED, null, now, false);
    for (Order candidate : new Order[] {expired, pending, sold, renewed, queued})
      current(candidate);
    when(store.expired(now)).thenReturn(Flux.just(expired, pending, sold, renewed, queued));
    StepVerifier.create(service.expire()).verifyComplete();
    verify(store)
        .transition(
            expired, OrderStatus.EXPIRED, null, TicketState.RESERVED, TicketState.AVAILABLE, false);
    verify(store)
        .transition(
            pending,
            OrderStatus.EXPIRED,
            null,
            TicketState.PENDING_CONFIRMATION,
            TicketState.AVAILABLE,
            false);
    verify(store, times(2))
        .transition(
            any(),
            any(),
            nullable(Instant.class),
            nullable(TicketState.class),
            nullable(TicketState.class),
            anyBoolean());
  }

  @Test
  void concurrentWriteFailureRereadsOrderBeforeRetrying() {
    Order queued = order(OrderStatus.QUEUED, false, null);
    current(queued);
    when(store.order(queued.id()))
        .thenReturn(Mono.error(new ConcurrentChange(new RuntimeException())), Mono.just(queued));
    StepVerifier.withVirtualTime(() -> service.reserve(queued.id()))
        .thenAwait(Duration.ofMinutes(1))
        .verifyComplete();
    verify(store, times(2)).order(queued.id());
  }

  @Test
  void temporaryFailureRetriesButEventuallyStops() {
    when(store.order("unavailable"))
        .thenReturn(Mono.error(new TransientStoreFailure(new RuntimeException())));
    StepVerifier.withVirtualTime(() -> service.reserve("unavailable"))
        .thenAwait(Duration.ofHours(1))
        .expectError(TransientStoreFailure.class)
        .verify();
    verify(store, times(9)).order("unavailable");
  }
}
