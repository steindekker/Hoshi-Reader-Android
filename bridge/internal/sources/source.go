// Package sources implements the text inputs: WebSocket clients (Textractor
// extensions, LunaTranslator) and clipboard polling.
package sources

import (
	"context"
	"encoding/json"
	"strings"
	"time"
)

// Emit receives each raw text chunk produced by a source.
type Emit func(text string)

// Report receives connection state changes.
type Report func(connected bool, err error)

// Source runs until ctx is cancelled, reconnecting on its own.
type Source interface {
	Run(ctx context.Context, emit Emit, report Report)
}

// Backoff is an exponential reconnect delay.
type Backoff struct {
	Min, Max time.Duration
	cur      time.Duration
}

// Next returns the next delay and doubles it up to Max.
func (b *Backoff) Next() time.Duration {
	if b.Min <= 0 {
		b.Min = time.Second
	}
	if b.Max < b.Min {
		b.Max = 30 * time.Second
	}
	if b.cur < b.Min {
		b.cur = b.Min
	}
	d := b.cur
	b.cur = min(b.cur*2, b.Max)
	return d
}

// Reset starts over at Min.
func (b *Backoff) Reset() { b.cur = 0 }

func sleep(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

// textFields are the JSON keys checked, in order, when a WebSocket message
// is a JSON object rather than plain text.
var textFields = []string{"sentence", "text", "line", "message", "data"}

// ExtractText turns a WebSocket payload into sentence text. Plain text is
// returned as-is. A JSON object yields its first string field among
// textFields; a JSON string literal yields its value. Anything else
// (including JSON without a known field) is treated as plain text.
func ExtractText(payload []byte) string {
	s := strings.TrimSpace(string(payload))
	if len(s) < 2 {
		return string(payload)
	}
	switch s[0] {
	case '{':
		var obj map[string]json.RawMessage
		if json.Unmarshal([]byte(s), &obj) != nil {
			return string(payload)
		}
		for _, k := range textFields {
			raw, ok := obj[k]
			if !ok {
				continue
			}
			var v string
			if json.Unmarshal(raw, &v) == nil {
				return v
			}
		}
		return string(payload)
	case '"':
		var v string
		if json.Unmarshal([]byte(s), &v) == nil {
			return v
		}
	}
	return string(payload)
}
