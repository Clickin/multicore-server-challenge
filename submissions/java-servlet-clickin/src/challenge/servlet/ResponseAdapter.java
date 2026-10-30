package challenge.servlet;

abstract class ResponseAdapter implements jakarta.servlet.http.HttpServletResponse {
    public void addCookie(jakarta.servlet.http.Cookie p0) { throw new UnsupportedOperationException(); }
    public void addDateHeader(java.lang.String p0, long p1) { throw new UnsupportedOperationException(); }
    public void addHeader(java.lang.String p0, java.lang.String p1) { throw new UnsupportedOperationException(); }
    public void addIntHeader(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    public boolean containsHeader(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.lang.String encodeRedirectURL(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.lang.String encodeURL(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public void flushBuffer() { throw new UnsupportedOperationException(); }
    public int getBufferSize() { throw new UnsupportedOperationException(); }
    public java.lang.String getCharacterEncoding() { throw new UnsupportedOperationException(); }
    public java.lang.String getContentType() { throw new UnsupportedOperationException(); }
    public java.util.Collection getHeaderNames() { throw new UnsupportedOperationException(); }
    public java.lang.String getHeader(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.util.Collection getHeaders(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.util.Locale getLocale() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.ServletOutputStream getOutputStream() { throw new UnsupportedOperationException(); }
    public int getStatus() { throw new UnsupportedOperationException(); }
    public java.util.function.Supplier getTrailerFields() { throw new UnsupportedOperationException(); }
    public java.io.PrintWriter getWriter() { throw new UnsupportedOperationException(); }
    public boolean isCommitted() { throw new UnsupportedOperationException(); }
    public void resetBuffer() { throw new UnsupportedOperationException(); }
    public void reset() { throw new UnsupportedOperationException(); }
    public void sendError(int p0, java.lang.String p1) { throw new UnsupportedOperationException(); }
    public void sendError(int p0) { throw new UnsupportedOperationException(); }
    public void sendRedirect(java.lang.String p0, boolean p1) { throw new UnsupportedOperationException(); }
    public void sendRedirect(java.lang.String p0, int p1, boolean p2) { throw new UnsupportedOperationException(); }
    public void sendRedirect(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    public void sendRedirect(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public void setBufferSize(int p0) { throw new UnsupportedOperationException(); }
    public void setCharacterEncoding(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public void setCharacterEncoding(java.nio.charset.Charset p0) { throw new UnsupportedOperationException(); }
    public void setContentLengthLong(long p0) { throw new UnsupportedOperationException(); }
    public void setContentLength(int p0) { throw new UnsupportedOperationException(); }
    public void setContentType(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public void setDateHeader(java.lang.String p0, long p1) { throw new UnsupportedOperationException(); }
    public void setHeader(java.lang.String p0, java.lang.String p1) { throw new UnsupportedOperationException(); }
    public void setIntHeader(java.lang.String p0, int p1) { throw new UnsupportedOperationException(); }
    public void setLocale(java.util.Locale p0) { throw new UnsupportedOperationException(); }
    public void setStatus(int p0) { throw new UnsupportedOperationException(); }
    public void setTrailerFields(java.util.function.Supplier p0) { throw new UnsupportedOperationException(); }
}
