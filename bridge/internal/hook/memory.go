package hook

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/steindekker/hoshi-reader/bridge/internal/hookproto"
)

// Choice is the remembered text thread of one game. Threads are matched by
// hook code plus the low 16 bits of ctx/ctx2 (the same rule LunaTranslator
// uses), because the high bits are addresses that change between runs.
type Choice struct {
	HookCode  string `json:"hookCode"`
	Name      string `json:"name"`
	Ctx       uint64 `json:"ctx"`
	Ctx2      uint64 `json:"ctx2"`
	Manual    bool   `json:"manual"` // picked by the user rather than by the scorer
	UpdatedAt int64  `json:"updatedAt"`
}

// Matches reports whether t is the remembered thread.
func (c Choice) Matches(t hookproto.Thread) bool {
	return c.HookCode == t.Code && c.Ctx == t.Ctx&0xFFFF && c.Ctx2 == t.Ctx2&0xFFFF
}

// ChoiceFor builds the remembered form of a thread.
func ChoiceFor(t hookproto.Thread, manual bool, now int64) Choice {
	return Choice{HookCode: t.Code, Name: t.Name, Ctx: t.Ctx & 0xFFFF, Ctx2: t.Ctx2 & 0xFFFF, Manual: manual, UpdatedAt: now}
}

// Memory persists the per-game thread choice in a small JSON file
// (<dataDir>/hook-threads.json), keyed by lower-case executable name.
type Memory struct {
	mu    sync.Mutex
	path  string
	Games map[string]Choice `json:"games"`
}

// OpenMemory loads path; a missing or unreadable file starts empty.
func OpenMemory(path string) (*Memory, error) {
	m := &Memory{path: path, Games: map[string]Choice{}}
	if path == "" {
		return m, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return m, nil
	}
	if err != nil {
		return m, err
	}
	if err := json.Unmarshal(data, m); err != nil {
		m.Games = map[string]Choice{}
		return m, err
	}
	if m.Games == nil {
		m.Games = map[string]Choice{}
	}
	return m, nil
}

func gameKey(exe string) string { return strings.ToLower(exe) }

// Get returns the remembered choice for a game.
func (m *Memory) Get(exe string) (Choice, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.Games[gameKey(exe)]
	return c, ok
}

// Set stores (and persists) the choice for a game.
func (m *Memory) Set(exe string, c Choice) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.Games[gameKey(exe)] = c
	return m.saveLocked()
}

// Forget removes the choice for a game.
func (m *Memory) Forget(exe string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.Games[gameKey(exe)]; !ok {
		return nil
	}
	delete(m.Games, gameKey(exe))
	return m.saveLocked()
}

func (m *Memory) saveLocked() error {
	if m.path == "" {
		return nil
	}
	data, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(m.path), 0o755); err != nil {
		return err
	}
	tmp := m.path + ".tmp"
	if err := os.WriteFile(tmp, append(data, '\n'), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, m.path)
}
