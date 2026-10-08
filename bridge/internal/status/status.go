// Package status tracks the health of text sources and the screenshot
// subsystem and reports meaningful changes.
package status

import "sync"

// Source is the per-source entry of the status object (PROTOCOL.md).
type Source struct {
	ID        string  `json:"id"`
	Kind      string  `json:"kind"`
	Target    string  `json:"target"`
	Connected bool    `json:"connected"`
	LastError *string `json:"lastError"`
}

// Screenshot is the screenshot entry of the status object.
type Screenshot struct {
	Available bool    `json:"available"`
	Backend   *string `json:"backend"`
}

// Tracker holds source and screenshot state. OnChange is invoked (outside
// the lock) whenever a source connects/disconnects or screenshot
// availability/backend changes.
type Tracker struct {
	mu       sync.Mutex
	sources  []*Source
	shot     Screenshot
	onChange func()
}

func NewTracker() *Tracker { return &Tracker{} }

// SetOnChange installs the change callback. Call before sources start.
func (t *Tracker) SetOnChange(f func()) {
	t.mu.Lock()
	t.onChange = f
	t.mu.Unlock()
}

// Handle lets one source report its state.
type Handle struct {
	t *Tracker
	s *Source
}

// Register adds a source in configuration order.
func (t *Tracker) Register(id, kind, target string, connected bool) *Handle {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := &Source{ID: id, Kind: kind, Target: target, Connected: connected}
	t.sources = append(t.sources, s)
	return &Handle{t: t, s: s}
}

// Set records the source state. A nil err clears lastError.
func (h *Handle) Set(connected bool, err error) {
	h.t.mu.Lock()
	changed := h.s.Connected != connected
	h.s.Connected = connected
	if err != nil {
		msg := err.Error()
		h.s.LastError = &msg
	} else {
		h.s.LastError = nil
	}
	f := h.t.onChange
	h.t.mu.Unlock()
	if changed && f != nil {
		f()
	}
}

// SetScreenshot records whether a screenshot backend is usable.
func (t *Tracker) SetScreenshot(available bool, backend string) {
	t.mu.Lock()
	var b *string
	if backend != "" {
		b = &backend
	}
	old := t.shot
	changed := old.Available != available || deref(old.Backend) != backend
	t.shot = Screenshot{Available: available, Backend: b}
	f := t.onChange
	t.mu.Unlock()
	if changed && f != nil {
		f()
	}
}

// Sources returns a copy of all source states.
func (t *Tracker) Sources() []Source {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := make([]Source, len(t.sources))
	for i, s := range t.sources {
		out[i] = *s
		if s.LastError != nil {
			e := *s.LastError
			out[i].LastError = &e
		}
	}
	return out
}

// Screenshot returns the screenshot state.
func (t *Tracker) Screenshot() Screenshot {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := t.shot
	if s.Backend != nil {
		b := *s.Backend
		s.Backend = &b
	}
	return s
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
