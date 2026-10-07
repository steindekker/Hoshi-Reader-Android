package server

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/coder/websocket"

	"github.com/steindekker/hoshi-reader/bridge/internal/hook"
	"github.com/steindekker/hoshi-reader/bridge/internal/store"
)

const (
	helloLastLines = 50
	helloMaxLines  = 1000
	wsBuffer       = 512
	writeTimeout   = 10 * time.Second
)

type helloMsg struct {
	Type     string       `json:"type"`
	Protocol int          `json:"protocol"`
	Status   Status       `json:"status"`
	Lines    []store.Line `json:"lines"`
}

type lineMsg struct {
	Type string     `json:"type"`
	Line store.Line `json:"line"`
}

type statusMsg struct {
	Type   string `json:"type"`
	Status Status `json:"status"`
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	var after int64
	hasAfter := false
	if v := r.URL.Query().Get("after"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n < 0 {
			writeError(w, http.StatusBadRequest, "after must be a non-negative integer")
			return
		}
		after, hasAfter = n, true
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{OriginPatterns: s.opts.AllowedOrigins})
	if err != nil {
		return // Accept already wrote the HTTP error
	}
	s.wsWG.Add(1)
	defer s.wsWG.Done()
	defer c.CloseNow()

	ctx := s.ctx

	// Client frames are reserved and ignored, but reading is required for
	// pongs and close frames to be processed. The reader gets its own
	// context: cancelling a Read's context tears the connection down, and on
	// shutdown we want to send a proper close frame first.
	readCtx, readCancel := context.WithCancel(context.Background())
	defer readCancel()
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		for {
			if _, _, err := c.Read(readCtx); err != nil {
				return
			}
		}
	}()

	// Subscribe before taking the snapshot so no line can fall in between;
	// duplicates are filtered by id below.
	sub := s.opts.Hub.Subscribe(wsBuffer)
	defer sub.Close()

	var lines []store.Line
	if hasAfter {
		lines = s.opts.Store.After(after, helloMaxLines)
	} else {
		lines = s.opts.Store.Last(helloLastLines)
	}
	lastSent := after
	if n := len(lines); n > 0 {
		lastSent = lines[n-1].ID
	} else if !hasAfter {
		lastSent = s.opts.Store.LatestID()
	}
	hello := helloMsg{Type: "hello", Protocol: Protocol, Status: s.Status(), Lines: s.decorate(lines)}
	if err := writeMsg(ctx, c, hello); err != nil {
		return
	}
	// A reconnecting client further behind than the hello cap gets the rest
	// of its backlog as ordinary line messages, so it never has a gap.
	if hasAfter && len(lines) == helloMaxLines {
		for {
			more := s.opts.Store.After(lastSent, helloMaxLines)
			if len(more) == 0 {
				break
			}
			for _, l := range s.decorate(more) {
				if err := writeMsg(ctx, c, lineMsg{Type: "line", Line: l}); err != nil {
					return
				}
				lastSent = l.ID
			}
		}
	}

	ping := time.NewTicker(s.opts.PingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			c.Close(websocket.StatusGoingAway, "server shutting down")
			return
		case <-readDone:
			return
		case v, ok := <-sub.C:
			if !ok {
				// Too slow to keep up; the client reconnects with ?after=.
				c.Close(websocket.StatusTryAgainLater, "client too slow")
				return
			}
			var msg any
			switch ev := v.(type) {
			case store.Line:
				if ev.ID <= lastSent {
					continue
				}
				lastSent = ev.ID
				ev.HasScreenshot = s.opts.Cache.Has(ev.ID)
				msg = lineMsg{Type: "line", Line: ev}
			case StatusChanged:
				msg = statusMsg{Type: "status", Status: s.Status()}
			case ThreadsChanged:
				body := threadsFrom(hook.Snapshot{Mode: hook.ModeAuto})
				if s.opts.Hooks != nil {
					body = threadsFrom(s.opts.Hooks.Threads())
				}
				msg = struct {
					Type string `json:"type"`
					threadsResponse
				}{Type: "threads", threadsResponse: body}
			default:
				continue
			}
			if err := writeMsg(ctx, c, msg); err != nil {
				return
			}
		case <-ping.C:
			pctx, pcancel := context.WithTimeout(readCtx, s.opts.PingInterval)
			err := c.Ping(pctx)
			pcancel()
			if err != nil {
				s.opts.Log.Debug("websocket client stopped answering pings", "remote", r.RemoteAddr)
				return
			}
		}
	}
}

func writeMsg(ctx context.Context, c *websocket.Conn, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	return c.Write(wctx, websocket.MessageText, b)
}
