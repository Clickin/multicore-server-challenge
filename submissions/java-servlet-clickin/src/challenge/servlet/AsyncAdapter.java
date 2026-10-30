package challenge.servlet;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

abstract class AsyncAdapter implements AsyncContext {
    public void addListener(AsyncListener p0, ServletRequest p1, ServletResponse p2) {
        throw new UnsupportedOperationException();
    }

    public void addListener(AsyncListener p0) {
        throw new UnsupportedOperationException();
    }

    public void complete() {
        throw new UnsupportedOperationException();
    }

    public AsyncListener createListener(Class p0) {
        throw new UnsupportedOperationException();
    }

    public void dispatch() {
        throw new UnsupportedOperationException();
    }

    public void dispatch(String p0) {
        throw new UnsupportedOperationException();
    }

    public void dispatch(ServletContext p0, String p1) {
        throw new UnsupportedOperationException();
    }

    public ServletRequest getRequest() {
        throw new UnsupportedOperationException();
    }

    public ServletResponse getResponse() {
        throw new UnsupportedOperationException();
    }

    public long getTimeout() {
        throw new UnsupportedOperationException();
    }

    public boolean hasOriginalRequestAndResponse() {
        throw new UnsupportedOperationException();
    }

    public void setTimeout(long p0) {
        throw new UnsupportedOperationException();
    }

    public void start(Runnable p0) {
        throw new UnsupportedOperationException();
    }
}
