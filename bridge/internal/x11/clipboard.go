package x11

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jezek/xgb/xproto"
)

// ClipboardReader reads the CLIPBOARD selection as UTF-8 text. It keeps a
// connection and a hidden requestor window open between reads.
type ClipboardReader struct {
	conn   *Conn
	win    xproto.Window
	clip   xproto.Atom
	utf8   xproto.Atom
	target xproto.Atom
	events chan xproto.SelectionNotifyEvent
}

// OpenClipboard connects to display and prepares a requestor window.
func OpenClipboard(ctx context.Context, display string) (*ClipboardReader, error) {
	c, err := Dial(ctx, display)
	if err != nil {
		return nil, err
	}
	r := &ClipboardReader{conn: c, events: make(chan xproto.SelectionNotifyEvent, 4)}
	if err := r.init(); err != nil {
		c.Close()
		return nil, err
	}
	go r.pump()
	return r, nil
}

func (r *ClipboardReader) init() error {
	var err error
	if r.clip, err = r.conn.Atom("CLIPBOARD", false); err != nil {
		return err
	}
	if r.utf8, err = r.conn.Atom("UTF8_STRING", false); err != nil {
		return err
	}
	if r.target, err = r.conn.Atom("VN_EXTRACTOR_CLIP", false); err != nil {
		return err
	}
	wid, err := xproto.NewWindowId(r.conn.X)
	if err != nil {
		return err
	}
	r.win = wid
	return xproto.CreateWindowChecked(r.conn.X, 0, wid, r.conn.Root, -10, -10, 1, 1, 0,
		xproto.WindowClassInputOnly, 0, 0, nil).Check()
}

func (r *ClipboardReader) pump() {
	defer close(r.events)
	for {
		ev, err := r.conn.X.WaitForEvent()
		if ev == nil && err == nil {
			return // connection closed
		}
		if sn, ok := ev.(xproto.SelectionNotifyEvent); ok {
			select {
			case r.events <- sn:
			default:
			}
		}
	}
}

// Read returns the current clipboard text ("" when nothing is owned).
func (r *ClipboardReader) Read(ctx context.Context) (string, error) {
	// Drain stale notifications from a previous timed-out request.
	for {
		select {
		case <-r.events:
			continue
		default:
		}
		break
	}
	if err := xproto.ConvertSelectionChecked(r.conn.X, r.win, r.clip, r.utf8, r.target, xproto.TimeCurrentTime).Check(); err != nil {
		return "", err
	}
	timer := time.NewTimer(2 * time.Second)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	case <-timer.C:
		return "", errors.New("clipboard owner did not answer")
	case ev, ok := <-r.events:
		if !ok {
			return "", errors.New("x11 connection closed")
		}
		if ev.Property == xproto.AtomNone {
			return "", nil // no owner or no UTF-8 conversion
		}
	}
	reply, err := xproto.GetProperty(r.conn.X, true, r.win, r.target, xproto.GetPropertyTypeAny, 0, 1<<20).Reply()
	if err != nil {
		return "", err
	}
	if reply == nil {
		return "", errors.New("x11 connection closed")
	}
	if reply.Type != r.utf8 && reply.Type != xproto.AtomString {
		return "", fmt.Errorf("unsupported clipboard type %d (INCR transfers are not supported)", reply.Type)
	}
	return string(reply.Value), nil
}

// Close releases the connection.
func (r *ClipboardReader) Close() { r.conn.Close() }
