package sources

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"time"

	"github.com/coder/websocket"
)

// WebSocket connects to a text hooker's WebSocket server (e.g. the
// Textractor websocket extension on ws://127.0.0.1:6677 or LunaTranslator's
// ws://127.0.0.1:2333/api/ws/text/origin) and emits every text frame.
type WebSocket struct {
	URL     string
	Header  http.Header
	Backoff Backoff
	// DialTimeout bounds each connection attempt (default 5 s).
	DialTimeout time.Duration
}

// Run implements Source.
func (w *WebSocket) Run(ctx context.Context, emit Emit, report Report) {
	if w.DialTimeout <= 0 {
		w.DialTimeout = 5 * time.Second
	}
	for ctx.Err() == nil {
		err := w.session(ctx, emit, report)
		if ctx.Err() != nil {
			return
		}
		report(false, err)
		if !sleep(ctx, w.Backoff.Next()) {
			return
		}
	}
}

func (w *WebSocket) session(ctx context.Context, emit Emit, report Report) error {
	dctx, cancel := context.WithTimeout(ctx, w.DialTimeout)
	c, resp, err := websocket.Dial(dctx, w.URL, &websocket.DialOptions{HTTPHeader: w.Header})
	cancel()
	if resp != nil && resp.Body != nil {
		resp.Body.Close()
	}
	if err != nil {
		// Surface the root cause ("connect: connection refused") instead of
		// the library's nested "failed to WebSocket dial: ..." chain.
		var opErr *net.OpError
		if errors.As(err, &opErr) {
			return opErr
		}
		return err
	}
	defer c.CloseNow()
	c.SetReadLimit(4 << 20)
	w.Backoff.Reset()
	report(true, nil)
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			var ce websocket.CloseError
			if errors.As(err, &ce) {
				return fmt.Errorf("server closed connection (%d %s)", ce.Code, ce.Reason)
			}
			return err
		}
		emit(ExtractText(data))
	}
}
