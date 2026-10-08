package mpv

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"
)

// Event is an mpv IPC event (property changes included).
type Event struct {
	Event string          `json:"event"`
	ID    int64           `json:"id"`
	Name  string          `json:"name"`
	Data  json.RawMessage `json:"data"`
}

type reply struct {
	Error     string          `json:"error"`
	Data      json.RawMessage `json:"data"`
	RequestID *int64          `json:"request_id"`
	Event     string          `json:"event"`
}

// ErrClosed is returned once the connection is gone.
var ErrClosed = errors.New("mpv connection closed")

// Client speaks mpv's JSON IPC protocol over a unix socket.
type Client struct {
	conn net.Conn

	wmu sync.Mutex // serializes writes

	mu      sync.Mutex
	nextID  int64
	pending map[int64]chan reply
	closed  bool

	// Events receives events in order; it is closed when the connection ends.
	Events chan Event
}

// Dial connects to the socket at path.
func Dial(ctx context.Context, path string) (*Client, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "unix", path)
	if err != nil {
		return nil, err
	}
	c := &Client{conn: conn, pending: map[int64]chan reply{}, Events: make(chan Event, 256)}
	go c.readLoop()
	return c, nil
}

// Close ends the connection.
func (c *Client) Close() error { return c.conn.Close() }

func (c *Client) readLoop() {
	defer func() {
		c.mu.Lock()
		c.closed = true
		for id, ch := range c.pending {
			close(ch)
			delete(c.pending, id)
		}
		c.mu.Unlock()
		close(c.Events)
	}()
	sc := bufio.NewScanner(c.conn)
	sc.Buffer(make([]byte, 64<<10), 16<<20)
	for sc.Scan() {
		line := sc.Bytes()
		var r reply
		if err := json.Unmarshal(line, &r); err != nil {
			continue
		}
		if r.Event != "" {
			var ev Event
			if json.Unmarshal(line, &ev) == nil {
				c.Events <- ev
			}
			continue
		}
		if r.RequestID == nil {
			continue
		}
		c.mu.Lock()
		ch := c.pending[*r.RequestID]
		delete(c.pending, *r.RequestID)
		c.mu.Unlock()
		if ch != nil {
			ch <- r
		}
	}
}

// Command runs an mpv command and returns its data.
func (c *Client) Command(ctx context.Context, args ...any) (json.RawMessage, error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, ErrClosed
	}
	c.nextID++
	id := c.nextID
	ch := make(chan reply, 1)
	c.pending[id] = ch
	c.mu.Unlock()

	b, err := json.Marshal(map[string]any{"command": args, "request_id": id})
	if err != nil {
		return nil, err
	}
	c.wmu.Lock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, err = c.conn.Write(append(b, '\n'))
	c.wmu.Unlock()
	if err != nil {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, err
	}
	select {
	case r, ok := <-ch:
		if !ok {
			return nil, ErrClosed
		}
		if r.Error != "success" {
			return nil, fmt.Errorf("mpv %v: %s", args[0], r.Error)
		}
		return r.Data, nil
	case <-ctx.Done():
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return nil, ctx.Err()
	}
}

// Get reads a property into v.
func (c *Client) Get(ctx context.Context, name string, v any) error {
	data, err := c.Command(ctx, "get_property", name)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, v)
}

// Set writes a property.
func (c *Client) Set(ctx context.Context, name string, v any) error {
	_, err := c.Command(ctx, "set_property", name, v)
	return err
}

// Observe asks mpv to send property-change events for name with id.
func (c *Client) Observe(ctx context.Context, id int64, name string) error {
	_, err := c.Command(ctx, "observe_property", id, name)
	return err
}
