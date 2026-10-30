package challenge.servlet;

import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import jakarta.servlet.http.PushBuilder;

abstract class RequestAdapter implements HttpServletRequest {
    public boolean authenticate(HttpServletResponse p0) {
        throw new UnsupportedOperationException();
    }

    public String changeSessionId() {
        throw new UnsupportedOperationException();
    }

    public AsyncContext getAsyncContext() {
        throw new UnsupportedOperationException();
    }

    public Enumeration getAttributeNames() {
        throw new UnsupportedOperationException();
    }

    public Object getAttribute(String p0) {
        throw new UnsupportedOperationException();
    }

    public String getAuthType() {
        throw new UnsupportedOperationException();
    }

    public String getCharacterEncoding() {
        throw new UnsupportedOperationException();
    }

    public long getContentLengthLong() {
        throw new UnsupportedOperationException();
    }

    public int getContentLength() {
        throw new UnsupportedOperationException();
    }

    public String getContentType() {
        throw new UnsupportedOperationException();
    }

    public String getContextPath() {
        throw new UnsupportedOperationException();
    }

    public Cookie[] getCookies() {
        throw new UnsupportedOperationException();
    }

    public long getDateHeader(String p0) {
        throw new UnsupportedOperationException();
    }

    public DispatcherType getDispatcherType() {
        throw new UnsupportedOperationException();
    }

    public Enumeration getHeaderNames() {
        throw new UnsupportedOperationException();
    }

    public String getHeader(String p0) {
        throw new UnsupportedOperationException();
    }

    public Enumeration getHeaders(String p0) {
        throw new UnsupportedOperationException();
    }

    public HttpServletMapping getHttpServletMapping() {
        throw new UnsupportedOperationException();
    }

    public ServletInputStream getInputStream() {
        throw new UnsupportedOperationException();
    }

    public int getIntHeader(String p0) {
        throw new UnsupportedOperationException();
    }

    public String getLocalAddr() {
        throw new UnsupportedOperationException();
    }

    public String getLocalName() {
        throw new UnsupportedOperationException();
    }

    public int getLocalPort() {
        throw new UnsupportedOperationException();
    }

    public Locale getLocale() {
        throw new UnsupportedOperationException();
    }

    public Enumeration getLocales() {
        throw new UnsupportedOperationException();
    }

    public String getMethod() {
        throw new UnsupportedOperationException();
    }

    public Map getParameterMap() {
        throw new UnsupportedOperationException();
    }

    public Enumeration getParameterNames() {
        throw new UnsupportedOperationException();
    }

    public String[] getParameterValues(String p0) {
        throw new UnsupportedOperationException();
    }

    public String getParameter(String p0) {
        throw new UnsupportedOperationException();
    }

    public Part getPart(String p0) {
        throw new UnsupportedOperationException();
    }

    public Collection getParts() {
        throw new UnsupportedOperationException();
    }

    public String getPathInfo() {
        throw new UnsupportedOperationException();
    }

    public String getPathTranslated() {
        throw new UnsupportedOperationException();
    }

    public String getProtocolRequestId() {
        throw new UnsupportedOperationException();
    }

    public String getProtocol() {
        throw new UnsupportedOperationException();
    }

    public String getQueryString() {
        throw new UnsupportedOperationException();
    }

    public BufferedReader getReader() {
        throw new UnsupportedOperationException();
    }

    public String getRemoteAddr() {
        throw new UnsupportedOperationException();
    }

    public String getRemoteHost() {
        throw new UnsupportedOperationException();
    }

    public int getRemotePort() {
        throw new UnsupportedOperationException();
    }

    public String getRemoteUser() {
        throw new UnsupportedOperationException();
    }

    public RequestDispatcher getRequestDispatcher(String p0) {
        throw new UnsupportedOperationException();
    }

    public String getRequestId() {
        throw new UnsupportedOperationException();
    }

    public String getRequestURI() {
        throw new UnsupportedOperationException();
    }

    public StringBuffer getRequestURL() {
        throw new UnsupportedOperationException();
    }

    public String getRequestedSessionId() {
        throw new UnsupportedOperationException();
    }

    public String getScheme() {
        throw new UnsupportedOperationException();
    }

    public String getServerName() {
        throw new UnsupportedOperationException();
    }

    public int getServerPort() {
        throw new UnsupportedOperationException();
    }

    public ServletConnection getServletConnection() {
        throw new UnsupportedOperationException();
    }

    public ServletContext getServletContext() {
        throw new UnsupportedOperationException();
    }

    public String getServletPath() {
        throw new UnsupportedOperationException();
    }

    public HttpSession getSession() {
        throw new UnsupportedOperationException();
    }

    public HttpSession getSession(boolean p0) {
        throw new UnsupportedOperationException();
    }

    public Map getTrailerFields() {
        throw new UnsupportedOperationException();
    }

    public Principal getUserPrincipal() {
        throw new UnsupportedOperationException();
    }

    public boolean isAsyncStarted() {
        throw new UnsupportedOperationException();
    }

    public boolean isAsyncSupported() {
        throw new UnsupportedOperationException();
    }

    public boolean isRequestedSessionIdFromCookie() {
        throw new UnsupportedOperationException();
    }

    public boolean isRequestedSessionIdFromURL() {
        throw new UnsupportedOperationException();
    }

    public boolean isRequestedSessionIdValid() {
        throw new UnsupportedOperationException();
    }

    public boolean isSecure() {
        throw new UnsupportedOperationException();
    }

    public boolean isTrailerFieldsReady() {
        throw new UnsupportedOperationException();
    }

    public boolean isUserInRole(String p0) {
        throw new UnsupportedOperationException();
    }

    public void login(String p0, String p1) {
        throw new UnsupportedOperationException();
    }

    public void logout() {
        throw new UnsupportedOperationException();
    }

    public PushBuilder newPushBuilder() {
        throw new UnsupportedOperationException();
    }

    public void removeAttribute(String p0) {
        throw new UnsupportedOperationException();
    }

    public void setAttribute(String p0, Object p1) {
        throw new UnsupportedOperationException();
    }

    public void setCharacterEncoding(String p0) {
        throw new UnsupportedOperationException();
    }

    public void setCharacterEncoding(Charset p0) {
        throw new UnsupportedOperationException();
    }

    public AsyncContext startAsync() {
        throw new UnsupportedOperationException();
    }

    public AsyncContext startAsync(ServletRequest p0, ServletResponse p1) {
        throw new UnsupportedOperationException();
    }

    public HttpUpgradeHandler upgrade(Class p0) {
        throw new UnsupportedOperationException();
    }
}
