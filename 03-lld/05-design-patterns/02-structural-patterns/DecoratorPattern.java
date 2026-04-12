import java.util.HashMap;
import java.util.Map;

// =============================================================================
// PATTERN: Decorator
// PURPOSE: Attach additional behavior to an object dynamically, at runtime.
//          Decorators provide a flexible alternative to subclassing for
//          extending functionality.
//
// REAL-WORLD ANALOGY:
//   A coffee order. You start with a base espresso. Add milk — one layer.
//   Add vanilla — another layer. Add whipped cream — another. Each addition
//   wraps the previous, adding to cost and description. You don't create
//   a separate EspressoWithMilkAndVanillaAndWhip class. You compose it by
//   layering wrappers at the time of ordering.
//
// THE PROBLEM THIS SOLVES:
//   Subclassing adds behavior to ALL instances of a class — not just specific
//   ones. And combinations explode:
//     LoggedHandler, AuthHandler, RateLimitedHandler,
//     LoggedAuthHandler, LoggedRateLimitedHandler,
//     AuthRateLimitedHandler, LoggedAuthRateLimitedHandler...
//   Decorator lets you compose any combination at runtime with no new classes.
//
// THE CRITICAL INSIGHT:
//   A Decorator IS a component AND HAS a component.
//   This double role is what enables stacking:
//   new Logging(new RateLimit(new Auth(new CoreHandler())))
//
// FOUR INGREDIENTS:
//   1. Component interface   → the common contract (HttpRequestHandler)
//   2. Concrete component    → the base object with core logic (OrderHandler)
//   3. Abstract decorator    → implements the interface, holds a wrapped component
//   4. Concrete decorators   → each adds exactly ONE concern
// =============================================================================

public class DecoratorPattern {

    // =========================================================================
    // SUPPORTING DATA CLASSES
    // =========================================================================

    static class HttpRequest {
        private final String method;
        private final String path;
        private final String body;
        private final Map<String, String> headers = new HashMap<>();

        public HttpRequest(String method, String path, String body) {
            this.method = method;
            this.path   = path;
            this.body   = body;
        }

        public void addHeader(String key, String value) { headers.put(key, value); }
        public String getHeader(String key)             { return headers.getOrDefault(key, ""); }
        public String getMethod()                       { return method; }
        public String getPath()                         { return path; }
        public String getBody()                         { return body; }
    }

    static class HttpResponse {
        private final int statusCode;
        private final String body;

        public HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body       = body;
        }

        public int getStatusCode() { return statusCode; }
        public String getBody()    { return body; }

        @Override
        public String toString() {
            return "HttpResponse{status=" + statusCode + ", body='" + body + "'}";
        }
    }


    // =========================================================================
    // STEP 1: COMPONENT INTERFACE
    // This is the contract. Both the real handler AND every decorator
    // must implement this. That's what makes them interchangeable and stackable.
    // =========================================================================
    interface HttpRequestHandler {
        HttpResponse handle(HttpRequest request);
    }


    // =========================================================================
    // STEP 2: CONCRETE COMPONENT — the core business logic
    // This is the actual work. It knows nothing about logging, auth, or rate
    // limiting. Its only job is to handle the order business logic.
    // =========================================================================
    static class OrderRequestHandler implements HttpRequestHandler {
        @Override
        public HttpResponse handle(HttpRequest request) {
            System.out.println("    [OrderHandler] Executing business logic for "
                    + request.getMethod() + " " + request.getPath());
            // Simulate creating an order
            String responseBody = "{\"orderId\": \"ORD-" + System.currentTimeMillis()
                    + "\", \"status\": \"created\"}";
            return new HttpResponse(200, responseBody);
        }
    }

    // Another concrete component — shows decorators work with any handler
    static class ProductRequestHandler implements HttpRequestHandler {
        @Override
        public HttpResponse handle(HttpRequest request) {
            System.out.println("    [ProductHandler] Fetching product catalog for "
                    + request.getPath());
            return new HttpResponse(200, "{\"products\": [\"laptop\", \"phone\", \"tablet\"]}");
        }
    }


    // =========================================================================
    // STEP 3: ABSTRACT DECORATOR — the structural key
    //
    // This class has a dual role:
    //   - It IS an HttpRequestHandler (implements the interface)
    //   - It HAS an HttpRequestHandler (wraps one inside it)
    //
    // This double role is what allows:
    //   new LoggingDecorator(new AuthDecorator(new OrderHandler()))
    //   Because LoggingDecorator wraps anything that IS an HttpRequestHandler,
    //   and AuthDecorator IS an HttpRequestHandler — it can be wrapped!
    //
    // Default behavior: just delegate to the wrapped handler.
    // Concrete decorators override handle() to add behavior before/after.
    // =========================================================================
    static abstract class RequestHandlerDecorator implements HttpRequestHandler {
        protected final HttpRequestHandler wrapped; // the thing being decorated

        protected RequestHandlerDecorator(HttpRequestHandler wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            // Default: pure delegation — subclasses add their behavior
            return wrapped.handle(request);
        }
    }


    // =========================================================================
    // STEP 4: CONCRETE DECORATORS — each adds exactly one concern
    //
    // RULE: Each decorator does ONE thing. Not two. Not three. One.
    // This keeps them independently testable and freely combinable.
    // =========================================================================

    // ── Decorator 1: Logging ──────────────────────────────────────────────────
    // Logs the request going in and the response coming out, with timing.
    // It doesn't know (or care) about auth, rate limiting, or business logic.
    static class LoggingDecorator extends RequestHandlerDecorator {

        public LoggingDecorator(HttpRequestHandler wrapped) {
            super(wrapped);
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            long startTime = System.currentTimeMillis();
            System.out.println("  [Logger] ──► " + request.getMethod() + " "
                    + request.getPath() + " | client: " + request.getHeader("X-Client-Id"));

            // Delegate to whatever is wrapped (could be auth, rate limiter, or the real handler)
            HttpResponse response = wrapped.handle(request);

            long duration = System.currentTimeMillis() - startTime;
            System.out.println("  [Logger] ◄── Status: " + response.getStatusCode()
                    + " | " + duration + "ms");
            return response;
        }
    }


    // ── Decorator 2: Authentication ───────────────────────────────────────────
    // Validates the Authorization header. Short-circuits with 401 if invalid.
    // If auth passes, delegates to the next handler in the chain.
    static class AuthenticationDecorator extends RequestHandlerDecorator {
        private static final String VALID_TOKEN = "Bearer valid-token-abc123";

        public AuthenticationDecorator(HttpRequestHandler wrapped) {
            super(wrapped);
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            String authHeader = request.getHeader("Authorization");

            if (!VALID_TOKEN.equals(authHeader)) {
                // SHORT-CIRCUIT: don't delegate further. The chain stops here.
                System.out.println("  [Auth] ✗ Rejected — invalid or missing token");
                return new HttpResponse(401, "{\"error\": \"Unauthorized\"}");
            }

            System.out.println("  [Auth] ✓ Token validated");
            return wrapped.handle(request); // auth passed — continue down the chain
        }
    }


    // ── Decorator 3: Rate Limiting ────────────────────────────────────────────
    // Tracks how many requests each client has made. Rejects if over the limit.
    // Each instance has its own counter state — decorators can hold state.
    static class RateLimitingDecorator extends RequestHandlerDecorator {
        private final Map<String, Integer> requestCounts = new HashMap<>();
        private final int maxRequestsPerSession;

        public RateLimitingDecorator(HttpRequestHandler wrapped, int maxRequestsPerSession) {
            super(wrapped);
            this.maxRequestsPerSession = maxRequestsPerSession;
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            String clientId = request.getHeader("X-Client-Id");
            if (clientId == null || clientId.isEmpty()) {
                clientId = "anonymous";
            }

            int count = requestCounts.getOrDefault(clientId, 0);

            if (count >= maxRequestsPerSession) {
                // SHORT-CIRCUIT: client has exceeded the rate limit
                System.out.println("  [RateLimit] ✗ Client '" + clientId
                        + "' exceeded limit of " + maxRequestsPerSession + " requests");
                return new HttpResponse(429, "{\"error\": \"Too Many Requests\"}");
            }

            requestCounts.put(clientId, count + 1);
            System.out.println("  [RateLimit] ✓ Client '" + clientId
                    + "' — request " + (count + 1) + "/" + maxRequestsPerSession);
            return wrapped.handle(request);
        }
    }


    // ── Decorator 4: Compression ──────────────────────────────────────────────
    // Compresses the response body before returning it to the client.
    // It only adds behavior AFTER delegating — doesn't touch the request at all.
    static class CompressionDecorator extends RequestHandlerDecorator {

        public CompressionDecorator(HttpRequestHandler wrapped) {
            super(wrapped);
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            // Delegate first — get the real response
            HttpResponse response = wrapped.handle(request);

            // Then add compression behavior on the way back out
            String originalBody  = response.getBody();
            String compressedBody = "[gzip | original: " + originalBody.length()
                    + " bytes → compressed: " + (originalBody.length() / 3) + " bytes]";
            System.out.println("  [Compression] Response compressed "
                    + originalBody.length() + "b → " + (originalBody.length() / 3) + "b");
            return new HttpResponse(response.getStatusCode(), compressedBody);
        }
    }


    // ── Decorator 5: Caching ──────────────────────────────────────────────────
    // Caches GET responses. On a cache hit, returns immediately without
    // calling the real handler at all. Demonstrates decorators can SKIP
    // delegation entirely for certain conditions.
    static class CachingDecorator extends RequestHandlerDecorator {
        private final Map<String, HttpResponse> cache = new HashMap<>();

        public CachingDecorator(HttpRequestHandler wrapped) {
            super(wrapped);
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            // Only cache GET requests
            if (!"GET".equalsIgnoreCase(request.getMethod())) {
                return wrapped.handle(request);
            }

            String cacheKey = request.getPath();
            if (cache.containsKey(cacheKey)) {
                System.out.println("  [Cache] ✓ Cache HIT for " + cacheKey + " — skipping handler");
                return cache.get(cacheKey); // return cached response — no delegation!
            }

            System.out.println("  [Cache] ✗ Cache MISS for " + cacheKey + " — calling handler");
            HttpResponse response = wrapped.handle(request); // delegate on cache miss
            cache.put(cacheKey, response); // store in cache for next time
            return response;
        }
    }


    // =========================================================================
    // MAIN — demonstrates decorator composition
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Decorator Pattern Demo ===\n");

        // ── Example 1: Full pipeline with all decorators stacked ──────────────
        System.out.println("─── Pipeline: Logging → RateLimit → Auth → Compression → Handler ───");
        System.out.println("(Outermost runs first. Each layer decides to delegate or short-circuit.)\n");

        HttpRequestHandler fullPipeline =
                new LoggingDecorator(               // Layer 4: outermost, runs first and last
                  new RateLimitingDecorator(         // Layer 3
                    new AuthenticationDecorator(     // Layer 2
                      new CompressionDecorator(      // Layer 1: runs just before the core handler
                        new OrderRequestHandler()    // Core: runs deepest
                      )
                    ), 3                             // max 3 requests per client
                  )
                );

        // Request 1: valid, authenticated — should succeed
        System.out.println(">>> Request 1: Valid request with auth token");
        HttpRequest req1 = new HttpRequest("POST", "/api/orders", "{\"product\": \"laptop\"}");
        req1.addHeader("Authorization", "Bearer valid-token-abc123");
        req1.addHeader("X-Client-Id", "client-A");
        System.out.println("<<< Response: " + fullPipeline.handle(req1));

        // Request 2: missing auth token — should be rejected at the Auth layer
        System.out.println("\n>>> Request 2: Missing auth token");
        HttpRequest req2 = new HttpRequest("POST", "/api/orders", "{\"product\": \"phone\"}");
        req2.addHeader("X-Client-Id", "client-B");
        System.out.println("<<< Response: " + fullPipeline.handle(req2));

        // Requests 3–5 from same client to trigger rate limiting
        System.out.println("\n>>> Requests 3–5: Same client, hammering the endpoint");
        for (int i = 3; i <= 5; i++) {
            System.out.println("\n  Attempt " + i + ":");
            HttpRequest req = new HttpRequest("POST", "/api/orders", "{}");
            req.addHeader("Authorization", "Bearer valid-token-abc123");
            req.addHeader("X-Client-Id", "client-A"); // same client — counter increments
            System.out.println("<<< Response: " + fullPipeline.handle(req));
        }


        // ── Example 2: Lightweight pipeline — only logging, no auth ───────────
        System.out.println("\n\n─── Lightweight Pipeline: Logging → Handler only ────────────────");
        System.out.println("(Same decorators, different combination — no new classes needed.)\n");

        HttpRequestHandler lightPipeline =
                new LoggingDecorator(
                  new ProductRequestHandler() // different base handler too
                );

        HttpRequest productReq = new HttpRequest("GET", "/api/products", "");
        productReq.addHeader("X-Client-Id", "client-C");
        System.out.println(">>> Request: GET /api/products");
        System.out.println("<<< Response: " + lightPipeline.handle(productReq));


        // ── Example 3: Caching decorator — shows skipping delegation ──────────
        System.out.println("\n\n─── Caching Pipeline: Logging → Cache → Handler ─────────────────");
        System.out.println("(Second identical GET request should be served from cache.)\n");

        HttpRequestHandler cachedPipeline =
                new LoggingDecorator(
                  new CachingDecorator(
                    new ProductRequestHandler()
                  )
                );

        HttpRequest cacheReq1 = new HttpRequest("GET", "/api/products", "");
        cacheReq1.addHeader("X-Client-Id", "client-D");
        System.out.println(">>> Request 1 (cache miss expected):");
        cachedPipeline.handle(cacheReq1);

        System.out.println("\n>>> Request 2 (cache hit expected — handler NOT called):");
        HttpRequest cacheReq2 = new HttpRequest("GET", "/api/products", "");
        cacheReq2.addHeader("X-Client-Id", "client-D");
        cachedPipeline.handle(cacheReq2);


        System.out.println("\n\n=== Key Takeaways ===");
        System.out.println("  1. Each decorator does ONE thing — independently testable");
        System.out.println("  2. Outermost decorator runs first (and last on the way back)");
        System.out.println("  3. Decorators can short-circuit (Auth, RateLimit) or wrap both sides (Logger, Compression)");
        System.out.println("  4. Decorators can hold state (RateLimitingDecorator's requestCounts)");
        System.out.println("  5. Adding a new concern = one new class, zero changes to existing code");
        System.out.println("  6. Java I/O (BufferedReader, GZIPOutputStream) is built exactly this way");
    }
}
