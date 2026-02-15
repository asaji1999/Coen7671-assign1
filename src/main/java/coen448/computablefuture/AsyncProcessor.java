package coen448.computablefuture.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class AsyncProcessor {

    
    public CompletableFuture<String> processAsync(List<Microservice> microservices, String message) {

        List<CompletableFuture<String>> futures = microservices.stream()
                .map(client -> client.retrieveAsync(message))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.joining(" ")));
    }

  
    public CompletableFuture<List<String>> processAsyncCompletionOrder(
            List<Microservice> microservices, String message) {

        List<String> completionOrder =
                Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> futures = microservices.stream()
                .map(ms -> ms.retrieveAsync(message)
                        .thenAccept(completionOrder::add))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> completionOrder);
    }

    // Fail-Fast Policy: ensures atomic failure semantics across all services
    public CompletableFuture<String> processAsyncFailFast(
            List<Microservice> services,
            List<String> messages
    ) {
        // Validate that services and messages lists are aligned before processing
        if (services.size() != messages.size()) {
            throw new IllegalArgumentException("services and messages must have the same size");
        }

        List<CompletableFuture<String>> futures = new ArrayList<>(services.size());
        for (int i = 0; i < services.size(); i++) {
            futures.add(services.get(i).retrieveAsync(messages.get(i)));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.joining(" ")));
    }

   
    public CompletableFuture<List<String>> processAsyncFailPartial(
            List<Microservice> services,
            List<String> messages
    ) {
        if (services.size() != messages.size()) {
            throw new IllegalArgumentException("services and messages must have the same size");
        }

        List<CompletableFuture<Optional<String>>> safeFutures = new ArrayList<>(services.size());
        for (int i = 0; i < services.size(); i++) {
            CompletableFuture<Optional<String>> safe =
                    services.get(i).retrieveAsync(messages.get(i))
                            .thenApply(Optional::of)
                            .exceptionally(ex -> Optional.empty());
            safeFutures.add(safe);
        }

        return CompletableFuture.allOf(safeFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> safeFutures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Optional::stream)
                        .collect(Collectors.toList()));
    }

   
    public CompletableFuture<String> processAsyncFailSoft(
            List<Microservice> services,
            List<String> messages,
            String fallbackValue
    ) {
        if (services.size() != messages.size()) {
            throw new IllegalArgumentException("services and messages must have the same size");
        }

        List<CompletableFuture<String>> safeFutures = new ArrayList<>(services.size());
        for (int i = 0; i < services.size(); i++) {
            CompletableFuture<String> safe =
                    services.get(i).retrieveAsync(messages.get(i))
                            .exceptionally(ex -> fallbackValue);
            safeFutures.add(safe);
        }

        return CompletableFuture.allOf(safeFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> safeFutures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.joining(" ")));
    }
}
