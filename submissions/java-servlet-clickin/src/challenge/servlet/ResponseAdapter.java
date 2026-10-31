package challenge.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Supplier;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

abstract class ResponseAdapter implements HttpServletResponse {
    public void addCookie(Cookie p0) {
        throw new UnsupportedOperationException();
    }

    public void addDateHeader(String p0, long p1) {
        throw new UnsupportedOperationException();
    }

    public void addHeader(String p0, String p1) {
        throw new UnsupportedOperationException();
    }

    public void addIntHeader(String p0, int p1) {
        throw new UnsupportedOperationException();
    }

    public boolean containsHeader(String p0) {
        throw new UnsupportedOperationException();
    }

    public String encodeRedirectURL(String p0) {
        throw new UnsupportedOperationException();
    }

    public String encodeURL(String p0) {
        throw new UnsupportedOperationException();
    }

    public void flushBuffer() {
        throw new UnsupportedOperationException();
    }

    public int getBufferSize() {
        throw new UnsupportedOperationException();
    }

    public String getCharacterEncoding() {
        throw new UnsupportedOperationException();
    }

    public String getContentType() {
        throw new UnsupportedOperationException();
    }

    public Collection getHeaderNames() {
        throw new UnsupportedOperationException();
    }

    public String getHeader(String p0) {
        throw new UnsupportedOperationException();
    }

    public Collection getHeaders(String p0) {
        throw new UnsupportedOperationException();
    }

    public Locale getLocale() {
        throw new UnsupportedOperationException();
    }

    public ServletOutputStream getOutputStream() {
        throw new UnsupportedOperationException();
    }

    public int getStatus() {
        throw new UnsupportedOperationException();
    }

    public Supplier getTrailerFields() {
        throw new UnsupportedOperationException();
    }

    public PrintWriter getWriter() {
        throw new UnsupportedOperationException();
    }

    public boolean isCommitted() {
        throw new UnsupportedOperationException();
    }

    public void resetBuffer() {
        throw new UnsupportedOperationException();
    }

    public void reset() {
        throw new UnsupportedOperationException();
    }

    public void sendError(int p0, String p1) throws IOException {
        throw new UnsupportedOperationException();
    }

    public void sendError(int p0) throws IOException {
        throw new UnsupportedOperationException();
    }

    public void sendRedirect(String p0, boolean p1) {
        throw new UnsupportedOperationException();
    }

    public void sendRedirect(String p0, int p1, boolean p2) {
        throw new UnsupportedOperationException();
    }

    public void sendRedirect(String p0, int p1) {
        throw new UnsupportedOperationException();
    }

    public void sendRedirect(String p0) {
        throw new UnsupportedOperationException();
    }

    public void setBufferSize(int p0) {
        throw new UnsupportedOperationException();
    }

    public void setCharacterEncoding(String p0) {
        throw new UnsupportedOperationException();
    }

    public void setCharacterEncoding(Charset p0) {
        throw new UnsupportedOperationException();
    }

    public void setContentLengthLong(long p0) {
        throw new UnsupportedOperationException();
    }

    public void setContentLength(int p0) {
        throw new UnsupportedOperationException();
    }

    public void setContentType(String p0) {
        throw new UnsupportedOperationException();
    }

    public void setDateHeader(String p0, long p1) {
        throw new UnsupportedOperationException();
    }

    public void setHeader(String p0, String p1) {
        throw new UnsupportedOperationException();
    }

    public void setIntHeader(String p0, int p1) {
        throw new UnsupportedOperationException();
    }

    public void setLocale(Locale p0) {
        throw new UnsupportedOperationException();
    }

    public void setStatus(int p0) {
        throw new UnsupportedOperationException();
    }

    public void setTrailerFields(Supplier p0) {
        throw new UnsupportedOperationException();
    }
}
