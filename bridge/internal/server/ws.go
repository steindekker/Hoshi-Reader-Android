package server

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"github.com/steindekker/hoshi-reader/bridge/internal/session"
)

const (
	wsBuffer     = 1024
	writeTimeout = 10 * time.Second
)

type helloMsg struct {
	Type     string             `json:"type"`
	Protocol int                `json:"protocol"`
	Now      int64              `json:"now"`
	Bridge   Bridge             `json:"bridge"`
	Sessions []session.Snapshot `json:"sessions"`
}

type bridgeMsg struct {
	Type   string `json:"type"`
	Bridge Bridge `json:"bridge"`
}

type sessionMsg struct {
	Type    string       `json:"type"`
	Session session.Info `json:"session"`
}

type sessionEndedMsg struct {
	Type      string `json:"type"`
	SessionID string `json:"sessionId"`
}

type cuesMsg struct {
	Type      string        `json:"type"`
	SessionID string        `json:"sessionId"`
	TrackID   string        `json:"trackId"`
	Mode      string        `json:"mode"`
	Cues      []session.Cue `json:"cues"`
}

type playbackMsg struct {
	Type      string `json:"type"`
	SessionID string `json:"sessionId"`
	session.Playback
}

type threadsMsg struct {
	Type string `json:"type"`
	threadsResponse
}

// wsMessage turns a hub event into its WebSocket frame, or nil.
func (s *Server) wsMessage(v any) any {
	switch ev := v.(type) {
	case session.SessionEvent:
		return sessionMsg{Type: "session", Session: ev.Info}
	case session.EndedEvent:
		return sessionEndedMsg{Type: "sessionEnded", SessionID: ev.ID}
	case session.CuesEvent:
		cues := ev.Cues
		if cues == nil {
			cues = []session.Cue{}
		}
		return cuesMsg{Type: "cues", SessionID: ev.SessionID, TrackID: ev.TrackID, Mode: ev.Mode, Cues: cues}
	case session.PlaybackEvent:
		return playbackMsg{Type: "playback", SessionID: ev.SessionID, Playback: ev.Playback}
	case StatusChanged:
		return bridgeMsg{Type: "bridge", Bridge: s.Bridge()}
	case ThreadsChanged:
		return threadsMsg{Type: "threads", threadsResponse: s.threads()}
	}
	return nil
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
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

	// The snapshot and the subscription are taken atomically, so the event
	// stream continues exactly where the hello leaves off.
	sub, sessions := s.opts.Registry.Subscribe(wsBuffer)
	defer sub.Close()
	for i := range sessions {
		if sessions[i].Cues == nil {
			sessions[i].Cues = []session.Cue{}
		}
	}
	hello := helloMsg{Type: "hello", Protocol: Protocol, Now: time.Now().UnixMilli(), Bridge: s.Bridge(), Sessions: sessions}
	if err := writeMsg(ctx, c, hello); err != nil {
		return
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
				// Too slow to keep up; the client reconnects and gets a fresh hello.
				c.Close(websocket.StatusTryAgainLater, "client too slow")
				return
			}
			msg := s.wsMessage(v)
			if msg == nil {
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
