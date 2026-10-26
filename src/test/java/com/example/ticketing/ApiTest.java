package com.example.ticketing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ticketing.application.TicketService;
import com.example.ticketing.domain.Model.*;
import com.example.ticketing.domain.Model.Order;
import com.example.ticketing.infrastructure.Api;
import com.example.ticketing.infrastructure.Errors;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.*;

class ApiTest {
  private TicketService service;
  private WebTestClient client;
  private final Instant date = Instant.parse("2099-01-01T00:00:00Z");
  private final Event event = new Event("event", "Concert", date, "Venue", 10, 10, 0, 0, 0, 0);
  private final String purchase =
      "{\"eventId\":\"event\",\"customerId\":\"customer\",\"quantity\":2}";

  @BeforeEach
  void setup() {
    service = mock(TicketService.class);
    client = bind("secret");
  }

  private WebTestClient bind(String secret) {
    return WebTestClient.bindToController(new Api(service, secret))
        .controllerAdvice(new Errors())
        .build();
  }

  private Order order(OrderStatus status) {
    return new Order("order-001", "event", "customer", 2, status, null, date, false);
  }

  @Test
  void eventEndpointsSerializeReactiveResponses() {
    when(service.create("Concert", date, "Venue", 10)).thenReturn(Mono.just(event));
    when(service.events()).thenReturn(Flux.just(event));
    when(service.event("event")).thenReturn(Mono.just(event));
    client
        .post()
        .uri("/api/events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"name\":\"Concert\",\"date\":\"2099-01-01T00:00:00Z\",\"venue\":\"Venue\",\"capacity\":10}")
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.available")
        .isEqualTo(10);
    client
        .get()
        .uri("/api/events")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].name")
        .isEqualTo("Concert");
    client
        .get()
        .uri("/api/events/event/availability")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.sold")
        .isEqualTo(0);
  }

  @Test
  void purchaseReturnsAcceptedAndStatusHasNoTicketBeforeReservation() {
    when(service.buy("order-001", "event", "customer", 2, false))
        .thenReturn(Mono.just(order(OrderStatus.QUEUED)));
    when(service.order("order-001")).thenReturn(Mono.just(order(OrderStatus.QUEUED)));
    client
        .post()
        .uri("/api/orders")
        .header("Idempotency-Key", "order-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(purchase)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.order.id")
        .isEqualTo("order-001");
    client
        .get()
        .uri("/api/orders/order-001")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.ticketState")
        .isEmpty();
  }

  @Test
  void confirmAndAuditEndpointsExposeStateAndHistory() {
    when(service.confirm("order-001"))
        .thenReturn(Mono.just(order(OrderStatus.PENDING_CONFIRMATION)));
    when(service.audit("order-001"))
        .thenReturn(
            Flux.just(new Audit("audit", "order-001", "RESERVED", "PENDING_CONFIRMATION", date)));
    client
        .post()
        .uri("/api/orders/order-001/confirm")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.ticketState")
        .isEqualTo("PENDING_CONFIRMATION");
    client
        .get()
        .uri("/api/orders/order-001/audit")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].to")
        .isEqualTo("PENDING_CONFIRMATION");
  }

  @Test
  void courtesyRequiresCorrectConfiguredSecret() {
    when(service.buy("order-001", "event", "customer", 2, true))
        .thenReturn(Mono.just(order(OrderStatus.COMPLIMENTARY)));
    client
        .post()
        .uri("/api/complimentary-orders")
        .header("X-Admin-Key", "secret")
        .header("Idempotency-Key", "order-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(purchase)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.ticketState")
        .isEqualTo("COMPLIMENTARY");
    client
        .post()
        .uri("/api/complimentary-orders")
        .header("X-Admin-Key", "wrong")
        .header("Idempotency-Key", "order-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(purchase)
        .exchange()
        .expectStatus()
        .isForbidden();
    bind("")
        .post()
        .uri("/api/complimentary-orders")
        .header("X-Admin-Key", "secret")
        .header("Idempotency-Key", "order-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(purchase)
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void malformedIdempotencyKeysAreRejectedBeforeServiceInvocation() {
    client
        .post()
        .uri("/api/orders")
        .header("Idempotency-Key", "short")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(purchase)
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .post()
        .uri("/api/complimentary-orders")
        .header("X-Admin-Key", "secret")
        .header("Idempotency-Key", "short")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(purchase)
        .exchange()
        .expectStatus()
        .isBadRequest();
    verifyNoInteractions(service);
  }

  @Test
  void beanValidationAndRequiredHeadersRejectInvalidRequests() {
    client
        .post()
        .uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(purchase)
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .post()
        .uri("/api/orders")
        .header("Idempotency-Key", "order-001")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"eventId\":\"event\",\"customerId\":\"customer\",\"quantity\":21}")
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .post()
        .uri("/api/events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"name\":\"\",\"date\":\"2000-01-01T00:00:00Z\",\"venue\":\"\",\"capacity\":0}")
        .exchange()
        .expectStatus()
        .isBadRequest();
    verifyNoInteractions(service);
  }

  @Test
  void domainErrorsMapToProblemDetails() {
    when(service.order("missing")).thenReturn(Mono.error(new Missing("Order does not exist")));
    when(service.confirm("expired")).thenReturn(Mono.error(new Conflict("Reservation expired")));
    client
        .get()
        .uri("/api/orders/missing")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(404);
    client
        .post()
        .uri("/api/orders/expired/confirm")
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.detail")
        .isEqualTo("Reservation expired");
  }
}
