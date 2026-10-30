package challenge.servlet;

abstract class RequestAdapter implements jakarta.servlet.http.HttpServletRequest {
    public boolean authenticate(jakarta.servlet.http.HttpServletResponse p0) { throw new UnsupportedOperationException(); }
    public java.lang.String changeSessionId() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.AsyncContext getAsyncContext() { throw new UnsupportedOperationException(); }
    public java.util.Enumeration getAttributeNames() { throw new UnsupportedOperationException(); }
    public java.lang.Object getAttribute(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.lang.String getAuthType() { throw new UnsupportedOperationException(); }
    public java.lang.String getCharacterEncoding() { throw new UnsupportedOperationException(); }
    public long getContentLengthLong() { throw new UnsupportedOperationException(); }
    public int getContentLength() { throw new UnsupportedOperationException(); }
    public java.lang.String getContentType() { throw new UnsupportedOperationException(); }
    public java.lang.String getContextPath() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.http.Cookie[] getCookies() { throw new UnsupportedOperationException(); }
    public long getDateHeader(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public jakarta.servlet.DispatcherType getDispatcherType() { throw new UnsupportedOperationException(); }
    public java.util.Enumeration getHeaderNames() { throw new UnsupportedOperationException(); }
    public java.lang.String getHeader(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.util.Enumeration getHeaders(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public jakarta.servlet.http.HttpServletMapping getHttpServletMapping() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.ServletInputStream getInputStream() { throw new UnsupportedOperationException(); }
    public int getIntHeader(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.lang.String getLocalAddr() { throw new UnsupportedOperationException(); }
    public java.lang.String getLocalName() { throw new UnsupportedOperationException(); }
    public int getLocalPort() { throw new UnsupportedOperationException(); }
    public java.util.Locale getLocale() { throw new UnsupportedOperationException(); }
    public java.util.Enumeration getLocales() { throw new UnsupportedOperationException(); }
    public java.lang.String getMethod() { throw new UnsupportedOperationException(); }
    public java.util.Map getParameterMap() { throw new UnsupportedOperationException(); }
    public java.util.Enumeration getParameterNames() { throw new UnsupportedOperationException(); }
    public java.lang.String[] getParameterValues(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.lang.String getParameter(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public jakarta.servlet.http.Part getPart(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.util.Collection getParts() { throw new UnsupportedOperationException(); }
    public java.lang.String getPathInfo() { throw new UnsupportedOperationException(); }
    public java.lang.String getPathTranslated() { throw new UnsupportedOperationException(); }
    public java.lang.String getProtocolRequestId() { throw new UnsupportedOperationException(); }
    public java.lang.String getProtocol() { throw new UnsupportedOperationException(); }
    public java.lang.String getQueryString() { throw new UnsupportedOperationException(); }
    public java.io.BufferedReader getReader() { throw new UnsupportedOperationException(); }
    public java.lang.String getRemoteAddr() { throw new UnsupportedOperationException(); }
    public java.lang.String getRemoteHost() { throw new UnsupportedOperationException(); }
    public int getRemotePort() { throw new UnsupportedOperationException(); }
    public java.lang.String getRemoteUser() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.RequestDispatcher getRequestDispatcher(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public java.lang.String getRequestId() { throw new UnsupportedOperationException(); }
    public java.lang.String getRequestURI() { throw new UnsupportedOperationException(); }
    public java.lang.StringBuffer getRequestURL() { throw new UnsupportedOperationException(); }
    public java.lang.String getRequestedSessionId() { throw new UnsupportedOperationException(); }
    public java.lang.String getScheme() { throw new UnsupportedOperationException(); }
    public java.lang.String getServerName() { throw new UnsupportedOperationException(); }
    public int getServerPort() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.ServletConnection getServletConnection() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.ServletContext getServletContext() { throw new UnsupportedOperationException(); }
    public java.lang.String getServletPath() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.http.HttpSession getSession() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.http.HttpSession getSession(boolean p0) { throw new UnsupportedOperationException(); }
    public java.util.Map getTrailerFields() { throw new UnsupportedOperationException(); }
    public java.security.Principal getUserPrincipal() { throw new UnsupportedOperationException(); }
    public boolean isAsyncStarted() { throw new UnsupportedOperationException(); }
    public boolean isAsyncSupported() { throw new UnsupportedOperationException(); }
    public boolean isRequestedSessionIdFromCookie() { throw new UnsupportedOperationException(); }
    public boolean isRequestedSessionIdFromURL() { throw new UnsupportedOperationException(); }
    public boolean isRequestedSessionIdValid() { throw new UnsupportedOperationException(); }
    public boolean isSecure() { throw new UnsupportedOperationException(); }
    public boolean isTrailerFieldsReady() { throw new UnsupportedOperationException(); }
    public boolean isUserInRole(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public void login(java.lang.String p0, java.lang.String p1) { throw new UnsupportedOperationException(); }
    public void logout() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.http.PushBuilder newPushBuilder() { throw new UnsupportedOperationException(); }
    public void removeAttribute(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public void setAttribute(java.lang.String p0, java.lang.Object p1) { throw new UnsupportedOperationException(); }
    public void setCharacterEncoding(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public void setCharacterEncoding(java.nio.charset.Charset p0) { throw new UnsupportedOperationException(); }
    public jakarta.servlet.AsyncContext startAsync() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest p0, jakarta.servlet.ServletResponse p1) { throw new UnsupportedOperationException(); }
    public jakarta.servlet.http.HttpUpgradeHandler upgrade(java.lang.Class p0) { throw new UnsupportedOperationException(); }
}
