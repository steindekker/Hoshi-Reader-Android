// Package vn exposes the text-hooker line log (LunaHook, Textractor,
// LunaTranslator, clipboard, HTTP ingest) as the bridge's "vn" session.
package vn

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"

	"github.com/steindekker/hoshi-reader/bridge/internal/screenshot"
	"github.com/steindekker/hoshi-reader/bridge/internal/session"
	"github.com/steindekker/hoshi-reader/bridge/internal/store"
)

// SessionID is the id of the VN session.
const SessionID = "vn"

// TrackID identifies the persisted line log; line ids are cue ids.
const TrackID = "log"

// MaxCues is how many recent lines the session (and hello) carries.
const MaxCues = 200

// Capturer grabs a screenshot now (satisfied by *screenshot.Manager).
type Capturer interface {
	Capture(ctx context.Context) (screenshot.Shot, error)
}

// Session keeps the VN session in the registry in sync with the line log.
type Session struct {
	reg   *session.Registry
	shots Capturer // nil when screenshots are disabled
	cache *screenshot.Cache

	mu         sync.Mutex
	registered bool
	title      string
}

// New creates the session; call Register (eagerly) or rely on the first Add.
func New(reg *session.Registry, shots Capturer, cache *screenshot.Cache) *Session {
	if cache == nil {
		cache = screenshot.NewCache(1)
	}
	return &Session{reg: reg, shots: shots, cache: cache}
}

func (s *Session) info() session.Info {
	caps := []string{}
	if s.shots != nil {
		caps = append(caps, session.CapScreenshot)
	}
	return session.Info{
		ID: SessionID, Kind: session.KindVN, Title: s.title, Source: "texthooker",
		Capabilities: caps, TrackID: TrackID,
	}
}

// Register publishes the session seeded with the most recent lines.
func (s *Session) Register(recent []store.Line) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.registerLocked(recent)
}

func (s *Session) registerLocked(recent []store.Line) {
	if s.registered {
		return
	}
	s.registered = true
	info := s.info()
	if n := len(recent); n > 0 {
		info.ActiveAt = recent[n-1].ReceivedAt
	}
	s.reg.Upsert(info, s, MaxCues)
	cues := make([]session.Cue, 0, len(recent))
	for _, l := range recent {
		cues = append(cues, CueFromLine(l))
	}
	if len(cues) > 0 {
		s.reg.ReplaceCues(SessionID, TrackID, cues)
	}
}

// Add appends a stored line (call from the store's OnAdd).
func (s *Session) Add(l store.Line) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.registered {
		s.registerLocked(nil)
	}
	s.reg.AppendCues(SessionID, CueFromLine(l))
}

// SetTitle updates the title (the hooked game, or "" when unknown).
func (s *Session) SetTitle(title string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.title == title {
		return
	}
	s.title = title
	if s.registered {
		s.reg.Update(SessionID, func(i *session.Info) { i.Title = title })
	}
}

// GameTitle turns a hooked executable name into a display title.
func GameTitle(exe *string) string {
	if exe == nil {
		return ""
	}
	t := strings.TrimSpace(*exe)
	if strings.HasSuffix(strings.ToLower(t), ".exe") {
		t = t[:len(t)-4]
	}
	return t
}

// CueFromLine converts a stored line.
func CueFromLine(l store.Line) session.Cue {
	return session.Cue{ID: l.ID, Text: l.Text, At: l.ReceivedAt}
}

// Control implements session.Handler (a VN cannot be controlled).
func (s *Session) Control(context.Context, string, *int64) error { return session.ErrUnsupported }

// Audio implements session.Handler.
func (s *Session) Audio(context.Context, session.RangeRequest) (session.Media, error) {
	return session.Media{}, session.ErrUnsupported
}

// Clip implements session.Handler.
func (s *Session) Clip(context.Context, session.RangeRequest) (session.Media, error) {
	return session.Media{}, session.ErrUnsupported
}

// ErrNoScreenshot wraps capture failures.
var ErrNoScreenshot = errors.New("screenshot failed")

// Screenshot returns the screenshot cached right after the cue's line
// arrived, or captures the screen now.
func (s *Session) Screenshot(ctx context.Context, req session.ScreenshotRequest) (session.Media, error) {
	if req.CueID != nil {
		if c, ok := s.cache.Get(*req.CueID); ok {
			if img, err := c.Image(); err == nil {
				if data, err := screenshot.EncodeJPEG(img, req.MaxWidth, req.Quality); err == nil {
					return session.Media{Data: data, ContentType: "image/jpeg", Header: map[string]string{
						"X-Screenshot-Cue-Id":      strconv.FormatInt(*req.CueID, 10),
						"X-Screenshot-Captured-At": strconv.FormatInt(c.CapturedAt.UnixMilli(), 10),
					}}, nil
				}
			}
		}
	}
	if s.shots == nil {
		return session.Media{}, session.ErrUnsupported
	}
	shot, err := s.shots.Capture(ctx)
	if err != nil {
		return session.Media{}, fmt.Errorf("%w: %v", ErrNoScreenshot, err)
	}
	data, err := screenshot.EncodeJPEG(shot.Image, req.MaxWidth, req.Quality)
	if err != nil {
		return session.Media{}, fmt.Errorf("%w: encode: %v", ErrNoScreenshot, err)
	}
	return session.Media{Data: data, ContentType: "image/jpeg", Header: map[string]string{
		"X-Screenshot-Captured-At": strconv.FormatInt(shot.CapturedAt.UnixMilli(), 10),
	}}, nil
}
