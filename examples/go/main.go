// Command stdgopg is the feed API (challenge SPEC.md) on the standard Go stack with Postgres:
// Gin (release mode) + gin-contrib/gzip, jackc/pgx/v5 pgxpool (pure Go, no cgo),
// golang-jwt/jwt/v5 for HS256 bearer tokens.
package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"runtime/debug"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/gin-contrib/gzip"
	"github.com/klauspost/compress/gzhttp"
	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
)

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func main() {
	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		log.Fatal("missing required environment variable DATABASE_URL")
	}
	secret := []byte(os.Getenv("JWT_SECRET"))
	if len(secret) == 0 {
		log.Fatal("missing required environment variable JWT_SECRET")
	}
	host, port := envOr("HOST", "127.0.0.1"), envOr("PORT", "3000")
	extraPort := os.Getenv("EXTRA_PORT")

	// Go sizes GOMAXPROCS from the process CPU affinity mask (sched_getaffinity) and,
	// since 1.25, the cgroup CPU limit. GOMAXPROCS="" in the unit counts as unset.
	procs := runtime.GOMAXPROCS(0)
	// One process: pool = min(128, 8 x cores); DB_POOL overrides. (8 per core: each connection serves only
	// ~600-1600 req/s because of the round trip to Postgres on another box; review measured 4c pool 16: 9.9k req/s, 32: 12.4k.)
	pool := min(128, 8*procs)
	if v := os.Getenv("DB_POOL"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			log.Fatalf("invalid DB_POOL %q", v)
		}
		pool = n
	}

	cfg, err := pgxpool.ParseConfig(dbURL)
	if err != nil {
		log.Fatalf("DATABASE_URL: %v", err)
	}
	cfg.MaxConns = int32(pool)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	db, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer db.Close()
	if err := db.Ping(ctx); err != nil {
		log.Fatalf("connect: %v", err)
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New() // no Logger middleware: no access log
	r.Use(gin.CustomRecovery(func(c *gin.Context, _ any) {
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "internal server error"})
	}))
	// GZIP_MW=gzhttp (diagnosis switch, off by default): klauspost/compress gzhttp around the whole engine instead of
	// gin-contrib/gzip. gin-contrib/gzip takes a ~1 MB flate writer before the handler runs, so every queued request
	// holds one; gzhttp only takes a writer when the response is written. MinSize(0): compress every response, as
	// gin-contrib/gzip does.
	var handler http.Handler = r
	if os.Getenv("GZIP_MW") == "gzhttp" {
		wrap, err := gzhttp.NewWrapper(gzhttp.MinSize(0), gzhttp.CompressionLevel(gzip.DefaultCompression))
		if err != nil {
			log.Fatal(err)
		}
		handler = wrap(r)
	} else {
		r.Use(gzip.Gzip(gzip.DefaultCompression))
	}
	a := &app{db: db, jwtSecret: secret, started: time.Now()}
	a.routes(r)

	// One http.Server per port, identical settings, same Gin engine (same handlers, same pool).
	newServer := func(p string) *http.Server {
		return &http.Server{
			Addr:    net.JoinHostPort(host, p),
			Handler: handler,
			// Load-test clients idle up to ~40 s between requests on one connection.
			IdleTimeout:       120 * time.Second,
			ReadHeaderTimeout: 30 * time.Second,
			// No ReadTimeout/WriteTimeout: with IdleTimeout set they don't apply to idle
			// keep-alive waits anyway, and the request bodies here are tiny.
		}
	}
	servers := []*http.Server{newServer(port)}
	if extraPort != "" {
		servers = append(servers, newServer(extraPort))
	}

	// Bind every listener before serving, so a bad port fails startup.
	listeners := make([]net.Listener, len(servers))
	for i, s := range servers {
		// TCP_KEEPALIVE=off (diagnosis switch, off by default): no TCP keep-alive probes on accepted connections. Go's
		// default sends one every 15 s on each idle connection; at 300k+ mostly idle clients that is ~20k packets/s.
		lc := net.ListenConfig{}
		if os.Getenv("TCP_KEEPALIVE") == "off" {
			lc.KeepAlive = -1
		}
		ln, err := lc.Listen(context.Background(), "tcp", s.Addr)
		if err != nil {
			log.Fatal(err)
		}
		listeners[i] = ln
	}

	addrs := make([]string, len(servers))
	for i, s := range servers {
		addrs[i] = "http://" + s.Addr
	}
	log.Printf("[server] gin %s on %v pid=%d GOMAXPROCS=%d pool=%d GOMEMLIMIT=%dMiB",
		gin.Version, addrs, os.Getpid(), procs, pool, debug.SetMemoryLimit(-1)>>20)

	errc := make(chan error, len(servers))
	var wg sync.WaitGroup
	for i, s := range servers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := s.Serve(listeners[i]); err != nil && !errors.Is(err, http.ErrServerClosed) {
				errc <- err
			}
		}()
	}

	select {
	case <-ctx.Done():
	case err := <-errc:
		log.Printf("[server] %v", err)
		stop()
	}
	sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, s := range servers {
		_ = s.Shutdown(sctx)
	}
	wg.Wait()
}
