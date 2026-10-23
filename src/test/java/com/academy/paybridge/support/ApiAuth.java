package com.academy.paybridge.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test helper: registers a throwaway customer over HTTP and returns a Bearer token for them. */
public final class ApiAuth {

    private ApiAuth() {}

    public static final String PASSWORD = "correct-horse-battery";

    /** A string field from a flat JSON body, or null. Good enough for these tests; avoids a JSON dependency. */
    public static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static HttpResponse<String> post(HttpClient http, int port, String path, String body, String token)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> get(HttpClient http, int port, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Any method, with a JSON body for the ones that carry one. */
    public static HttpResponse<String> request(HttpClient http, int port, String method, String path,
                                               String body, String token) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, publisher);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Like {@link #request} with an Idempotency-Key header, for the money-moving routes. */
    public static HttpResponse<String> request(HttpClient http, int port, String method, String path,
                                               String body, String token, String idempotencyKey) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .method(method, publisher);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static String registerBody(String email, String password, String fullName) {
        return String.format("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"%s\"}", email, password, fullName);
    }

    public static String loginBody(String email, String password) {
        return String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password);
    }

    public static String login(HttpClient http, int port, String email, String password) throws Exception {
        HttpResponse<String> r = post(http, port, "/auth/login", loginBody(email, password), null);
        if (r.statusCode() != 200) {
            throw new IllegalStateException("login failed: " + r.statusCode() + " " + r.body());
        }
        return field(r.body(), "accessToken");
    }

    /** Registers a new customer with a random email and returns their access token. */
    public static String customerToken(HttpClient http, int port) throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> r = post(http, port, "/auth/register", registerBody(email, PASSWORD, "Test User"), null);
        if (r.statusCode() != 201) {
            throw new IllegalStateException("register failed: " + r.statusCode() + " " + r.body());
        }
        return login(http, port, email, PASSWORD);
    }
}
