package org.example.apitester;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

public class ConcurrentApiTester {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final int concurrentRequests;
    private final int timeoutSeconds;
    private final int maxRetries;
    private final Map<String, String> defaultHeaders;

    public static class TestConfig {
        private final String baseUrl;
        private final int concurrentRequests;
        private final int timeoutSeconds;
        private final int maxRetries;
        private final Map<String, String> defaultHeaders;

        private TestConfig(Builder builder) {
            this.baseUrl = builder.baseUrl;
            this.concurrentRequests = builder.concurrentRequests;
            this.timeoutSeconds = builder.timeoutSeconds;
            this.maxRetries = builder.maxRetries;
            this.defaultHeaders = builder.defaultHeaders;
        }

        public static class Builder {
            private final String baseUrl;
            private int concurrentRequests = 10;
            private int timeoutSeconds = 30;
            private int maxRetries = 3;
            private Map<String, String> defaultHeaders = new HashMap<>();

            public Builder(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public Builder concurrentRequests(int val) {
                concurrentRequests = val;
                return this;
            }

            public Builder timeoutSeconds(int val) {
                timeoutSeconds = val;
                return this;
            }

            public Builder maxRetries(int val) {
                maxRetries = val;
                return this;
            }

            public Builder addDefaultHeader(String key, String value) {
                defaultHeaders.put(key, value);
                return this;
            }

            public TestConfig build() {
                return new TestConfig(this);
            }
        }
    }

    public static class RequestDetails {
        private final String endpoint;
        private final String method;
        private final Map<String, String> headers;
        private final String body;
        private final Predicate<TestResult> successCriteria;

        private RequestDetails(Builder builder) {
            this.endpoint = builder.endpoint;
            this.method = builder.method;
            this.headers = builder.headers;
            this.body = builder.body;
            this.successCriteria = builder.successCriteria;
        }

        public static class Builder {
            private String endpoint = "";
            private String method = "GET";
            private Map<String, String> headers = new HashMap<>();
            private String body = "";
            private Predicate<TestResult> successCriteria = r -> r.statusCode >= 200 && r.statusCode < 300;

            public Builder endpoint(String val) {
                endpoint = val;
                return this;
            }

            public Builder method(String val) {
                method = val;
                return this;
            }

            public Builder addHeader(String key, String value) {
                headers.put(key, value);
                return this;
            }

            public Builder body(String val) {
                body = val;
                return this;
            }

            public Builder successCriteria(Predicate<TestResult> val) {
                successCriteria = val;
                return this;
            }

            public RequestDetails build() {
                return new RequestDetails(this);
            }
        }
    }

    public static class TestResult {
        public final int statusCode;
        public final String body;
        public final long responseTimeMs;
        public final boolean isSuccess;
        public final int retryCount;
        public final String requestMethod;
        public final String requestUrl;
        public final Map<String, String> requestHeaders;
        public final String requestBody;
        public final Map<String, List<String>> responseHeaders;

        public TestResult(HttpResponse<String> response, long responseTimeMs, int retryCount,
                          String method, String url, Map<String, String> reqHeaders,
                          String reqBody, Predicate<TestResult> successCriteria) {
            this.statusCode = response.statusCode();
            this.body = response.body();
            this.responseTimeMs = responseTimeMs;
            this.retryCount = retryCount;
            this.requestMethod = method;
            this.requestUrl = url;
            this.requestHeaders = reqHeaders;
            this.requestBody = reqBody;
            this.responseHeaders = response.headers().map();
            this.isSuccess = successCriteria.test(this);
        }

        public TestResult(String errorBody, long responseTimeMs, int retryCount,
                          String method, String url, Map<String, String> reqHeaders, String reqBody) {
            this.statusCode = -1;
            this.body = errorBody;
            this.responseTimeMs = responseTimeMs;
            this.retryCount = retryCount;
            this.requestMethod = method;
            this.requestUrl = url;
            this.requestHeaders = reqHeaders;
            this.requestBody = reqBody;
            this.responseHeaders = new HashMap<>();
            this.isSuccess = false;
        }
    }

    public static class TestStatistics {
        public final int totalRequests;
        public final int successfulRequests;
        public final int failedRequests;
        public final double averageResponseTime;
        public final long minResponseTime;
        public final long maxResponseTime;
        public final double standardDeviation;
        //public final Map<Integer, Integer> statusCodeDistribution;
        //public final Map<Integer, Integer> retryDistribution;
        public final double percentile90;
        public final double percentile95;
        public final double percentile99;

        public TestStatistics(List<TestResult> results) {
            this.totalRequests = results.size();
            this.successfulRequests = (int) results.stream().filter(r -> r.isSuccess).count();
            this.failedRequests = totalRequests - successfulRequests;

            List<Long> responseTimes = results.stream()
                    .map(r -> r.responseTimeMs)
                    .sorted()
                    .toList();

            this.averageResponseTime = responseTimes.stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0.0);

            this.minResponseTime = responseTimes.get(0);
            this.maxResponseTime = responseTimes.get(responseTimes.size() - 1);

            double variance = responseTimes.stream()
                    .mapToDouble(rt -> Math.pow(rt - averageResponseTime, 2))
                    .average()
                    .orElse(0.0);
            this.standardDeviation = Math.sqrt(variance);

//            this.statusCodeDistribution = results.stream()
//                    .collect(HashMap::new,
//                            (map, result) -> map.merge(result.statusCode, 1, Integer::sum),
//                            HashMap::merge);
//
//            this.retryDistribution = results.stream()
//                    .collect(HashMap::new,
//                            (map, result) -> map.merge(result.retryCount, 1, Integer::sum),
//                            HashMap::merge);

            int size = responseTimes.size();
            this.percentile90 = responseTimes.get((int) (size * 0.9));
            this.percentile95 = responseTimes.get((int) (size * 0.95));
            this.percentile99 = responseTimes.get((int) (size * 0.99));
        }
    }

    public ConcurrentApiTester(TestConfig config) {
        this.baseUrl = config.baseUrl;
        this.concurrentRequests = config.concurrentRequests;
        this.timeoutSeconds = config.timeoutSeconds;
        this.maxRetries = config.maxRetries;
        this.defaultHeaders = config.defaultHeaders;

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .executor(executor)
                .build();
    }

    public List<TestResult> runTests(RequestDetails requestDetails) {
        List<CompletableFuture<TestResult>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentRequests; i++) {
            futures.add(makeRequest(requestDetails, 0));
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private CompletableFuture<TestResult> makeRequest(RequestDetails details, int retryCount) {
        String fullUrl = baseUrl + details.endpoint;
        Map<String, String> mergedHeaders = new HashMap<>(defaultHeaders);
        mergedHeaders.putAll(details.headers);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        mergedHeaders.forEach(requestBuilder::header);

        switch (details.method.toUpperCase()) {
            case "POST":
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(details.body));
                break;
            case "PUT":
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(details.body));
                break;
            case "DELETE":
                requestBuilder.DELETE();
                break;
            case "PATCH":
                requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(details.body));
                break;
            default:
                requestBuilder.GET();
        }

        HttpRequest request = requestBuilder.build();
        long startTime = System.currentTimeMillis();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new TestResult(
                        response,
                        System.currentTimeMillis() - startTime,
                        retryCount,
                        details.method,
                        fullUrl,
                        mergedHeaders,
                        details.body,
                        details.successCriteria
                ))
                .exceptionally(ex -> {
                    if (retryCount < maxRetries) {
                        return makeRequest(details, retryCount + 1).join();
                    }
                    return new TestResult(
                            "Error: " + ex.getMessage(),
                            System.currentTimeMillis() - startTime,
                            retryCount,
                            details.method,
                            fullUrl,
                            mergedHeaders,
                            details.body
                    );
                });
    }

    public void printResults(List<TestResult> results) {
        TestStatistics stats = new TestStatistics(results);

        System.out.println("\nTest Results Summary:");
        System.out.println("====================");
        System.out.printf("Total Requests: %d%n", stats.totalRequests);
        System.out.printf("Successful Requests: %d (%.2f%%)%n",
                stats.successfulRequests,
                (double) stats.successfulRequests / stats.totalRequests * 100);
        System.out.printf("Failed Requests: %d (%.2f%%)%n",
                stats.failedRequests,
                (double) stats.failedRequests / stats.totalRequests * 100);

        System.out.println("\nResponse Time Statistics:");
        System.out.println("========================");
        System.out.printf("Average Response Time: %.2fms%n", stats.averageResponseTime);
        System.out.printf("Minimum Response Time: %dms%n", stats.minResponseTime);
        System.out.printf("Maximum Response Time: %dms%n", stats.maxResponseTime);
        System.out.printf("Standard Deviation: %.2fms%n", stats.standardDeviation);
        System.out.printf("90th Percentile: %.2fms%n", stats.percentile90);
        System.out.printf("95th Percentile: %.2fms%n", stats.percentile95);
        System.out.printf("99th Percentile: %.2fms%n", stats.percentile99);

        System.out.println("\nStatus Code Distribution:");
        System.out.println("========================");
//        stats.statusCodeDistribution.forEach((code, count) ->
//                System.out.printf("Status %d: %d requests (%.2f%%)%n",
//                        code, count, (double) count / stats.totalRequests * 100));

        System.out.println("\nRetry Distribution:");
        System.out.println("==================");
//        stats.retryDistribution.forEach((retries, count) ->
//                System.out.printf("%d retries: %d requests (%.2f%%)%n",
//                        retries, count, (double) count / stats.totalRequests * 100));

        System.out.println("\nDetailed Results:");
        System.out.println("=================");
        for (int i = 0; i < results.size(); i++) {
            TestResult result = results.get(i);
            System.out.printf("\nRequest #%d:%n", i + 1);
            System.out.printf("URL: %s%n", result.requestUrl);
            System.out.printf("Method: %s%n", result.requestMethod);
            System.out.printf("Status Code: %d%n", result.statusCode);
            System.out.printf("Response Time: %dms%n", result.responseTimeMs);
            System.out.printf("Retry Count: %d%n", result.retryCount);
            System.out.println("Request Headers:");
            result.requestHeaders.forEach((key, value) ->
                    System.out.printf("  %s: %s%n", key, value));
            if (!result.requestBody.isEmpty()) {
                System.out.printf("Request Body: %s%n", result.requestBody);
            }
            System.out.println("Response Headers:");
            result.responseHeaders.forEach((key, values) ->
                    System.out.printf("  %s: %s%n", key, String.join(", ", values)));
            System.out.printf("Response Body: %s%n", result.body);
        }
    }

    public static void main(String[] args) {
        // Example usage with all new features
        TestConfig config = new TestConfig.Builder("http://localhost:8080")
                .concurrentRequests(10)
                .timeoutSeconds(30)
                .maxRetries(3)
                //.addDefaultHeader("Authorization", "Bearer your-token-here")
                .addDefaultHeader("Content-Type", "application/json")
                .build();

        ConcurrentApiTester tester = new ConcurrentApiTester(config);

        // Example POST request with custom success criteria
        RequestDetails postRequest = new RequestDetails.Builder()
                .endpoint("/tasks")
                .method("POST")
                //.body("{\"name\": \"John Doe\", \"email\": \"john@example.com\"}")
                .body("{\"taskId\": \"string\", \"description\": \"Concurrent API Test\", \"severity\": 1, \"assignee\": \"string\", \"storyPoint\": 1}")
                .addHeader("X-Custom-Header", "custom-value")
                .successCriteria(result ->
                        result.statusCode == 201 && result.body.contains("\"success\":true"))
                .build();

        List<TestResult> results = tester.runTests(postRequest);
        tester.printResults(results);
    }
}