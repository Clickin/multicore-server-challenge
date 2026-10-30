package challenge.servlet;

import challenge.http.Loop;
import jakarta.servlet.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Request extends RequestAdapter {
    public byte[] data;
    public int pathStart, pathEnd, queryStart, queryEnd, authStart = -1, authEnd, bodyStart, bodyLength;
    public boolean gzip, close, multipleAuthorization;
    private int[] headerOffsets = new int[64];
    private int headerCount;
    private final Map<String,Object> attributes = new HashMap<>();
    private Map<String,String[]> parameters;
    private final Loop loop;
    private Response response;
    private Async async;
    private boolean started;
    public final int[] segmentStarts = new int[6], segmentEnds = new int[6];
    public Object applicationSlot;
    public Request(Loop loop) { this.loop = loop; }
    public void init(Response response) { this.response = response; this.async = new Async(this, response); }
    public void reset() { data = null; headerCount = 0; attributes.clear(); parameters = null; started = false; authStart = -1; gzip = close = multipleAuthorization = false; }
    public void header(int nameStart, int nameEnd, int valueStart, int valueEnd) {
        if (headerCount + 4 > headerOffsets.length) headerOffsets = Arrays.copyOf(headerOffsets, headerOffsets.length * 2);
        headerOffsets[headerCount++] = nameStart; headerOffsets[headerCount++] = nameEnd;
        headerOffsets[headerCount++] = valueStart; headerOffsets[headerCount++] = valueEnd;
    }
    public Loop loop() { return loop; }
    public byte[] bytes() { return data; }
    public int pathOffset() { return pathStart; }
    public int pathLength() { return pathEnd-pathStart; }
    public int queryOffset() { return queryStart; }
    public int queryLength() { return queryEnd-queryStart; }
    public int authorizationOffset() { return authStart; }
    public int authorizationLength() { return authStart < 0 ? 0 : authEnd-authStart; }
    public int bodyOffset() { return bodyStart; }
    public int bodyLength() { return bodyLength; }
    public String getMethod() { return new String(data,0,pathStart-1,StandardCharsets.ISO_8859_1); }
    public String getRequestURI() { return new String(data,pathStart,pathEnd-pathStart,StandardCharsets.ISO_8859_1); }
    public String getQueryString() { return queryStart < 0 ? null : new String(data,queryStart,queryEnd-queryStart,StandardCharsets.ISO_8859_1); }
    public String getHeader(String name) {
        String result=null;
        for(int i=0;i<headerCount;i+=4) {
            int a=headerOffsets[i], z=headerOffsets[i+1];
            if(z-a!=name.length())continue;
            boolean match=true; for(int j=0;j<name.length();j++) if(Character.toLowerCase((char)(data[a+j]&255))!=Character.toLowerCase(name.charAt(j))){match=false;break;}
            if(match) { String value=new String(data,headerOffsets[i+2],headerOffsets[i+3]-headerOffsets[i+2],StandardCharsets.ISO_8859_1); result=result==null?value:result+", "+value; }
        }
        return result;
    }
    public int getContentLength() { return bodyLength; }
    public long getContentLengthLong() { return bodyLength; }
    public Object getAttribute(String name) { return attributes.get(name); }
    public void setAttribute(String name,Object value) { if(value==null) attributes.remove(name); else attributes.put(name,value); }
    public void removeAttribute(String name) { attributes.remove(name); }
    public Enumeration<String> getAttributeNames() { return Collections.enumeration(attributes.keySet()); }
    public String getParameter(String name) { String[] v=getParameterMap().get(name); return v==null?null:v[0]; }
    public String[] getParameterValues(String name) { return getParameterMap().get(name); }
    public Enumeration<String> getParameterNames() { return Collections.enumeration(getParameterMap().keySet()); }
    public Map<String,String[]> getParameterMap() {
        if(parameters==null) { parameters=new LinkedHashMap<>(); String q=getQueryString(); if(q!=null&&!q.isEmpty()) for(String pair:q.split("&",-1)) { int i=pair.indexOf('='); String k=URLDecoder.decode(i<0?pair:pair.substring(0,i),StandardCharsets.UTF_8); String v=URLDecoder.decode(i<0?"":pair.substring(i+1),StandardCharsets.UTF_8); parameters.compute(k,(x,old)->{ if(old==null)return new String[]{v}; String[] n=Arrays.copyOf(old,old.length+1); n[old.length]=v; return n; }); } }
        return Collections.unmodifiableMap(parameters);
    }
    public ServletInputStream getInputStream() { return new ServletInputStream() { int pos; public int read() { return pos<bodyLength ? data[bodyStart+pos++]&255 : -1; } public boolean isFinished(){return pos>=bodyLength;} public boolean isReady(){return true;} public void setReadListener(ReadListener l){throw new UnsupportedOperationException();} }; }
    public AsyncContext startAsync() { if(started) throw new IllegalStateException(); started=true; return async; }
    public AsyncContext startAsync(ServletRequest r,ServletResponse s) { if(r!=this||s!=response) throw new UnsupportedOperationException(); return startAsync(); }
    public boolean isAsyncStarted() { return started; }
    public boolean isAsyncSupported() { return true; }
    public AsyncContext getAsyncContext() { if(!started) throw new IllegalStateException(); return async; }
    void completed() { if(!started) throw new IllegalStateException(); started=false; }
}
