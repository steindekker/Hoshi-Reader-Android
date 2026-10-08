package session

import (
	"testing"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hub"
)

func drain(sub *hub.Sub) []any {
	var out []any
	for {
		select {
		case v := <-sub.C:
			out = append(out, v)
		default:
			return out
		}
	}
}

func TestRegistryLifecycle(t *testing.T) {
	h := hub.New()
	r := NewRegistry(h)
	clock := time.UnixMilli(1000)
	r.SetClock(func() time.Time { return clock })
	sub, snap := r.Subscribe(64)
	if len(snap) != 0 {
		t.Fatalf("empty registry snapshot %+v", snap)
	}

	r.Upsert(Info{ID: "vn", Kind: KindVN, TrackID: "log"}, nil, 3)
	for i := int64(1); i <= 5; i++ {
		clock = time.UnixMilli(1000 + i)
		r.AppendCues("vn", Cue{ID: i, Text: "x"})
	}
	s := r.Snapshot()
	if len(s) != 1 || len(s[0].Cues) != 3 || s[0].Cues[0].ID != 3 || s[0].ActiveAt != 1005 {
		t.Fatalf("bounded cues %+v", s)
	}
	if s[0].Capabilities == nil {
		t.Fatal("capabilities must serialize as []")
	}

	r.Upsert(Info{ID: "mpv", Kind: KindVideo, TrackID: "a", Capabilities: []string{CapControl}}, nil, 0)
	r.ReplaceCues("mpv", "a", []Cue{{ID: 1, Text: "a", StartMs: Ms(0), EndMs: Ms(10)}})
	if c, track, ok := r.Cue("mpv", 1); !ok || track != "a" || !c.Timed() {
		t.Fatalf("cue lookup %+v %q %v", c, track, ok)
	}
	// Re-upserting with a new track id drops the old cues.
	r.Upsert(Info{ID: "mpv", Kind: KindVideo, TrackID: "b", Capabilities: []string{CapControl}}, nil, 0)
	if _, track, ok := r.Cue("mpv", 1); ok || track != "b" {
		t.Fatalf("old cues must be gone after a track change, track=%q", track)
	}
	clock = time.UnixMilli(5000)
	r.SetPlayback("mpv", Playback{PositionMs: 10, Paused: true}, false)
	if pb, ok := r.Playback("mpv"); !ok || pb.Speed != 1 || pb.At != 5000 {
		t.Fatalf("playback defaults %+v", pb)
	}
	info, _, _ := r.Get("mpv")
	if info.ActiveAt == 5000 {
		t.Fatal("a paused heartbeat is not activity")
	}
	r.Update("mpv", func(i *Info) { i.Title = "t"; i.TrackID = "hijack" })
	if info, _, _ := r.Get("mpv"); info.Title != "t" || info.TrackID != "b" {
		t.Fatalf("update %+v", info)
	}
	r.End("mpv")
	r.End("mpv")

	var kinds []string
	for _, v := range drain(sub) {
		switch v.(type) {
		case SessionEvent:
			kinds = append(kinds, "session")
		case CuesEvent:
			kinds = append(kinds, "cues")
		case PlaybackEvent:
			kinds = append(kinds, "playback")
		case EndedEvent:
			kinds = append(kinds, "ended")
		}
	}
	want := "session cues cues cues cues cues session cues session playback session ended"
	if got := join(kinds); got != want {
		t.Fatalf("events\n got %s\nwant %s", got, want)
	}
}

func join(s []string) string {
	out := ""
	for i, v := range s {
		if i > 0 {
			out += " "
		}
		out += v
	}
	return out
}
