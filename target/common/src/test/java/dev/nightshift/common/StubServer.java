package dev.nightshift.common;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Tiny programmable HTTP stub standing in for a downstream service in tests. */
public final class StubServer implements AutoCloseable {

    private final HttpServer server;
    private volatile int status = 201;
    private volatile String body = "{}";
    private volatile long delayMs = 0;
    private volatile int requests = 0;

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            requests++;
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubServer respond(int status, String body) {
        this.status = status;
        this.body = body;
        this.delayMs = 0;
        return this;
    }

    public StubServer delay(long ms) {
        this.delayMs = ms;
        return this;
    }

    public int requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
