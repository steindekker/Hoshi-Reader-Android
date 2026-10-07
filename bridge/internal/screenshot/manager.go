// Package screenshot captures the game screen through a chain of
// auto-detected backends and caches per-line screenshots.
package screenshot

import (
	"context"
	"errors"
	"fmt"
	"image"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/desktop"
)

// ErrUnavailable is returned when no backend could capture.
var ErrUnavailable = errors.New("no screenshot backend available")

// Shot is a captured screen.
type Shot struct {
	Image      image.Image
	CapturedAt time.Time
	Backend    string
}

// Manager serializes captures across the backend chain.
type Manager struct {
	backends []Backend
	timeout  time.Duration
	tmpDir   string
	log      *slog.Logger

	mu  sync.Mutex // one capture at a time: gamescope handles one request per frame
	seq int
}

// NewManager creates a manager. timeout bounds each backend attempt.
func NewManager(backends []Backend, timeout time.Duration, log *slog.Logger) *Manager {
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	if log == nil {
		log = slog.Default()
	}
	// The runtime dir is a per-user tmpfs that both we and gamescope (same
	// user) can write; fall back to the system temp dir.
	dir := filepath.Join(desktop.RuntimeDir(), "hoshi-bridge")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		dir = filepath.Join(os.TempDir(), fmt.Sprintf("hoshi-bridge-%d", os.Getuid()))
		_ = os.MkdirAll(dir, 0o700)
	}
	return &Manager{backends: backends, timeout: timeout, tmpDir: dir, log: log}
}

// Detect returns the name of the first available backend, or "".
func (m *Manager) Detect(ctx context.Context) string {
	for _, b := range m.backends {
		cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		err := b.Available(cctx)
		cancel()
		if err == nil {
			return b.Name()
		}
	}
	return ""
}

// Capture tries each available backend in order until one succeeds.
func (m *Manager) Capture(ctx context.Context) (Shot, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var reasons []string
	for _, b := range m.backends {
		actx, cancel := context.WithTimeout(ctx, 2*time.Second)
		err := b.Available(actx)
		cancel()
		if err != nil {
			reasons = append(reasons, b.Name()+": "+err.Error())
			continue
		}
		m.seq++
		out := filepath.Join(m.tmpDir, fmt.Sprintf("capture-%d-%s.png", m.seq, b.Name()))
		cctx, cancel := context.WithTimeout(ctx, m.timeout)
		start := time.Now()
		img, err := b.Capture(cctx, out)
		cancel()
		_ = os.Remove(out)
		if err == nil && img != nil {
			m.log.Debug("screenshot captured", "backend", b.Name(), "took", time.Since(start).Round(time.Millisecond))
			return Shot{Image: img, CapturedAt: time.Now(), Backend: b.Name()}, nil
		}
		if err == nil {
			err = errors.New("no image")
		}
		m.log.Warn("screenshot backend failed", "backend", b.Name(), "err", err)
		reasons = append(reasons, b.Name()+": "+err.Error())
		if ctx.Err() != nil {
			break
		}
	}
	if len(reasons) == 0 {
		return Shot{}, ErrUnavailable
	}
	return Shot{}, fmt.Errorf("%w (%s)", ErrUnavailable, strings.Join(reasons, "; "))
}
