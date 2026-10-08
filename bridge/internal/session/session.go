// Package session is the bridge's v2 data model (see PROTOCOL.md): a bridge
// hosts sessions, each with a cue track, optional playback state, and the
// capabilities its source can serve. The Registry owns that state and
// publishes every change on the hub, under one lock, so a WebSocket client
// can take a consistent snapshot and then follow the event stream.
package session

import (
	"context"
	"errors"
	"slices"
	"sync"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hub"
)

// Kind is what a session streams.
type Kind string

const (
	// KindVN: cues append over time, no timestamps, no playback.
	KindVN Kind = "vn"
	// KindVideo: the whole subtitle track up front, with playback.
	KindVideo Kind = "video"
	// KindWeb: reserved for browser producers (behaves like video).
	KindWeb Kind = "web"
)

// Capabilities a session can advertise.
const (
	CapControl    = "control"
	CapScreenshot = "screenshot"
	CapAudio      = "audio"
	CapClip       = "clip"
)

// Cue modes of a cues message.
const (
	ModeReplace = "replace"
	ModeAppend  = "append"
)

// Cue is one subtitle or text-hooker line.
type Cue struct {
	ID      int64  `json:"id"`
	Text    string `json:"text"`
	StartMs *int64 `json:"startMs,omitempty"`
	EndMs   *int64 `json:"endMs,omitempty"`
	At      int64  `json:"at,omitempty"`
}

// Timed reports whether the cue has a start and end time.
func (c Cue) Timed() bool { return c.StartMs != nil && c.EndMs != nil }

// Playback is the player state of a video session.
type Playback struct {
	PositionMs int64   `json:"positionMs"`
	Paused     bool    `json:"paused"`
	Speed      float64 `json:"speed"`
	At         int64   `json:"at"`
}

// Info describes a session without its cues.
type Info struct {
	ID           string   `json:"id"`
	Kind         Kind     `json:"kind"`
	Title        string   `json:"title"`
	Source       string   `json:"source"`
	Capabilities []string `json:"capabilities"`
	TrackID      string   `json:"trackId"`
	ActiveAt     int64    `json:"activeAt"`
}

// Has reports whether the session advertises capability c.
func (i Info) Has(c string) bool { return slices.Contains(i.Capabilities, c) }

// Snapshot is a session with its cues and playback (the hello payload).
type Snapshot struct {
	Info
	Cues     []Cue     `json:"cues"`
	Playback *Playback `json:"playback,omitempty"`
}

// Hub events.
type (
	// SessionEvent is a session upsert.
	SessionEvent struct{ Info Info }
	// EndedEvent reports that a session went away.
	EndedEvent struct{ ID string }
	// CuesEvent replaces or extends a session's cue track.
	CuesEvent struct {
		SessionID string
		TrackID   string
		Mode      string
		Cues      []Cue
	}
	// PlaybackEvent is a playback state change or heartbeat.
	PlaybackEvent struct {
		SessionID string
		Playback  Playback
	}
)

// Media is an encoded file produced for a command.
type Media struct {
	Data        []byte
	ContentType string
	Header      map[string]string
}

// ScreenshotRequest is a resolved screenshot command. AtMs is set for
// timed sessions (the cue midpoint or an explicit time); CueID for cue-based
// requests.
type ScreenshotRequest struct {
	CueID    *int64
	AtMs     *int64
	Cue      *Cue
	MaxWidth int
	Quality  int
}

// RangeRequest is a resolved audio or clip command.
type RangeRequest struct {
	StartMs  int64
	EndMs    int64
	PadMs    int64
	MaxWidth int
	FPS      int
}

// ErrUnsupported is returned by a Handler for commands it cannot serve.
var ErrUnsupported = errors.New("not supported by this session")

// Handler serves a session's commands.
type Handler interface {
	Control(ctx context.Context, action string, ms *int64) error
	Screenshot(ctx context.Context, req ScreenshotRequest) (Media, error)
	Audio(ctx context.Context, req RangeRequest) (Media, error)
	Clip(ctx context.Context, req RangeRequest) (Media, error)
}

// NoCommands is a Handler that supports nothing (ingest sessions).
type NoCommands struct{}

func (NoCommands) Control(context.Context, string, *int64) error { return ErrUnsupported }
func (NoCommands) Screenshot(context.Context, ScreenshotRequest) (Media, error) {
	return Media{}, ErrUnsupported
}
func (NoCommands) Audio(context.Context, RangeRequest) (Media, error) { return Media{}, ErrUnsupported }
func (NoCommands) Clip(context.Context, RangeRequest) (Media, error)  { return Media{}, ErrUnsupported }

type entry struct {
	info     Info
	cues     []Cue
	maxCues  int // 0 = unbounded
	playback *Playback
	handler  Handler
}

// Registry holds all live sessions.
type Registry struct {
	mu       sync.Mutex
	hub      *hub.Hub
	now      func() time.Time
	sessions map[string]*entry
	order    []string
}

// NewRegistry publishes changes on h.
func NewRegistry(h *hub.Hub) *Registry {
	return &Registry{hub: h, now: time.Now, sessions: map[string]*entry{}}
}

// SetClock overrides the clock (tests).
func (r *Registry) SetClock(now func() time.Time) { r.now = now }

func (r *Registry) nowMs() int64 { return r.now().UnixMilli() }

func cloneInfo(i Info) Info {
	i.Capabilities = append([]string{}, i.Capabilities...)
	return i
}

// Upsert creates or updates a session. maxCues bounds the retained cues
// (0 = keep the whole track). Cues are kept when the track id is unchanged.
func (r *Registry) Upsert(info Info, h Handler, maxCues int) {
	if h == nil {
		h = NoCommands{}
	}
	if info.Capabilities == nil {
		info.Capabilities = []string{}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[info.ID]
	if !ok {
		e = &entry{}
		r.sessions[info.ID] = e
		r.order = append(r.order, info.ID)
		if info.ActiveAt == 0 {
			info.ActiveAt = r.nowMs()
		}
	} else {
		if info.TrackID != e.info.TrackID {
			e.cues = nil
		}
		if info.ActiveAt == 0 {
			info.ActiveAt = e.info.ActiveAt
		}
	}
	e.info, e.handler, e.maxCues = cloneInfo(info), h, maxCues
	r.publish(SessionEvent{Info: cloneInfo(e.info)})
}

// Update applies fn to a session's info and publishes it when it changed.
func (r *Registry) Update(id string, fn func(*Info)) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[id]
	if !ok {
		return
	}
	next := cloneInfo(e.info)
	fn(&next)
	next.ID, next.TrackID = e.info.ID, e.info.TrackID
	if equalInfo(next, e.info) {
		return
	}
	e.info = next
	r.publish(SessionEvent{Info: cloneInfo(next)})
}

func equalInfo(a, b Info) bool {
	return a.ID == b.ID && a.Kind == b.Kind && a.Title == b.Title && a.Source == b.Source &&
		a.TrackID == b.TrackID && a.ActiveAt == b.ActiveAt && slices.Equal(a.Capabilities, b.Capabilities)
}

// End removes a session.
func (r *Registry) End(id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.sessions[id]; !ok {
		return
	}
	delete(r.sessions, id)
	r.order = slices.DeleteFunc(r.order, func(s string) bool { return s == id })
	r.publish(EndedEvent{ID: id})
}

// ReplaceCues sets a session's whole track.
func (r *Registry) ReplaceCues(id, trackID string, cues []Cue) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[id]
	if !ok {
		return
	}
	cues = append([]Cue{}, cues...)
	if e.maxCues > 0 && len(cues) > e.maxCues {
		cues = cues[len(cues)-e.maxCues:]
	}
	e.cues = cues
	e.info.TrackID = trackID
	e.info.ActiveAt = r.nowMs()
	r.publish(CuesEvent{SessionID: id, TrackID: trackID, Mode: ModeReplace, Cues: append([]Cue{}, cues...)})
}

// AppendCues adds cues to the end of the current track.
func (r *Registry) AppendCues(id string, cues ...Cue) {
	if len(cues) == 0 {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[id]
	if !ok {
		return
	}
	e.cues = append(e.cues, cues...)
	if e.maxCues > 0 && len(e.cues) > e.maxCues {
		e.cues = append([]Cue(nil), e.cues[len(e.cues)-e.maxCues:]...)
	}
	e.info.ActiveAt = r.nowMs()
	r.publish(CuesEvent{SessionID: id, TrackID: e.info.TrackID, Mode: ModeAppend, Cues: append([]Cue{}, cues...)})
}

// SetPlayback records and publishes the playback state. A playing session,
// or a change of pause/speed/seek, counts as activity.
func (r *Registry) SetPlayback(id string, pb Playback, activity bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[id]
	if !ok {
		return
	}
	if pb.At == 0 {
		pb.At = r.nowMs()
	}
	if pb.Speed == 0 {
		pb.Speed = 1
	}
	p := pb
	e.playback = &p
	if activity || !pb.Paused {
		e.info.ActiveAt = r.nowMs()
	}
	r.publish(PlaybackEvent{SessionID: id, Playback: pb})
}

func (r *Registry) publish(v any) {
	if r.hub != nil {
		r.hub.Publish(v)
	}
}

func (r *Registry) snapshotLocked() []Snapshot {
	out := make([]Snapshot, 0, len(r.order))
	for _, id := range r.order {
		e := r.sessions[id]
		s := Snapshot{Info: cloneInfo(e.info), Cues: append([]Cue{}, e.cues...)}
		if e.playback != nil {
			p := *e.playback
			s.Playback = &p
		}
		out = append(out, s)
	}
	return out
}

// Snapshot returns every session with its cues.
func (r *Registry) Snapshot() []Snapshot {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.snapshotLocked()
}

// Infos returns every session without cues.
func (r *Registry) Infos() []Info {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]Info, 0, len(r.order))
	for _, id := range r.order {
		out = append(out, cloneInfo(r.sessions[id].info))
	}
	return out
}

// Subscribe atomically takes a snapshot and subscribes to later events, so
// no change falls in between.
func (r *Registry) Subscribe(buffer int) (*hub.Sub, []Snapshot) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.hub.Subscribe(buffer), r.snapshotLocked()
}

// Get returns a session's info and handler.
func (r *Registry) Get(id string) (Info, Handler, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[id]
	if !ok {
		return Info{}, nil, false
	}
	return cloneInfo(e.info), e.handler, true
}

// Playback returns a session's last playback state.
func (r *Registry) Playback(id string) (Playback, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[id]
	if !ok || e.playback == nil {
		return Playback{}, false
	}
	return *e.playback, true
}

// Cue finds a cue of the session's current track.
func (r *Registry) Cue(id string, cueID int64) (Cue, string, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[id]
	if !ok {
		return Cue{}, "", false
	}
	i := slices.IndexFunc(e.cues, func(c Cue) bool { return c.ID == cueID })
	if i < 0 {
		return Cue{}, e.info.TrackID, false
	}
	return e.cues[i], e.info.TrackID, true
}

// Ms returns a pointer to v (for optional cue times).
func Ms(v int64) *int64 { return &v }
