package screenshot

import (
	"bytes"
	"context"
	"image"
	"image/jpeg"
	"log/slog"
	"sync"
	"time"
)

// CachedQuality is the JPEG quality used to keep per-line screenshots in
// memory at full resolution; requests re-encode from it.
const CachedQuality = 95

// Cached is a stored per-line screenshot.
type Cached struct {
	LineID     int64
	JPEG       []byte
	CapturedAt time.Time
}

// Image decodes the cached JPEG.
func (c Cached) Image() (image.Image, error) { return jpeg.Decode(bytes.NewReader(c.JPEG)) }

// Cache keeps the screenshots of the last N lines.
type Cache struct {
	mu    sync.Mutex
	size  int
	order []int64
	items map[int64]Cached
}

func NewCache(size int) *Cache {
	if size < 1 {
		size = 1
	}
	return &Cache{size: size, items: map[int64]Cached{}}
}

// Put stores a screenshot for a line, evicting the oldest beyond capacity.
func (c *Cache) Put(v Cached) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.items[v.LineID]; !ok {
		c.order = append(c.order, v.LineID)
	}
	c.items[v.LineID] = v
	for len(c.order) > c.size {
		delete(c.items, c.order[0])
		c.order = c.order[1:]
	}
}

// Get returns the screenshot cached for a line.
func (c *Cache) Get(lineID int64) (Cached, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	v, ok := c.items[lineID]
	return v, ok
}

// Has reports whether a line has a cached screenshot.
func (c *Cache) Has(lineID int64) bool {
	_, ok := c.Get(lineID)
	return ok
}

// Capturer is what OnLineWorker needs (satisfied by *Manager).
type Capturer interface {
	Capture(ctx context.Context) (Shot, error)
}

// OnLineWorker captures a screenshot shortly after a line arrives. Lines
// are debounced: while text keeps arriving faster than delay (skipping),
// only the newest line is captured once things settle.
func OnLineWorker(ctx context.Context, lines <-chan int64, delay time.Duration, cap Capturer, cache *Cache, log *slog.Logger) {
	var (
		pending int64
		timer   *time.Timer
		fire    <-chan time.Time
	)
	for {
		select {
		case <-ctx.Done():
			if timer != nil {
				timer.Stop()
			}
			return
		case id, ok := <-lines:
			if !ok {
				return
			}
			pending = id
			if timer == nil {
				timer = time.NewTimer(delay)
			} else {
				timer.Stop()
				timer.Reset(delay)
			}
			fire = timer.C
		case <-fire:
			fire = nil
			shot, err := cap.Capture(ctx)
			if err != nil {
				log.Debug("on-line screenshot failed", "line", pending, "err", err)
				continue
			}
			var buf bytes.Buffer
			if err := jpeg.Encode(&buf, opaque(shot.Image), &jpeg.Options{Quality: CachedQuality}); err != nil {
				log.Warn("encode cached screenshot", "err", err)
				continue
			}
			cache.Put(Cached{LineID: pending, JPEG: buf.Bytes(), CapturedAt: shot.CapturedAt})
		}
	}
}

// opaque returns img flattened onto black at its native size.
func opaque(img image.Image) image.Image {
	if o, ok := img.(interface{ Opaque() bool }); ok && o.Opaque() {
		return img
	}
	f, err := Flatten(img, 0)
	if err != nil {
		return img
	}
	return f
}
