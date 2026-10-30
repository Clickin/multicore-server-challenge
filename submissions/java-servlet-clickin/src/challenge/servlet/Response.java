package challenge.servlet;

import challenge.JsonWriter;
import jakarta.servlet.*;
import java.io.*;
import java.util.*;

public final class Response extends ResponseAdapter {
    private final JsonWriter out = new JsonWriter();
    public int status=200;
    public String contentType;
    public final Map<String,List<String>> headers=new LinkedHashMap<>();
    public Runnable finish;
    public JsonWriter jsonOut() { return out; }
    public void reset() { status=200; contentType=null; headers.clear(); out.reset(); finish=null; }
    public void setStatus(int value) { status=value; }
    public int getStatus() { return status; }
    public void setContentType(String value) { contentType=value; }
    public String getContentType() { return contentType; }
    public void setContentLength(int length) { }
    public void setContentLengthLong(long length) { }
    public void setHeader(String name,String value) { if(name.equalsIgnoreCase("Content-Type")) { contentType=value; return; } if(name.equalsIgnoreCase("Content-Length")) return; headers.entrySet().removeIf(e->e.getKey().equalsIgnoreCase(name)); headers.put(name,new ArrayList<>(List.of(value))); }
    public void addHeader(String name,String value) { if(name.equalsIgnoreCase("Content-Type")||name.equalsIgnoreCase("Content-Length")){setHeader(name,value);return;} String key=headers.keySet().stream().filter(k->k.equalsIgnoreCase(name)).findFirst().orElse(name); headers.computeIfAbsent(key,k->new ArrayList<>()).add(value); }
    public ServletOutputStream getOutputStream() { return new ServletOutputStream() { public void write(int b) {out.put(b);} public void write(byte[] b,int off,int len) {out.raw(b,off,len);} public boolean isReady(){return true;} public void setWriteListener(WriteListener l){throw new UnsupportedOperationException();} }; }
}
