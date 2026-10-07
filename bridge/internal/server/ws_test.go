package server

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/steindekker/hoshi-reader/bridge/internal/store"
)

type wsMsg struct {
	Type     string       `json:"type"`
	Protocol int          `json:"protocol"`
	Status   *Status      `json:"status"`
	Lines    []store.Line `json:"lines"`
	Line     *store.Line  `json:"line"`
}

func dialWS(t *testing.T, e *env, query string) (*websocket.Conn, context.Context) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	u := "ws" + strings.TrimPrefix(e.ts.URL, "http") + "/api/ws" + query
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

func TestWSHelloLineStatus(t *testing.T) {
	e := newEnv(t, "", false)
	for i := 1; i <= 60; i++ {
		e.store.Add(fmt.Sprintf("過去の行 %d", i), "t")
	}
	c, ctx := dialWS(t, e, "")
	hello := readMsg(t, ctx, c)
	if hello.Type != "hello" || hello.Protocol != 1 || hello.Status == nil || hello.Status.LatestLineID != 60 {
		t.Fatalf("bad hello %+v", hello)
	}
	if len(hello.Lines) != 50 || hello.Lines[0].ID != 11 || hello.Lines[49].ID != 60 {
		t.Fatalf("hello should carry the last 50 lines, got %d", len(hello.Lines))
	}

	e.store.Add("新しい行", "textractor")
	m := readMsg(t, ctx, c)
	if m.Type != "line" || m.Line == nil || m.Line.ID != 61 || m.Line.Text != "新しい行" || m.Line.Source != "textractor" {
		t.Fatalf("bad line msg %+v", m)
	}

	h := e.tracker.Register("clip", "clipboard", "x11:auto", false)
	h.Set(true, nil)
	m = readMsg(t, ctx, c)
	if m.Type != "status" || m.Status == nil || len(m.Status.Sources) != 2 || !m.Status.Sources[1].Connected {
		t.Fatalf("bad status msg %+v", m)
	}
	e.tracker.SetScreenshot(true, "gamescope")
	m = readMsg(t, ctx, c)
	if m.Type != "status" || !m.Status.Screenshot.Available {
		t.Fatalf("screenshot change should push status: %+v", m)
	}

	// Lines keep arriving in id order.
	for i := 0; i < 20; i++ {
		e.store.Add(fmt.Sprintf("連続 %d", i), "t")
	}
	for want := int64(62); want < 82; want++ {
		m = readMsg(t, ctx, c)
		if m.Type != "line" || m.Line.ID != want {
			t.Fatalf("want line %d, got %+v", want, m)
		}
	}
}

func TestWSHelloEmptyStore(t *testing.T) {
	e := newEnv(t, "", false)
	c, ctx := dialWS(t, e, "")
	b := func() string {
		_, b, err := c.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return string(b)
	}()
	if !strings.Contains(b, `"lines":[]`) {
		t.Fatalf("lines must be an empty array: %s", b)
	}
	e.store.Add("最初", "t")
	if m := readMsg(t, ctx, c); m.Type != "line" || m.Line.ID != 1 {
		t.Fatalf("got %+v", m)
	}
}

func TestWSAfterFillsGap(t *testing.T) {
	e := newEnv(t, "", false)
	for i := 1; i <= 30; i++ {
		e.store.Add(fmt.Sprintf("行 %d", i), "t")
	}
	c, ctx := dialWS(t, e, "?after=25")
	hello := readMsg(t, ctx, c)
	if len(hello.Lines) != 5 || hello.Lines[0].ID != 26 || hello.Lines[4].ID != 30 {
		t.Fatalf("after=25 should give 26..30, got %+v", hello.Lines)
	}
	e.store.Add("次", "t")
	if m := readMsg(t, ctx, c); m.Line == nil || m.Line.ID != 31 {
		t.Fatalf("got %+v", m)
	}

	c2, ctx2 := dialWS(t, e, "?after=31")
	if h := readMsg(t, ctx2, c2); h.Type != "hello" || len(h.Lines) != 0 {
		t.Fatalf("up-to-date client should get no lines, got %d", len(h.Lines))
	}
	e.store.Add("その次", "t")
	if m := readMsg(t, ctx2, c2); m.Line == nil || m.Line.ID != 32 {
		t.Fatalf("got %+v", m)
	}
}

func TestWSAfterCappedThenBacklogStreamed(t *testing.T) {
	e := newEnv(t, "", false)
	for i := 1; i <= 1500; i++ {
		e.store.Add(fmt.Sprintf("行 %d", i), "t")
	}
	c, ctx := dialWS(t, e, "?after=0")
	hello := readMsg(t, ctx, c)
	if len(hello.Lines) != 1000 || hello.Lines[0].ID != 1 || hello.Lines[999].ID != 1000 {
		t.Fatalf("hello must be capped at the first 1000 lines after 'after', got %d", len(hello.Lines))
	}
	for want := int64(1001); want <= 1500; want++ {
		m := readMsg(t, ctx, c)
		if m.Type != "line" || m.Line.ID != want {
			t.Fatalf("backlog: want %d got %+v", want, m)
		}
	}
	e.store.Add("live", "t")
	if m := readMsg(t, ctx, c); m.Line == nil || m.Line.ID != 1501 {
		t.Fatalf("got %+v", m)
	}
}

func TestWSRejectsBadAfter(t *testing.T) {
	e := newEnv(t, "", false)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(e.ts.URL, "http")+"/api/ws?after=x", nil)
	if err == nil || resp == nil || resp.StatusCode != 400 {
		t.Fatalf("expected 400, err=%v", err)
	}
}

func TestWSPingsAndIgnoresClientFrames(t *testing.T) {
	e := newEnv(t, "", false) // PingInterval is 200ms in tests
	c, ctx := dialWS(t, e, "")
	readMsg(t, ctx, c) // hello
	// Client frames are ignored and must not break the session.
	if err := c.Write(ctx, websocket.MessageText, []byte(`{"type":"whatever"}`)); err != nil {
		t.Fatal(err)
	}
	// The coder/websocket client answers pings while a Read is pending, so
	// keep one pending across several ping intervals.
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
	if m := <-got; m.Type != "line" {
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
	e := newEnv(t, "", false) // PingInterval is 200ms in tests
	c, ctx := dialWS(t, e, "")
	readMsg(t, ctx, c)
	// No pending Read: pings go unanswered and the server gives up.
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
