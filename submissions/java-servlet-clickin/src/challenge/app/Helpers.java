package challenge.app;

import challenge.Json;
import challenge.JsonWriter;
import challenge.Jwt;
import challenge.pg.Query;
import challenge.servlet.Request;
import challenge.servlet.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

/** Shared request validation and DataRow formatting. */
public final class Helpers {
    private Helpers() { }
    public static void raw(JsonWriter out, String s) { out.raw(s); }
    public static void string(JsonWriter out, String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); out.string(b, 0, b.length); }
    public static void column(JsonWriter out, Query q, int row, int col) {
        if (q.isNull(row, col)) raw(out, "null"); else out.string(q.bytes(), q.offset(row, col), q.length(row, col));
    }
    public static void error(Response res, int status, String message) {
        res.setStatus(status); res.setContentType("application/json");
        JsonWriter out = res.jsonOut(); raw(out, "{\"errors\":{\"body\":["); string(out, message); raw(out, "]}}");
    }
    public static long viewer(Request req, Jwt jwt, boolean required) {
        int at = req.authorizationOffset(), len = req.authorizationLength();
        if (at < 0) return required ? -1 : 0;
        if (req.multipleAuthorization) return -1;
        byte[] b = req.bytes();
        for(int i=at;i<at+len;i++) if(b[i]==',')return -1;
        if (len <= 6 || b[at]!='T' || b[at+1]!='o' || b[at+2]!='k' || b[at+3]!='e' || b[at+4]!='n' || b[at+5]!=' ') return -1;
        long id = jwt.verify(b, at+6, len-6, System.currentTimeMillis()/1000);
        return id;
    }
    public record Slice(byte[] bytes, int offset, int length) {
        public Object value() throws Invalid {
            try { return Json.parse(Json.utf8(bytes, offset, length)); }
            catch (Json.Malformed | NumberFormatException e) { throw new Invalid(); }
        }
    }
    public static final class Body {
        private final Map<String, Slice> fields;
        private Body(Map<String, Slice> fields) { this.fields = fields; }
        public Slice get(String key) { return fields.get(key); }
    }
    public static Body body(Request req, String wrapper) throws Invalid {
        byte[] bytes=req.bytes(); int start=req.bodyOffset(), end=start+req.bodyLength();
        Slice whole=new Slice(bytes,start,end-start);
        if (!(whole.value() instanceof Map<?,?>)) throw new Invalid();
        Slice object=members(whole).get(wrapper);
        if (object==null || !(object.value() instanceof Map<?,?>)) throw new Invalid();
        return new Body(members(object));
    }
    private static Map<String,Slice> members(Slice object) throws Invalid {
        byte[] b=object.bytes(); int end=object.offset()+object.length(), p=space(b,object.offset(),end)+1;
        Map<String,Slice> fields=new HashMap<>();
        while ((p=space(b,p,end))<end && b[p]!='}') {
            int keyEnd=stringEnd(b,p,end); Object key=new Slice(b,p,keyEnd-p).value();
            p=space(b,keyEnd,end)+1; p=space(b,p,end); int valueEnd=valueEnd(b,p,end);
            fields.put((String)key,new Slice(b,p,valueEnd-p)); p=space(b,valueEnd,end);
            if(p<end && b[p]==',') p++;
        }
        return fields;
    }
    private static int space(byte[] b,int p,int end) { while(p<end && (b[p]==' '||b[p]=='\n'||b[p]=='\r'||b[p]=='\t'))p++; return p; }
    private static int stringEnd(byte[] b,int p,int end) {
        p++; while(p<end) { if(b[p]=='\\') p+=2; else if(b[p++]=='"') return p; } return p;
    }
    private static int valueEnd(byte[] b,int p,int end) {
        if(b[p]=='"') return stringEnd(b,p,end);
        if(b[p]=='{' || b[p]=='[') {
            int depth=0;
            while(p<end) { byte c=b[p]; if(c=='"') { p=stringEnd(b,p,end); continue; }
                if(c=='{'||c=='[')depth++; else if(c=='}'||c==']') { if(--depth==0)return p+1; } p++; }
            return p;
        }
        while(p<end && b[p]!=',' && b[p]!='}' && b[p]!=']' && b[p]!=' ' && b[p]!='\n' && b[p]!='\r' && b[p]!='\t')p++;
        return p;
    }
    public static String field(Body body, String key, boolean required, boolean nonempty) throws Invalid {
        Slice slice=body.get(key); Object value=slice==null?null:slice.value();
        if (value == null || value == Json.NULL) { if (required) throw new Invalid(); return null; }
        if (!(value instanceof String s) || nonempty && s.isEmpty() || s.indexOf('\0') >= 0) throw new Invalid();
        for(int i=0;i<s.length();i++) if(Character.isSurrogate(s.charAt(i))) {
            if(!Character.isHighSurrogate(s.charAt(i)) || i+1==s.length() || !Character.isLowSurrogate(s.charAt(++i))) throw new Invalid();
        }
        return s;
    }
    public static String password(Body body, boolean required) throws Invalid {
        String s = field(body, "password", required, true);
        if (s != null && s.getBytes(StandardCharsets.UTF_8).length > 72) throw new Invalid();
        return s;
    }
    public static String[] stringArray(Body body, String key) throws Invalid {
        Slice slice=body.get(key); Object value=slice==null?null:slice.value();
        if(value==null || value==Json.NULL) return null;
        if(!(value instanceof List<?> list)) throw new Invalid();
        String[] result=new String[list.size()];
        for(int i=0;i<result.length;i++) {
            if(!(list.get(i) instanceof String s) || s.isEmpty() || s.indexOf('\0')>=0) throw new Invalid();
            for(int j=0;j<s.length();j++) if(Character.isSurrogate(s.charAt(j))) {
                if(!Character.isHighSurrogate(s.charAt(j)) || j+1==s.length() || !Character.isLowSurrogate(s.charAt(++j))) throw new Invalid();
            }
            result[i]=s;
        }
        return result;
    }
    public static int page(Request req, String name, int fallback, int min, int max) throws Invalid {
        String s;
        try { s = req.getParameter(name); } catch (IllegalArgumentException e) { throw new Invalid(); }
        if (s == null) return fallback;
        if (s.isEmpty()) throw new Invalid();
        int n = 0;
        for (int i=0; i<s.length(); i++) { int d=s.charAt(i)-'0'; if(d<0||d>9||n>(max-d)/10) throw new Invalid(); n=n*10+d; }
        if (n<min || n>max) throw new Invalid(); return n;
    }
    public static final class Params {
        private static final String[] NAMES={"limit","offset","tag","author","favorited"};
        private byte[] data=new byte[256];
        private final int[] offsets=new int[5], lengths=new int[5];
        private int size;
        public byte[] bytes() { return data; }
        public int offset(int index) { return offsets[index]; }
        public int length(int index) { return lengths[index]; }
        public void parse(Request request) throws Invalid {
            if(data.length>65536) data=new byte[256];
            java.util.Arrays.fill(lengths,-1); size=0;
            int p=request.queryOffset(), end=p+request.queryLength();
            if(p<0 || p==end) return;
            byte[] input=request.bytes();
            while(p<=end) {
                int stop=p; while(stop<end && input[stop]!='&') stop++;
                int equals=p; while(equals<stop && input[equals]!='=') equals++;
                int base=size; decode(input,p,equals); int key=-1;
                for(int i=0;i<NAMES.length;i++) {
                    String name=NAMES[i]; if(size-base!=name.length()) continue;
                    int j=0; while(j<name.length() && data[base+j]==(byte)name.charAt(j)) j++;
                    if(j==name.length()) { key=i; break; }
                }
                size=base; decode(input,equals<stop?equals+1:stop,stop);
                if(key>=0 && lengths[key]<0) { offsets[key]=base; lengths[key]=size-base; }
                else size=base;
                if(stop==end) break; p=stop+1;
            }
        }
        private void put(int b) {
            if(size==data.length) data=java.util.Arrays.copyOf(data,data.length*2);
            data[size++]=(byte)b;
        }
        private void decode(byte[] input,int p,int end) throws Invalid {
            while(p<end) {
                int c=input[p++]&255;
                if(c=='%') {
                    int base=size; boolean nonAscii=false;
                    do {
                        if(p+1>=end) throw new Invalid();
                        int hi=Character.digit((char)(input[p++]&255),16), lo=Character.digit((char)(input[p++]&255),16);
                        if(hi<0 || lo<0) throw new Invalid();
                        int b=hi*16+lo; put(b); nonAscii|=b>=128;
                        if(p>=end || input[p]!='%') break; p++;
                    } while(true);
                    if(nonAscii) {
                        byte[] normalized=new String(data,base,size-base,StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
                        size=base; for(byte b:normalized) put(b);
                    }
                } else if(c=='+') put(' ');
                else if(c>=128) { put(0xc0|(c>>6)); put(0x80|(c&63)); }
                else put(c);
            }
        }
        public int page(int index,int fallback,int min,int max) throws Invalid {
            int len=lengths[index]; if(len<0) return fallback; if(len==0) throw new Invalid();
            int n=0, end=offsets[index]+len;
            for(int i=offsets[index];i<end;i++) { int d=data[i]-'0'; if(d<0||d>9||n>(max-d)/10) throw new Invalid(); n=n*10+d; }
            if(n<min || n>max) throw new Invalid(); return n;
        }
    }
    public static final class Invalid extends Exception { }
    public static void user(JsonWriter out, Query q, int row, byte[] token, int offset, int length) {
        raw(out,"{\"email\":"); column(out,q,row,2); raw(out,",\"token\":"); out.string(token,offset,length);
        raw(out,",\"username\":"); column(out,q,row,1); raw(out,",\"bio\":"); column(out,q,row,3);
        raw(out,",\"image\":"); column(out,q,row,4); out.put('}');
    }
    public static void profile(JsonWriter out, Query q, int row, int base, boolean following) {
        raw(out,"{\"username\":"); column(out,q,row,base); raw(out,",\"bio\":"); column(out,q,row,base+1);
        raw(out,",\"image\":"); column(out,q,row,base+2); raw(out,",\"following\":"); raw(out,following?"true":"false"); out.put('}');
    }
    public static void article(JsonWriter out, Query q, int row, boolean body) {
        int shift=body?1:0;
        raw(out,"{\"slug\":"); column(out,q,row,1); raw(out,",\"title\":"); column(out,q,row,2);
        raw(out,",\"description\":"); column(out,q,row,3);
        if(body) { raw(out,",\"body\":"); column(out,q,row,4); }
        raw(out,",\"createdAt\":"); Formats.timestamp(out,q.int8At(row,4+shift));
        raw(out,",\"updatedAt\":"); Formats.timestamp(out,q.int8At(row,5+shift));
        raw(out,",\"tagList\":["); int col=6+shift;
        for(int i=0;i<q.arrayCount(row,col);i++) { if(i>0)out.put(','); int p=q.arrayElementOffset(row,col,i); if(p<0)raw(out,"null"); else out.string(q.bytes(),p,q.arrayElementLength(p)); }
        raw(out,"],\"favoritesCount\":"); out.number(q.int8At(row,7+shift));
        raw(out,",\"favorited\":"); raw(out,q.boolAt(row,8+shift)?"true":"false"); raw(out,",\"author\":");
        profile(out,q,row,9+shift,q.boolAt(row,12+shift)); out.put('}');
    }
    public static void comment(JsonWriter out, Query q, int row, boolean following) {
        raw(out,"{\"id\":"); out.number(q.int8At(row,0)); raw(out,",\"body\":"); column(out,q,row,1);
        raw(out,",\"createdAt\":"); Formats.timestamp(out,q.int8At(row,2)); raw(out,",\"updatedAt\":"); Formats.timestamp(out,q.int8At(row,3));
        raw(out,",\"author\":"); profile(out,q,row,4,following); out.put('}');
    }
}
