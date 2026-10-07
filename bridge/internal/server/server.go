// Package server implements the HTTP + WebSocket API described in PROTOCOL.md.
package server

import (
	"context"
	"crypto/subtle"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hook"
	"github.com/steindekker/hoshi-reader/bridge/internal/hub"
	"github.com/steindekker/hoshi-reader/bridge/internal/screenshot"
	"github.com/steindekker/hoshi-reader/bridge/internal/status"
	"github.com/steindekker/hoshi-reader/bridge/internal/store"
)

// Protocol is the PROTOCOL.md version implemented here.
const Protocol = 1

// ServerName is the "server" field of the status object.
const ServerName = "steamdeck-vn-extractor"

//go:embed debug.html
var debugPage []byte

// StatusChanged is published on the hub when the status object changes.
type StatusChanged struct{}

// ThreadsChanged is published when the hook thread list or the selection
// changes. The WebSocket payload is built from Hooks at send time.
type ThreadsChanged struct{}

// Hooks is the automatic text-hook controller. Nil disables the hook API
// beyond an idle status object.
type Hooks interface {
	View() hook.PublicStatus
	Threads() hook.Snapshot
	Select(threadID *string) error
}

// Capturer grabs a screenshot now (satisfied by *screenshot.Manager).
type Capturer interface {
	Capture(ctx context.Context) (screenshot.Shot, error)
}

// Options wires the server to the rest of the daemon.
type Options struct {
	Version        string
	Hostname       string
	Token          string
	AllowedOrigins []string
	Store          *store.Store
	Hub            *hub.Hub
	Tracker        *status.Tracker
	Shots          Capturer // nil when screenshots are disabled
	Cache          *screenshot.Cache
	Hooks          Hooks
	Log            *slog.Logger
	PingInterval   time.Duration // default 20 s
}

// Server is the API server.
type Server struct {
	opts    Options
	mux     *http.ServeMux
	ctx     context.Context // cancelled on Shutdown; ends WebSocket sessions
	cancel  context.CancelFunc
	wsWG    sync.WaitGroup
	httpSrv *http.Server
}

// Status is the status object (PROTOCOL.md).
type Status struct {
	Server       string            `json:"server"`
	Version      string            `json:"version"`
	Protocol     int               `json:"protocol"`
	Hostname     string            `json:"hostname"`
	LatestLineID int64             `json:"latestLineId"`
	Sources      []status.Source   `json:"sources"`
	Screenshot   status.Screenshot `json:"screenshot"`
	Hook         hook.PublicStatus `json:"hook"`
}

// New builds the server and its routes.
func New(opts Options) *Server {
	if opts.Log == nil {
		opts.Log = slog.Default()
	}
	if opts.PingInterval <= 0 {
		opts.PingInterval = 20 * time.Second
	}
	if opts.Cache == nil {
		opts.Cache = screenshot.NewCache(1)
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Server{opts: opts, mux: http.NewServeMux(), ctx: ctx, cancel: cancel}
	s.mux.HandleFunc("GET /api/status", s.handleStatus)
	s.mux.HandleFunc("GET /api/lines", s.handleGetLines)
	s.mux.HandleFunc("POST /api/lines", s.handlePostLines)
	s.mux.HandleFunc("GET /api/ws", s.handleWS)
	s.mux.HandleFunc("POST /api/screenshot", s.handleScreenshot)
	s.mux.HandleFunc("GET /api/screenshot", s.handleScreenshot) // convenience for browsers
	s.mux.HandleFunc("GET /api/hook/threads", s.handleHookThreads)
	s.mux.HandleFunc("POST /api/hook/threads/select", s.handleHookSelect)
	s.mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "not found")
	})
	s.mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(debugPage)
	})
	return s
}

// Handler returns the root handler including authentication.
func (s *Server) Handler() http.Handler { return s.auth(s.mux) }

// Serve accepts connections on l until Shutdown.
func (s *Server) Serve(l net.Listener) error {
	s.httpSrv = &http.Server{
		Handler:           s.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ErrorLog:          slog.NewLogLogger(s.opts.Log.Handler(), slog.LevelDebug),
	}
	err := s.httpSrv.Serve(l)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// Shutdown stops accepting requests, closes WebSocket sessions and waits
// for in-flight requests until ctx expires.
func (s *Server) Shutdown(ctx context.Context) error {
	s.cancel()
	var err error
	if s.httpSrv != nil {
		err = s.httpSrv.Shutdown(ctx)
	}
	done := make(chan struct{})
	go func() { s.wsWG.Wait(); close(done) }()
	select {
	case <-done:
	case <-ctx.Done():
	}
	return err
}

// Status builds the current status object.
func (s *Server) Status() Status {
	src := s.opts.Tracker.Sources()
	if src == nil {
		src = []status.Source{}
	}
	return Status{
		Server:       ServerName,
		Version:      s.opts.Version,
		Protocol:     Protocol,
		Hostname:     s.opts.Hostname,
		LatestLineID: s.opts.Store.LatestID(),
		Sources:      src,
		Screenshot:   s.opts.Tracker.Screenshot(),
		Hook:         s.hookStatus(),
	}
}

func (s *Server) hookStatus() hook.PublicStatus {
	if s.opts.Hooks == nil {
		return hook.PublicStatus{State: hook.StateIdle, Mode: hook.ModeAuto}
	}
	return s.opts.Hooks.View()
}

type threadsResponse struct {
	Mode             string            `json:"mode"`
	SelectedThreadID *string           `json:"selectedThreadId"`
	Threads          []hook.ThreadInfo `json:"threads"`
}

func threadsFrom(snap hook.Snapshot) threadsResponse {
	mode := snap.Mode
	if mode == "" {
		mode = hook.ModeAuto
	}
	threads := snap.Threads
	if threads == nil {
		threads = []hook.ThreadInfo{}
	}
	var selected *string
	if snap.SelectedID != "" {
		selected = &snap.SelectedID
	}
	return threadsResponse{Mode: mode, SelectedThreadID: selected, Threads: threads}
}

func (s *Server) handleHookThreads(w http.ResponseWriter, r *http.Request) {
	if s.opts.Hooks == nil {
		writeJSON(w, http.StatusOK, threadsFrom(hook.Snapshot{Mode: hook.ModeAuto}))
		return
	}
	writeJSON(w, http.StatusOK, threadsFrom(s.opts.Hooks.Threads()))
}

func (s *Server) handleHookSelect(w http.ResponseWriter, r *http.Request) {
	if s.opts.Hooks == nil {
		writeError(w, http.StatusConflict, hook.ErrDisabled.Error())
		return
	}
	var body struct {
		ThreadID *string `json:"threadId"`
	}
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "body must be {\"threadId\": \"...\"} or {\"threadId\": null}")
		return
	}
	if body.ThreadID != nil && *body.ThreadID == "" {
		writeError(w, http.StatusBadRequest, "threadId must be a thread id or null")
		return
	}
	if err := s.opts.Hooks.Select(body.ThreadID); err != nil {
		switch {
		case errors.Is(err, hook.ErrDisabled):
			writeError(w, http.StatusConflict, err.Error())
		case errors.Is(err, hook.ErrUnknownThread):
			writeError(w, http.StatusNotFound, err.Error())
		default:
			writeError(w, http.StatusInternalServerError, "could not select thread")
		}
		return
	}
	writeJSON(w, http.StatusOK, threadsFrom(s.opts.Hooks.Threads()))
}

func (s *Server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.opts.Token == "" || !strings.HasPrefix(r.URL.Path, "/api/") {
			next.ServeHTTP(w, r)
			return
		}
		got := ""
		if h := r.Header.Get("Authorization"); len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
			got = strings.TrimSpace(h[7:])
		} else if r.URL.Path == "/api/ws" {
			got = r.URL.Query().Get("token")
		}
		if subtle.ConstantTimeCompare([]byte(got), []byte(s.opts.Token)) != 1 {
			w.Header().Set("WWW-Authenticate", `Bearer realm="hoshi-bridge"`)
			writeError(w, http.StatusUnauthorized, "missing or invalid token")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.Status())
}

// decorate fills the runtime-only hasScreenshot flag.
func (s *Server) decorate(lines []store.Line) []store.Line {
	for i := range lines {
		lines[i].HasScreenshot = s.opts.Cache.Has(lines[i].ID)
	}
	if lines == nil {
		lines = []store.Line{}
	}
	return lines
}

func (s *Server) handleGetLines(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit := 100
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			writeError(w, http.StatusBadRequest, "limit must be a positive integer")
			return
		}
		limit = min(n, 1000)
	}
	var lines []store.Line
	if v := q.Get("after"); v != "" {
		after, err := strconv.ParseInt(v, 10, 64)
		if err != nil || after < 0 {
			writeError(w, http.StatusBadRequest, "after must be a non-negative integer")
			return
		}
		lines = s.opts.Store.After(after, limit)
	} else {
		lines = s.opts.Store.Last(limit)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"lines":        s.decorate(lines),
		"latestLineId": s.opts.Store.LatestID(),
	})
}

const maxIngestBytes = 1 << 20

func (s *Server) handlePostLines(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, maxIngestBytes+1))
	if err != nil {
		writeError(w, http.StatusBadRequest, "read body: "+err.Error())
		return
	}
	if len(body) > maxIngestBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "line too large")
		return
	}
	text, source := string(body), "http"
	if mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); mt == "application/json" {
		var in struct {
			Text   string `json:"text"`
			Source string `json:"source"`
		}
		if err := json.Unmarshal(body, &in); err != nil {
			writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
			return
		}
		text = in.Text
		if src := strings.TrimSpace(in.Source); src != "" {
			if len(src) > 64 {
				writeError(w, http.StatusBadRequest, "source id too long")
				return
			}
			source = src
		}
	}
	if strings.TrimSpace(text) == "" {
		writeError(w, http.StatusBadRequest, "empty line")
		return
	}
	line, res, err := s.opts.Store.Add(text, source)
	if err != nil {
		s.opts.Log.Error("store line", "err", err)
		writeError(w, http.StatusInternalServerError, "could not store line")
		return
	}
	switch res {
	case store.Duplicate:
		writeJSON(w, http.StatusOK, map[string]any{"duplicate": true})
	case store.Filtered:
		// Additive to PROTOCOL.md: dropped by a configured filter.
		writeJSON(w, http.StatusOK, map[string]any{"duplicate": false, "filtered": true})
	default:
		writeJSON(w, http.StatusCreated, line)
	}
}

func (s *Server) handleScreenshot(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	maxWidth, quality := 1280, 85
	var lineID int64
	hasLine := false
	if v := q.Get("maxWidth"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 0 {
			writeError(w, http.StatusBadRequest, "maxWidth must be a non-negative integer")
			return
		}
		maxWidth = n
	}
	if v := q.Get("quality"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 100 {
			writeError(w, http.StatusBadRequest, "quality must be 1-100")
			return
		}
		quality = n
	}
	if v := q.Get("lineId"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			writeError(w, http.StatusBadRequest, "lineId must be an integer")
			return
		}
		lineID, hasLine = n, true
	}

	if hasLine {
		if c, ok := s.opts.Cache.Get(lineID); ok {
			img, err := c.Image()
			if err == nil {
				if data, err := screenshot.EncodeJPEG(img, maxWidth, quality); err == nil {
					w.Header().Set("X-Screenshot-Line-Id", strconv.FormatInt(lineID, 10))
					writeJPEG(w, data, c.CapturedAt)
					return
				}
			}
			s.opts.Log.Warn("cached screenshot unusable, capturing now", "line", lineID, "err", err)
		}
	}
	if s.opts.Shots == nil {
		writeError(w, http.StatusServiceUnavailable, "screenshots are disabled in the server config")
		return
	}
	shot, err := s.opts.Shots.Capture(r.Context())
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "screenshot failed: "+err.Error())
		return
	}
	data, err := screenshot.EncodeJPEG(shot.Image, maxWidth, quality)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "encode screenshot: "+err.Error())
		return
	}
	writeJPEG(w, data, shot.CapturedAt)
}

func writeJPEG(w http.ResponseWriter, data []byte, at time.Time) {
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Header().Set("X-Screenshot-Captured-At", strconv.FormatInt(at.UnixMilli(), 10))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_, _ = w.Write(append(b, '\n'))
}

func writeError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}
