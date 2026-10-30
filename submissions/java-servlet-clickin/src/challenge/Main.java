package challenge;

import challenge.app.ConduitServlet;
import challenge.http.Loop;
import challenge.pg.PgDispatcher;
import java.net.InetAddress;
import java.util.Collections;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

public final class Main {
    public static void main(String[] args) throws Exception {
        InetAddress host=InetAddress.getByName(env("HOST","0.0.0.0"));
        int port=Integer.parseInt(env("PORT","3000"));
        int n=Math.max(1,Integer.parseInt(env("LOOPS",Integer.toString(Math.max(1,Runtime.getRuntime().availableProcessors()/2)))));
        PgDispatcher dispatcher=new PgDispatcher(env("DATABASE_URL","postgres://conduit:conduit@127.0.0.1:5432/conduit"));
        ConduitServlet servlet=new ConduitServlet(dispatcher, new BcryptPool(), env("JWT_SECRET", "multicore-server-challenge"));
        ServletContext context=(ServletContext)java.lang.reflect.Proxy.newProxyInstance(Main.class.getClassLoader(),new Class<?>[]{ServletContext.class},(proxy,method,arguments)->{throw new UnsupportedOperationException();});
        servlet.init(new ServletConfig() {
            public String getServletName(){return "conduit";}
            public ServletContext getServletContext(){return context;}
            public String getInitParameter(String name){return null;}
            public java.util.Enumeration<String> getInitParameterNames(){return Collections.emptyEnumeration();}
        });
        for(int i=0;i<n;i++) { Loop loop=new Loop(host,port,servlet); Thread t=new Thread(loop,"http-"+i); t.setUncaughtExceptionHandler((thread,error)->{error.printStackTrace(); Runtime.getRuntime().halt(1);}); t.start(); }
    }
    private static String env(String key,String fallback) { String value=System.getenv(key); return value==null||value.isEmpty()?fallback:value; }
}
