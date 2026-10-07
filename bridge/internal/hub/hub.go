// Package hub is a tiny non-blocking fan-out used to push events to
// WebSocket clients and internal workers.
package hub

import "sync"

// Hub broadcasts values to subscribers. Publish never blocks: a subscriber
// whose buffer is full is dropped (its channel is closed) and is expected to
// resubscribe and catch up, e.g. via ?after=<id>.
type Hub struct {
	mu   sync.Mutex
	subs map[*Sub]struct{}
}

// Sub is one subscription.
type Sub struct {
	C    <-chan any
	c    chan any
	h    *Hub
	once sync.Once
}

func New() *Hub { return &Hub{subs: map[*Sub]struct{}{}} }

// Subscribe registers a subscriber with the given buffer size.
func (h *Hub) Subscribe(buffer int) *Sub {
	c := make(chan any, buffer)
	s := &Sub{C: c, c: c, h: h}
	h.mu.Lock()
	h.subs[s] = struct{}{}
	h.mu.Unlock()
	return s
}

// Publish delivers v to every subscriber without blocking.
func (h *Hub) Publish(v any) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.subs {
		select {
		case s.c <- v:
		default:
			delete(h.subs, s)
			s.once.Do(func() { close(s.c) })
		}
	}
}

// Close unsubscribes and closes the channel. Safe to call more than once.
func (s *Sub) Close() {
	s.h.mu.Lock()
	delete(s.h.subs, s)
	s.h.mu.Unlock()
	s.once.Do(func() { close(s.c) })
}
