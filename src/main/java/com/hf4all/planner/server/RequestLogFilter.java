package com.hf4all.planner.server;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Logs one line per HTTP request once the handler has finished:
 * {@code METHOD path?query -> status (N ms) from ip}.
 *
 * <p>Installed on every context in {@link PlannerServer}, so the request
 * history lands in the same rotating JUL log ({@code target/planner-%g.log})
 * as the rest of the server's output.
 */
public final class RequestLogFilter extends Filter {

    private static final Logger LOG = Logger.getLogger(RequestLogFilter.class.getName());

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        long startNanos = System.nanoTime();
        int status = -1;
        try {
            chain.doFilter(exchange);
            // Valid once the handler has called sendResponseHeaders(); stays -1
            // if the handler threw before responding.
            status = exchange.getResponseCode();
        } finally {
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            String target = query == null ? path : path + "?" + query;
            String method = exchange.getRequestMethod();
            String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
            int finalStatus = status;
            LOG.info(() -> String.format("%s %s -> %d (%d ms) from %s",
                    method, target, finalStatus, millis, remote));
        }
    }

    @Override
    public String description() {
        return "Logs method, path, status, duration and client IP for each request.";
    }
}
