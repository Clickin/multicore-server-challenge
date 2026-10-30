package challenge.app;

import challenge.BcryptPool;
import challenge.Jwt;
import challenge.JsonWriter;
import challenge.http.Task;
import challenge.pg.PgDispatcher;
import challenge.pg.Completions;
import challenge.pg.Query;
import challenge.pg.Stmt;
import challenge.pg.Unit;
import challenge.servlet.Request;
import challenge.servlet.Response;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ConduitServlet extends HttpServlet {
    private static final int TAGS=1, REGISTER=2, LOGIN=3, USER=4, UPDATE=5, PROFILE=6, FOLLOW=7, UNFOLLOW=8, STUB=9, ARTICLES=10, ARTICLES_TAG=11, ARTICLES_AUTHOR=12, ARTICLES_FAVORITED=13, FEED=14, ARTICLE=15, COMMENTS=16, CREATE_ARTICLE=17, UPDATE_ARTICLE=18, DELETE_ARTICLE=19, FAVORITE=20, UNFAVORITE=21, CREATE_COMMENT=22, DELETE_COMMENT=23;
    private static final int[] STATEMENTS={0,Stmt.TAGS,Stmt.REGISTER,Stmt.LOGIN,Stmt.USER,Stmt.UPDATE_USER,Stmt.PROFILE,Stmt.FOLLOW,Stmt.UNFOLLOW,0,Stmt.ARTICLES,Stmt.ARTICLES_TAG,Stmt.ARTICLES_AUTHOR,Stmt.ARTICLES_FAVORITED,Stmt.FEED,Stmt.ARTICLE,Stmt.COMMENTS,Stmt.CREATE_ARTICLE,Stmt.UPDATE_ARTICLE,Stmt.DELETE_ARTICLE,Stmt.FAVORITE,Stmt.UNFAVORITE,Stmt.CREATE_COMMENT,Stmt.DELETE_COMMENT};
    private final PgDispatcher dispatcher;
    private final BcryptPool bcrypt;
    private final String secret;
    public ConduitServlet(PgDispatcher dispatcher, BcryptPool bcrypt, String secret) { this.dispatcher=dispatcher; this.bcrypt=bcrypt; this.secret=secret; }

    @Override public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Request req=(Request)request; Response res=(Response)response;
        int[] start=req.segmentStarts, end=req.segmentEnds; int count=segments(req,start,end);
        boolean get=method(req,"GET"), post=method(req,"POST"), put=method(req,"PUT"), delete=method(req,"DELETE");
        if (count==1 && eq(req,start[0],end[0],"__async") && get) {
            AsyncContext async=req.startAsync(); req.loop().post(new Task(){ public void run(){async.complete();} }); return;
        }
        if (count==1 && eq(req,start[0],end[0],"__echo") && post) { res.getOutputStream().write(req.bytes(),req.bodyOffset(),req.bodyLength()); return; }
        int route=0;
        if(count>=2 && eq(req,start[0],end[0],"api")) {
            if(count==2 && eq(req,start[1],end[1],"tags") && get) route=TAGS;
            else if(count==2 && eq(req,start[1],end[1],"users") && post) route=REGISTER;
            else if(count==3 && eq(req,start[1],end[1],"users") && eq(req,start[2],end[2],"login") && post) route=LOGIN;
            else if(count==2 && eq(req,start[1],end[1],"user")) route=get?USER:put?UPDATE:0;
            else if(eq(req,start[1],end[1],"profiles") && count>=3) {
                if(count==3 && get) route=PROFILE;
                else if(count==4 && eq(req,start[3],end[3],"follow")) route=post?FOLLOW:delete?UNFOLLOW:0;
            } else if(eq(req,start[1],end[1],"articles")) {
                if(count==2 && (get||post)) route=get?ARTICLES:CREATE_ARTICLE;
                else if(count==3 && (get||put||delete)) route=get?(eq(req,start[2],end[2],"feed")?FEED:ARTICLE):put?UPDATE_ARTICLE:DELETE_ARTICLE;
                else if(count==4 && eq(req,start[3],end[3],"favorite") && (post||delete)) route=post?FAVORITE:UNFAVORITE;
                else if(count==4 && eq(req,start[3],end[3],"comments") && (get||post)) route=get?COMMENTS:CREATE_COMMENT;
                else if(count==5 && eq(req,start[3],end[3],"comments") && delete) route=DELETE_COMMENT;
            }
        }
        if(route==0) { Helpers.error(res,404,"not found"); return; }
        if(route==STUB) { Helpers.error(res,501,"not implemented"); return; }
        Slot slot=(Slot)req.applicationSlot;
        if(slot==null) req.applicationSlot=slot=new Slot(req);
        long viewer=0;
        if(route==USER || route==UPDATE || route==PROFILE || route==FOLLOW || route==UNFOLLOW || route>=ARTICLES) {
            viewer=Helpers.viewer(req,slot.jwt,route==USER || route==UPDATE || route==FOLLOW || route==UNFOLLOW || route==FEED || route>=CREATE_ARTICLE);
            if(viewer<0) { Helpers.error(res,401,"unauthorized"); return; }
        }
        String username=null,email=null,password=null,bio=null,image=null,title=null,description=null,bodyText=null; String[] tags=null; long commentId=0;
        int limit=20,offset=0;
        try {
            if(route==ARTICLES || route==FEED) {
                slot.params.parse(req);
                limit=slot.params.page(0,20,1,100); offset=slot.params.page(1,0,0,Integer.MAX_VALUE);
                if(route==ARTICLES) {
                    if(slot.params.length(2)>0) route=ARTICLES_TAG;
                    else if(slot.params.length(3)>0) route=ARTICLES_AUTHOR;
                    else if(slot.params.length(4)>0) route=ARTICLES_FAVORITED;
                }
            }
        } catch(Helpers.Invalid | IllegalArgumentException e) { Helpers.error(res,422,"invalid parameters"); return; }
        try {
            if(route==REGISTER || route==LOGIN || route==UPDATE) {
                Helpers.Body body=Helpers.body(req,"user");
                if(route!=LOGIN) username=Helpers.field(body,"username",route==REGISTER,true);
                email=Helpers.field(body,"email",route!=UPDATE,true);
                password=Helpers.password(body,route!=UPDATE);
                if(route==UPDATE) { bio=Helpers.field(body,"bio",false,false); image=Helpers.field(body,"image",false,false); }
            }
            if(route==CREATE_ARTICLE || route==UPDATE_ARTICLE) {
                Helpers.Body body=Helpers.body(req,"article"); boolean required=route==CREATE_ARTICLE;
                title=Helpers.field(body,"title",required,true); description=Helpers.field(body,"description",required,true); bodyText=Helpers.field(body,"body",required,true);
                if(required) { tags=Helpers.stringArray(body,"tagList"); if(tags==null)tags=new String[0]; }
            }
            if(route==CREATE_COMMENT) bodyText=Helpers.field(Helpers.body(req,"comment"),"body",true,true);
        } catch(Helpers.Invalid e) { Helpers.error(res,422,"invalid body"); return; }
        if(route==DELETE_COMMENT) {
            try { commentId=Long.parseLong(new String(req.bytes(),start[4],end[4]-start[4],StandardCharsets.UTF_8)); }
            catch(NumberFormatException e) { Helpers.error(res,404,"not found"); return; }
        }
        slot.title=title; slot.description=description; slot.bodyText=bodyText; slot.tags=tags; slot.commentId=commentId;
        slot.route=route; slot.response=res; slot.viewer=viewer; slot.username=username; slot.email=email; slot.password=password; slot.bio=bio; slot.image=image;
        slot.limit=limit; slot.offset=offset;
        slot.tokenOffset=req.authorizationOffset()<0?0:req.authorizationOffset()+6;
        slot.tokenLength=req.authorizationOffset()<0?0:req.authorizationLength()-6;
        if(route==PROFILE || route==FOLLOW || route==UNFOLLOW || route==ARTICLE || route==COMMENTS || route>=UPDATE_ARTICLE) { slot.targetOffset=start[2]; slot.targetLength=end[2]-start[2]; slot.targetValid=utf8(req.bytes(),slot.targetOffset,slot.targetLength); }
        slot.async=req.startAsync(); slot.start();
    }
    private static boolean utf8(byte[] b,int offset,int length) {
        int end=offset+length;
        for(int i=offset;i<end;) {
            int c=b[i++]&255;
            if(c<128)continue;
            int n, min, value;
            if(c>=0xc2 && c<=0xdf){n=1;min=0x80;value=c&31;}
            else if(c>=0xe0 && c<=0xef){n=2;min=0x800;value=c&15;}
            else if(c>=0xf0 && c<=0xf4){n=3;min=0x10000;value=c&7;}
            else return false;
            if(end-i<n)return false;
            while(n-->0){int x=b[i++]&255;if((x&0xc0)!=0x80)return false;value=value<<6|(x&63);}
            if(value<min || value>0x10ffff || value>=0xd800 && value<=0xdfff)return false;
        }
        return true;
    }
    private static boolean method(Request r,String s) { return eq(r,0,r.pathOffset()-1,s); }
    private static boolean eq(Request r,int a,int b,String s) { if(b-a!=s.length())return false; for(int i=0;i<s.length();i++)if(r.bytes()[a+i]!=(byte)s.charAt(i))return false; return true; }
    private static int segments(Request r,int[] start,int[] end) {
        byte[] b=r.bytes(); int p=r.pathOffset()+1, limit=r.pathOffset()+r.pathLength(), n=0;
        while(p<limit) { if(n==start.length)return -1; int a=p; while(p<limit && b[p]!='/')p++; if(p==a)return -1; start[n]=a;end[n++]=p; if(p<limit && ++p==limit)return -1; } return n;
    }
    private final class Slot extends Task implements Completions, Unit.Callback {
        final Request request; final Jwt jwt; final Query query=new Query(0,false); Query secondary;
        final Query[] one={query}, two=new Query[2]; final Unit unit; final Helpers.Params params=new Helpers.Params(); final byte[] signed=new byte[256];
        Response response; AsyncContext async; int route,phase,tokenOffset,tokenLength,targetOffset,targetLength; long viewer; boolean targetValid;
        String username,email,password,bio,image,title,description,bodyText; String[] tags; long commentId; int limit,offset;
        Slot(Request request) {
            this.request=request;
            try { jwt=request.loop()==null?new Jwt(secret):request.loop().jwt(secret); }
            catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
            unit=new Unit(this,this,false,false,one);
        }
        void start() {
            boolean write=route==REGISTER||route==UPDATE||route==FOLLOW||route==UNFOLLOW||route>=CREATE_ARTICLE;
            query.prepare(STATEMENTS[route],write);
            boolean dual=route==CREATE_ARTICLE||route==FAVORITE||route==UNFAVORITE;
            if(dual) {
                if(secondary==null) secondary=new Query(0,true);
                secondary.prepare(route==CREATE_ARTICLE?Stmt.INSERT_TAGS:Stmt.ARTICLE,true);
                two[0]=route==CREATE_ARTICLE?secondary:query; two[1]=route==CREATE_ARTICLE?query:secondary;
            } else if(secondary!=null) secondary.reset();
            unit.prepare(false,write,dual?two:one); phase=0;
            Query q=query;
            switch(route) {
                case TAGS -> dispatcher.submit(unit);
                case REGISTER -> { unit.bcryptPassword=password; phase=1; bcrypt.submit(unit); }
                case LOGIN -> { q.text(email); dispatcher.submit(unit); }
                case USER -> { q.int8(viewer); dispatcher.submit(unit); }
                case UPDATE -> { if(password!=null) { unit.bcryptPassword=password; phase=1; bcrypt.submit(unit); } else update(null); }
                case CREATE_ARTICLE -> { unit.queries[0].textArray(tags); q.int8(viewer).text(title).text(description).text(bodyText).textArray(tags).text(Formats.slugify(title)); dispatcher.submit(unit); }
                case UPDATE_ARTICLE -> { target(q.int8(viewer)).text(title).text(description).text(bodyText).text(title==null?null:Formats.slugify(title)); dispatcher.submit(unit); }
                case DELETE_ARTICLE -> { target(q.int8(viewer)); dispatcher.submit(unit); }
                case FAVORITE,UNFAVORITE -> { target(q.int8(viewer)); target(unit.queries[1].int8(viewer)); dispatcher.submit(unit); }
                case CREATE_COMMENT -> { target(q.int8(viewer)).text(bodyText); dispatcher.submit(unit); }
                case DELETE_COMMENT -> { target(q.int8(viewer)).int8(commentId); dispatcher.submit(unit); }
                case ARTICLE,COMMENTS,PROFILE,FOLLOW,UNFOLLOW -> { target(q.int8(viewer)); dispatcher.submit(unit); }
                case ARTICLES,ARTICLES_TAG,ARTICLES_AUTHOR,ARTICLES_FAVORITED,FEED -> {
                    q.int8(viewer).int4(limit).int4(offset);
                    if(route==ARTICLES_TAG || route==ARTICLES_AUTHOR || route==ARTICLES_FAVORITED) q.text(params.bytes(),params.offset(route-ARTICLES_TAG+2),params.length(route-ARTICLES_TAG+2));
                    dispatcher.submit(unit);
                }
                default -> throw new AssertionError();
            }
        }
        Query target(Query q) {
            return targetValid ? q.text(request.bytes(),targetOffset,targetLength)
                    : q.text(new String(request.bytes(),targetOffset,targetLength,StandardCharsets.UTF_8));
        }
        void update(String hash) { query.int8(viewer).text(email).text(username).text(hash).text(bio).text(image); dispatcher.submit(unit); }
        public void post(Unit ignored) { request.loop().post(this); }
        public void done(Unit ignored) { run(); }
        public void run() {
            request.loop().assertLoopThread();
            if(phase==1) {
                if(unit.errorMessage!=null) { finishError(500,"password error"); return; }
                phase=0;
                if(route==REGISTER) { query.text(username).text(email).text(unit.bcryptHash); dispatcher.submit(unit); return; }
                if(route==UPDATE) { update(unit.bcryptHash); return; }
                if(!unit.bcryptMatches) { finishError(401,"invalid credentials"); return; }
                writeUser(); return;
            }
            Unit u=unit; Query q=query;
            if(u.errorMessage!=null) { finishError("23505".equals(u.sqlState)?422:500,"database error"); return; }
            if(route==TAGS) {
                response.setContentType("application/json"); JsonWriter out=response.jsonOut(); Helpers.raw(out,"{\"tags\":[");
                for(int i=0;i<q.rowCount();i++){if(i!=0)out.put(','); Helpers.column(out,q,i,0);} Helpers.raw(out,"]}"); async.complete(); return;
            }
            if(route>=ARTICLES && route<=FEED) {
                response.setContentType("application/json"); JsonWriter out=response.jsonOut(); Helpers.raw(out,"{\"articles\":[");
                for(int i=0;i<q.rowCount();i++) { if(q.isNull(i,0))continue; if(i>0)out.put(','); Helpers.article(out,q,i,false); }
                Helpers.raw(out,"],\"articlesCount\":"); out.number(q.int8At(0,13)); out.put('}'); async.complete(); return;
            }
            if(route==FAVORITE || route==UNFAVORITE) q=u.queries[1];
            if(route>=UPDATE_ARTICLE && q.rowCount()==0) { finishError(404,"not found"); return; }
            if(route==UPDATE_ARTICLE && q.isNull(0,0) || (route==DELETE_ARTICLE || route==DELETE_COMMENT) && !q.boolAt(0,0)) { finishError(403,"forbidden"); return; }
            if(route==DELETE_ARTICLE || route==DELETE_COMMENT) { response.setStatus(204); async.complete(); return; }
            if(route==CREATE_COMMENT) { response.setStatus(201); response.setContentType("application/json"); JsonWriter out=response.jsonOut(); Helpers.raw(out,"{\"comment\":"); Helpers.comment(out,q,0,false); out.put('}'); async.complete(); return; }
            if(q.rowCount()==0) { finishError(route==PROFILE||route==FOLLOW||route==UNFOLLOW||route==ARTICLE||route==COMMENTS?404:401,"not found"); return; }
            if(route==ARTICLE || route==CREATE_ARTICLE || route==UPDATE_ARTICLE || route==FAVORITE || route==UNFAVORITE) {
                if(route==CREATE_ARTICLE) response.setStatus(201);
                response.setContentType("application/json"); JsonWriter out=response.jsonOut(); Helpers.raw(out,"{\"article\":"); Helpers.article(out,q,0,true); out.put('}'); async.complete(); return;
            }
            if(route==COMMENTS) {
                response.setContentType("application/json"); JsonWriter out=response.jsonOut(); Helpers.raw(out,"{\"comments\":[");
                for(int i=0;i<q.rowCount();i++) { if(q.isNull(i,0))continue; if(i>0)out.put(','); Helpers.comment(out,q,i,q.boolAt(i,7)); }
                Helpers.raw(out,"]}"); async.complete(); return;
            }
            if(route==LOGIN) { unit.bcryptPassword=password; unit.bcryptHash=new String(q.bytes(),q.offset(0,5),q.length(0,5),StandardCharsets.UTF_8); phase=1; bcrypt.submit(unit); return; }
            if(route==REGISTER || route==USER || route==UPDATE) { writeUser(); return; }
            response.setContentType("application/json"); JsonWriter out=response.jsonOut(); Helpers.raw(out,"{\"profile\":");
            Helpers.profile(out,q,0,0,route==PROFILE?q.boolAt(0,3):route==FOLLOW); out.put('}'); async.complete();
        }
        void writeUser() {
            Query q=query; byte[] token=request.bytes(); int at=tokenOffset,len=tokenLength;
            if(route==REGISTER||route==LOGIN) { at=0; len=jwt.sign(q.int8At(0,0),System.currentTimeMillis()/1000,signed,0); token=signed; }
            response.setContentType("application/json"); if(route==REGISTER)response.setStatus(201);
            JsonWriter out=response.jsonOut(); Helpers.raw(out,"{\"user\":"); Helpers.user(out,q,0,token,at,len); out.put('}'); async.complete();
        }
        void finishError(int status,String message) { Helpers.error(response,status,message); async.complete(); }
    }
}
