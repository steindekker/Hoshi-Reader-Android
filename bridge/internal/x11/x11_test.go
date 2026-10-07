package x11

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/jezek/xgb/xproto"
)

// TestClipboardRoundTrip needs a real X server: run with
// VNX_TEST_DISPLAY=:0 go test ./internal/x11/
func TestClipboardRoundTrip(t *testing.T) {
	d := os.Getenv("VNX_TEST_DISPLAY")
	if d == "" {
		t.Skip("set VNX_TEST_DISPLAY to run against a real X server")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	const text = "「おはよう、今日もいい天気だね」"

	// Own CLIPBOARD from a separate connection, like Wine would.
	o, err := Dial(ctx, d)
	if err != nil {
		t.Fatal(err)
	}
	defer o.Close()
	clip, _ := o.Atom("CLIPBOARD", false)
	utf8, _ := o.Atom("UTF8_STRING", false)
	wid, _ := xproto.NewWindowId(o.X)
	if err := xproto.CreateWindowChecked(o.X, 0, wid, o.Root, 0, 0, 1, 1, 0, xproto.WindowClassInputOnly, 0, 0, nil).Check(); err != nil {
		t.Fatal(err)
	}
	if err := xproto.SetSelectionOwnerChecked(o.X, wid, clip, xproto.TimeCurrentTime).Check(); err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			ev, err := o.X.WaitForEvent()
			if ev == nil && err == nil {
				return
			}
			req, ok := ev.(xproto.SelectionRequestEvent)
			if !ok {
				continue
			}
			prop := req.Property
			if req.Target == utf8 {
				xproto.ChangeProperty(o.X, xproto.PropModeReplace, req.Requestor, req.Property, utf8, 8, uint32(len(text)), []byte(text))
			} else {
				prop = xproto.AtomNone
			}
			n := xproto.SelectionNotifyEvent{Time: req.Time, Requestor: req.Requestor, Selection: req.Selection, Target: req.Target, Property: prop}
			xproto.SendEvent(o.X, false, req.Requestor, 0, string(n.Bytes()))
		}
	}()

	r, err := OpenClipboard(ctx, d)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	got, err := r.Read(ctx)
	if err != nil || got != text {
		t.Fatalf("Read = %q, %v", got, err)
	}
}
