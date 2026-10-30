package challenge.servlet;

abstract class AsyncAdapter implements jakarta.servlet.AsyncContext {
    public void addListener(jakarta.servlet.AsyncListener p0, jakarta.servlet.ServletRequest p1, jakarta.servlet.ServletResponse p2) { throw new UnsupportedOperationException(); }
    public void addListener(jakarta.servlet.AsyncListener p0) { throw new UnsupportedOperationException(); }
    public void complete() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.AsyncListener createListener(java.lang.Class p0) { throw new UnsupportedOperationException(); }
    public void dispatch() { throw new UnsupportedOperationException(); }
    public void dispatch(java.lang.String p0) { throw new UnsupportedOperationException(); }
    public void dispatch(jakarta.servlet.ServletContext p0, java.lang.String p1) { throw new UnsupportedOperationException(); }
    public jakarta.servlet.ServletRequest getRequest() { throw new UnsupportedOperationException(); }
    public jakarta.servlet.ServletResponse getResponse() { throw new UnsupportedOperationException(); }
    public long getTimeout() { throw new UnsupportedOperationException(); }
    public boolean hasOriginalRequestAndResponse() { throw new UnsupportedOperationException(); }
    public void setTimeout(long p0) { throw new UnsupportedOperationException(); }
    public void start(java.lang.Runnable p0) { throw new UnsupportedOperationException(); }
}
