package hook

import (
	"errors"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hookproto"
)

// Selection modes.
const (
	ModeAuto   = "auto"
	ModeManual = "manual"
)

// ErrUnknownThread is returned by Select for an id that is not in the list.
var ErrUnknownThread = errors.New("unknown thread id")

// Sample is one recent output of a thread (cleaned text).
type Sample struct {
	Text string `json:"text"`
	At   int64  `json:"at"`
}

// ThreadInfo is a text thread as reported by GET /api/hook/threads.
type ThreadInfo struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	HookCode     string   `json:"hookCode"`
	Process      string   `json:"process"`
	Score        float64  `json:"score"`
	Selected     bool     `json:"selected"`
	Remembered   bool     `json:"remembered"`
	OutputCount  int      `json:"outputCount"`
	LastOutputAt *int64   `json:"lastOutputAt"`
	Samples      []Sample `json:"samples"`
}

const (
	keepSamples   = 8  // samples used for scoring
	showSamples   = 5  // samples reported over the API
	keepTimes     = 64 // output timestamps used for cadence
	minAutoScore  = 0.35
	freshForEmit  = 15 * time.Second // a newly selected thread's last sample becomes a line if younger
	rememberAfter = 3                // auto choice is persisted after this many lines
)

type thread struct {
	key        string
	t          hookproto.Thread
	exe        string
	samples    []Sample
	times      []time.Time
	count      int
	lastAt     time.Time
	lastText   string
	remembered bool
}

// Selector keeps the text threads of the attached game, scores them, picks
// the dialogue thread (automatically with hysteresis, or as chosen by the
// user), remembers the choice per game, and forwards the selected thread's
// sentences to Emit.
type Selector struct {
	mu       sync.Mutex
	threads  map[string]*thread
	selected string
	mode     string
	// linesSinceSelect counts sentences emitted by the current selection
	// (for persisting an automatic choice once it proved itself).
	linesSinceSelect int
	persisted        bool

	memory   *Memory
	emit     func(text string)
	onChange func(important bool)
	now      func() time.Time
	log      *slog.Logger
}

// SelectorOptions configures a Selector.
type SelectorOptions struct {
	Memory   *Memory           // nil = do not remember choices
	Emit     func(text string) // receives the selected thread's sentences
	OnChange func(important bool)
	Now      func() time.Time
	Log      *slog.Logger
}

// NewSelector creates an empty selector in auto mode.
func NewSelector(o SelectorOptions) *Selector {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.Log == nil {
		o.Log = slog.Default()
	}
	if o.Emit == nil {
		o.Emit = func(string) {}
	}
	if o.OnChange == nil {
		o.OnChange = func(bool) {}
	}
	if o.Memory == nil {
		o.Memory, _ = OpenMemory("")
	}
	return &Selector{
		threads: map[string]*thread{}, mode: ModeAuto,
		memory: o.Memory, emit: o.Emit, onChange: o.OnChange, now: o.Now, log: o.Log,
	}
}

// Reset forgets all threads (the game exited). The mode returns to auto.
func (s *Selector) Reset() {
	s.mu.Lock()
	changed := len(s.threads) > 0 || s.selected != "" || s.mode != ModeAuto
	s.threads = map[string]*thread{}
	s.selected = ""
	s.mode = ModeAuto
	s.linesSinceSelect = 0
	s.persisted = false
	s.mu.Unlock()
	if changed {
		s.onChange(true)
	}
}

// AddThread registers a new text thread of process exe.
func (s *Selector) AddThread(t hookproto.Thread, exe string) {
	s.mu.Lock()
	key := t.Key()
	th, ok := s.threads[key]
	if !ok {
		th = &thread{key: key, t: t, exe: exe}
		s.threads[key] = th
	}
	if c, ok := s.memory.Get(exe); ok && c.Matches(t) {
		th.remembered = true
	}
	var emits []string
	if th.remembered {
		cur := s.threads[s.selected]
		userPicked := s.mode == ModeManual && cur != nil
		if !userPicked && (cur == nil || !cur.remembered) {
			if c, _ := s.memory.Get(exe); c.Manual {
				s.mode = ModeManual
			}
			emits = s.selectLocked(th, "remembered")
		}
	}
	s.mu.Unlock()
	s.flush(emits)
	s.onChange(true)
}

// RemoveThread drops a thread (its hook was removed).
func (s *Selector) RemoveThread(t hookproto.Thread) {
	s.mu.Lock()
	key := t.Key()
	if _, ok := s.threads[key]; !ok {
		s.mu.Unlock()
		return
	}
	delete(s.threads, key)
	var emits []string
	if s.selected == key {
		s.selected = ""
		s.mode = ModeAuto
		emits = s.evaluateLocked()
	}
	s.mu.Unlock()
	s.flush(emits)
	s.onChange(true)
}

// Output records a thread's raw output. It returns the cleaned text.
func (s *Selector) Output(t hookproto.Thread, exe, raw string) string {
	text := strings.TrimSpace(Clean(raw))
	if text == "" {
		return ""
	}
	now := s.now()
	s.mu.Lock()
	key := t.Key()
	th, ok := s.threads[key]
	if !ok {
		// Output for a thread whose create event we missed.
		th = &thread{key: key, t: t, exe: exe}
		if c, ok := s.memory.Get(exe); ok && c.Matches(t) {
			th.remembered = true
		}
		s.threads[key] = th
	}
	th.samples = append(th.samples, Sample{Text: text, At: now.UnixMilli()})
	if len(th.samples) > keepSamples {
		th.samples = th.samples[len(th.samples)-keepSamples:]
	}
	th.times = append(th.times, now)
	if len(th.times) > keepTimes {
		th.times = th.times[len(th.times)-keepTimes:]
	}
	th.count++
	th.lastAt = now
	var emits []string
	important := !ok
	if s.selected == key {
		emits = append(emits, text)
		th.lastText = text
		s.linesSinceSelect++
		if s.mode == ModeAuto && !s.persisted && s.linesSinceSelect >= rememberAfter {
			s.rememberLocked(th, false)
		}
	} else {
		before := s.selected
		emits = s.evaluateLocked()
		important = important || s.selected != before
	}
	s.mu.Unlock()
	s.flush(emits)
	s.onChange(important)
	return text
}

// Select picks a thread manually (id != nil) or returns to automatic
// selection (id == nil).
func (s *Selector) Select(id *string) error {
	s.mu.Lock()
	var emits []string
	if id == nil {
		s.mode = ModeAuto
		if th := s.threads[s.selected]; th != nil {
			if c, ok := s.memory.Get(th.exe); ok && c.Manual {
				if err := s.memory.Forget(th.exe); err != nil {
					s.log.Warn("forget thread choice", "err", err)
				}
				for _, o := range s.threads {
					if o.exe == th.exe {
						o.remembered = false
					}
				}
			}
		}
		s.persisted = false
		emits = s.evaluateLocked()
	} else {
		th, ok := s.threads[*id]
		if !ok {
			s.mu.Unlock()
			return ErrUnknownThread
		}
		s.mode = ModeManual
		if s.selected != th.key {
			emits = s.selectLocked(th, "manual")
		}
		s.rememberLocked(th, true)
	}
	s.mu.Unlock()
	s.flush(emits)
	s.onChange(true)
	return nil
}

func (s *Selector) flush(texts []string) {
	for _, t := range texts {
		s.emit(t)
	}
}

func (s *Selector) rememberLocked(th *thread, manual bool) {
	if th.exe == "" {
		return
	}
	if err := s.memory.Set(th.exe, ChoiceFor(th.t, manual, s.now().UnixMilli())); err != nil {
		s.log.Warn("remember thread choice", "err", err)
		return
	}
	for _, o := range s.threads {
		o.remembered = o.exe == th.exe && o.key == th.key
	}
	s.persisted = true
	s.log.Info("remembered text thread", "game", th.exe, "hook", th.t.Code, "name", th.t.Name, "manual", manual)
}

// selectLocked makes th the selected thread. The thread's latest sample is
// returned for emission when it is fresh, so the sentence on screen when the
// choice is made is not lost.
func (s *Selector) selectLocked(th *thread, reason string) []string {
	s.selected = th.key
	s.linesSinceSelect = 0
	s.persisted = th.remembered
	s.log.Info("text thread selected", "reason", reason, "id", th.key, "hook", th.t.Code, "name", th.t.Name,
		"score", scoreThread(th.samples, th.times, s.now()))
	if n := len(th.samples); n > 0 {
		last := th.samples[n-1]
		if s.now().Sub(time.UnixMilli(last.At)) <= freshForEmit && last.Text != th.lastText {
			th.lastText = last.Text
			return []string{last.Text}
		}
	}
	return nil
}

// evaluateLocked applies automatic selection with hysteresis.
func (s *Selector) evaluateLocked() []string {
	if s.mode == ModeManual && s.threads[s.selected] != nil {
		return nil
	}
	now := s.now()
	var best *thread
	bestScore := -1.0
	for _, th := range s.threads {
		sc := scoreThread(th.samples, th.times, now)
		if sc > bestScore || sc == bestScore && best != nil && th.lastAt.After(best.lastAt) {
			best, bestScore = th, sc
		}
	}
	cur := s.threads[s.selected]
	if best == nil || best == cur {
		return nil
	}
	if cur == nil {
		if bestScore >= minAutoScore {
			return s.selectLocked(best, "auto")
		}
		return nil
	}
	curScore := scoreThread(cur.samples, cur.times, now)
	factor, margin, need := 1.3, 0.05, 3
	if cur.remembered {
		factor, margin, need = 2.0, 0.1, 6
	}
	// Outputs of the challenger since the current thread last spoke.
	since := 0
	for _, t := range best.times {
		if t.After(cur.lastAt) {
			since++
		}
	}
	clearlyBetter := bestScore > curScore*factor+margin && len(best.samples) >= 3
	takesOver := since >= need && bestScore >= 0.5 && bestScore >= curScore*0.8
	if clearlyBetter || takesOver {
		return s.selectLocked(best, "auto")
	}
	return nil
}

// Snapshot is the selector part of the thread list.
type Snapshot struct {
	Mode       string
	SelectedID string
	Threads    []ThreadInfo
}

// Snapshot returns the threads sorted by score (best first).
func (s *Selector) Snapshot() Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	out := Snapshot{Mode: s.mode, SelectedID: s.selected, Threads: make([]ThreadInfo, 0, len(s.threads))}
	if s.threads[s.selected] == nil {
		out.SelectedID = ""
	}
	for _, th := range s.threads {
		info := ThreadInfo{
			ID: th.key, Name: th.t.Name, HookCode: th.t.Code, Process: th.exe,
			Score:    scoreThread(th.samples, th.times, now),
			Selected: th.key == s.selected, Remembered: th.remembered,
			OutputCount: th.count, Samples: []Sample{},
		}
		if !th.lastAt.IsZero() {
			ms := th.lastAt.UnixMilli()
			info.LastOutputAt = &ms
		}
		start := max(len(th.samples)-showSamples, 0)
		info.Samples = append(info.Samples, th.samples[start:]...)
		out.Threads = append(out.Threads, info)
	}
	sort.SliceStable(out.Threads, func(i, j int) bool {
		a, b := out.Threads[i], out.Threads[j]
		if a.Score != b.Score {
			return a.Score > b.Score
		}
		if a.OutputCount != b.OutputCount {
			return a.OutputCount > b.OutputCount
		}
		return a.ID < b.ID
	})
	return out
}

// Selected returns the selected thread id ("" if none) and the mode.
func (s *Selector) Selected() (string, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.threads[s.selected] == nil {
		return "", s.mode
	}
	return s.selected, s.mode
}

// Count returns the number of known threads.
func (s *Selector) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.threads)
}
