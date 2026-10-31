package challenge.servlet;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import challenge.JsonWriter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;

public final class Response extends ResponseAdapter {
    private final JsonWriter out = new JsonWriter();
    public int status = 200;
    public String contentType;
    public final Map<String, List<String>> headers = new LinkedHashMap<>();
    public Runnable finish;
    private Request request;
    private Container container;

    public void init(Request request, Container container) {
        this.request = request;
        this.container = container;
    }

    /**
     * The unwrapped container response behind any {@link
     * ServletResponseWrapper}s.
     */
    public static Response of(ServletResponse response) {
        while (response instanceof ServletResponseWrapper w) {
            response = w.getResponse();
        }
        return (Response) response;
    }

    /**
     * Clears the body and renders the error through the container's error
     * servlet (status and message as attributes).
     */
    public void sendError(int status, String message) throws IOException {
        out.reset();
        contentType = null;
        this.status = status;
        HttpServlet errors = container == null ? null : container.errorServlet();
        if (errors == null) {
            return;
        }
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message);
        try {
            errors.service(request, this);
        } catch (ServletException e) {
            throw new IOException(e);
        }
    }

    public void sendError(int status) throws IOException {
        sendError(status, null);
    }

    public boolean containsHeader(String name) {
        return name.equalsIgnoreCase("Content-Type") ? contentType != null : headers.keySet()
                .stream()
                .anyMatch(k -> k.equalsIgnoreCase(name));
    }

    public void setDateHeader(String name, long date) {
        setHeader(name, DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(date)
                .atOffset(ZoneOffset.UTC)));
    }

    public void addDateHeader(String name, long date) {
        addHeader(name, DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(date)
                .atOffset(ZoneOffset.UTC)));
    }

    public boolean isCommitted() {
        return false;
    }

    public JsonWriter jsonOut() {
        return out;
    }

    public void reset() {
        status = 200;
        contentType = null;
        headers.clear();
        out.reset();
        finish = null;
    }

    public void setStatus(int value) {
        status = value;
    }

    public int getStatus() {
        return status;
    }

    public void setContentType(String value) {
        contentType = value;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentLength(int length) {}

    public void setContentLengthLong(long length) {}

    public void setHeader(String name, String value) {
        if (name.equalsIgnoreCase("Content-Type")) {
            contentType = value;
            return;
        }
        if (name.equalsIgnoreCase("Content-Length")) {
            return;
        }
        headers.entrySet().removeIf(e -> e.getKey().equalsIgnoreCase(name));
        headers.put(name, new ArrayList<>(List.of(value)));
    }

    public void addHeader(String name, String value) {
        if (name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Content-Length")) {
            setHeader(name, value);
            return;
        }
        String key = headers.keySet()
                            .stream()
                            .filter(k -> k.equalsIgnoreCase(name))
                            .findFirst()
                            .orElse(name);
        headers.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
            public void write(int b) {
                out.put(b);
            }

            public void write(byte[] b, int off, int len) {
                out.raw(b, off, len);
            }

            public boolean isReady() {
                return true;
            }

            public void setWriteListener(WriteListener l) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
