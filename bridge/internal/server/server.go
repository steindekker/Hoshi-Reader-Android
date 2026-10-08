// Package server implements the HTTP + WebSocket API described in PROTOCOL.md (v2).
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
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hook"
	"github.com/steindekker/hoshi-reader/bridge/internal/session"
	"github.com/steindekker/hoshi-reader/bridge/internal/status"
	"github.com/steindekker/hoshi-reader/bridge/internal/store"
)

// Protocol is the PROTOCOL.md version implemented here.
const Protocol = 2

// BridgeName is the "name" field of the bridge object.
const BridgeName = "hoshi-bridge"

//go:embed debug.html
var debugPage []byte

// StatusChanged is published on the hub when the bridge object changes.
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

// LineAdder ingests a text-hooker line (satisfied by *store.Store).
type LineAdder interface {
	Add(text, source string) (store.Line, store.Result, error)
}

// Options wires the server to the rest of the daemon.
type Options struct {
	Version        string
	Hostname       string
	Token          string
	AllowedOrigins []string
	Registry       *session.Registry
	Lines          LineAdder // POST /v2/ingest/lines; nil disables it
	Tracker        *status.Tracker
	Hooks          Hooks
	Log            *slog.Logger
	PingInterval   time.Duration // default 20 s
	// Reserved session ids that ingest producers may not use.
	ReservedIDs []string
}

// Server is the API server.
type Server struct {
	opts    Options
	mux     *http.ServeMux
	ctx     context.Context // cancelled on Shutdown; ends WebSocket sessions
	cancel  context.CancelFunc
	wsWG    sync.WaitGroup
	httpSrv *http.Server

	ingestMu sync.Mutex
	ingest   map[string]int64 // ingest session id -> next cue id
}

// Bridge is the bridge object (PROTOCOL.md).
type Bridge struct {
	Name       string            `json:"name"`
	Version    string            `json:"version"`
	Protocol   int               `json:"protocol"`
	Hostname   string            `json:"hostname"`
	Sources    []status.Source   `json:"sources"`
	Screenshot status.Screenshot `json:"screenshot"`
	Hook       hook.PublicStatus `json:"hook"`
}

// New builds the server and its routes.
func New(opts Options) *Server {
	if opts.Log == nil {
		opts.Log = slog.Default()
	}
	if opts.PingInterval <= 0 {
		opts.PingInterval = 20 * time.Second
	}
	if opts.Tracker == nil {
		opts.Tracker = status.NewTracker()
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Server{opts: opts, mux: http.NewServeMux(), ctx: ctx, cancel: cancel, ingest: map[string]int64{}}
	s.mux.HandleFunc("GET /v2/status", s.handleStatus)
	s.mux.HandleFunc("GET /v2/ws", s.handleWS)
	s.mux.HandleFunc("POST /v2/sessions/{id}/control", s.handleControl)
	s.mux.HandleFunc("POST /v2/sessions/{id}/screenshot", s.handleScreenshot)
	s.mux.HandleFunc("POST /v2/sessions/{id}/audio", s.handleAudio)
	s.mux.HandleFunc("POST /v2/sessions/{id}/clip", s.handleClip)
	s.mux.HandleFunc("POST /v2/ingest/lines", s.handleIngestLines)
	s.mux.HandleFunc("POST /v2/ingest/sessions/{id}", s.handleIngestSession)
	s.mux.HandleFunc("DELETE /v2/ingest/sessions/{id}", s.handleIngestEnd)
	s.mux.HandleFunc("GET /v2/hook/threads", s.handleHookThreads)
	s.mux.HandleFunc("POST /v2/hook/threads/select", s.handleHookSelect)
	s.mux.HandleFunc("/v2/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "not found")
	})
	s.mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusGone, "protocol v1 was removed; update Hoshi Reader (this bridge speaks /v2)")
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

// Bridge builds the current bridge object.
func (s *Server) Bridge() Bridge {
	src := s.opts.Tracker.Sources()
	if src == nil {
		src = []status.Source{}
	}
	return Bridge{
		Name:       BridgeName,
		Version:    s.opts.Version,
		Protocol:   Protocol,
		Hostname:   s.opts.Hostname,
		Sources:    src,
		Screenshot: s.opts.Tracker.Screenshot(),
		Hook:       s.hookStatus(),
	}
}

func (s *Server) hookStatus() hook.PublicStatus {
	if s.opts.Hooks == nil {
		return hook.PublicStatus{State: hook.StateIdle, Mode: hook.ModeAuto}
	}
	return s.opts.Hooks.View()
}

func (s *Server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.opts.Token == "" || !strings.HasPrefix(r.URL.Path, "/v2/") {
			next.ServeHTTP(w, r)
			return
		}
		got := ""
		if h := r.Header.Get("Authorization"); len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
			got = strings.TrimSpace(h[7:])
		} else if r.URL.Path == "/v2/ws" {
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
	writeJSON(w, http.StatusOK, map[string]any{
		"bridge":   s.Bridge(),
		"sessions": s.opts.Registry.Infos(),
	})
}

// --- hook threads ----------------------------------------------------------

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

func (s *Server) threads() threadsResponse {
	if s.opts.Hooks == nil {
		return threadsFrom(hook.Snapshot{Mode: hook.ModeAuto})
	}
	return threadsFrom(s.opts.Hooks.Threads())
}

func (s *Server) handleHookThreads(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.threads())
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
	writeJSON(w, http.StatusOK, s.threads())
}

// --- session commands ------------------------------------------------------

const (
	defaultShotWidth = 1280
	defaultQuality   = 85
	defaultPadMs     = 150
	maxPadMs         = 2000
	defaultClipWidth = 480
	defaultClipFPS   = 12
	maxClipMs        = 6000
	maxAudioMs       = 60000
)

// decodeBody decodes an optional JSON body (empty body = zero value).
func decodeBody(r *http.Request, v any) error {
	data, err := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	if err != nil {
		return err
	}
	if len(strings.TrimSpace(string(data))) == 0 {
		return nil
	}
	dec := json.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

func (s *Server) session(w http.ResponseWriter, r *http.Request, capability string) (session.Info, session.Handler, bool) {
	id := r.PathValue("id")
	info, h, ok := s.opts.Registry.Get(id)
	if !ok {
		writeError(w, http.StatusNotFound, "unknown session "+strconv.Quote(id))
		return info, nil, false
	}
	if capability != "" && !info.Has(capability) {
		writeError(w, http.StatusNotImplemented, fmt.Sprintf("session %q does not support %s", id, capability))
		return info, nil, false
	}
	return info, h, true
}

// resolveCue looks up cueId in the session's current track. When trackId is
// given and differs from the current track, the cue is stale (409).
func (s *Server) resolveCue(w http.ResponseWriter, sessionID, trackID string, cueID int64) (session.Cue, bool) {
	cue, current, ok := s.opts.Registry.Cue(sessionID, cueID)
	if trackID != "" && trackID != current {
		writeError(w, http.StatusConflict, "the subtitle track changed; cue ids no longer match")
		return cue, false
	}
	if !ok {
		writeError(w, http.StatusNotFound, fmt.Sprintf("unknown cue %d", cueID))
		return cue, false
	}
	return cue, true
}

func (s *Server) handleControl(w http.ResponseWriter, r *http.Request) {
	_, h, ok := s.session(w, r, session.CapControl)
	if !ok {
		return
	}
	var body struct {
		Action string `json:"action"`
		Ms     *int64 `json:"ms"`
	}
	if err := decodeBody(r, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	switch body.Action {
	case "pause", "play":
	case "seek":
		if body.Ms == nil || *body.Ms < 0 {
			writeError(w, http.StatusBadRequest, "seek needs a non-negative ms")
			return
		}
	default:
		writeError(w, http.StatusBadRequest, "action must be pause, play or seek")
		return
	}
	if err := h.Control(r.Context(), body.Action, body.Ms); err != nil {
		s.commandError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) handleScreenshot(w http.ResponseWriter, r *http.Request) {
	info, h, ok := s.session(w, r, session.CapScreenshot)
	if !ok {
		return
	}
	var body struct {
		TrackID  string `json:"trackId"`
		CueID    *int64 `json:"cueId"`
		AtMs     *int64 `json:"atMs"`
		MaxWidth *int   `json:"maxWidth"`
		Quality  *int   `json:"quality"`
	}
	if err := decodeBody(r, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	req := session.ScreenshotRequest{MaxWidth: defaultShotWidth, Quality: defaultQuality, AtMs: body.AtMs}
	if body.MaxWidth != nil {
		if *body.MaxWidth < 0 {
			writeError(w, http.StatusBadRequest, "maxWidth must be non-negative")
			return
		}
		req.MaxWidth = *body.MaxWidth
	}
	if body.Quality != nil {
		if *body.Quality < 1 || *body.Quality > 100 {
			writeError(w, http.StatusBadRequest, "quality must be 1-100")
			return
		}
		req.Quality = *body.Quality
	}
	if body.AtMs != nil && *body.AtMs < 0 {
		writeError(w, http.StatusBadRequest, "atMs must be non-negative")
		return
	}
	if body.CueID != nil {
		req.CueID = body.CueID
		if info.Kind != session.KindVN {
			cue, ok := s.resolveCue(w, info.ID, body.TrackID, *body.CueID)
			if !ok {
				return
			}
			req.Cue = &cue
			if req.AtMs == nil && cue.Timed() {
				mid := (*cue.StartMs + *cue.EndMs) / 2
				req.AtMs = &mid
			}
		}
	}
	m, err := h.Screenshot(r.Context(), req)
	if err != nil {
		s.commandError(w, err)
		return
	}
	writeMedia(w, m)
}

type rangeBody struct {
	TrackID  string `json:"trackId"`
	CueID    *int64 `json:"cueId"`
	StartMs  *int64 `json:"startMs"`
	EndMs    *int64 `json:"endMs"`
	PadMs    *int64 `json:"padMs"`
	MaxWidth *int   `json:"maxWidth"`
	FPS      *int   `json:"fps"`
}

// resolveRange turns a cue or explicit range into start/end times.
func (s *Server) resolveRange(w http.ResponseWriter, r *http.Request, info session.Info, maxMs int64) (session.RangeRequest, bool) {
	var body rangeBody
	if err := decodeBody(r, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return session.RangeRequest{}, false
	}
	req := session.RangeRequest{PadMs: defaultPadMs, MaxWidth: defaultClipWidth, FPS: defaultClipFPS}
	switch {
	case body.CueID != nil:
		cue, ok := s.resolveCue(w, info.ID, body.TrackID, *body.CueID)
		if !ok {
			return req, false
		}
		if !cue.Timed() {
			writeError(w, http.StatusBadRequest, "cue has no time range")
			return req, false
		}
		req.StartMs, req.EndMs = *cue.StartMs, *cue.EndMs
	case body.StartMs != nil && body.EndMs != nil:
		req.StartMs, req.EndMs = *body.StartMs, *body.EndMs
	default:
		writeError(w, http.StatusBadRequest, "give cueId or startMs and endMs")
		return req, false
	}
	if req.StartMs < 0 || req.EndMs <= req.StartMs {
		writeError(w, http.StatusBadRequest, "need 0 <= startMs < endMs")
		return req, false
	}
	if req.EndMs-req.StartMs > maxMs {
		req.EndMs = req.StartMs + maxMs
	}
	if body.PadMs != nil {
		req.PadMs = min(max(*body.PadMs, 0), maxPadMs)
	}
	if body.MaxWidth != nil {
		if *body.MaxWidth < 16 || *body.MaxWidth > 1920 {
			writeError(w, http.StatusBadRequest, "maxWidth must be 16-1920")
			return req, false
		}
		req.MaxWidth = *body.MaxWidth
	}
	if body.FPS != nil {
		if *body.FPS < 1 || *body.FPS > 30 {
			writeError(w, http.StatusBadRequest, "fps must be 1-30")
			return req, false
		}
		req.FPS = *body.FPS
	}
	return req, true
}

func (s *Server) handleAudio(w http.ResponseWriter, r *http.Request) {
	info, h, ok := s.session(w, r, session.CapAudio)
	if !ok {
		return
	}
	req, ok := s.resolveRange(w, r, info, maxAudioMs)
	if !ok {
		return
	}
	m, err := h.Audio(r.Context(), req)
	if err != nil {
		s.commandError(w, err)
		return
	}
	writeMedia(w, m)
}

func (s *Server) handleClip(w http.ResponseWriter, r *http.Request) {
	info, h, ok := s.session(w, r, session.CapClip)
	if !ok {
		return
	}
	req, ok := s.resolveRange(w, r, info, maxClipMs)
	if !ok {
		return
	}
	req.PadMs = 0
	m, err := h.Clip(r.Context(), req)
	if err != nil {
		s.commandError(w, err)
		return
	}
	writeMedia(w, m)
}

func (s *Server) commandError(w http.ResponseWriter, err error) {
	if errors.Is(err, session.ErrUnsupported) {
		writeError(w, http.StatusNotImplemented, err.Error())
		return
	}
	s.opts.Log.Warn("session command failed", "err", err)
	writeError(w, http.StatusServiceUnavailable, err.Error())
}

// --- ingest -----------------------------------------------------------------

const maxIngestBytes = 1 << 20

func (s *Server) handleIngestLines(w http.ResponseWriter, r *http.Request) {
	if s.opts.Lines == nil {
		writeError(w, http.StatusNotFound, "line ingest is not available")
		return
	}
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
	line, res, err := s.opts.Lines.Add(text, source)
	if err != nil {
		s.opts.Log.Error("store line", "err", err)
		writeError(w, http.StatusInternalServerError, "could not store line")
		return
	}
	switch res {
	case store.Duplicate:
		writeJSON(w, http.StatusOK, map[string]any{"duplicate": true})
	case store.Filtered:
		writeJSON(w, http.StatusOK, map[string]any{"duplicate": false, "filtered": true})
	default:
		writeJSON(w, http.StatusCreated, session.Cue{ID: line.ID, Text: line.Text, At: line.ReceivedAt})
	}
}

var ingestIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

func (s *Server) ingestID(w http.ResponseWriter, r *http.Request) (string, bool) {
	id := r.PathValue("id")
	if !ingestIDPattern.MatchString(id) {
		writeError(w, http.StatusBadRequest, "session id must match "+ingestIDPattern.String())
		return "", false
	}
	for _, reserved := range s.opts.ReservedIDs {
		if id == reserved {
			writeError(w, http.StatusConflict, "session id "+strconv.Quote(id)+" is reserved")
			return "", false
		}
	}
	return id, true
}

func (s *Server) handleIngestSession(w http.ResponseWriter, r *http.Request) {
	id, ok := s.ingestID(w, r)
	if !ok {
		return
	}
	var body struct {
		Kind     session.Kind      `json:"kind"`
		Title    string            `json:"title"`
		TrackID  string            `json:"trackId"`
		Mode     string            `json:"mode"`
		Cues     []session.Cue     `json:"cues"`
		Playback *session.Playback `json:"playback"`
	}
	data, err := io.ReadAll(io.LimitReader(r.Body, 16<<20))
	if err != nil || json.Unmarshal(data, &body) != nil {
		writeError(w, http.StatusBadRequest, "body must be a JSON object")
		return
	}
	switch body.Kind {
	case "":
		body.Kind = session.KindWeb
	case session.KindVN, session.KindVideo, session.KindWeb:
	default:
		writeError(w, http.StatusBadRequest, "kind must be vn, video or web")
		return
	}
	if body.Mode == "" {
		body.Mode = session.ModeAppend
	}
	if body.Mode != session.ModeAppend && body.Mode != session.ModeReplace {
		writeError(w, http.StatusBadRequest, "mode must be replace or append")
		return
	}
	if body.TrackID == "" {
		body.TrackID = "ingest"
	}
	s.ingestMu.Lock()
	defer s.ingestMu.Unlock()
	info, _, exists := s.opts.Registry.Get(id)
	if !exists || info.Kind != body.Kind || info.Title != body.Title || info.TrackID != body.TrackID {
		maxCues := 0
		if body.Kind == session.KindVN {
			maxCues = 200
		}
		s.opts.Registry.Upsert(session.Info{
			ID: id, Kind: body.Kind, Title: body.Title, Source: "ingest",
			Capabilities: []string{}, TrackID: body.TrackID,
		}, session.NoCommands{}, maxCues)
		if !exists || info.TrackID != body.TrackID {
			s.ingest[id] = 1
		}
	}
	cues := make([]session.Cue, 0, len(body.Cues))
	next := s.ingest[id]
	if body.Mode == session.ModeReplace {
		next = 1
	}
	for _, c := range body.Cues {
		if strings.TrimSpace(c.Text) == "" {
			continue
		}
		if c.ID <= 0 {
			c.ID = next
		}
		next = max(next, c.ID+1)
		cues = append(cues, c)
	}
	s.ingest[id] = next
	if body.Mode == session.ModeReplace {
		s.opts.Registry.ReplaceCues(id, body.TrackID, cues)
	} else {
		s.opts.Registry.AppendCues(id, cues...)
	}
	if body.Playback != nil {
		s.opts.Registry.SetPlayback(id, *body.Playback, true)
	}
	info, _, _ = s.opts.Registry.Get(id)
	writeJSON(w, http.StatusOK, info)
}

func (s *Server) handleIngestEnd(w http.ResponseWriter, r *http.Request) {
	id, ok := s.ingestID(w, r)
	if !ok {
		return
	}
	s.ingestMu.Lock()
	delete(s.ingest, id)
	s.ingestMu.Unlock()
	s.opts.Registry.End(id)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// --- helpers ----------------------------------------------------------------

func writeMedia(w http.ResponseWriter, m session.Media) {
	for k, v := range m.Header {
		w.Header().Set(k, v)
	}
	w.Header().Set("Content-Type", m.ContentType)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.Itoa(len(m.Data)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(m.Data)
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
