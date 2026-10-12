package dev.nightshift.common.http;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JSON client for one downstream service. Never throws for HTTP statuses or transport errors;
 * returns a {@link DownstreamResult} instead. The read timeout can be changed at runtime
 * (it is a config setting in the calling service).
 */
public class DownstreamClient {

    private final JdkClientHttpRequestFactory factory;
    private final RestClient client;

    public DownstreamClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.factory = new JdkClientHttpRequestFactory(http);
        this.factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void setReadTimeout(Duration readTimeout) {
        factory.setReadTimeout(readTimeout);
    }

    public DownstreamResult post(String uri, Object body) {
        return run(() -> client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> new DownstreamResult(res.getStatusCode().value(),
                        StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8), DownstreamResult.Failure.NONE, null)));
    }

    public DownstreamResult get(String uri, Object... vars) {
        return run(() -> client.get().uri(uri, vars)
                .exchange((req, res) -> new DownstreamResult(res.getStatusCode().value(),
                        StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8), DownstreamResult.Failure.NONE, null)));
    }

    private interface Call {
        DownstreamResult run();
    }

    private static DownstreamResult run(Call call) {
        try {
            return call.run();
        } catch (ResourceAccessException e) {
            return classify(e.getCause() != null ? e.getCause() : e);
        }
    }

    static DownstreamResult classify(Throwable cause) {
        if (cause instanceof HttpTimeoutException) {
            return new DownstreamResult(0, null, DownstreamResult.Failure.TIMEOUT, cause.getMessage());
        }
        if (cause instanceof ConnectException || cause instanceof IOException) {
            return new DownstreamResult(0, null, DownstreamResult.Failure.UNREACHABLE, cause.toString());
        }
        return new DownstreamResult(0, null, DownstreamResult.Failure.UNREACHABLE, String.valueOf(cause));
    }
}
