// Package store keeps the sentence log: an in-memory window of the most
// recent lines backed by an append-only JSONL file.
package store

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Line is one extracted sentence (see PROTOCOL.md "Line object").
type Line struct {
	ID            int64  `json:"id"`
	Text          string `json:"text"`
	Source        string `json:"source"`
	ReceivedAt    int64  `json:"receivedAt"`
	HasScreenshot bool   `json:"hasScreenshot"`
}

// record is the on-disk JSONL form. hasScreenshot is runtime-only state.
type record struct {
	ID         int64  `json:"id"`
	Text       string `json:"text"`
	Source     string `json:"source"`
	ReceivedAt int64  `json:"receivedAt"`
}

// Result describes what Add did with a line.
type Result int

const (
	Added     Result = iota
	Duplicate        // same text as the previous line
	Filtered         // empty after cleaning, too long, or matched a drop pattern
)

// Options configures a Store.
type Options struct {
	// Path of the JSONL log. Empty keeps the log in memory only.
	Path string
	// MaxLines is how many lines are retained (memory and, after compaction, disk).
	MaxLines int
	// Dedupe drops a line whose text equals the previous line's text.
	Dedupe bool
	// Normalizer cleans text; nil only trims.
	Normalizer *Normalizer
	// OnAdd is called synchronously, in id order, for each added line. It
	// runs under the store lock and must not block or call back into the store.
	OnAdd func(Line)
	// Now overrides the clock (tests).
	Now func() time.Time
	Log *slog.Logger
}

// Store is safe for concurrent use.
type Store struct {
	mu        sync.Mutex
	opts      Options
	lines     []Line // ascending by id, at most MaxLines
	nextID    int64
	lastText  string
	file      *os.File
	fileLines int
}

const defaultMaxLines = 5000

// Open loads the existing log (if any) and opens it for appending.
func Open(opts Options) (*Store, error) {
	if opts.MaxLines <= 0 {
		opts.MaxLines = defaultMaxLines
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.Log == nil {
		opts.Log = slog.Default()
	}
	s := &Store{opts: opts, nextID: 1}
	if opts.Path == "" {
		return s, nil
	}
	if err := os.MkdirAll(filepath.Dir(opts.Path), 0o755); err != nil {
		return nil, err
	}
	if err := s.load(); err != nil {
		return nil, err
	}
	if s.fileLines > s.compactThreshold() {
		if err := s.compactLocked(); err != nil {
			return nil, err
		}
	}
	if s.file == nil {
		f, err := os.OpenFile(opts.Path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
		if err != nil {
			return nil, err
		}
		s.file = f
	}
	return s, nil
}

func (s *Store) load() error {
	f, err := os.Open(s.opts.Path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	defer f.Close()

	var all []Line
	bad := 0
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64*1024), 16*1024*1024)
	for sc.Scan() {
		raw := sc.Bytes()
		if len(raw) == 0 {
			continue
		}
		var r record
		if err := json.Unmarshal(raw, &r); err != nil || r.ID <= 0 || r.Text == "" {
			bad++ // typically a torn final write after a crash
			continue
		}
		all = append(all, Line{ID: r.ID, Text: r.Text, Source: r.Source, ReceivedAt: r.ReceivedAt})
	}
	if err := sc.Err(); err != nil {
		return fmt.Errorf("read %s: %w", s.opts.Path, err)
	}
	if bad > 0 {
		s.opts.Log.Warn("skipped malformed log lines", "count", bad, "path", s.opts.Path)
	}
	sort.SliceStable(all, func(i, j int) bool { return all[i].ID < all[j].ID })
	s.fileLines = len(all) + bad
	if n := len(all); n > 0 {
		s.nextID = all[n-1].ID + 1
		s.lastText = all[n-1].Text
	}
	if len(all) > s.opts.MaxLines {
		all = all[len(all)-s.opts.MaxLines:]
	}
	s.lines = all
	return nil
}

// compactThreshold is how many lines the file may hold before it is
// rewritten down to MaxLines.
func (s *Store) compactThreshold() int {
	return s.opts.MaxLines + max(s.opts.MaxLines/4, 100)
}

// compactLocked rewrites the log with only the retained lines. Ids stay
// monotonic across restarts because the newest line is always retained.
func (s *Store) compactLocked() error {
	tmp := s.opts.Path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	w := bufio.NewWriter(f)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	for _, l := range s.lines {
		if err := enc.Encode(toRecord(l)); err != nil {
			f.Close()
			return err
		}
	}
	if err := w.Flush(); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if s.file != nil {
		s.file.Close()
		s.file = nil
	}
	if err := os.Rename(tmp, s.opts.Path); err != nil {
		return err
	}
	nf, err := os.OpenFile(s.opts.Path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	s.file = nf
	s.fileLines = len(s.lines)
	return nil
}

func toRecord(l Line) record {
	return record{ID: l.ID, Text: l.Text, Source: l.Source, ReceivedAt: l.ReceivedAt}
}

// Add cleans, dedupes, persists and publishes a line.
func (s *Store) Add(text, source string) (Line, Result, error) {
	clean, ok := s.opts.Normalizer.Normalize(text)
	if !ok {
		return Line{}, Filtered, nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.opts.Dedupe && clean == s.lastText {
		return Line{}, Duplicate, nil
	}
	l := Line{
		ID:         s.nextID,
		Text:       clean,
		Source:     source,
		ReceivedAt: s.opts.Now().UnixMilli(),
	}
	if s.file != nil {
		b, err := json.Marshal(toRecord(l))
		if err != nil {
			return Line{}, Filtered, err
		}
		if _, err := s.file.Write(append(b, '\n')); err != nil {
			return Line{}, Filtered, fmt.Errorf("append log: %w", err)
		}
		s.fileLines++
	}
	s.nextID++
	s.lastText = clean
	s.lines = append(s.lines, l)
	if len(s.lines) > s.opts.MaxLines {
		// Copy so the backing array does not grow without bound.
		s.lines = append([]Line(nil), s.lines[len(s.lines)-s.opts.MaxLines:]...)
	}
	if s.file != nil && s.fileLines > s.compactThreshold() {
		if err := s.compactLocked(); err != nil {
			s.opts.Log.Error("compact log", "err", err)
		}
	}
	if s.opts.OnAdd != nil {
		s.opts.OnAdd(l)
	}
	return l, Added, nil
}

// Last returns up to limit of the newest lines, ascending.
func (s *Store) Last(limit int) []Line {
	s.mu.Lock()
	defer s.mu.Unlock()
	start := max(len(s.lines)-limit, 0)
	return append([]Line(nil), s.lines[start:]...)
}

// After returns up to limit lines with id > after, ascending.
func (s *Store) After(after int64, limit int) []Line {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := sort.Search(len(s.lines), func(i int) bool { return s.lines[i].ID > after })
	end := min(i+limit, len(s.lines))
	return append([]Line(nil), s.lines[i:end]...)
}

// Get returns a retained line by id.
func (s *Store) Get(id int64) (Line, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := sort.Search(len(s.lines), func(i int) bool { return s.lines[i].ID >= id })
	if i < len(s.lines) && s.lines[i].ID == id {
		return s.lines[i], true
	}
	return Line{}, false
}

// LatestID is the id of the newest line ever stored, 0 when none.
func (s *Store) LatestID() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.nextID - 1
}

// Close flushes and closes the log file.
func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file == nil {
		return nil
	}
	err := s.file.Sync()
	if cerr := s.file.Close(); err == nil {
		err = cerr
	}
	s.file = nil
	return err
}
