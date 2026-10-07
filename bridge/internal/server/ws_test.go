package server

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/steindekker/hoshi-reader/bridge/internal/session"
)

type wsMsg struct {
	Type      string             `json:"type"`
	Protocol  int                `json:"protocol"`
	Now       int64              `json:"now"`
	Bridge    *Bridge            `json:"bridge"`
	Sessions  []session.Snapshot `json:"sessions"`
	Session   *session.Info      `json:"session"`
	SessionID string             `json:"sessionId"`
	TrackID   string             `json:"trackId"`
	Mode      string             `json:"mode"`
	Cues      []session.Cue      `json:"cues"`
	session.Playback
}

func dialWS(t *testing.T, e *env, query string) (*websocket.Conn, context.Context) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	u := "ws" + strings.TrimPrefix(e.ts.URL, "http") + "/v2/ws" + query
	c, _, err := websocket.Dial(ctx, u, nil)
	if err != nil {
		t.Fatal(err)
	}
	c.SetReadLimit(16 << 20)
	t.Cleanup(func() { c.CloseNow() })
	return c, ctx
}

func readMsg(t *testing.T, ctx context.Context, c *websocket.Conn) wsMsg {
	t.Helper()
	typ, b, err := c.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if typ != websocket.MessageText {
		t.Fatalf("expected text frame, got %v", typ)
	}
	var m wsMsg
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("bad json %s: %v", b, err)
	}
	return m
}

func TestWSHelloSnapshotAndEvents(t *testing.T) {
	e := newEnv(t, "", false)
	for i := 1; i <= 250; i++ {
		e.store.Add(fmt.Sprintf("過去の行 %d", i), "t")
	}
	e.addVideo(&fakeVideo{})
	e.reg.SetPlayback("mpv", session.Playback{PositionMs: 1500, Paused: true, Speed: 1}, true)

	c, ctx := dialWS(t, e, "")
	hello := readMsg(t, ctx, c)
	if hello.Type != "hello" || hello.Protocol != 2 || hello.Now == 0 || hello.Bridge == nil || hello.Bridge.Name != "hoshi-bridge" {
		t.Fatalf("bad hello %+v", hello)
	}
	if len(hello.Sessions) != 2 {
		t.Fatalf("want vn + mpv sessions, got %+v", hello.Sessions)
	}
	vnSnap, video := hello.Sessions[0], hello.Sessions[1]
	if vnSnap.ID != "vn" || len(vnSnap.Cues) != 200 || vnSnap.Cues[0].ID != 51 || vnSnap.Cues[199].ID != 250 || vnSnap.Playback != nil {
		t.Fatalf("vn snapshot should carry the last 200 cues: %d first %d", len(vnSnap.Cues), vnSnap.Cues[0].ID)
	}
	if video.ID != "mpv" || video.TrackID != "t1" || len(video.Cues) != 2 || video.Playback == nil || !video.Playback.Paused {
		t.Fatalf("video snapshot %+v", video)
	}

	e.store.Add("新しい行", "textractor")
	m := readMsg(t, ctx, c)
	if m.Type != "cues" || m.SessionID != "vn" || m.Mode != "append" || len(m.Cues) != 1 || m.Cues[0].ID != 251 || m.Cues[0].Text != "新しい行" {
		t.Fatalf("bad append %+v", m)
	}
	e.reg.SetPlayback("mpv", session.Playback{PositionMs: 2000, Paused: false, Speed: 1.5, At: 77}, true)
	m = readMsg(t, ctx, c)
	if m.Type != "playback" || m.SessionID != "mpv" || m.PositionMs != 2000 || m.Paused || m.Speed != 1.5 || m.At != 77 {
		t.Fatalf("bad playback %+v", m)
	}
	e.reg.ReplaceCues("mpv", "t2", []session.Cue{{ID: 1, Text: "新", StartMs: session.Ms(0), EndMs: session.Ms(10)}})
	m = readMsg(t, ctx, c)
	if m.Type != "cues" || m.Mode != "replace" || m.TrackID != "t2" || len(m.Cues) != 1 {
		t.Fatalf("bad replace %+v", m)
	}
	e.reg.Update("mpv", func(i *session.Info) { i.Title = "Frieren 02" })
	m = readMsg(t, ctx, c)
	if m.Type != "session" || m.Session == nil || m.Session.Title != "Frieren 02" || m.Session.TrackID != "t2" {
		t.Fatalf("bad session upsert %+v", m)
	}
	e.reg.End("mpv")
	if m = readMsg(t, ctx, c); m.Type != "sessionEnded" || m.SessionID != "mpv" {
		t.Fatalf("bad end %+v", m)
	}
	e.tracker.Register("clip", "clipboard", "x11:auto", false).Set(true, nil)
	if m = readMsg(t, ctx, c); m.Type != "bridge" || m.Bridge == nil || len(m.Bridge.Sources) != 2 {
		t.Fatalf("bad bridge msg %+v", m)
	}

	// A reconnect always gets a full snapshot.
	c2, ctx2 := dialWS(t, e, "")
	if h := readMsg(t, ctx2, c2); len(h.Sessions) != 1 || len(h.Sessions[0].Cues) != 200 || h.Sessions[0].Cues[199].ID != 251 {
		t.Fatalf("reconnect hello %+v", h.Sessions)
	}
}

func TestWSHelloEmpty(t *testing.T) {
	e := newEnv(t, "", false)
	e.vn.Register(nil)
	c, ctx := dialWS(t, e, "")
	_, b, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(b), `"cues":[]`) {
		t.Fatalf("cues must be an empty array: %s", b)
	}
}

func TestWSPingsAndIgnoresClientFrames(t *testing.T) {
	e := newEnv(t, "", false) // PingInterval is 200ms in tests
	c, ctx := dialWS(t, e, "")
	readMsg(t, ctx, c) // hello
	if err := c.Write(ctx, websocket.MessageText, []byte(`{"type":"whatever"}`)); err != nil {
		t.Fatal(err)
	}
	got := make(chan wsMsg, 1)
	go func() {
		_, b, err := c.Read(ctx)
		var m wsMsg
		if err == nil {
			json.Unmarshal(b, &m)
		}
		got <- m
	}()
	time.Sleep(700 * time.Millisecond)
	e.store.Add("まだ生きてる", "t")
	if m := <-got; m.Type != "session" && m.Type != "cues" {
		t.Fatalf("connection should survive pings, got %+v", m)
	}
}

func TestWSClosedOnShutdown(t *testing.T) {
	e := newEnv(t, "", false)
	c, ctx := dialWS(t, e, "")
	readMsg(t, ctx, c)
	sctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	go e.srv.Shutdown(sctx)
	_, _, err := c.Read(ctx)
	if websocket.CloseStatus(err) != websocket.StatusGoingAway {
		t.Fatalf("expected going-away close, got %v", err)
	}
}

func TestWSDropsClientThatStopsAnsweringPings(t *testing.T) {
	e := newEnv(t, "", false)
	c, ctx := dialWS(t, e, "")
	readMsg(t, ctx, c)
	time.Sleep(900 * time.Millisecond)
	e.store.Add("届かない", "t")
	rctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	for {
		if _, _, err := c.Read(rctx); err != nil {
			if rctx.Err() != nil {
				t.Fatal("server did not drop the unresponsive client")
			}
			return
		}
	}
}
