package br.com.delta.delta_api_mongo.support;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Records completed live requests, including concurrent requests, without logging payloads. */
public final class TimedHttpClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HttpResponse<String> send(HttpRequest request, HttpResponse.BodyHandler<String> handler) throws Exception {
        long start = System.nanoTime();
        return record(request, client.send(request, handler), start);
    }

    public CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<String> handler) {
        long start = System.nanoTime();
        return client.sendAsync(request, handler).thenApply(response -> record(request, response, start));
    }

    private HttpResponse<String> record(HttpRequest request, HttpResponse<String> response, long start) {
        System.out.printf(Locale.ROOT, "HTTP_TIMING method=%s target=%s path=%s status=%d elapsed_ms=%.3f%n",
                request.method(), request.uri().getAuthority(), request.uri().getPath(),
                response.statusCode(), (System.nanoTime() - start) / 1_000_000.0);
        return response;
    }
}
