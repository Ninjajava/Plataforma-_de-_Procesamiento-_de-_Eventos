package com.example.ticketing.infrastructure;

import com.example.ticketing.application.TicketService;
import com.example.ticketing.application.TicketStore;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@org.springframework.context.annotation.Configuration
public class Configuration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  TicketService ticketService(TicketStore store, Clock clock) {
    return new TicketService(store, clock);
  }

  @Bean(destroyMethod = "close")
  DynamoDbAsyncClient dynamo(
      @Value("${database.endpoint}") String endpoint, @Value("${aws.region}") String region) {
    return DynamoDbAsyncClient.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }

  @Bean(destroyMethod = "close")
  SqsAsyncClient sqs(
      @Value("${queue.endpoint}") String endpoint, @Value("${aws.region}") String region) {
    return SqsAsyncClient.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }
}
