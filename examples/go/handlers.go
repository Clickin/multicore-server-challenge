package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	feedSize          = 20
	maxPostBodyLength = 500
	maxRequestBody    = 16 << 10
	// Upper bound on waiting for a pool connection plus the statement (pgxpool has no
	// separate acquire timeout; Acquire honours the context deadline).
	dbTimeout = 5 * time.Second
	// created_at on the wire: UTC, ms. Go's fractional-second layout truncates, never rounds.
	createdAtLayout = "2006-01-02T15:04:05.000Z"
)

const postSelect = `SELECT p.id, p.body, p.created_at, u.username,
       (SELECT count(*) FROM likes l WHERE l.post_id = p.id)::int AS like_count
  FROM posts p
  JOIN users u ON u.id = p.user_id`

type app struct {
	db        *pgxpool.Pool
	jwtSecret []byte
	started   time.Time
}

func (a *app) routes(r *gin.Engine) {
	r.GET("/health", a.health)
	r.GET("/feed", a.feed)
	r.GET("/posts/:id", a.getPost)
	r.POST("/posts", a.requireAuth, a.createPost)
	r.POST("/posts/:id/like", a.requireAuth, a.likePost)
	r.NoRoute(func(c *gin.Context) { fail(c, http.StatusNotFound, "not found") })
}

// Field order is wire order.
type post struct {
	ID        int64  `json:"id"`
	Body      string `json:"body"`
	CreatedAt string `json:"created_at"`
	Author    string `json:"author"`
	LikeCount int64  `json:"like_count"`
}

func scanPost(row pgx.Row) (post, error) {
	var p post
	var created time.Time
	err := row.Scan(&p.ID, &p.Body, &created, &p.Author, &p.LikeCount)
	p.CreatedAt = formatTime(created)
	return p, err
}

func formatTime(t time.Time) string { return t.UTC().Format(createdAtLayout) }

func dbCtx(c *gin.Context) (context.Context, context.CancelFunc) {
	return context.WithTimeout(c.Request.Context(), dbTimeout)
}

func fail(c *gin.Context, status int, msg string) {
	c.AbortWithStatusJSON(status, gin.H{"error": msg})
}

func internalError(c *gin.Context, err error) {
	log.Printf("[error] %v", err)
	fail(c, http.StatusInternalServerError, "internal server error")
}

func parseID(c *gin.Context) (int64, bool) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	return id, err == nil && id > 0
}

type user struct {
	id       int64
	username string
}

// requireAuth verifies `Authorization: Bearer <jwt>` (HS256 only, exp checked).
func (a *app) requireAuth(c *gin.Context) {
	header := c.GetHeader("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		fail(c, http.StatusUnauthorized, "missing bearer token")
		return
	}
	claims := jwt.MapClaims{}
	_, err := jwt.ParseWithClaims(strings.TrimSpace(header[len("Bearer "):]), claims,
		func(*jwt.Token) (any, error) { return a.jwtSecret, nil },
		jwt.WithValidMethods([]string{"HS256"}))
	if err != nil {
		fail(c, http.StatusUnauthorized, "invalid or expired token")
		return
	}
	id, okID := userID(claims["sub"])
	name, okName := claims["username"].(string)
	if !okID || !okName {
		fail(c, http.StatusUnauthorized, "invalid token payload")
		return
	}
	c.Set("user", user{id: id, username: name})
	c.Next()
}

func userID(sub any) (int64, bool) {
	switch v := sub.(type) {
	case string:
		id, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
		return id, err == nil && id > 0
	case float64:
		return int64(v), v > 0 && v == math.Trunc(v) && v <= 1<<53
	}
	return 0, false
}

func (a *app) health(c *gin.Context) {
	ctx, cancel := dbCtx(c)
	defer cancel()
	if _, err := a.db.Exec(ctx, "SELECT 1"); err != nil {
		c.JSON(http.StatusServiceUnavailable, struct {
			Status string `json:"status"`
			DB     string `json:"db"`
			Error  string `json:"error"`
		}{"degraded", "unreachable", err.Error()})
		return
	}
	c.JSON(http.StatusOK, struct {
		Status  string `json:"status"`
		DB      string `json:"db"`
		UptimeS int64  `json:"uptime_s"`
	}{"ok", "ok", int64(time.Since(a.started).Seconds())})
}

func (a *app) feed(c *gin.Context) {
	ctx, cancel := dbCtx(c)
	defer cancel()
	rows, err := a.db.Query(ctx, postSelect+` ORDER BY p.created_at DESC, p.id DESC LIMIT $1`, feedSize)
	if err != nil {
		internalError(c, err)
		return
	}
	defer rows.Close()
	posts := make([]post, 0, feedSize)
	for rows.Next() {
		p, err := scanPost(rows)
		if err != nil {
			internalError(c, err)
			return
		}
		posts = append(posts, p)
	}
	if err := rows.Err(); err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"posts": posts})
}

func (a *app) getPost(c *gin.Context) {
	id, ok := parseID(c)
	if !ok {
		fail(c, http.StatusBadRequest, "invalid post id")
		return
	}
	ctx, cancel := dbCtx(c)
	defer cancel()
	p, err := scanPost(a.db.QueryRow(ctx, postSelect+` WHERE p.id = $1`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		fail(c, http.StatusNotFound, "post not found")
		return
	}
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"post": p})
}

func (a *app) createPost(c *gin.Context) {
	u := c.MustGet("user").(user)
	var req struct {
		Body any `json:"body"`
	}
	// Plain encoding/json decode rather than ShouldBindJSON: binding also accepts an empty
	// body as an error and can't tell "malformed" from "missing", which the spec separates.
	err := json.NewDecoder(http.MaxBytesReader(c.Writer, c.Request.Body, maxRequestBody)).Decode(&req)
	if err != nil && !errors.Is(err, io.EOF) {
		fail(c, http.StatusBadRequest, "malformed JSON body")
		return
	}
	body, _ := req.Body.(string)
	body = strings.TrimSpace(body)
	if body == "" {
		fail(c, http.StatusBadRequest, "body is required")
		return
	}
	if len(utf16.Encode([]rune(body))) > maxPostBodyLength {
		fail(c, http.StatusBadRequest, "body must be at most 500 characters")
		return
	}
	p := post{Body: body, Author: u.username}
	ctx, cancel := dbCtx(c)
	defer cancel()
	var created time.Time
	err = a.db.QueryRow(ctx,
		`INSERT INTO posts (user_id, body) VALUES ($1, $2) RETURNING id, created_at`,
		u.id, body).Scan(&p.ID, &created)
	p.CreatedAt = formatTime(created)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"post": p})
}

func (a *app) likePost(c *gin.Context) {
	u := c.MustGet("user").(user)
	postID, ok := parseID(c)
	if !ok {
		fail(c, http.StatusBadRequest, "invalid post id")
		return
	}
	ctx, cancel := dbCtx(c)
	defer cancel()
	tag, err := a.db.Exec(ctx,
		`INSERT INTO likes (user_id, post_id) VALUES ($1, $2) ON CONFLICT (user_id, post_id) DO NOTHING`,
		u.id, postID)
	if err != nil {
		var pe *pgconn.PgError
		if errors.As(err, &pe) && pe.Code == "23503" { // foreign_key_violation
			fail(c, http.StatusNotFound, "post not found")
			return
		}
		internalError(c, err)
		return
	}
	n := tag.RowsAffected()
	status := http.StatusOK
	if n > 0 {
		status = http.StatusCreated
	}
	c.JSON(status, struct {
		Liked        bool  `json:"liked"`
		AlreadyLiked bool  `json:"already_liked"`
		PostID       int64 `json:"post_id"`
	}{true, n == 0, postID})
}
